import { Mic, MicOff, RefreshCcw, Send, X } from 'lucide-react'
import { useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useDictation } from '@/lib/useDictation'
import { useStore } from 'zustand'
import { useStudioContext } from '@/features/studio/StudioContext'
import { KINDS, ROUTE_LABEL } from '@/features/studio/model/kinds'
import type { Definition, NodeKind, RouteKind } from '@/features/studio/model/types'
import { proposeDraftEdits, useAiStatus, type AiDraftEditOperation, type AiDraftEditProposal } from './api'
import { applyDraftChanges, applyRegeneration } from './draftEdits'

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
 * Conversational authoring panel (STU02, docs/build/S95), shown as the "Ask AI" tab of the studio side panel. Each turn
 * asks the server for structured edits to the saved draft; every proposed change was validated by the compiler and is
 * shown as a reviewable list with unticked checkboxes. Nothing changes until the author applies the chosen changes,
 * which become one undoable edit. When AI is not available the panel says why instead of disappearing.
 * `focus` is the step the next instruction is about; the side panel owns it so the Details tab can set it.
 */
export function AuthoringPanel({ focus, onFocusChange }: { focus: FocusStep | null; onFocusChange: (next: FocusStep | null) => void }) {
  return (
    <section className="flex min-h-0 flex-1 flex-col" aria-label="Ask AI to change this workflow" data-testid="authoring-panel">
      <AuthoringBody focus={focus} onFocusChange={onFocusChange} />
    </section>
  )
}

export interface FocusStep { id: string; label: string }

function AuthoringBody({ focus, onFocusChange }: { focus: FocusStep | null; onFocusChange: (next: FocusStep | null) => void }) {
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
  return <Conversation focus={focus} onFocusChange={onFocusChange} />
}

function Conversation({ focus: focusStep, onFocusChange: setFocusStep }: { focus: FocusStep | null; onFocusChange: (next: FocusStep | null) => void }) {
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
  // "Ask about this step": the selected step can be pinned as the subject of the next instruction.
  const selectedId = useStore(store, (st) => (st.selection?.type === 'node' ? st.selection.id : null))
  const selectedLabel = useStore(store, (st) => st.definition.nodes.find((n) => n.id === selectedId)?.label ?? null)
  const dictation = useDictation((spoken) => setText((t) => `${t}${t && !t.endsWith(' ') ? ' ' : ''}${spoken}`.slice(0, MAX)))

  const ask = async (mode: 'edit' | 'regenerate') => {
    const typed = text.trim()
    if (typed.length < MIN || typed.length > MAX || busy) return
    dictation.stop()
    const focus = mode === 'edit' && focusStep && store.getState().definition.nodes.some((n) => n.id === focusStep.id) ? focusStep : null
    const instruction = focus ? `About the step “${focus.label}” (id ${focus.id}): ${typed}`.slice(0, MAX) : typed
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
      setTurns((all) => [...all, { id: nextId.current++, role: 'author', text: focus ? `${focus.label}: ${typed}` : typed, mode }, { id: nextId.current++, role: 'assistant', proposal, baseline, chosen: [], result: null }])
      setText('')
      setFocusStep(null)
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
            Type or say what you want, for example “Add a finance approval when the amount is 5,000 or more”. You can then follow up, such as “make it 10,000”.
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
          <Label htmlFor={inputId}>What should change?</Label>
          {focusStep ? (
            <p className="flex items-center justify-between gap-2 rounded-control bg-surface-muted px-2 py-1 text-sm" data-testid="authoring-focus">
              <span className="min-w-0 truncate">About the step <strong title={focusStep.label}>{focusStep.label}</strong></span>
              <Button variant="ghost" size="icon" className="size-6" onClick={() => setFocusStep(null)} aria-label="Stop asking about this step"><X aria-hidden="true" /></Button>
            </p>
          ) : selectedId && selectedLabel ? (
            <Button variant="secondary" size="sm" className="max-w-full" onClick={() => setFocusStep({ id: selectedId, label: selectedLabel })} data-testid="authoring-ask-step">
              <span className="truncate">Ask about “{selectedLabel}”</span>
            </Button>
          ) : (
            <p className="text-xs text-muted-foreground">Tip: select a step on the canvas to ask about just that step.</p>
          )}
          <Textarea id={inputId} rows={3} value={text} maxLength={MAX} disabled={busy} aria-describedby={`${inputId}-help`} onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} />
          <p id={`${inputId}-help`} className="text-xs text-muted-foreground">Ctrl+Enter sends. The AI sees the saved draft without secrets. You review every change before it is applied.</p>
          {dictation.problem ? <p role="status" className="text-xs text-danger">{dictation.problem}</p> : null}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => void ask('edit')} disabled={busy || length < MIN || length > MAX} loading={busy}>
            <Send aria-hidden="true" /> Send
          </Button>
          {dictation.supported ? (
            <Button variant="secondary" onClick={() => (dictation.listening ? dictation.stop() : dictation.start())} disabled={busy} aria-pressed={dictation.listening} data-testid="authoring-mic">
              {dictation.listening ? <MicOff aria-hidden="true" /> : <Mic aria-hidden="true" />} {dictation.listening ? 'Stop talking' : 'Talk'}
            </Button>
          ) : null}
          <Button variant="secondary" onClick={() => setConfirmRegenerate(true)} disabled={busy || length < MIN || length > MAX}>
            <RefreshCcw aria-hidden="true" /> Start over with AI
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
