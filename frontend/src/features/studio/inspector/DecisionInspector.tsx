import { FlaskConical, Plus, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import type { DecisionEvaluateResult } from '@/api/types'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { humanize } from '@/lib/text'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { renameDecisionKey, upsertDecision } from '../model/ops'
import { outputsInWords } from '@/lib/events'
import type { Condition, DecisionTable, Literal, WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitNumber, InspectorSection } from './controls'
import { ConditionEditor, defaultLiteral, LiteralInput } from './ConditionEditor'

/** Decision step: rule table editor and "Test this rule" (docs/build/S7-frontend.md section 5). */
export function DecisionInspector({ node }: { node: WorkflowNode }) {
  const { store, saver, wsId, workflowId, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const ref = node.config.decisionRef as string | undefined
  const table = def.decisions.find((d) => d.key === ref)
  const fields = useMemo(() => availableFields(def, node.id, connections), [def, node.id, connections])
  const [pendingField, setPendingField] = useState('')
  const [keyError, setKeyError] = useState<string>()
  const [newKey, setNewKey] = useState('')
  const [newKeyError, setNewKeyError] = useState<string>()

  if (!table) {
    return (
      <InspectorSection title="Basic">
        <p className="text-sm text-muted-foreground">This step has no rule table yet.</p>
        {!readOnly ? (
          <Button
            variant="secondary"
            onClick={() => {
              const key = ref && /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(ref) ? ref : `rule-${def.decisions.length + 1}`
              store.getState().apply((d) => ({
                ...upsertDecision(d, { key, version: 1, hitPolicy: 'UNIQUE', rows: [], default: { result: 'standard' } }),
                nodes: d.nodes.map((n) => (n.id === node.id ? { ...n, config: { ...n.config, decisionRef: key } } : n)),
              }))
            }}
          >
            Create a rule table
          </Button>
        ) : null}
      </InspectorSection>
    )
  }

  const keys = Object.keys(table.default)
  const fieldRef = table.rows[0]?.when.fieldRef ?? (pendingField || fields.find((f) => f.type === 'number')?.ref || fields[0]?.ref || '')
  const info = fields.find((f) => f.ref === fieldRef)
  const save = (next: DecisionTable) => store.getState().apply((d) => upsertDecision(d, next))

  const setField = (ref2: string) => {
    setPendingField(ref2)
    const next = fields.find((f) => f.ref === ref2)
    const numeric = next?.type === 'number' || next?.type === 'integer'
    save({
      ...table,
      rows: table.rows.map((r) => ({ ...r, when: { fieldRef: ref2, operator: numeric ? r.when.operator : 'eq', value: typeof r.when.value === (numeric ? 'number' : next?.type === 'boolean' ? 'boolean' : 'string') ? r.when.value : defaultLiteral(next?.type, next?.enumValues) } })),
    })
  }

  const addRow = () => {
    const numeric = info?.type === 'number' || info?.type === 'integer'
    const row = { when: { fieldRef, operator: numeric ? ('gte' as const) : ('eq' as const), value: defaultLiteral(info?.type, info?.enumValues) }, then: { ...table.default } }
    save({ ...table, rows: [...table.rows, row] })
  }

  const setRowWhen = (index: number, when: Condition) => save({ ...table, rows: table.rows.map((r, i) => (i === index ? { ...r, when } : r)) })
  const setRowOutput = (index: number, key: string, value: Literal) => save({ ...table, rows: table.rows.map((r, i) => (i === index ? { ...r, then: { ...r.then, [key]: value } } : r)) })
  const setDefaultOutput = (key: string, value: Literal) => save({ ...table, default: { ...table.default, [key]: value } })
  const removeRow = (index: number) => save({ ...table, rows: table.rows.filter((_, i) => i !== index) })

  const addResultKey = () => {
    const key = newKey.trim()
    if (!/^[a-z][A-Za-z0-9]{0,63}$/.test(key)) return setNewKeyError('Start with a lowercase letter; use letters and digits only.')
    if (keys.includes(key)) return setNewKeyError('That result field already exists.')
    setNewKeyError(undefined)
    setNewKey('')
    save({ ...table, default: { ...table.default, [key]: '' }, rows: table.rows.map((r) => ({ ...r, then: { ...r.then, [key]: '' } })) })
  }

  const removeResultKey = (key: string) => {
    const strip = (o: Record<string, Literal>) => Object.fromEntries(Object.entries(o).filter(([k]) => k !== key))
    save({ ...table, default: strip(table.default), rows: table.rows.map((r) => ({ ...r, then: strip(r.then) })) })
  }

  return (
    <>
      <InspectorSection title="Basic">
        <div className="space-y-1.5">
          <Label htmlFor={`dec-field-${node.id}`}>Input field</Label>
          <Select id={`dec-field-${node.id}`} data-field="table" value={fieldRef} disabled={readOnly} onChange={(e) => setField(e.target.value)}>
            {!info && fieldRef ? <option value={fieldRef}>{fieldRef}</option> : null}
            {fields.map((f) => (
              <option key={f.ref} value={f.ref}>
                {chipLabel(f)}
              </option>
            ))}
          </Select>
          <p className="text-xs text-muted-foreground">Every row looks at this same value. Rows must not overlap.</p>
        </div>

        <ol className="space-y-3" aria-label="Rule rows">
          {table.rows.map((row, i) => (
            <li key={i} className="space-y-3 rounded-card border border-border bg-surface-muted p-3" data-testid={`rule-row-${i}`}>
              <div className="flex items-center justify-between">
                <h4 className="text-sm font-semibold">Row {i + 1}: when</h4>
                {!readOnly ? (
                  <Button size="icon" variant="ghost" className="size-8" onClick={() => removeRow(i)} aria-label={`Delete row ${i + 1}`}>
                    <Trash2 aria-hidden="true" />
                  </Button>
                ) : null}
              </div>
              <ConditionEditor idBase={`dec-${node.id}-r${i}`} dataFieldBase={`rows.${i}.when`} condition={row.when} fields={fields} fixedField disabled={readOnly} onChange={(c) => setRowWhen(i, c)} />
              <div className="space-y-2 border-t border-border pt-3">
                <p className="text-xs font-semibold text-muted-foreground uppercase">Then</p>
                {keys.map((k) => (
                  <div key={k} className="space-y-1.5">
                    <Label htmlFor={`dec-${node.id}-r${i}-out-${k}`}>{humanize(k)}</Label>
                    <LiteralInput id={`dec-${node.id}-r${i}-out-${k}`} info={undefined} value={row.then[k] ?? ''} disabled={readOnly} label={humanize(k)} dataField={`rows.${i}.then.${k}`} onCommit={(v) => setRowOutput(i, k, v)} />
                  </div>
                ))}
              </div>
            </li>
          ))}
        </ol>
        {!readOnly ? (
          <Button variant="secondary" onClick={addRow}>
            <Plus aria-hidden="true" /> Add a row
          </Button>
        ) : null}

        <div className="space-y-2 rounded-card border border-border p-3" data-testid="rule-otherwise">
          <h4 className="text-sm font-semibold">Otherwise (no row matches)</h4>
          {keys.map((k) => (
            <div key={k} className="space-y-1.5">
              <Label htmlFor={`dec-${node.id}-def-${k}`}>{humanize(k)}</Label>
              <LiteralInput id={`dec-${node.id}-def-${k}`} info={undefined} value={table.default[k] ?? ''} disabled={readOnly} label={humanize(k)} dataField={`default.${k}`} onCommit={(v) => setDefaultOutput(k, v)} />
            </div>
          ))}
        </div>

        <TestRule key={`${table.key}-${fieldRef}`} fieldRef={fieldRef} info={info} tableKey={table.key} wsId={wsId} workflowId={workflowId} flush={() => saver.flush()} revision={() => store.getState().revision} />
      </InspectorSection>

      <Advanced>
        <FormField label="Table key" id={`dec-key-${node.id}`} error={keyError} description="Used in exports and in the compiled rule.">
          {(ids) => (
            <CommitInput
              id={ids.input}
              data-field="key"
              value={table.key}
              disabled={readOnly}
              spellCheck={false}
              aria-invalid={keyError ? true : undefined}
              aria-describedby={ids.describedBy}
              onCommit={(v) => {
                const result = renameDecisionKey(store.getState().definition, table.key, v.trim())
                if (result.ok) store.getState().apply(() => result.def)
                setKeyError(result.ok ? undefined : result.reason)
              }}
            />
          )}
        </FormField>
        <FormField label="Version" id={`dec-version-${node.id}`} description="Bump when you change the meaning of the table.">
          {(ids) => <CommitNumber id={ids.input} integer value={table.version} disabled={readOnly} onCommit={(v) => v !== undefined && v >= 1 && save({ ...table, version: v })} />}
        </FormField>
        <FormField label="Add a result field" optionalText id={`dec-newkey-${node.id}`} error={newKeyError} description="Each row and the Otherwise result give a value for every result field.">
          {(ids) => (
            <div className="flex gap-2">
              <Input id={ids.input} value={newKey} placeholder="route" disabled={readOnly} spellCheck={false} aria-describedby={ids.describedBy} onChange={(e) => setNewKey(e.target.value)} />
              <Button variant="secondary" onClick={addResultKey} disabled={readOnly}>
                Add
              </Button>
            </div>
          )}
        </FormField>
        {keys.length > 1 && !readOnly ? (
          <ul className="space-y-1">
            {keys.map((k) => (
              <li key={k} className="flex items-center justify-between text-sm">
                <span>Result field: {k}</span>
                <Button size="sm" variant="ghost" onClick={() => removeResultKey(k)} aria-label={`Remove result field ${k}`}>
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        ) : null}
      </Advanced>
    </>
  )
}

function TestRule({
  fieldRef,
  info,
  tableKey,
  wsId,
  workflowId,
  flush,
  revision,
}: {
  fieldRef: string
  info: { type: 'string' | 'number' | 'integer' | 'boolean'; enumValues?: string[] } | undefined
  tableKey: string
  wsId: string
  workflowId: string
  flush: () => Promise<boolean>
  revision: () => number
}) {
  const [sample, setSample] = useState<Literal>(() => defaultLiteral(info?.type, info?.enumValues))
  const [result, setResult] = useState<DecisionEvaluateResult | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const [pending, setPending] = useState(false)

  const run = async () => {
    setPending(true)
    setError(null)
    setResult(null)
    try {
      // The server evaluates the saved draft, so save first.
      const saved = await flush()
      if (!saved) throw new Error('save')
      setResult(await endpoints.evaluateDecision(wsId, workflowId, tableKey, { revision: revision(), values: { [fieldRef]: sample } }))
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="space-y-3 rounded-card border border-dashed border-input-border p-3">
      <h4 className="flex items-center gap-1.5 text-sm font-semibold">
        <FlaskConical className="size-4" aria-hidden="true" /> Test this rule
      </h4>
      <div className="space-y-1.5">
        <Label htmlFor={`test-${tableKey}`}>Sample value</Label>
        <LiteralInput id={`test-${tableKey}`} info={info} value={sample} label="Sample value" onCommit={setSample} />
      </div>
      <Button variant="secondary" onClick={() => void run()} loading={pending} disabled={!fieldRef}>
        Test this rule
      </Button>
      <div role="status" aria-live="polite" data-testid="rule-test-result">
        {result ? (
          <p className="rounded-control bg-surface-muted px-3 py-2 text-sm">
            {result.usedDefault ? 'No row matched, so the Otherwise result applies: ' : `Row ${(result.matchedRow ?? 0) + 1} matched: `}
            <strong>{outputsInWords(result.output)}</strong>
          </p>
        ) : null}
      </div>
      {error ? <ProblemAlert error={error} /> : null}
    </div>
  )
}
