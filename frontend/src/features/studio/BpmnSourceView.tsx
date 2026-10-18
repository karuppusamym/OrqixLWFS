import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { components } from '@/api/schema'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { isDirty } from '@/stores/studioStore'
import { useStudio, useStudioContext } from './StudioContext'
import { normalizeDefinition } from './model/types'

type DraftBpmn = components['schemas']['DraftBpmn']
type BpmnCheck = components['schemas']['BpmnCheck']
type Problem = BpmnCheck['problems'][number]

const KIND_LABEL: Record<Problem['kind'], string> = {
  xml: 'Unreadable',
  unsupported: 'Not supported',
  identity: 'Wrong workflow',
  blocker: 'Blocking issue',
  notRepresentable: 'Cannot be represented',
  otherSection: 'Owned by another section',
  roundTrip: 'Round trip',
  draftRoundTrip: 'Draft has no exact BPMN',
}

/**
 * BPMN source (UI03, docs/build/S56): the draft exactly as publishing would compile it. Anyone who can read the draft
 * sees it; editors can edit it when the draft converts to BPMN and back without any difference. The server checks
 * edited BPMN (nothing unsupported, the document can express every element and value, the result compiles and reads
 * back identically) and Apply saves it as the next draft revision in one undoable step. Same permissions as the YAML
 * source: reading needs workflow.read, checking and applying need workflow.edit.
 */
