import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Input, Textarea } from '@/components/ui/input'
import { isProblemError, ProblemError } from '@/api/problem'
import { describeProblem } from '@/lib/problemMessages'
import { uuid } from '@/lib/text'
import { getConversation, sendMessage, type Conversation, type ConversationMessage } from './api'

type Entry = { role: 'You' | 'Assistant'; message: string }
type Field = { name?: string; title?: string; type?: string; description?: string; required?: boolean; enum?: unknown[]; maxLength?: number; format?: string }
type ReviewRow = { name: string; label: string; value: unknown; display?: string }

const START_OPTIONS = [
  { label: 'Start a request', command: 'workflows' },
  { label: 'My tasks', command: 'my tasks' },
  { label: 'My requests', command: 'my requests' },
]

export function ConversationPage() {
  const { workspace, me } = useWorkspace()
  return <ConversationScreen key={`${workspace.id}:${me.subject.id}`} ws={workspace.id} subject={me.subject.id} />
}

/** Business wording for a value: lists read as "A, B", booleans as Yes or No. */
export function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return 'Not provided'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (Array.isArray(value)) return value.map(displayValue).join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function reviewRows(review: NonNullable<Conversation['review']>): ReviewRow[] {
  const fields = (review as { fields?: ReviewRow[] }).fields
  if (fields?.length) return fields
  return Object.entries(review.input).map(([name, value]) => ({ name, label: name, value }))
}

function answerHint(field: Field | undefined): string {
  if (!field) return 'Type what you need, for example “I need a laptop”, or choose an option above.'
  const optional = field.required === false ? ' This question is optional; you can skip it.' : ''
  switch (field.type) {
    case 'number':
    case 'integer':
      return `Type a number, for example 1500 or 1,500.50.${optional}`
    case 'string':
      if (field.format === 'date') return `Enter the date as year-month-day, for example 2026-10-14.${optional}`
      return `${field.description ? `${field.description} ` : ''}Answer one question at a time.${optional}`
    case 'boolean':
      return `Choose Yes or No, or type it.${optional}`
    case 'array':
      return `List the items separated by commas, for example: Laptop, Monitor.${optional}`
    default:
      return `${field.description ? `${field.description} ` : ''}Answer one question at a time.${optional}`
  }
}

