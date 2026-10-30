import { FileDown, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError, type DmnDecisionChoice, type ProblemError } from '@/api/problem'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { downloadBlob } from '@/lib/download'
import { isDirty } from '@/stores/studioStore'
import { useStudioContext } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { InspectorSection } from './controls'

async function textOf(file: File): Promise<string> {
  if (typeof file.text === 'function') return file.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

/**
 * Standard DMN download and upload of one decision table (docs/build/S121). Download writes the portable DMN 1.3 form
 * that other DMN tools read; import replaces the table in the draft as the next draft revision (never published). A
 * file with several decisions asks which one to import.
 */
export function DecisionDmnSection({ tableKey }: { tableKey: string }) {
  const { store, saver, wsId, workflowId, readOnly } = useStudioContext()
  const announce = useAnnounce()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState<'export' | 'import' | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [problem, setProblem] = useState<ProblemError | null>(null)
  const [done, setDone] = useState<string>()
  const [pending, setPending] = useState<{ name: string; xml: string; decisions: DmnDecisionChoice[] } | null>(null)
  const [choice, setChoice] = useState('')

  const reset = () => {
    setErrors([])
    setProblem(null)
    setDone(undefined)
  }

  const download = async () => {
    reset()
    setBusy('export')
    try {
      if (!readOnly && !(await saver.flush())) throw new Error('save')
      const { blob, filename } = await endpoints.exportDecisionDmn(wsId, workflowId, tableKey)
      downloadBlob(blob, filename)
      announce(`Downloaded ${filename}`)
      setDone(`Downloaded ${filename}.`)
    } catch (cause) {
      setProblem(toProblem(cause))
    } finally {
      setBusy(null)
    }
  }

  const upload = async (name: string, xml: string, decisionId?: string) => {
    reset()
    setBusy('import')
    try {
      if (!(await saver.flush()) || isDirty(store.getState())) throw new Error('save')
      const { draft } = await endpoints.importDecisionDmn(wsId, workflowId, tableKey, store.getState().revision, xml, decisionId)
      const definition = normalizeDefinition(draft.definition)
      store.setState({ definition, lastSaved: definition, revision: draft.revision, savedAt: draft.savedAt, savedBy: draft.savedBy.displayName })
      setPending(null)
      announce('The DMN file was imported into the draft. Run Check workflow to validate the workflow.')
      setDone(`Imported ${name} as draft revision ${draft.revision}. Nothing is published.`)
    } catch (cause) {
      if (isProblemError(cause) && cause.code === 'VALIDATION_FAILED' && !decisionId && cause.decisions.length > 1 && cause.fieldErrors.length === 0) {
        const tables = cause.decisions.filter((d) => d.table)
        setPending({ name, xml, decisions: tables.length ? tables : cause.decisions })
        setChoice((tables[0] ?? cause.decisions[0])?.id ?? '')
        announce('The file has several decisions. Choose the one to import.')
      } else if (isProblemError(cause) && cause.code === 'VALIDATION_FAILED' && cause.fieldErrors.length > 0) {
        setErrors(cause.fieldErrors.map((f) => f.message))
        announce(`The DMN file was not imported: ${cause.fieldErrors.length} ${cause.fieldErrors.length === 1 ? 'problem' : 'problems'}.`)
      } else {
        setProblem(toProblem(cause))
      }
    } finally {
      setBusy(null)
      if (input.current) input.current.value = ''
    }
  }

  const pick = async (file: File | undefined) => {
    if (!file) return
    setPending(null)
    await upload(file.name, await textOf(file))
  }

  return (
    <InspectorSection title="Standard file (DMN)">
      <p className="text-xs text-muted-foreground">
        Exchange this table with other DMN tools. Importing replaces the rows, the policy and the Otherwise result in the draft; nothing is published.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={() => void download()} loading={busy === 'export'} disabled={busy !== null}>
          <FileDown aria-hidden="true" /> Download DMN
        </Button>
        {!readOnly ? (
          <>
            <Button variant="secondary" size="sm" onClick={() => input.current?.click()} loading={busy === 'import'} disabled={busy !== null}>
              <Upload aria-hidden="true" /> Import DMN
            </Button>
            <input ref={input} type="file" accept=".dmn,.xml,application/xml,text/xml" className="sr-only" tabIndex={-1} aria-label="DMN file to import" data-testid="decision-dmn-file" onChange={(e) => void pick(e.target.files?.[0])} />
          </>
        ) : null}
      </div>
      {pending ? (
        <div className="space-y-2 rounded-control border border-border p-3" data-testid="decision-dmn-choice">
          <Label htmlFor={`dmn-choice-${tableKey}`}>{pending.name} has several decisions. Import</Label>
          <Select id={`dmn-choice-${tableKey}`} value={choice} onChange={(e) => setChoice(e.target.value)}>
            {pending.decisions.map((d) => <option key={d.id} value={d.id}>{d.name === d.id ? d.id : `${d.name} (${d.id})`}</option>)}
          </Select>
          <div className="flex gap-2">
            <Button size="sm" onClick={() => void upload(pending.name, pending.xml, choice)} loading={busy === 'import'} disabled={!choice || busy !== null}>Import this decision</Button>
            <Button size="sm" variant="ghost" onClick={() => setPending(null)} disabled={busy !== null}>Cancel</Button>
          </div>
        </div>
      ) : null}
      <div role="status" aria-live="polite">
        {done ? <p className="rounded-control bg-surface-muted px-3 py-2 text-sm" data-testid="decision-dmn-done">{done}</p> : null}
      </div>
      {errors.length > 0 ? (
        <div role="alert" className="space-y-2 rounded-control border border-danger/40 bg-danger/5 p-3" data-testid="decision-dmn-errors">
          <h4 className="text-sm font-semibold">{errors.length} {errors.length === 1 ? 'problem' : 'problems'}; nothing was changed</h4>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {errors.map((e, i) => <li key={i}>{e}</li>)}
          </ul>
        </div>
      ) : null}
      {problem ? <ProblemAlert error={problem} /> : null}
    </InspectorSection>
  )
}