export function BpmnSourceView() {
  const { store, saver, wsId, workflowId, readOnly } = useStudioContext()
  const revision = useStudio((s) => s.revision)
  const dirty = useStudio((s) => isDirty(s))
  const announce = useAnnounce()
  const [nonce, setNonce] = useState(0)
  const query = useQuery({
    queryKey: ['draft-bpmn', wsId, workflowId, nonce],
    queryFn: async (): Promise<DraftBpmn> => {
      // The BPMN is compiled from a stored revision, so unsaved edits are saved first (editors only).
      if (!readOnly) await saver.flush()
      return endpoints.getDraftBpmn(wsId, workflowId, store.getState().revision)
    },
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  })
  const loaded = query.data ?? null
  const [edit, setEdit] = useState<{ nonce: number; text: string } | null>(null)
  const text = edit && edit.nonce === nonce ? edit.text : (loaded?.bpmn ?? '')
  const setText = (value: string) => setEdit({ nonce, text: value })
  const [checked, setChecked] = useState<BpmnCheck | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)

  const reload = () => {
    setChecked(null)
    setActionError(null)
    setEdit(null)
    setNonce((n) => n + 1)
  }

  const stale = loaded !== null && (loaded.revision !== revision || dirty)
  const editable = !readOnly && loaded?.available === true && loaded.roundTrip.identical && !stale
  const unchanged = text === (loaded?.bpmn ?? '')

  const check = async () => {
    if (!loaded) return null
    setBusy(true)
    setActionError(null)
    try {
      const result = await endpoints.checkDraftBpmn(wsId, workflowId, loaded.revision, text)
      setChecked(result)
      announce(result.applicable ? 'The BPMN can be applied.' : `The BPMN has ${result.problems.length} ${result.problems.length === 1 ? 'problem' : 'problems'}.`)
      return result
    } catch (cause) {
      setActionError(toProblem(cause))
      return null
    } finally {
      setBusy(false)
    }
  }

  const apply = async () => {
    if (!loaded) return
    if (isDirty(store.getState()) || store.getState().revision !== loaded.revision) {
      setActionError(null)
      announce('The draft changed since this BPMN was loaded. Reload the BPMN first.')
      return
    }
    setBusy(true)
    setActionError(null)
    try {
      const { draft } = await endpoints.applyDraftBpmn(wsId, workflowId, loaded.revision, text)
      const definition = normalizeDefinition(draft.definition)
      // One undoable step that is already saved: undo restores the previous document and autosave stores it.
      store.setState({ definition, lastSaved: definition, revision: draft.revision, savedAt: draft.savedAt, savedBy: draft.savedBy.displayName })
      announce('The BPMN was applied to the draft. Run Check workflow to validate it.')
      reload()
    } catch (cause) {
      if (isProblemError(cause) && cause.code === 'VALIDATION_FAILED' && cause.fieldErrors.length > 0) {
        setChecked({
          revision: loaded.revision,
          applicable: false,
          problems: cause.fieldErrors.map((f) => ({ kind: (f.code in KIND_LABEL ? f.code : 'unsupported') as Problem['kind'], elementId: null, message: f.message })),
          notes: [],
          changes: [],
        })
        announce('The BPMN was not applied.')
      } else {
        setActionError(toProblem(cause))
      }
    } finally {
      setBusy(false)
    }
  }

  if (query.isPending) return <p className="text-sm text-muted" data-testid="bpmn-loading">Compiling the draft to BPMN...</p>
  if (query.isError) {
    return (
      <div className="space-y-3">
        <ProblemAlert error={toProblem(query.error)} />
        <Button variant="secondary" onClick={reload}>Try again</Button>
      </div>
    )
  }
  if (!loaded) return null

  return (
    <div className="space-y-3" data-testid="bpmn-source">
      <p className="text-sm text-muted">
        The draft as BPMN 2.0, exactly as publishing would compile it (revision {loaded.revision}). It carries the workflow name, the steps, the connections{loaded.diagram ? ' and the canvas positions' : ''}.
        {loaded.keptFromDraft.length > 0 ? ` Kept from the draft and not in BPMN: ${loaded.keptFromDraft.join(', ')}.` : ''}
      </p>
      {!loaded.available ? (
        <Alert tone="warning" title="This draft has no BPMN yet" data-testid="bpmn-unavailable">
          <p>The draft has blocking issues, so it cannot be compiled. Fix them (Check workflow lists them) and reload.</p>
          {loaded.blockers.length > 0 ? (
            <ul className="mt-1 list-disc pl-5">
              {loaded.blockers.slice(0, 10).map((b, i) => <li key={i}>{b.message}</li>)}
            </ul>
          ) : null}
        </Alert>
      ) : loaded.roundTrip.identical ? (
        <Alert tone="info" live="off" data-testid="bpmn-roundtrip">
          Round trip: the draft converts to BPMN and back without any difference.
        </Alert>
      ) : (
        <Alert tone="warning" title="BPMN editing is not available for this draft" data-testid="bpmn-roundtrip">
          <p>The draft does not convert to BPMN and back exactly, so applying BPMN would lose these settings:</p>
          <ul className="mt-1 list-disc pl-5">
            {loaded.roundTrip.differences.map((d, i) => <li key={i}>{d.message}</li>)}
          </ul>
        </Alert>
      )}
      {stale ? (
        <Alert tone="info" live="off" data-testid="bpmn-stale">
          The draft changed since this BPMN was compiled. <Button variant="secondary" onClick={reload}>Reload BPMN</Button>
        </Alert>
      ) : null}
      {loaded.available ? (
        <>
          <Textarea
            aria-label="Workflow source (BPMN)"
            spellCheck={false}
            readOnly={!editable}
            value={text}
            onChange={(e) => { setText(e.target.value); setChecked(null) }}
            rows={28}
            className="w-full font-mono text-xs"
            data-testid="bpmn-text"
          />
          <p className="text-xs text-muted">{text.length.toLocaleString('en-US')} characters{editable ? '' : ' (read-only)'}</p>
        </>
      ) : null}
      {editable ? (
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => void check()} disabled={unchanged || busy}>Check BPMN</Button>
          <Button onClick={() => void apply()} disabled={unchanged || busy}>Apply BPMN to draft</Button>
          <Button variant="secondary" onClick={() => { setEdit(null); setChecked(null) }} disabled={unchanged || busy}>Discard edits</Button>
        </div>
      ) : null}
      {actionError ? <ProblemAlert error={actionError} /> : null}
      {checked && !checked.applicable ? (
        <Alert tone="danger" title="The BPMN cannot be applied" data-testid="bpmn-problems">
          <ul className="list-disc pl-5">
            {checked.problems.map((p, i) => <li key={i}><span className="font-medium">{KIND_LABEL[p.kind]}</span>{p.elementId ? ` (${p.elementId})` : ''}: {p.message}</li>)}
          </ul>
        </Alert>
      ) : null}
      {checked?.applicable ? (
        <Alert tone="success" title="The BPMN can be applied" data-testid="bpmn-ok">
          {checked.changes.length === 0 ? <p>No difference from the draft.</p> : (
            <ul className="mt-1 list-disc pl-5">
              {checked.changes.map((c, i) => <li key={i}>{c.kind === 'added' ? 'Adds' : c.kind === 'removed' ? 'Removes' : 'Changes'}: {c.what}</li>)}
            </ul>
          )}
          {checked.notes.length > 0 ? (
            <ul className="mt-1 list-disc pl-5 text-xs">
              {checked.notes.map((n, i) => <li key={i}>{n}</li>)}
            </ul>
          ) : null}
        </Alert>
      ) : null}
    </div>
  )
}
