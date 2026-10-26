import { Download, FileDown, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { ProblemError } from '@/api/problem'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { downloadBlob } from '@/lib/download'
import { isDirty } from '@/stores/studioStore'
import { useStudioContext } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { InspectorSection } from './controls'

interface CsvError {
  line: number | null
  column: number | null
  message: string
}

/** Reads the line and column out of a `/csv/lines/<line>/columns/<column>` field error pointer. */
function csvErrors(cause: ProblemError): CsvError[] {
  return cause.fieldErrors.map((f) => {
    const m = /^\/csv\/lines\/(\d+)\/columns\/(\d+)$/.exec(f.pointer)
    return { line: m ? Number(m[1]) : null, column: m ? Number(m[2]) : null, message: f.message }
  })
}

async function bytesOf(file: File): Promise<Uint8Array> {
  if (typeof file.arrayBuffer === 'function') return new Uint8Array(await file.arrayBuffer())
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file)
  })
}

/**
 * Export, template and import of one decision table as CSV (G3, docs/build/S89-decision-csv-io.md). Import replaces the
 * table in the draft as the next draft revision (undoable, never published); every problem is listed with its line
 * and column.
 */
export function DecisionCsvSection({ tableKey }: { tableKey: string }) {
  const { store, saver, wsId, workflowId, readOnly } = useStudioContext()
  const announce = useAnnounce()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState<'export' | 'template' | 'import' | null>(null)
  const [errors, setErrors] = useState<CsvError[]>([])
  const [problem, setProblem] = useState<ProblemError | null>(null)
  const [done, setDone] = useState<string>()

  const reset = () => {
    setErrors([])
    setProblem(null)
    setDone(undefined)
  }

  const download = async (kind: 'export' | 'template') => {
    reset()
    setBusy(kind)
    try {
      if (!readOnly && !(await saver.flush())) throw new Error('save')
      const { text, filename } = await (kind === 'export' ? endpoints.exportDecisionCsv : endpoints.getDecisionCsvTemplate)(wsId, workflowId, tableKey)
      downloadBlob(new Blob([text], { type: 'text/csv;charset=utf-8' }), filename)
      announce(`Downloaded ${filename}`)
      setDone(`Downloaded ${filename}.`)
    } catch (cause) {
      const p = toProblem(cause)
      setProblem(p)
      if (isProblemError(cause) && cause.fieldErrors.length > 0) {
        setErrors(cause.fieldErrors.map((f) => ({ line: null, column: null, message: f.message })))
        setProblem(null)
      }
    } finally {
      setBusy(null)
    }
  }

  const upload = async (file: File | undefined) => {
    if (!file) return
    reset()
    setBusy('import')
    try {
      if (!(await saver.flush()) || isDirty(store.getState())) throw new Error('save')
      const { draft } = await endpoints.importDecisionCsv(wsId, workflowId, tableKey, store.getState().revision, await bytesOf(file))
      const definition = normalizeDefinition(draft.definition)
      store.setState({ definition, lastSaved: definition, revision: draft.revision, savedAt: draft.savedAt, savedBy: draft.savedBy.displayName })
      announce('The CSV file was imported into the draft. Run Check workflow to validate the workflow.')
      setDone(`Imported ${file.name} as draft revision ${draft.revision}. Nothing is published.`)
    } catch (cause) {
      if (isProblemError(cause) && cause.code === 'VALIDATION_FAILED' && cause.fieldErrors.length > 0) {
        const list = csvErrors(cause)
        setErrors(list)
        announce(`The CSV file was not imported: ${list.length} ${list.length === 1 ? 'problem' : 'problems'}.`)
      } else {
        setProblem(toProblem(cause))
      }
    } finally {
      setBusy(null)
      if (input.current) input.current.value = ''
    }
  }

  return (
    <InspectorSection title="Spreadsheet (CSV)">
      <p className="text-xs text-muted-foreground">
        Keep the rules in a spreadsheet: download this table, edit it, and import it back. Importing replaces the rows, the policy and the Otherwise result in the draft; nothing is published.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={() => void download('export')} loading={busy === 'export'} disabled={busy !== null}>
          <FileDown aria-hidden="true" /> Export CSV
        </Button>
        <Button variant="secondary" size="sm" onClick={() => void download('template')} loading={busy === 'template'} disabled={busy !== null}>
          <Download aria-hidden="true" /> Download template
        </Button>
        {!readOnly ? (
          <>
            <Button variant="secondary" size="sm" onClick={() => input.current?.click()} loading={busy === 'import'} disabled={busy !== null}>
              <Upload aria-hidden="true" /> Import CSV
            </Button>
            <input ref={input} type="file" accept=".csv,text/csv" className="sr-only" tabIndex={-1} aria-label="CSV file to import" data-testid="decision-csv-file" onChange={(e) => void upload(e.target.files?.[0])} />
          </>
        ) : null}
      </div>
      <div role="status" aria-live="polite">
        {done ? <p className="rounded-control bg-surface-muted px-3 py-2 text-sm" data-testid="decision-csv-done">{done}</p> : null}
      </div>
      {errors.length > 0 ? (
        <div role="alert" className="space-y-2 rounded-control border border-danger/40 bg-danger/5 p-3" data-testid="decision-csv-errors">
          <h4 className="text-sm font-semibold">{errors.length} {errors.length === 1 ? 'problem' : 'problems'}; nothing was changed</h4>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {errors.map((e, i) => (
              <li key={i}>
                {e.line !== null ? <strong>Line {e.line}, column {e.column}: </strong> : null}
                {e.message}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {problem ? <ProblemAlert error={problem} /> : null}
    </InspectorSection>
  )
}
