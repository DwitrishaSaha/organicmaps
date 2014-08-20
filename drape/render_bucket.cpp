#include "render_bucket.hpp"

#include "overlay_handle.hpp"
#include "attribute_buffer_mutator.hpp"
#include "vertex_array_buffer.hpp"
#include "overlay_tree.hpp"

#include "../base/stl_add.hpp"
#include "../std/bind.hpp"

namespace dp
{

RenderBucket::RenderBucket(TransferPointer<VertexArrayBuffer> buffer)
  : m_buffer(buffer)
{
}

RenderBucket::~RenderBucket()
{
  m_buffer.Destroy();
  (void)GetRangeDeletor(m_overlay, MasterPointerDeleter())();
}

RefPointer<VertexArrayBuffer> RenderBucket::GetBuffer()
{
  return m_buffer.GetRefPointer();
}

void RenderBucket::AddOverlayHandle(TransferPointer<OverlayHandle> handle)
{
  m_overlay.push_back(MasterPointer<OverlayHandle>(handle));
}

void RenderBucket::Update(ScreenBase const & modelView)
{
  for_each(m_overlay.begin(), m_overlay.end(), bind(&OverlayHandle::Update,
                                                    bind(&dp::NonConstGetter<OverlayHandle>, _1),
                                                    modelView));
}

void RenderBucket::CollectOverlayHandles(RefPointer<OverlayTree> tree)
{
  for_each(m_overlay.begin(), m_overlay.end(), bind(&OverlayTree::Add, tree.GetRaw(),
                                                    bind(&MasterPointer<OverlayHandle>::GetRefPointer, _1)));
}

namespace
{

void AccumulateMutations(MasterPointer<OverlayHandle> const & handle,
                         RefPointer<IndexBufferMutator> indexMutator,
                         RefPointer<AttributeBufferMutator> attributeMutator,
                         ScreenBase const & screen)
{
  if (handle->IsVisible())
  {
    handle->GetElementIndexes(indexMutator);
    if (handle->HasDynamicAttributes())
      handle->GetAttributeMutation(attributeMutator, screen);
  }
}

} // namespace

void RenderBucket::Render(ScreenBase const & screen)
{
  if (!m_overlay.empty())
  {
    // in simple case when overlay is symbol each element will be contains 6 indexes
    AttributeBufferMutator attributeMutator;
    IndexBufferMutator indexMutator(6 * m_overlay.size());
    for_each(m_overlay.begin(), m_overlay.end(), bind(&AccumulateMutations, _1,
                                                      MakeStackRefPointer(&indexMutator),
                                                      MakeStackRefPointer(&attributeMutator),
                                                      screen));
    m_buffer->ApplyMutation(MakeStackRefPointer(&indexMutator),
                            MakeStackRefPointer(&attributeMutator));
  }
  m_buffer->Render();
}

} // namespace dp
