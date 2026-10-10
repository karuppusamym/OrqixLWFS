import { useRef, useState } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'
import { uuid } from '@/lib/text'

export function RunCommentsPanel({ ws, runId, canPost }: { ws: string; runId: string; canPost: boolean }) {
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const request = useRef({ text: '', key: uuid() })
  const queryKey = ['ws', ws, 'run-comments', runId]
  const comments = useInfiniteQuery({ queryKey, initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => call(api.GET('/workspaces/{workspaceId}/runs/{runId}/comments', { params: { path: { workspaceId: ws, runId }, query: { before: pageParam } } })),
    getNextPageParam: last => last.hasMore ? last.comments.at(-1)?.id : undefined,
  })
  const add = useMutation({ mutationFn: () => {
    if (request.current.text !== text) request.current = { text, key: uuid() }
    return call(api.POST('/workspaces/{workspaceId}/runs/{runId}/comments', { params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': request.current.key } }, body: { text } }))
  }, onSuccess: async () => { setText(''); request.current = { text: '', key: uuid() }; await queryClient.invalidateQueries({ queryKey }) } })
  return <Card><CardHeader><h2>Comments</h2></CardHeader><CardContent className="space-y-4">
    <p className="text-sm text-muted-foreground">Comments are visible to people who can read this request's data. Saved comments are permanent.</p>
    {comments.isPending ? <p>Loading comments...</p> : comments.isError ? <Alert tone="danger">Comments could not be loaded. <Button variant="ghost" onClick={() => void comments.refetch()}>Retry</Button></Alert> : null}
    <ol className="space-y-3">{comments.data?.pages.flatMap(page => page.comments).map(comment => <li key={comment.id} className="rounded-card border border-border p-3">
      <p className="text-sm text-muted-foreground">{comment.author.displayName} - {formatDateTime(comment.createdAt)}</p><p className="whitespace-pre-wrap break-words mt-1">{comment.text}</p>
    </li>)}</ol>
    {comments.data && comments.data.pages.every(page => page.comments.length === 0) ? <p className="text-sm text-muted-foreground">No comments yet.</p> : null}
    {comments.hasNextPage ? <Button variant="secondary" disabled={comments.isFetchingNextPage} onClick={() => void comments.fetchNextPage()}>Load older comments</Button> : null}
    {canPost ? <form className="space-y-2" onSubmit={event => { event.preventDefault(); if (text.trim()) add.mutate() }}>
      <Label htmlFor={`comment-${runId}`}>Add a comment</Label><Textarea id={`comment-${runId}`} value={text} maxLength={2000} rows={3} disabled={add.isPending} onChange={event => setText(event.target.value)} />
      <p className="text-xs text-muted-foreground">{text.length}/2000 characters</p>
      {add.isError ? <Alert tone="danger">The comment could not be saved. Your text is retained; check your access and retry.</Alert> : null}
      <Button type="submit" variant="primary" disabled={add.isPending || !text.trim()}>{add.isPending ? 'Saving...' : 'Save comment'}</Button>
    </form> : null}
  </CardContent></Card>
}
