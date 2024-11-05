package app.organicmaps;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.content.Context;
import android.content.SharedPreferences;


import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.CallSuper;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;

import app.organicmaps.base.BaseMwmFragmentActivity;
import app.organicmaps.downloader.CountryItem;
import app.organicmaps.downloader.MapManager;
import app.organicmaps.intent.Factory;
import app.organicmaps.location.LocationHelper;
import app.organicmaps.location.LocationListener;
import app.organicmaps.util.Config;
import app.organicmaps.util.ConnectionState;
import app.organicmaps.util.StringUtils;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import app.organicmaps.bookmarks.data.BookmarkManager;


import java.util.List;
import java.util.Objects;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import android.util.Log;


@SuppressLint("StringFormatMatches")
public class DownloadResourcesLegacyActivity extends BaseMwmFragmentActivity
{
  private static final String TAG = DownloadResourcesLegacyActivity.class.getSimpleName();

  // Error codes, should match the same codes in JNI
  private static final int ERR_DOWNLOAD_SUCCESS = 0;
  private static final int ERR_DISK_ERROR = -1;
  private static final int ERR_NOT_ENOUGH_FREE_SPACE = -2;
  private static final int ERR_STORAGE_DISCONNECTED = -3;
  private static final int ERR_DOWNLOAD_ERROR = -4;
  private static final int ERR_NO_MORE_FILES = -5;
  private static final int ERR_FILE_IN_PROGRESS = -6;

  private TextView mTvMessage;
  private LinearProgressIndicator mProgress;
  private Button mBtnDownload;
  private CheckBox mChbDownloadCountry;

  private String mCurrentCountry;

  @Nullable
  private Dialog mAlertDialog;

  @NonNull
  private ActivityResultLauncher<Intent> mApiRequest;

  private boolean mAreResourcesDownloaded;

  private static final int DOWNLOAD = 0;
  private static final int PAUSE = 1;
  private static final int RESUME = 2;
  private static final int TRY_AGAIN = 3;
  private static final int PROCEED_TO_MAP = 4;
  private static final int BTN_COUNT = 5;

  private static final String PREFS_NAME = "BookmarkPrefs";
  private static final String GPX_PRELOADED_KEY = "isGpxPreloaded";



  private View.OnClickListener[] mBtnListeners;
  private String[] mBtnNames;

  private int mCountryDownloadListenerSlot;

  private interface Listener
  {
    // Called by JNI.
    @Keep
    @SuppressWarnings("unused")
    void onProgress(int percent);

    // Called by JNI.
    @Keep
    @SuppressWarnings("unused")
    void onFinish(int errorCode);
  }

  // Listens for location updates to determine the user's current country.
  private final LocationListener mLocationListener = new LocationListener()
  {
    @Override
    public void onLocationUpdated(Location location)
    {
      if (mCurrentCountry != null)
        return;

      final double lat = location.getLatitude();
      final double lon = location.getLongitude();
      mCurrentCountry = MapManager.nativeFindCountry(lat, lon);
      if (TextUtils.isEmpty(mCurrentCountry))
      {
        mCurrentCountry = null;
        return;
      }

      int status = MapManager.nativeGetStatus(mCurrentCountry);
      String name = MapManager.nativeGetName(mCurrentCountry);

      if (status != CountryItem.STATUS_DONE)
      {
        UiUtils.show(mChbDownloadCountry);
        String checkBoxText;
        if (status == CountryItem.STATUS_UPDATABLE)
          checkBoxText = String.format(getString(R.string.update_country_ask), name);
        else
          checkBoxText = String.format(getString(R.string.download_country_ask), name);

        mChbDownloadCountry.setText(checkBoxText);
      }

      LocationHelper.from(DownloadResourcesLegacyActivity.this).removeListener(this);
    }
  };

  // Listener to monitor progress and completion of resource downloads.
  private final Listener mResourcesDownloadListener = new Listener()
  {
    @Override
    public void onProgress(final int percent)
    {
      if (!isFinishing())
        mProgress.setProgressCompat(percent, true);
    }

    @Override
    public void onFinish(final int errorCode)
    {
      if (isFinishing())
        return;

      if (errorCode == ERR_DOWNLOAD_SUCCESS)
      {
        final int res = nativeStartNextFileDownload(mResourcesDownloadListener);
        if (res == ERR_NO_MORE_FILES)
          finishFilesDownload(res);
      }
      else
        finishFilesDownload(errorCode);
    }
  };

