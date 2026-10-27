import { MessageSquareText, PanelLeftClose, RefreshCcw, Send } from 'lucide-react'
import { useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { readStorage, writeStorage } from '@/lib/storage'
import { useStudioContext } from '@/features/studio/StudioContext'
import { KINDS, ROUTE_LABEL } from '@/features/studio/model/kinds'
import type { Definition, NodeKind, RouteKind } from '@/features/studio/model/types'
import { proposeDraftEdits, useAiStatus, type AiDraftEditOperation, type AiDraftEditProposal } from './api'
import { applyDraftChanges, applyRegeneration } from './draftEdits'

const COLLAPSED_KEY = 'orqix.studio.assistantCollapsed'
const MIN = 2
const MAX = 2000
/** Turns sent with each request (the server accepts at most 8). */
const HISTORY = 8

type Turn =
  | { id: number; role: 'author'; text: string; mode: 'edit' | 'regenerate' }
  | { id: number; role: 'assistant'; proposal: AiDraftEditProposal; baseline: Definition; chosen: string[]; result: string | null }

/** One line of plain language per operation, with step labels instead of ids. */
export function describeOperation(op: AiDraftEditOperation, labels: Record<string, string>): string {
  const label = (id?: string) => `“${(id && labels[id]) ?? id ?? ''}”`
  const before = op.before as { label?: string; from?: string; to?: string; title?: string } | undefined
  switch (op.op) {
    case 'addStep':
      return `Add ${KINDS[op.kind as NodeKind]?.caption.toLowerCase() ?? 'a'} step “${op.label ?? op.id}”`
    case 'updateStep':
      return `Change ${label(op.id)}: ${Object.entries(op.config ?? {}).map(([k, v]) => (v === null ? `remove ${k}` : `${k} = ${typeof v === 'string' ? v : JSON.stringify(v)}`)).join(', ')}`
    case 'renameStep':
      return `Rename “${before?.label ?? op.id}” to “${op.label ?? ''}”`
    case 'removeStep':
      return `Remove “${before?.label ?? op.id}” and its connections`
    case 'connect':
      return `Connect ${label(op.from)} to ${label(op.to)} (${ROUTE_LABEL[op.on as RouteKind] ?? op.on}${op.label ? `: ${op.label}` : ''})`
    case 'disconnect':
      return `Remove the connection from ${label(before?.from)} to ${label(before?.to)}`
    case 'changeFormField': {
      const title = (op.field as { title?: string } | undefined)?.title ?? before?.title ?? op.key
      const verb = op.action === 'add' ? 'Add' : op.action === 'remove' ? 'Remove' : 'Change'
      return `${verb} the question “${title}”${op.action === 'remove' ? '' : op.required ? ' (required)' : ' (optional)'}`
    }
  }
}

/** What an assistant turn contributes to the history sent with the next request. */
function assistantText(proposal: AiDraftEditProposal): string {
  const parts = [proposal.message ?? '', ...proposal.changes.map((c) => `${c.id}: ${c.summary}`)]
  return (parts.filter(Boolean).join('\n') || 'No changes proposed.').slice(0, MAX)
}

/**
 * Conversational authoring panel (STU02, docs/build/S95), docked left of the canvas next to the step list. Each turn
 * asks the server for structured edits to the saved draft; every proposed change was validated by the compiler and is
 * shown as a reviewable list with unticked checkboxes. Nothing changes until the author applies the chosen changes,
 * which become one undoable edit. When AI is not available the panel says why instead of disappearing.
 */
export function AuthoringPanel() {
  const [collapsed, setCollapsed] = useState(() => readStorage(COLLAPSED_KEY) === 'true')
  const toggle = (next: boolean) => {
    setCollapsed(next)
    writeStorage(COLLAPSED_KEY, String(next))
  }
  if (collapsed) {
    return (
      <aside className="flex w-12 shrink-0 flex-col items-center border-r border-border bg-surface py-3" aria-label="Authoring assistant">
        <Button variant="ghost" size="icon" onClick={() => toggle(false)} aria-label="Show the authoring assistant" aria-expanded={false} title="Show the authoring assistant">
          <MessageSquareText aria-hidden="true" />
        </Button>
      </aside>
    )
  }
  return (
    <aside className="flex w-80 shrink-0 flex-col border-r border-border bg-surface" aria-label="Authoring assistant" data-testid="authoring-panel">
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <h2 className="text-sm font-semibold" id="authoring-heading">Change this draft with AI</h2>
        <Button variant="ghost" size="icon" className="-mr-1 size-8" onClick={() => toggle(true)} aria-label="Hide the authoring assistant" aria-expanded={true} title="Hide the authoring assistant">
          <PanelLeftClose aria-hidden="true" />
        </Button>
      </div>
      <AuthoringBody />
    </aside>
  )
}

function AuthoringBody() {
  const status = useAiStatus()
  if (status.isPending) return <p role="status" className="p-3 text-sm text-muted-foreground">Checking whether AI assistance is available…</p>
  if (status.isError) {
    return (
      <div className="p-3">
        <Alert tone="warning" title="AI assistance is unavailable">The AI status could not be loaded, so the assistant cannot be used right now. The studio keeps working.</Alert>
      </div>
    )
  }
  if (!status.data.enabled) {
    return (
      <div className="p-3" data-testid="authoring-not-configured">
        <Alert tone="warning" title="AI is not configured">
          <p>{status.data.reason ?? 'AI assistance is turned off for this workspace.'}</p>
          <p className="mt-1">You can keep editing by hand. An administrator can configure AI in the deployment settings.</p>
        </Alert>
      </div>
    )
  }
  return <Conversation />
}

function Conversation() {
  const { store, saver, wsId, workflowId } = useStudioContext()
  const [turns, setTurns] = useState<Turn[]>([])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [status, setStatus] = useState('')
  const [confirmRegenerate, setConfirmRegenerate] = useState(false)
  const [confirmReplace, setConfirmReplace] = useState<number | null>(null)
  const nextId = useRef(1)
  const inputId = useId()
  const length = text.trim().length

  const ask = async (mode: 'edit' | 'regenerate') => {
    const instruction = text.trim()
    if (instruction.length < MIN || instruction.length > MAX || busy) return
    setBusy(true); setError(null); setStatus('')
    try {
      const saved = await saver.flush()
      if (!saved) {
        setStatus('The draft could not be saved, so nothing was sent. Fix the save problem first.')
        return
      }
      const history = turns.slice(-HISTORY).map((t) => (t.role === 'author' ? { role: 'author' as const, text: t.text } : { role: 'assistant' as const, text: assistantText(t.proposal) }))
      const baseline = store.getState().definition
      const proposal = await proposeDraftEdits(wsId, workflowId, { instruction, revision: store.getState().revision, mode, history })
      setTurns((all) => [...all, { id: nextId.current++, role: 'author', text: instruction, mode }, { id: nextId.current++, role: 'assistant', proposal, baseline, chosen: [], result: null }])
      setText('')
      const n = proposal.changes.length
      setStatus(mode === 'regenerate'
        ? (n > 0 ? 'A new draft is proposed. Nothing has been changed yet.' : 'No new draft could be proposed.')
        : n === 0 ? 'No changes to review.' : `${n} proposed change${n === 1 ? '' : 's'} to review. Nothing has been applied.`)
    } catch (cause) {
      setError(cause)
    } finally {
      setBusy(false)
    }
  }

  const updateTurn = (id: number, patch: Partial<Extract<Turn, { role: 'assistant' }>>) =>
    setTurns((all) => all.map((t) => (t.id === id && t.role === 'assistant' ? { ...t, ...patch } : t)))

  const applyChosen = (turn: Extract<Turn, { role: 'assistant' }>) => {
    let message = ''
    store.getState().apply((d) => {
      const outcome = applyDraftChanges(d, turn.proposal.changes, new Set(turn.chosen))
      const skipped = outcome.skipped.map((s) => `${s.id} (${s.reason})`).join('; ')
      message = outcome.applied.length === 0
        ? `Nothing was applied.${skipped ? ` Skipped ${skipped}.` : ''}`
        : `Applied ${outcome.applied.length} change${outcome.applied.length === 1 ? '' : 's'} as one edit.${skipped ? ` Skipped ${skipped}.` : ''} Use Undo to revert.`
      return outcome.def
    })
    updateTurn(turn.id, { result: message, chosen: [] })
    setStatus(message)
  }

  const replaceDraft = (turn: Extract<Turn, { role: 'assistant' }>) => {
    let message = ''
    store.getState().apply((d) => {
      const next = turn.proposal.definition ? applyRegeneration(d, turn.baseline, turn.proposal.definition) : null
      message = next ? 'The draft was replaced with the proposal as one edit. Use Undo to revert.' : 'The draft changed since this proposal, so it was not replaced. Ask again.'
      return next ?? d
    })
    updateTurn(turn.id, { result: message })
    setStatus(message)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault()
      void ask('edit')
    }
  }

  const replaceTurn = turns.find((t) => t.id === confirmReplace && t.role === 'assistant') as Extract<Turn, { role: 'assistant' }> | undefined

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
        {turns.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Ask for a change to this draft, for example “Add a finance approval when the amount is 5,000 or more” or “Rename the first review to Manager review”. Every proposal is checked and nothing changes until you apply it.
          </p>
        ) : (
          <ol className="space-y-3" aria-label="Conversation">
            {turns.map((turn) => turn.role === 'author'
              ? (
                  <li key={turn.id} className="ml-6 rounded-card bg-surface-muted p-2 text-sm">
                    <span className="sr-only">You: </span>{turn.mode === 'regenerate' ? <span className="font-medium">Regenerate: </span> : null}{turn.text}
                  </li>
                )
              : <AssistantTurn key={turn.id} turn={turn} onChoose={(chosen) => updateTurn(turn.id, { chosen })} onApply={() => applyChosen(turn)} onReplace={() => setConfirmReplace(turn.id)} />)}
          </ol>
        )}
      </div>
      <div className="space-y-2 border-t border-border p-3">
        <p role="status" aria-live="polite" className="text-sm">{busy ? 'Asking the AI. This can take up to a minute…' : status}</p>
        {error ? <ProblemAlert error={error} /> : null}
        <div className="space-y-1.5">
          <Label htmlFor={inputId}>Your instruction</Label>
          <Textarea id={inputId} rows={3} value={text} maxLength={MAX} disabled={busy} aria-describedby={`${inputId}-help`} onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} />
          <p id={`${inputId}-help`} className="text-xs text-muted-foreground">Press Ctrl+Enter (Cmd+Enter on Mac) to send. The AI sees a summary of the saved draft without secrets.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => void ask('edit')} disabled={busy || length < MIN || length > MAX} loading={busy}>
            <Send aria-hidden="true" /> Send
          </Button>
          <Button variant="secondary" onClick={() => setConfirmRegenerate(true)} disabled={busy || length < MIN || length > MAX}>
            <RefreshCcw aria-hidden="true" /> Regenerate from scratch
          </Button>
          {turns.length > 0 ? <Button variant="ghost" onClick={() => { setTurns([]); setStatus('Conversation cleared.'); setError(null) }} disabled={busy}>Clear conversation</Button> : null}
        </div>
      </div>
      <ConfirmDialog
        open={confirmRegenerate}
        title="Regenerate this draft from scratch?"
        confirmLabel="Ask for a new draft"
        onCancel={() => setConfirmRegenerate(false)}
        onConfirm={() => { setConfirmRegenerate(false); void ask('regenerate') }}
      >
        <p>The AI proposes a new set of steps from your instruction, keeping only the request form. Nothing changes until you review the proposal and choose to replace the draft.</p>
      </ConfirmDialog>
      <ConfirmDialog
        open={!!replaceTurn}
        title="Replace the draft with the proposal?"
        confirmLabel="Replace the draft"
        destructive
        onCancel={() => setConfirmReplace(null)}
        onConfirm={() => { if (replaceTurn) replaceDraft(replaceTurn); setConfirmReplace(null) }}
      >
        <p>Every current step, connection and rule table is replaced by the proposed draft. The request form stays. You can undo this with Ctrl+Z (Cmd+Z on Mac).</p>
      </ConfirmDialog>
    </div>
  )
}

