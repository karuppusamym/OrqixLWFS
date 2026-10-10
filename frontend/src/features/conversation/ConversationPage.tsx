import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/input'
import { ProblemError } from '@/api/problem'
import { describeProblem } from '@/lib/problemMessages'
import { uuid } from '@/lib/text'
import { getConversation, sendMessage, type Conversation, type ConversationMessage } from './api'

export function ConversationPage() {
  const { workspace, me } = useWorkspace()
  return <ConversationScreen key={`${workspace.id}:${me.subject.id}`} ws={workspace.id} subject={me.subject.id} />
}

function ConversationScreen({ ws, subject }: { ws: string; subject: string }) {
  const storage = `orqix.conversation:${ws}:${subject}`
  const [id, setId] = useState<string | null>(() => sessionStorage.getItem(storage))
  const [text, setText] = useState('')
  const [latest, setLatest] = useState<Conversation | null>(null)
  const [history, setHistory] = useState<{ role: 'You' | 'Assistant'; message: string }[]>([])
  const resume = useQuery({ queryKey: ['ws', ws, 'conversation', id], queryFn: () => getConversation(ws, id!), enabled: !!id, retry: false })
  const current = latest ?? resume.data
  const mutation = useMutation({
    mutationFn: ({ body, key }: { body: ConversationMessage; key: string }) => sendMessage(ws, body, key),
    onSuccess: (reply, variables) => {
      setLatest(reply); setId(reply.id); sessionStorage.setItem(storage, reply.id)
      setHistory((items) => [...items.slice(-38), { role: 'You', message: variables.body.message }, { role: 'Assistant', message: reply.message }])
      setText('')
    },
  })
  const send = (message: string) => {
    if (!message.trim() || mutation.isPending) return
    mutation.mutate({ body: { message, ...(current ? { conversationId: current.id, expectedVersion: current.version } : {}) }, key: uuid() })
  }
  const startNew = () => { sessionStorage.removeItem(storage); setId(null); setLatest(null); setHistory([]); mutation.reset() }
  const review = current?.review
  const canConfirm = current?.stage === 'review' || current?.stage === 'confirmingTask'
  return <div className="mx-auto w-full max-w-3xl">
    <PageHeader title="Assistant" description="Start a request, collect its details, and review task actions in a guided conversation." actions={<Button variant="secondary" onClick={startNew} disabled={mutation.isPending}>New conversation</Button>} />
    <div className="mb-4 rounded-card border border-border bg-surface-muted p-4 text-sm">
      <p>Try “start Purchase approval”, “my tasks”, “my requests”, or “status ORQ-1001”. Request and task actions require your review and confirmation.</p>
      <div className="mt-3 flex flex-wrap gap-2">{['workflows', 'my tasks', 'my requests'].map((command) => <Button key={command} size="sm" variant="secondary" disabled={mutation.isPending} onClick={() => send(command)}>{command}</Button>)}</div>
    </div>
    <section aria-label="Conversation" aria-live="polite" className="space-y-3">
      {history.map((entry, index) => <div key={index} className="rounded-card border border-border bg-surface p-4"><p className="mb-1 text-xs font-semibold text-muted-foreground">{entry.role}</p><p className="whitespace-pre-wrap break-words">{entry.message}</p></div>)}
      {history.length === 0 && current ? <p className="rounded-card border border-border bg-surface p-4">{current.message}</p> : null}
      {resume.isPending && id && !current ? <p role="status">Loading your conversation…</p> : null}
      {resume.isError && !latest ? <div role="alert"><p>This conversation could not be resumed.</p><Button variant="secondary" onClick={() => void resume.refetch()}>Try again</Button></div> : null}
      {current?.choices.length ? <div className="flex flex-wrap gap-2">{current.choices.map((choice) => <Button key={choice.command} variant="secondary" disabled={mutation.isPending} onClick={() => send(choice.command)}>{choice.label}</Button>)}</div> : null}
      {review ? <div className="rounded-card border border-border bg-surface p-4"><h2 className="mb-3 text-lg">Review {review.workflowName}</h2><dl className="space-y-2">{Object.entries(review.input).map(([name, value]) => <div key={name} className="grid gap-1 sm:grid-cols-2"><dt className="font-semibold break-words">{name}</dt><dd className="break-words">{typeof value === 'string' ? value : JSON.stringify(value)}</dd></div>)}</dl></div> : null}
      {current?.runId ? <Link className="inline-block font-semibold text-link underline" to={`/w/${ws}/runs/${current.runId}`}>Open request</Link> : null}
      {current?.taskId && current.stage === 'completed' ? <Link className="inline-block font-semibold text-link underline" to={`/w/${ws}/tasks/${current.taskId}`}>Open task</Link> : null}
      {canConfirm ? <div className="flex gap-2"><Button variant="primary" disabled={mutation.isPending} onClick={() => send('confirm')}>{current.stage === 'review' ? 'Submit request' : 'Confirm task action'}</Button><Button variant="secondary" disabled={mutation.isPending} onClick={() => send('cancel')}>Discard proposed action</Button></div> : null}
    </section>
    {mutation.isError ? <div role="alert" className="mt-4 rounded-card border border-danger bg-danger-soft p-4"><p>{mutation.error instanceof ProblemError ? describeProblem(mutation.error).message : 'The message could not be sent. Try again.'}</p><Button className="mt-2" variant="secondary" onClick={() => mutation.variables && mutation.mutate(mutation.variables)}>Retry message</Button></div> : null}
    <form className="mt-6 space-y-2" onSubmit={(event) => { event.preventDefault(); send(text) }}>
      <Label htmlFor="conversation-message">{current?.field?.title ? `Your answer: ${String(current.field.title)}` : 'Message'}</Label>
      <Textarea id="conversation-message" maxLength={4000} value={text} disabled={mutation.isPending} onChange={(event) => setText(event.target.value)} aria-describedby="conversation-help" />
      <p id="conversation-help" className="text-xs text-muted-foreground">For a collection, enter a JSON list such as ["Laptop", "Monitor"]. To change a reviewed value, use fieldName=value.</p>
      <Button variant="primary" type="submit" disabled={!text.trim() || mutation.isPending}>{mutation.isPending ? 'Sending…' : 'Send'}</Button>
    </form>
  </div>
}