  // Callback for country map downloads, updates UI or handles failures.
  private final MapManager.StorageCallback mCountryDownloadListener = new MapManager.StorageCallback()
  {
    @Override
    public void onStatusChanged(List<MapManager.StorageCallbackData> data)
    {
      for (MapManager.StorageCallbackData item : data)
      {
        if (!item.isLeafNode)
          continue;

        switch (item.newStatus)
        {
          case CountryItem.STATUS_DONE:
            mAreResourcesDownloaded = true;
            showMap();
            return;

          case CountryItem.STATUS_FAILED:
            MapManager.showError(DownloadResourcesLegacyActivity.this, item, null);
            return;
        }
      }
    }

    @Override
    public void onProgress(String countryId, long localSize, long remoteSize)
    {
      mProgress.setProgressCompat((int) localSize, true);
    }
  };

   @CallSuper
  @Override
  protected void onSafeCreate(@Nullable Bundle savedInstanceState) {
    super.onSafeCreate(savedInstanceState);
    UiUtils.setLightStatusBar(this, true);
    setContentView(R.layout.activity_download_resources);
    initViewsAndListeners();
    mApiRequest = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      setResult(result.getResultCode(), result.getData());
      finish();
    });



    // Automatically select India for download
     setIndiaDownloadOption();

    if (prepareFilesDownload(false)) {
      Utils.keepScreenOn(true, getWindow());
      setAction(DOWNLOAD);
      return;
    }

    showMap();


        // // Check if bookmarks have been preloaded; if not, preload them.
        // SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // if (!prefs.getBoolean(GPX_PRELOADED_KEY, false)) {
        //     preloadBookmarksFromGpx();
        //     prefs.edit().putBoolean(GPX_PRELOADED_KEY, true).apply();  // Mark as preloaded
        // }
  }

  private void parseGpxAndAddBookmarks(InputStream inputStream) {
    Log.i(TAG, "Parsing GPX content.");
    try {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(inputStream, null);

        int eventType = parser.getEventType();
        String name = null;
        double latitude = 0;
        double longitude = 0;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                if ("wpt".equals(tagName)) {
                    latitude = Double.parseDouble(parser.getAttributeValue(null, "lat"));
                    longitude = Double.parseDouble(parser.getAttributeValue(null, "lon"));
                } else if ("name".equals(tagName)) {
                    name = parser.nextText();
                }
            } else if (eventType == XmlPullParser.END_TAG && "wpt".equals(parser.getName())) {
                if (name != null) {
                    addBookmark(name, latitude, longitude);
                    Log.i(TAG, "Added bookmark: " + name + " (" + latitude + ", " + longitude + ")");
                    name = null;
                }
            }
            eventType = parser.next();
        }
    } catch (XmlPullParserException | IOException e) {
        Log.e(TAG, "Failed to parse GPX data", e);
    }
  }

  private void addBookmark(String name, double latitude, double longitude) {
    BookmarkManager.INSTANCE.addNewBookmark(latitude, longitude);
    Log.i(TAG, "Bookmark added: " + name + " at (" + latitude + ", " + longitude + ")");
  }

// Sets the option to download India
// Sets the option to download specific regions: Assam, Arunachal Pradesh, and Nagaland
private void setIndiaDownloadOption() {
  String[] regions = {"India_Assam", "India_Arunachal Pradesh", "India_Nagaland"};

  // Build a combined message for the CheckBox text with all regions listed
  StringBuilder checkBoxText = new StringBuilder();
  for (String region : regions) {
    checkBoxText.append(region).append(" ");
  }

  // Update CheckBox text with the list of regions
  mChbDownloadCountry.setText(String.format(getString(R.string.download_country_ask), checkBoxText.toString().trim()));
  mChbDownloadCountry.setChecked(true);
  mChbDownloadCountry.setEnabled(false);
  //UiUtils.show(mChbDownloadCountry);  // Show the download option

  // Start download for each specified region if not already downloaded
  for (String region : regions) {
    mCurrentCountry = region;
    int status = MapManager.nativeGetStatus(region);
    if (status != CountryItem.STATUS_DONE) {
      MapManager.nativeDownload(region);  // Start download for each region
    }
  }
}


  // Sets the option to download Assam, Arunachal Pradesh, and Nagaland
