import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { useStudio, useStudioContext } from './StudioContext'

type Discussion = Awaited<ReturnType<typeof endpoints.getWorkflowDiscussion>>
type Comment = Discussion['threads'][number]['root']

export const discussionKey = (ws: string, workflowId: string) => ['ws', ws, 'workflow', workflowId, 'discussion'] as const

/** All discussion of the workflow, shared by the inspector panel and the lane badges. */
export function useDiscussion(ws: string, workflowId: string, enabled = true) {
  return useQuery({ queryKey: discussionKey(ws, workflowId), queryFn: () => endpoints.getWorkflowDiscussion(ws, workflowId), enabled, staleTime: 15_000, retry: false })
}

/**
 * Threaded discussion for the selected step or connection (UI03, docs/build/S53). Readers can read; people who can
 * edit or publish can post, reply and resolve. Comments are review notes: they never change the draft.
 */
export function DiscussionPanel() {
  const { wsId, workflowId, saver } = useStudioContext()
  const selection = useStudio((s) => s.selection)
  const query = useDiscussion(wsId, workflowId)
  const client = useQueryClient()
  const announce = useAnnounce()
  const [text, setText] = useState('')
  const [reply, setReply] = useState<{ threadId: string; text: string } | null>(null)
  const refresh = () => client.invalidateQueries({ queryKey: discussionKey(wsId, workflowId) })

  const post = useMutation({
    mutationFn: async (input: { elementId: string; body: string; parentId?: string }) => {
      // The server checks the element against the saved draft, so a just-added step must be saved first.
      if (!(await saver.flush())) throw new Error('The draft could not be saved, so the comment was not posted.')
      return endpoints.postWorkflowComment(wsId, workflowId, input)
    },
    onSuccess: async (_c, input) => {
      if (input.parentId) setReply(null)
      else setText('')
      await refresh()
      announce('Comment posted.')
    },
  })
  const resolve = useMutation({
    mutationFn: (input: { id: string; resolved: boolean }) => endpoints.resolveWorkflowThread(wsId, workflowId, input.id, input.resolved),
    onSuccess: async (_c, input) => {
      await refresh()
      announce(input.resolved ? 'Thread resolved.' : 'Thread reopened.')
    },
  })

  if (!selection) return null
  if (query.isError) return null // no workflow.read: the studio itself would not be open
  const data = query.data
  const threads = (data?.threads ?? []).filter((t) => t.root.elementId === selection.id)
  const canPost = data?.canPost ?? false
  const error = post.error ?? resolve.error

  return (
    <section className="border-t border-border p-4" aria-label="Discussion" data-testid="discussion">
      <h2 className="text-base">Discussion</h2>
      <p className="mt-1 text-xs text-muted">Notes for reviewers about this {selection.type === 'edge' ? 'connection' : 'step'}. They do not change the workflow.</p>
      {query.isPending ? <p className="mt-2 text-sm text-muted">Loading discussion…</p> : null}
      {data && threads.length === 0 ? <p className="mt-2 text-sm text-muted">No comments yet.</p> : null}
      <ul className="mt-3 space-y-3">
        {threads.map((thread) => (
          <li key={thread.root.id} className="rounded-md border border-border p-2" data-testid="thread">
            <CommentView comment={thread.root} />
            {thread.replies.map((r) => (
              <div key={r.id} className="mt-2 border-l-2 border-border pl-2">
                <CommentView comment={r} />
              </div>
            ))}
            <div className="mt-2 flex flex-wrap items-center gap-2">
              {thread.root.resolved ? <Badge tone="success">Resolved{thread.root.resolvedByName ? ` by ${thread.root.resolvedByName}` : ''}</Badge> : <Badge tone="info">Open</Badge>}
              {canPost ? (
                <>
                  <Button size="sm" variant="secondary" onClick={() => setReply({ threadId: thread.root.id, text: '' })}>Reply</Button>
                  <Button size="sm" variant="secondary" loading={resolve.isPending && resolve.variables?.id === thread.root.id} onClick={() => resolve.mutate({ id: thread.root.id, resolved: !thread.root.resolved })}>
                    {thread.root.resolved ? 'Reopen' : 'Resolve'}
                  </Button>
                </>
              ) : null}
            </div>
            {reply?.threadId === thread.root.id ? (
              <div className="mt-2 space-y-2">
                <Textarea aria-label="Your reply" rows={2} maxLength={2000} value={reply.text} onChange={(e) => setReply({ threadId: reply.threadId, text: e.target.value })} />
                <div className="flex gap-2">
                  <Button size="sm" loading={post.isPending} disabled={reply.text.trim() === ''} onClick={() => post.mutate({ elementId: selection.id, body: reply.text.trim(), parentId: thread.root.id })}>Post reply</Button>
                  <Button size="sm" variant="secondary" onClick={() => setReply(null)}>Cancel</Button>
                </div>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {canPost ? (
        <div className="mt-3 space-y-2">
          <Textarea aria-label="New comment" rows={3} maxLength={2000} placeholder="Ask a question or leave a note" value={text} onChange={(e) => setText(e.target.value)} />
          <Button size="sm" loading={post.isPending && !post.variables?.parentId} disabled={text.trim() === ''} onClick={() => post.mutate({ elementId: selection.id, body: text.trim() })}>Comment</Button>
        </div>
      ) : data ? (
        <p className="mt-3 text-xs text-muted">You can read the discussion but not add to it.</p>
      ) : null}
      {error ? <ProblemAlert error={toProblem(error)} className="mt-3" /> : null}
    </section>
  )
}

function CommentView({ comment }: { comment: Comment }) {
  return (
    <div className="text-sm">
      <p className="whitespace-pre-wrap break-words">{comment.body}</p>
      <p className="mt-1 text-xs text-muted">
        {comment.authorName} &middot; <RelativeTime value={comment.createdAt} />
      </p>
    </div>
  )
}