function ConversationScreen({ ws, subject }: { ws: string; subject: string }) {
  const storage = `orqix.conversation:${ws}:${subject}`
  const [id, setId] = useState<string | null>(() => {
    try { return sessionStorage.getItem(storage) } catch { return null }
  })
  const [text, setText] = useState('')
  const [latest, setLatest] = useState<Conversation | null>(null)
  const [history, setHistory] = useState<Entry[]>([])
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [changing, setChanging] = useState<ReviewRow | null>(null)
  const [newValue, setNewValue] = useState('')
  const answerRef = useRef<HTMLTextAreaElement>(null)
  const stepRef = useRef<HTMLDivElement>(null)
  const changeRef = useRef<HTMLInputElement>(null)
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const refocus = useRef(false)

  const resume = useQuery({ queryKey: ['ws', ws, 'conversation', id], queryFn: () => getConversation(ws, id!), enabled: !!id, retry: false })
  const current = latest ?? resume.data
  const field = current?.stage === 'collecting' ? (current.field as Field | undefined) : undefined

  const mutation = useMutation({
    mutationFn: ({ body, key }: { body: ConversationMessage; key: string; shown: string }) => sendMessage(ws, body, key),
    onSuccess: (reply, variables) => {
      setLatest(reply); setId(reply.id)
      try { sessionStorage.setItem(storage, reply.id) } catch { /* the draft simply is not remembered */ }
      setHistory((items) => [...items.slice(-38), { role: 'You', message: variables.shown }, { role: 'Assistant', message: reply.message }])
      setText(''); setRejecting(false); setReason(''); setChanging(null); setNewValue('')
      refocus.current = true
    },
  })

  /** After each reply move focus to where the person acts next: the answer box, or the step itself. */
  useEffect(() => {
    if (!refocus.current || mutation.isPending) return
    refocus.current = false
    if (current?.stage === 'collecting' || !current || current.stage === 'idle') answerRef.current?.focus()
    else stepRef.current?.focus()
  }, [current, mutation.isPending])
  useEffect(() => { if (changing) changeRef.current?.focus() }, [changing])
  useEffect(() => { if (rejecting) reasonRef.current?.focus() }, [rejecting])

  const send = (message: string, shown = message) => {
    if (!message.trim() || mutation.isPending) return
    mutation.mutate({ body: { message, ...(current ? { conversationId: current.id, expectedVersion: current.version } : {}) }, key: uuid(), shown })
  }
  const startNew = () => {
    try { sessionStorage.removeItem(storage) } catch { /* nothing to forget */ }
    setId(null); setLatest(null); setHistory([]); setText(''); setRejecting(false); setChanging(null); mutation.reset()
    refocus.current = true
  }
  const reload = async () => {
    if (!current) return
    try {
      const fresh = await getConversation(ws, current.id)
      setLatest(fresh); mutation.reset()
    } catch { startNew() }
  }
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send(text) }
  }

  const stage = current?.stage
  const taskAction = (current as { taskAction?: string } | undefined)?.taskAction
  const review = current?.review
  const rows = review ? reviewRows(review) : []
  const reviewingTask = stage === 'confirmingTask'
  const taskId = current?.taskId
  const busy = mutation.isPending
  const sendError = mutation.error
  const conflict = isProblemError(sendError) && sendError.code === 'TASK_VERSION_CONFLICT' && !!current
  const expired = isProblemError(sendError) && sendError.code === 'NOT_FOUND' && !!current
  const showStarters = !current || stage === 'completed' || (stage === 'idle' && current.choices.length === 0)

  return <div className="mx-auto w-full max-w-3xl">
    <PageHeader title="Assistant" description="Start a request, decide a task that is waiting for you, or check on a request. The Assistant asks one question at a time, and nothing is submitted or decided until you confirm." actions={<Button variant="secondary" onClick={startNew} disabled={busy}>New conversation</Button>} />

    {resume.isPending && id && !current ? <LoadingBlock rows={2} label="Loading your conversation" /> : null}
    {resume.isError && !latest ? <div role="alert" className="mb-4 rounded-card border border-danger-border bg-danger-soft p-4"><p>We could not reopen your last conversation. It may have expired.</p><div className="mt-2 flex flex-wrap gap-2"><Button variant="secondary" onClick={() => void resume.refetch()}>Try again</Button><Button variant="secondary" onClick={startNew}>Start a new conversation</Button></div></div> : null}

    {history.length > 0 ? <div role="log" aria-label="Conversation" aria-live="polite" aria-relevant="additions text" className="mb-4">
      <ol className="space-y-2">{history.map((entry, index) => <li key={index} className={entry.role === 'You' ? 'ml-6 rounded-card border border-primary-soft-border bg-primary-soft p-3' : 'mr-6 rounded-card border border-border bg-surface p-3'}><p className="mb-0.5 text-xs font-semibold text-muted-foreground">{entry.role === 'You' ? 'You said' : 'Assistant'}</p><p className="whitespace-pre-wrap break-words">{entry.message}</p></li>)}</ol>
    </div> : <div role="log" aria-label="Conversation" aria-live="polite" className="mb-4">
      {current && !resume.isPending ? <p className="rounded-card border border-border bg-surface p-3"><span className="mb-0.5 block text-xs font-semibold text-muted-foreground">Assistant</span>{current.message}</p> : null}
    </div>}

    <div ref={stepRef} tabIndex={-1} aria-label="Current step" className="space-y-3 outline-none">
      {!current && !id ? <div className="rounded-card border border-border bg-surface-muted p-4">
        <h2 className="mb-1 text-lg">How can I help?</h2>
        <p className="text-sm">Choose an option, or type what you need in your own words, for example “I need a laptop”.</p>
      </div> : null}
      {showStarters ? <div className="flex flex-wrap gap-2" role="group" aria-label="What would you like to do?">{START_OPTIONS.map((option) => <Button key={option.command} variant={option.command === 'workflows' ? 'primary' : 'secondary'} disabled={busy} onClick={() => send(option.command, option.label)}>{option.label}</Button>)}</div> : null}

      {current && !reviewingTask && current.choices.length > 0 ? <div className="flex flex-wrap gap-2" role="group" aria-label="Choose one">{current.choices.map((choice) => <Button key={choice.command} variant="secondary" disabled={busy} onClick={() => send(choice.command, choice.label)}>{choice.label}</Button>)}</div> : null}

      {field ? <div className="flex flex-wrap gap-2" role="group" aria-label="Quick answers">
        {Array.isArray(field.enum) ? field.enum.map((option) => <Button key={String(option)} variant="secondary" disabled={busy} onClick={() => send(String(option))}>{String(option)}</Button>) : null}
        {field.type === 'boolean' ? ['Yes', 'No'].map((option) => <Button key={option} variant="secondary" disabled={busy} onClick={() => send(option)}>{option}</Button>) : null}
        {field.required === false ? <Button variant="ghost" disabled={busy} onClick={() => send('skip', 'Skip this question')}>Skip this question</Button> : null}
      </div> : null}

      {review ? <section aria-labelledby="assistant-review" className="rounded-card border border-border bg-surface p-4">
        <h2 id="assistant-review" className="mb-3 text-lg">Review {review.workflowName}</h2>
        {rows.length === 0 ? <p className="text-sm text-muted-foreground">No details were provided.</p> : <dl className="divide-y divide-border">{rows.map((row) => <div key={row.name} className="grid gap-1 py-2 sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)_auto] sm:items-start sm:gap-3">
          <dt className="font-semibold break-words">{row.label}</dt>
          <dd className="break-words">{row.display ?? displayValue(row.value)}</dd>
          {stage === 'review' ? <dd><Button size="sm" variant="ghost" disabled={busy} onClick={() => { setChanging(row); setNewValue(typeof row.value === 'string' || typeof row.value === 'number' ? String(row.value) : Array.isArray(row.value) ? row.value.join(', ') : '') }} aria-label={`Change ${row.label}`}>Change</Button></dd> : null}
        </div>)}</dl>}
        {stage === 'review' && changing ? <form className="mt-3 space-y-2 rounded-card border border-border bg-surface-muted p-3" onSubmit={(event) => { event.preventDefault(); if (newValue.trim()) send(`${changing.name}=${newValue.trim()}`, `Change ${changing.label} to ${newValue.trim()}`) }}>
          <Label htmlFor="assistant-change">New value for {changing.label}</Label>
          <Input id="assistant-change" ref={changeRef} value={newValue} maxLength={1000} onChange={(event) => setNewValue(event.target.value)} />
          <div className="flex flex-wrap gap-2"><Button type="submit" variant="primary" size="sm" disabled={busy || !newValue.trim()}>Save change</Button><Button type="button" size="sm" variant="secondary" onClick={() => setChanging(null)}>Cancel</Button></div>
        </form> : null}
      </section> : null}

      {stage === 'review' ? <div className="space-y-2">
        <p className="text-sm text-muted-foreground">Nothing is submitted until you choose Submit request.</p>
        <div className="flex flex-wrap gap-2"><Button variant="primary" disabled={busy} onClick={() => send('confirm', 'Submit request')}>Submit request</Button><Button variant="secondary" disabled={busy} onClick={() => send('cancel', 'Discard this draft')}>Discard this draft</Button></div>
      </div> : null}

      {reviewingTask && taskId ? (taskAction ? <div className="space-y-2">
        <p className="text-sm text-muted-foreground">Nothing is recorded until you confirm.</p>
        <div className="flex flex-wrap gap-2">
          <Button variant={taskAction === 'reject' ? 'danger-solid' : 'primary'} disabled={busy} onClick={() => send('confirm', taskAction === 'reject' ? 'Confirm rejection' : taskAction === 'approve' ? 'Confirm approval' : 'Confirm')}>{taskAction === 'reject' ? 'Confirm rejection' : taskAction === 'approve' ? 'Confirm approval' : 'Confirm'}</Button>
          <Button variant="secondary" disabled={busy} onClick={() => send(`review ${taskId}`, 'Go back to the task')}>Go back</Button>
          <Button variant="ghost" disabled={busy} onClick={() => send('cancel', 'Leave this task alone')}>Leave this task alone</Button>
        </div>
      </div> : <div className="space-y-2">
        <p className="text-sm text-muted-foreground">Choose what you want to do. Nothing is recorded until you confirm on the next step.</p>
        {rejecting ? <form className="space-y-2 rounded-card border border-border bg-surface-muted p-3" onSubmit={(event) => { event.preventDefault(); if (reason.trim()) send(`reject ${taskId}: ${reason.trim()}`, `Reject with reason: ${reason.trim()}`) }}>
          <Label htmlFor="assistant-reason">Why are you rejecting this? (required)</Label>
          <Textarea id="assistant-reason" ref={reasonRef} value={reason} maxLength={1000} onChange={(event) => setReason(event.target.value)} />
          <div className="flex flex-wrap gap-2"><Button type="submit" variant="primary" size="sm" disabled={busy || !reason.trim()}>Continue to confirm</Button><Button type="button" size="sm" variant="secondary" onClick={() => setRejecting(false)}>Cancel</Button></div>
        </form> : <div className="flex flex-wrap gap-2"><Button variant="primary" disabled={busy} onClick={() => send(`approve ${taskId}`, 'Approve')}>Approve</Button><Button variant="danger" disabled={busy} onClick={() => setRejecting(true)}>Reject…</Button><Button variant="ghost" disabled={busy} onClick={() => send('cancel', 'Leave this task alone')}>Leave this task alone</Button></div>}
      </div>) : null}

      {current?.runId ? <Link className="inline-block font-semibold text-link underline" to={`/w/${ws}/runs/${current.runId}`}>Open request</Link> : null}
      {current?.taskId && stage === 'completed' ? <Link className="inline-block font-semibold text-link underline" to={`/w/${ws}/tasks/${current.taskId}`}>Open task</Link> : null}
    </div>

    {sendError ? <div role="alert" className="mt-4 rounded-card border border-danger-border bg-danger-soft p-4">
      <p>{conflict ? 'This conversation was changed somewhere else, so your last message was not applied. Reload it to continue.' : expired ? 'This conversation has expired. Start a new one to continue.' : sendError instanceof ProblemError ? describeProblem(sendError).message : 'The message could not be sent. Try again.'}</p>
      <div className="mt-2 flex flex-wrap gap-2">
        {conflict ? <Button variant="secondary" onClick={() => void reload()}>Reload conversation</Button> : expired ? <Button variant="secondary" onClick={startNew}>Start a new conversation</Button> : <Button variant="secondary" onClick={() => mutation.variables && mutation.mutate(mutation.variables)}>Retry message</Button>}
      </div>
    </div> : null}

    <form className="mt-6 space-y-2" onSubmit={(event) => { event.preventDefault(); send(text) }}>
      <Label htmlFor="conversation-message">{field?.title ? `Your answer: ${String(field.title)}` : 'Message'}</Label>
      <Textarea id="conversation-message" ref={answerRef} maxLength={4000} value={text} readOnly={busy} aria-busy={busy} onKeyDown={onKeyDown} onChange={(event) => setText(event.target.value)} aria-describedby="conversation-help" />
      <p id="conversation-help" className="text-sm text-muted-foreground">{answerHint(field)} Press Enter to send, or Shift and Enter for a new line.</p>
      <p className="text-sm text-muted-foreground">If you describe what you need in your own words, your message and the names of the request types you can use may be sent to your organization’s AI service to find the right one. You always review before anything is submitted.</p>
      <Button variant="primary" type="submit" disabled={!text.trim() || busy}>{busy ? 'Sending…' : 'Send'}</Button>
    </form>
  </div>
}