//  private void setIndiaDownloadOption() {
//    String[] regions = {"Assam", "Arunachal Pradesh", "Nagaland"};
//
//    StringBuilder checkBoxText = new StringBuilder();
//    for (String region : regions) {
//      checkBoxText.append(region).append(" ");
//    }
//
//    // Update checkbox text with all regions listed and show download option
//    mChbDownloadCountry.setText(String.format(getString(R.string.download_country_ask), checkBoxText.toString().trim()));
//    mChbDownloadCountry.setChecked(true);
//    mChbDownloadCountry.setEnabled(false);
//    UiUtils.show(mChbDownloadCountry);
//
//    // Automatically initiate download for specified regions
//    for (String region : regions) {
//      mCurrentCountry = region;
//      int status = MapManager.nativeGetStatus(region);
//      if (status != CountryItem.STATUS_DONE) {
//        MapManager.nativeDownload(region);  // Start download for each region
//      }
//    }
//  }




  @CallSuper
  @Override
  protected void onSafeDestroy()
  {
    super.onSafeDestroy();
    mApiRequest.unregister();
    mApiRequest = null;
    Utils.keepScreenOn(Config.isKeepScreenOnEnabled(), getWindow());
    if (mCountryDownloadListenerSlot != 0)
    {
      MapManager.nativeUnsubscribe(mCountryDownloadListenerSlot);
      mCountryDownloadListenerSlot = 0;
    }
  }

  @CallSuper
  @Override
  protected void onResume()
  {
    super.onResume();
    if (!isFinishing())
      LocationHelper.from(this).addListener(mLocationListener);
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    LocationHelper.from(this).removeListener(mLocationListener);
    if (mAlertDialog != null && mAlertDialog.isShowing())
      mAlertDialog.dismiss();
    mAlertDialog = null;
  }

  private void setDownloadMessage(int bytesToDownload)
  {
    mTvMessage.setText(getString(R.string.download_resources,
      StringUtils.getFileSizeString(this, bytesToDownload)));
  }

  // Prepares files for download, and updates the progress UI.
  private boolean prepareFilesDownload(boolean showMap)
  {
    final int bytes = nativeGetBytesToDownload();
    if (bytes == 0)
    {
      mAreResourcesDownloaded = true;
      if (showMap)
        showMap();

      return false;
    }

    if (bytes > 0)
    {
      setDownloadMessage(bytes);

      mProgress.setMax(bytes);
      mProgress.setProgressCompat(0, true);
    }
    else
      finishFilesDownload(bytes);

    return true;
  }

  private void initViewsAndListeners()
  {
    mTvMessage = findViewById(R.id.download_message);
    mProgress = findViewById(R.id.progressbar);
    mBtnDownload = findViewById(R.id.btn_download_resources);
    mChbDownloadCountry = findViewById(R.id.chb_download_country);

    mBtnListeners = new View.OnClickListener[BTN_COUNT];
    mBtnNames = new String[BTN_COUNT];

    mBtnListeners[DOWNLOAD] = v -> onDownloadClicked();
    mBtnNames[DOWNLOAD] = getString(R.string.download);

    mBtnListeners[PAUSE] = v -> onPauseClicked();
    mBtnNames[PAUSE] = getString(R.string.pause);

    mBtnListeners[RESUME] = v -> onResumeClicked();
    mBtnNames[RESUME] = getString(R.string.continue_button);

    mBtnListeners[TRY_AGAIN] = v -> onTryAgainClicked();
    mBtnNames[TRY_AGAIN] = getString(R.string.try_again);

    mBtnListeners[PROCEED_TO_MAP] = v -> onProceedToMapClicked();
    mBtnNames[PROCEED_TO_MAP] = getString(R.string.download_resources_continue);
  }

  private void setAction(int action)
  {
    mBtnDownload.setOnClickListener(mBtnListeners[action]);
    mBtnDownload.setText(mBtnNames[action]);
  }

  private void doDownload()
  {
    if (nativeStartNextFileDownload(mResourcesDownloadListener) == ERR_NO_MORE_FILES)
      finishFilesDownload(ERR_NO_MORE_FILES);
  }

  private void onDownloadClicked()
  {
    setAction(PAUSE);
    doDownload();
  }

  private void onPauseClicked()
  {
    setAction(RESUME);
    nativeCancelCurrentFile();
  }

  private void onResumeClicked()
  {
    setAction(PAUSE);
    doDownload();
  }

  private void onTryAgainClicked()
  {
    if (prepareFilesDownload(true))
    {
      setAction(PAUSE);
      doDownload();
    }
  }

  private void onProceedToMapClicked()
  {
    mAreResourcesDownloaded = true;
    showMap();
  }

  public void showMap()
  {
    if (!mAreResourcesDownloaded)
      return;

    // Re-use original intent to retain all flags and payload.
    final Intent intent = Objects.requireNonNull(getIntent());
    intent.setComponent(new ComponentName(this, MwmActivity.class));

    // Disable animation because MwmActivity should appear exactly over this one
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_CLEAR_TOP);

    if (Factory.isStartedForApiResult(intent))
    {
      mApiRequest.launch(intent);
      return;
    }

    startActivity(intent);
    finish();
  }

  private void finishFilesDownload(int result)
  {
    if (result == ERR_NO_MORE_FILES)
    {
      Framework.nativeReloadWorldMaps();

      if (mCurrentCountry != null && mChbDownloadCountry.isChecked())
      {
        CountryItem item = CountryItem.fill(mCurrentCountry);
        UiUtils.hide(mChbDownloadCountry);
        mTvMessage.setText(getString(R.string.downloading_country_can_proceed, item.name));
        mProgress.setMax((int)item.totalSize);
        mProgress.setProgressCompat(0, true);

        mCountryDownloadListenerSlot = MapManager.nativeSubscribe(mCountryDownloadListener);
        MapManager.nativeDownload(mCurrentCountry);
        setAction(PROCEED_TO_MAP);
      }
      else
      {
        mAreResourcesDownloaded = true;
        showMap();
      }
    }
    else
    {
      showErrorDialog(result);
    }
  }

  private void showErrorDialog(int result)
  {
    if (mAlertDialog != null && mAlertDialog.isShowing())
      return;

    @StringRes final int titleId;
    @StringRes final int messageId = switch (result)
    {
      case ERR_NOT_ENOUGH_FREE_SPACE ->
      {
        titleId = R.string.routing_not_enough_space;
        yield R.string.not_enough_free_space_on_sdcard;
      }
      case ERR_STORAGE_DISCONNECTED ->
      {
        titleId = R.string.disconnect_usb_cable_title;
        yield R.string.disconnect_usb_cable;
      }
      case ERR_DOWNLOAD_ERROR ->
      {
        titleId = R.string.connection_failure;
        yield (ConnectionState.INSTANCE.isConnected() ? R.string.download_has_failed
          : R.string.common_check_internet_connection_dialog);
      }
      case ERR_DISK_ERROR ->
      {
        titleId = R.string.disk_error_title;
        yield R.string.disk_error;
      }
      default -> throw new AssertionError("Unexpected result code = " + result);
    };

    mAlertDialog = new MaterialAlertDialogBuilder(this, R.style.MwmTheme_AlertDialog)
      .setTitle(titleId)
      .setMessage(messageId)
      .setCancelable(true)
      .setOnCancelListener((dialog) -> setAction(PAUSE))
      .setPositiveButton(R.string.try_again, (dialog, which) -> {
        setAction(TRY_AGAIN);
        onTryAgainClicked();
      })
      .setOnDismissListener(dialog -> mAlertDialog = null)
      .show();
  }

  @Override
  @StyleRes
  public int getThemeResourceId(@NonNull String theme)
  {
    return R.style.MwmTheme_DownloadResourcesLegacy;
  }

  private static native int nativeGetBytesToDownload();
  private static native int nativeStartNextFileDownload(Listener listener);
  private static native void nativeCancelCurrentFile();
}