function AssistantTurn({ turn, onChoose, onApply, onReplace }: {
  turn: Extract<Turn, { role: 'assistant' }>
  onChoose: (chosen: string[]) => void
  onApply: () => void
  onReplace: () => void
}) {
  const { proposal } = turn
  const baseId = useId()
  const labels = useMemo(() => {
    const map: Record<string, string> = Object.fromEntries(turn.baseline.nodes.map((n) => [n.id, n.label]))
    for (const change of proposal.changes) for (const op of change.operations) if (op.op === 'addStep' && op.id) map[op.id] = op.label ?? op.id
    return map
  }, [turn.baseline, proposal])
  const regenerate = proposal.mode === 'regenerate'
  const selected = turn.chosen.length
  return (
    <li className="space-y-2 rounded-card border border-border p-2 text-sm" data-testid="assistant-turn">
      <span className="sr-only">Assistant: </span>
      {proposal.message ? <p>{proposal.message}</p> : null}
      {proposal.notAnEdit ? <Alert tone="warning" live="off"><p>{proposal.notAnEdit}</p></Alert> : null}
      {proposal.changes.length > 0 ? (
        <ul className="space-y-2" aria-label={regenerate ? 'Proposed new draft' : 'Proposed changes'}>
          {proposal.changes.map((change) => {
            const id = `${baseId}-${change.id}`
            return (
              <li key={change.id} className="rounded-card bg-surface-muted p-2" data-testid={`draft-change-${change.id}`}>
                {regenerate ? <p className="font-medium">{change.summary}</p> : (
                  <div className="flex items-start gap-2">
                    <Checkbox id={id} checked={turn.chosen.includes(change.id)} disabled={turn.result !== null} onCheckedChange={(v) => onChoose(v === true ? [...turn.chosen, change.id] : turn.chosen.filter((c) => c !== change.id))} aria-describedby={`${id}-ops`} />
                    <Label htmlFor={id} className="font-medium">{change.id}. {change.summary}</Label>
                  </div>
                )}
                <ul id={`${id}-ops`} className="mt-1 list-disc space-y-0.5 pl-5 text-xs" aria-label={`Operations of ${change.summary}`}>
                  {change.operations.map((op, i) => <li key={i}>{describeOperation(op, labels)}</li>)}
                </ul>
                {change.dependsOn.length > 0 ? <p className="mt-1 text-xs text-muted-foreground">Needs change {change.dependsOn.join(', ')}.</p> : null}
              </li>
            )
          })}
        </ul>
      ) : !proposal.notAnEdit ? <p className="text-muted-foreground">No change passed the checks.</p> : null}
      {proposal.rejected.length > 0 ? (
        <div>
          <p className="font-medium">Not offered: they failed the workflow checks</p>
          <ul className="list-disc pl-5 text-xs" aria-label="Rejected changes">
            {proposal.rejected.map((r, i) => <li key={i}>{r.summary}: {r.reasons.join(' ')}</li>)}
          </ul>
        </div>
      ) : null}
      {proposal.notes.length > 0 ? <ul className="list-disc pl-5 text-xs text-muted-foreground" aria-label="AI notes">{proposal.notes.map((n, i) => <li key={i}>{n}</li>)}</ul> : null}
      {turn.result ? <p className="text-xs font-medium">{turn.result}</p> : proposal.changes.length > 0 ? (
        regenerate
          ? <Button size="sm" variant="danger-solid" onClick={onReplace}>Replace the draft…</Button>
          : <Button size="sm" onClick={onApply} disabled={selected === 0}>Apply {selected} selected change{selected === 1 ? '' : 's'}</Button>
      ) : null}
    </li>
  )
}
