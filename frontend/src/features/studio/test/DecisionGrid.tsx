import { Play, Plus, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { conditionTerms, type Condition, type Definition, type DecisionTable } from '../model/types'

/** Columns of a decision table's test grid: its input fields (with the value kind) and its results. */
export interface GridColumns {
  inputs: Array<{ ref: string; type: 'text' | 'number' | 'boolean' }>
  outputs: Array<{ name: string; type: 'text' | 'number' | 'boolean' }>
}

export interface GridRow {
  inputs: Record<string, string>
  expected: Record<string, string>
}

export const MAX_GRID_ROWS = 50
const BOM = new RegExp('^' + String.fromCharCode(0xfeff))

const kindOf = (v: unknown): 'text' | 'number' | 'boolean' => (typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : 'text')

export function gridColumns(table: DecisionTable): GridColumns {
  const inputs: GridColumns['inputs'] = []
  const add = (c: Condition) => {
    for (const t of conditionTerms(c)) if (!inputs.some((i) => i.ref === t.fieldRef)) inputs.push({ ref: t.fieldRef, type: kindOf(t.value) })
  }
  table.rows.forEach((r) => add(r.when))
  return { inputs, outputs: Object.entries(table.default).map(([name, v]) => ({ name, type: kindOf(v) })) }
}

/** RFC 4180 cells of one line (quotes, doubled quotes); null when quoting is unbalanced. */
function cells(line: string): string[] | null {
  const out: string[] = []
  let cur = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i] as string
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') {
        cur += '"'
        i++
      } else if (ch === '"') quoted = false
      else cur += ch
    } else if (ch === '"' && cur === '') quoted = true
    else if (ch === ',') {
      out.push(cur)
      cur = ''
    } else cur += ch
  }
  if (quoted) return null
  out.push(cur)
  return out
}

/** One leading apostrophe protects a formula-like cell in exports (S89); it is removed again here. */
const unprotect = (v: string) => (/^'[=+\-@']/.test(v) ? v.slice(1) : v)

/**
 * Bulk rows from CSV with the column headers of the decision CSV format (docs/build/S89): `in:<type>:<fieldRef>` for an
 * input and `out:<type>:<name>` for an expected result, one test case per line with plain values (not conditions).
 * Columns the table does not have are refused, so a file of another table is not read by mistake.
 */
export function parseGridCsv(text: string, columns: GridColumns): { rows: GridRow[]; error: string | null } {
  const lines = text.replace(BOM, '').split(/\r?\n/).filter((l) => l.trim() !== '')
  if (lines.length < 2) return { rows: [], error: 'Paste a header line and at least one case.' }
  const header = cells(lines[0] as string)
  if (!header) return { rows: [], error: 'Line 1: the quotes are not balanced.' }
  const map: Array<{ kind: 'in' | 'out'; key: string }> = []
  for (const [i, h] of header.entries()) {
    const m = /^(in|out):(text|number|boolean):(.+)$/.exec(h.trim())
    if (!m) return { rows: [], error: `Line 1, column ${i + 1}: use in:<type>:<field> or out:<type>:<name>.` }
    const kind = m[1] as 'in' | 'out'
    const key = m[3] as string
    if (kind === 'in' ? !columns.inputs.some((c) => c.ref === key) : !columns.outputs.some((c) => c.name === key)) return { rows: [], error: `Line 1, column ${i + 1}: this table has no ${kind === 'in' ? 'input' : 'result'} ${key}.` }
    map.push({ kind, key })
  }
  if (lines.length - 1 > MAX_GRID_ROWS) return { rows: [], error: `At most ${MAX_GRID_ROWS} cases.` }
  const rows: GridRow[] = []
  for (const [n, line] of lines.slice(1).entries()) {
    const values = cells(line)
    if (!values) return { rows: [], error: `Line ${n + 2}: the quotes are not balanced.` }
    if (values.length !== map.length) return { rows: [], error: `Line ${n + 2}: expected ${map.length} cells, found ${values.length}.` }
    const row: GridRow = { inputs: {}, expected: {} }
    values.forEach((v, i) => {
      const col = map[i] as (typeof map)[number]
      ;(col.kind === 'in' ? row.inputs : row.expected)[col.key] = unprotect(v)
    })
    rows.push(row)
  }
  return { rows, error: null }
}

function typed(text: string, type: 'text' | 'number' | 'boolean'): string | number | boolean | undefined {
  const t = text.trim()
  if (t === '') return undefined
  if (type === 'number') return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : undefined
  if (type === 'boolean') return t === 'true' ? true : t === 'false' ? false : undefined
  return text
}

interface RowResult {
  passed: boolean
  rule: string
  differences: Array<{ name: string; expected: string; actual: string }>
  error?: string
}

/**
 * "Test this table" (STU03): a grid of inputs and expected results per row, run against the saved draft through the
 * decision evaluator (the same code the runtime uses), with bulk paste from CSV. Readers can run it; nothing is stored.
 */
export function DecisionGrid({ def, wsId, workflowId, revision, flush }: { def: Definition; wsId: string; workflowId: string; revision: () => number; flush: () => Promise<boolean> }) {
  const announce = useAnnounce()
  const tables = def.decisions
  const [key, setKey] = useState(tables[0]?.key ?? '')
  const table = tables.find((t) => t.key === key)
  const columns = useMemo(() => (table ? gridColumns(table) : { inputs: [], outputs: [] }), [table])
  const [rows, setRows] = useState<GridRow[]>([{ inputs: {}, expected: {} }])
  const [results, setResults] = useState<RowResult[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [csv, setCsv] = useState('')
  const [csvError, setCsvError] = useState<string | null>(null)
  const [problem, setProblem] = useState<unknown>(null)
  if (tables.length === 0) return <p className="text-sm text-muted-foreground">This workflow has no decision table.</p>

  const label = (ref: string) => ref.replace(/^\/input\//, '').replace(/^\/steps\//, '')
  const update = (i: number, part: 'inputs' | 'expected', name: string, v: string) => {
    setRows(rows.map((r, j) => (j === i ? { ...r, [part]: { ...r[part], [name]: v } } : r)))
    setResults(null)
  }

  const run = async () => {
    if (!table) return
    setBusy(true)
    setProblem(null)
    try {
      if (!(await flush())) throw new Error('The draft could not be saved, so the table was not tested.')
      const out: RowResult[] = []
      for (const row of rows) {
        const values: Record<string, string | number | boolean> = {}
        for (const c of columns.inputs) {
          const v = typed(row.inputs[c.ref] ?? '', c.type)
          if (v !== undefined) values[c.ref] = v
        }
        try {
          const r = await endpoints.evaluateDecision(wsId, workflowId, table.key, { revision: revision(), values })
          const differences = columns.outputs
            .filter((c) => (row.expected[c.name] ?? '').trim() !== '')
            .map((c) => ({ name: c.name, expected: (row.expected[c.name] ?? '').trim(), actual: String(r.output[c.name] ?? '') }))
            .filter((d) => d.expected !== d.actual)
          out.push({ passed: differences.length === 0, rule: r.matchedRow === null || r.matchedRow === undefined ? 'Otherwise' : `Rule ${r.matchedRow + 1}`, differences })
        } catch (cause) {
          out.push({ passed: false, rule: '-', differences: [], error: cause instanceof Error ? cause.message : 'The row could not be evaluated.' })
        }
      }
      setResults(out)
      announce(`${out.filter((r) => r.passed).length} of ${out.length} table cases passed.`)
    } catch (cause) {
      setProblem(cause)
    } finally {
      setBusy(false)
    }
  }

  const paste = () => {
    const parsed = parseGridCsv(csv, columns)
    setCsvError(parsed.error)
    if (!parsed.error) {
      setRows(parsed.rows)
      setResults(null)
      announce(`Pasted ${parsed.rows.length} cases.`)
    }
  }

  const template = [...columns.inputs.map((c) => `in:${c.type}:${c.ref}`), ...columns.outputs.map((c) => `out:${c.type}:${c.name}`)].join(',')

  return (
    <div className="space-y-3" data-testid="decision-grid">
      <div>
        <Label htmlFor="grid-table">Decision table</Label>
        <Select id="grid-table" value={key} onChange={(e) => { setKey(e.target.value); setRows([{ inputs: {}, expected: {} }]); setResults(null) }}>
          {tables.map((t) => (
            <option key={t.key} value={t.key}>
              {t.key}
            </option>
          ))}
        </Select>
      </div>
      {problem ? <ProblemAlert error={problem} /> : null}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Test cases for the decision table {key}: inputs and the expected result of each row</caption>
          <thead>
            <tr>
              <th scope="col" className="pr-2">#</th>
              {columns.inputs.map((c) => (
                <th key={c.ref} scope="col" className="pr-2">{label(c.ref)}</th>
              ))}
              {columns.outputs.map((c) => (
                <th key={c.name} scope="col" className="pr-2">Expected {c.name}</th>
              ))}
              <th scope="col" className="pr-2">Result</th>
              <th scope="col"><span className="sr-only">Remove</span></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => {
              const r = results?.[i]
              return (
                <tr key={i} className="align-top" data-testid={`grid-row-${i}`}>
                  <th scope="row" className="pr-2 font-normal">{i + 1}</th>
                  {columns.inputs.map((c) => (
                    <td key={c.ref} className="pr-2">
                      <Input aria-label={`Case ${i + 1}: ${label(c.ref)}`} value={row.inputs[c.ref] ?? ''} onChange={(e) => update(i, 'inputs', c.ref, e.target.value)} className="min-w-20" />
                    </td>
                  ))}
                  {columns.outputs.map((c) => (
                    <td key={c.name} className="pr-2">
                      <Input aria-label={`Case ${i + 1}: expected ${c.name}`} value={row.expected[c.name] ?? ''} onChange={(e) => update(i, 'expected', c.name, e.target.value)} className="min-w-20" />
                    </td>
                  ))}
                  <td className="pr-2" data-testid={`grid-result-${i}`}>
                    {r ? (
                      <span className={r.passed ? 'text-success-foreground' : 'font-semibold text-danger'}>
                        {r.passed ? 'Passed' : 'Failed'} ({r.rule})
                        {r.error ? `: ${r.error}` : null}
                        {r.differences.map((d) => (
                          <span key={d.name} className="block font-normal">
                            {d.name}: expected {d.expected}, got {d.actual || 'nothing'}
                          </span>
                        ))}
                      </span>
                    ) : (
                      <span className="text-muted-foreground">Not run</span>
                    )}
                  </td>
                  <td>
                    <Button variant="ghost" size="icon" aria-label={`Remove case ${i + 1}`} disabled={rows.length === 1} onClick={() => { setRows(rows.filter((_, j) => j !== i)); setResults(null) }}>
                      <Trash2 aria-hidden="true" />
                    </Button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" disabled={rows.length >= MAX_GRID_ROWS} onClick={() => setRows([...rows, { inputs: {}, expected: {} }])}>
          <Plus aria-hidden="true" /> Add a case
        </Button>
        <Button variant="primary" size="sm" loading={busy} onClick={() => void run()} data-testid="run-grid">
          {busy ? null : <Play aria-hidden="true" />} Test this table
        </Button>
      </div>
      {results ? (
        <p role="status" className="text-sm font-semibold" data-testid="grid-summary">
          {results.filter((r) => r.passed).length} of {results.length} table cases passed
        </p>
      ) : null}
      <details>
        <summary className="cursor-pointer text-sm">Paste cases from a spreadsheet (CSV)</summary>
        <div className="mt-2 space-y-2">
          <p className="text-sm text-muted-foreground">
            Same column headers as the decision CSV: <code className="break-all">{template}</code>. One case per line with plain values.
          </p>
          <Label htmlFor="grid-csv" className="font-normal">CSV text</Label>
          <Textarea id="grid-csv" rows={5} className="font-mono text-xs" value={csv} aria-invalid={csvError ? true : undefined} aria-describedby={csvError ? 'grid-csv-error' : undefined} onChange={(e) => setCsv(e.target.value)} />
          {csvError ? (
            <p id="grid-csv-error" role="alert" className="text-sm text-danger">
              {csvError}
            </p>
          ) : null}
          <Button variant="secondary" size="sm" onClick={paste} disabled={csv.trim() === ''}>
            Use these cases
          </Button>
        </div>
      </details>
    </div>
  )
}
