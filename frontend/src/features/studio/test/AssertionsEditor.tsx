import { Plus, Trash2 } from 'lucide-react'
import type { SimulationAssertionResult } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { CaseAssertions, OutputOp, StepControls } from './scenarioModel'

/**
 * What a saved case checks besides its outcome (STU03): output values, the path of steps, the decision rules that fired
 * and the approver choices. Every control has a visible label; rows are added and removed with buttons (keyboard).
 */
export function AssertionsEditor({
  idPrefix,
  value,
  onChange,
  controls,
  outputKeys,
  steps,
  lastPath,
  readOnly,
}: {
  idPrefix: string
  value: CaseAssertions
  onChange: (next: CaseAssertions) => void
  controls: StepControls
  /** Output keys of the workflow, offered as suggestions. */
  outputKeys: string[]
  /** All steps (id and label), for the path. */
  steps: Array<{ id: string; label: string }>
  /** The steps the last run visited, to take as the expected path. */
  lastPath: string[] | null
  readOnly: boolean
}) {
  const label = (id: string) => steps.find((s) => s.id === id)?.label ?? id
  const listId = `${idPrefix}-outputs-list`
  return (
    <fieldset className="space-y-3" disabled={readOnly} data-testid={`${idPrefix}-assertions`}>
      <legend className="text-sm font-semibold">Also check</legend>
      <datalist id={listId}>
        {outputKeys.map((k) => (
          <option key={k} value={k} />
        ))}
      </datalist>

      <div className="space-y-2">
        <p className="text-sm font-semibold">Values</p>
        {value.outputs.map((o, i) => {
          const update = (patch: Partial<CaseAssertions['outputs'][number]>) => onChange({ ...value, outputs: value.outputs.map((x, j) => (j === i ? { ...x, ...patch } : x)) })
          const id = `${idPrefix}-output-${i}`
          return (
            <div key={i} className="grid grid-cols-[1fr_auto] gap-2 rounded border border-border p-2 sm:grid-cols-[2fr_1fr_2fr_auto]">
              <div>
                <Label htmlFor={`${id}-field`} className="font-normal">
                  Output or /steps/step/value
                </Label>
                <Input id={`${id}-field`} list={listId} value={o.field} onChange={(e) => update({ field: e.target.value })} />
              </div>
              <div>
                <Label htmlFor={`${id}-op`} className="font-normal">
                  Check
                </Label>
                <Select id={`${id}-op`} value={o.op} onChange={(e) => update({ op: e.target.value as OutputOp })}>
                  <option value="equals">equals</option>
                  <option value="contains">contains</option>
                  <option value="range">is between</option>
                </Select>
              </div>
              {o.op === 'range' ? (
                <div className="flex gap-2">
                  <div>
                    <Label htmlFor={`${id}-min`} className="font-normal">
                      From
                    </Label>
                    <Input id={`${id}-min`} inputMode="decimal" value={o.min} onChange={(e) => update({ min: e.target.value })} />
                  </div>
                  <div>
                    <Label htmlFor={`${id}-max`} className="font-normal">
                      To
                    </Label>
                    <Input id={`${id}-max`} inputMode="decimal" value={o.max} onChange={(e) => update({ max: e.target.value })} />
                  </div>
                </div>
              ) : (
                <div>
                  <Label htmlFor={`${id}-value`} className="font-normal">
                    Value
                  </Label>
                  <Input id={`${id}-value`} value={o.value} onChange={(e) => update({ value: e.target.value })} />
                </div>
              )}
              <Button variant="ghost" size="icon" className="self-end" aria-label={`Remove the value check ${i + 1}`} onClick={() => onChange({ ...value, outputs: value.outputs.filter((_, j) => j !== i) })}>
                <Trash2 aria-hidden="true" />
              </Button>
            </div>
          )
        })}
        {value.outputs.length < 20 ? (
          <Button variant="secondary" size="sm" onClick={() => onChange({ ...value, outputs: [...value.outputs, { field: outputKeys[0] ?? '', op: 'equals', value: '', min: '', max: '' }] })}>
            <Plus aria-hidden="true" /> Check a value
          </Button>
        ) : null}
      </div>

      <div className="space-y-2">
        <p className="text-sm font-semibold">Path</p>
        {value.path ? (
          <div className="space-y-2 rounded border border-border p-2">
            <ol className="list-decimal pl-5 text-sm" aria-label="Expected steps">
              {value.path.steps.map((s, i) => (
                <li key={`${s}-${i}`}>{label(s)}</li>
              ))}
            </ol>
            <div className="flex flex-wrap items-end gap-2">
              <div>
                <Label htmlFor={`${idPrefix}-path-match`} className="font-normal">
                  Match
                </Label>
                <Select id={`${idPrefix}-path-match`} value={value.path.match} onChange={(e) => onChange({ ...value, path: { ...(value.path as NonNullable<CaseAssertions['path']>), match: e.target.value as 'exact' | 'inOrder' } })}>
                  <option value="exact">Exactly these steps, in this order</option>
                  <option value="inOrder">These steps in this order, others may come between</option>
                </Select>
              </div>
              <Button variant="ghost" size="sm" onClick={() => onChange({ ...value, path: null })}>
                <Trash2 aria-hidden="true" /> Remove the path check
              </Button>
            </div>
          </div>
        ) : (
          <Button variant="secondary" size="sm" disabled={!lastPath || lastPath.length === 0} onClick={() => lastPath && onChange({ ...value, path: { steps: lastPath, match: 'exact' } })}>
            <Plus aria-hidden="true" /> Expect the path of the last run
          </Button>
        )}
      </div>

      {controls.decisions.length > 0 ? (
        <div className="space-y-2">
          <p className="text-sm font-semibold">Decisions</p>
          {value.rules.map((r, i) => (
            <div key={i} className="flex flex-wrap items-end gap-2">
              <div>
                <Label htmlFor={`${idPrefix}-rule-${i}-step`} className="font-normal">
                  Decision step
                </Label>
                <Select id={`${idPrefix}-rule-${i}-step`} value={r.stepId} onChange={(e) => onChange({ ...value, rules: value.rules.map((x, j) => (j === i ? { ...x, stepId: e.target.value } : x)) })}>
                  {controls.decisions.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.label}
                    </option>
                  ))}
                </Select>
              </div>
              <div>
                <Label htmlFor={`${idPrefix}-rule-${i}-rule`} className="font-normal">
                  Rule that fires
                </Label>
                <Select id={`${idPrefix}-rule-${i}-rule`} value={r.rule} onChange={(e) => onChange({ ...value, rules: value.rules.map((x, j) => (j === i ? { ...x, rule: e.target.value } : x)) })}>
                  {Array.from({ length: controls.decisions.find((d) => d.id === r.stepId)?.rows ?? 0 }, (_, k) => (
                    <option key={k} value={String(k + 1)}>
                      Rule {k + 1}
                    </option>
                  ))}
                  <option value="otherwise">Otherwise</option>
                </Select>
              </div>
              <Button variant="ghost" size="icon" aria-label={`Remove the decision check ${i + 1}`} onClick={() => onChange({ ...value, rules: value.rules.filter((_, j) => j !== i) })}>
                <Trash2 aria-hidden="true" />
              </Button>
            </div>
          ))}
          <Button variant="secondary" size="sm" onClick={() => onChange({ ...value, rules: [...value.rules, { stepId: controls.decisions[0]?.id ?? '', rule: 'otherwise' }] })}>
            <Plus aria-hidden="true" /> Check a decision
          </Button>
        </div>
      ) : null}

      {controls.approvals.length > 0 ? (
        <div className="space-y-2">
          <p className="text-sm font-semibold">Approver choices</p>
          {value.approvals.map((r, i) => (
            <div key={i} className="flex flex-wrap items-end gap-2">
              <div>
                <Label htmlFor={`${idPrefix}-approval-${i}-step`} className="font-normal">
                  Approval step
                </Label>
                <Select id={`${idPrefix}-approval-${i}-step`} value={r.stepId} onChange={(e) => onChange({ ...value, approvals: value.approvals.map((x, j) => (j === i ? { ...x, stepId: e.target.value } : x)) })}>
                  {controls.approvals.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.label}
                    </option>
                  ))}
                </Select>
              </div>
              <div>
                <Label htmlFor={`${idPrefix}-approval-${i}-decision`} className="font-normal">
                  Decision used
                </Label>
                <Select id={`${idPrefix}-approval-${i}-decision`} value={r.decision} onChange={(e) => onChange({ ...value, approvals: value.approvals.map((x, j) => (j === i ? { ...x, decision: e.target.value as typeof r.decision } : x)) })}>
                  <option value="approved">approved</option>
                  <option value="rejected">rejected</option>
                  <option value="timedOut">timed out</option>
                </Select>
              </div>
              <Button variant="ghost" size="icon" aria-label={`Remove the approver check ${i + 1}`} onClick={() => onChange({ ...value, approvals: value.approvals.filter((_, j) => j !== i) })}>
                <Trash2 aria-hidden="true" />
              </Button>
            </div>
          ))}
          <Button variant="secondary" size="sm" onClick={() => onChange({ ...value, approvals: [...value.approvals, { stepId: controls.approvals[0]?.id ?? '', decision: 'approved' }] })}>
            <Plus aria-hidden="true" /> Check an approver choice
          </Button>
        </div>
      ) : null}
    </fieldset>
  )
}

/**
 * Assertion results with a readable expected-versus-actual diff. Failed checks come first; the table has a caption and
 * header cells for screen readers; pass and fail are said in words, not only by colour.
 */
export function AssertionResults({ results, caption }: { results: SimulationAssertionResult[]; caption: string }) {
  if (results.length === 0) return null
  const ordered = [...results.filter((r) => !r.passed), ...results.filter((r) => r.passed)]
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm" data-testid="assertion-results">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-border">
            <th scope="col" className="py-1 pr-2">
              Check
            </th>
            <th scope="col" className="py-1 pr-2">
              Result
            </th>
            <th scope="col" className="py-1 pr-2">
              Expected
            </th>
            <th scope="col" className="py-1">
              Actual
            </th>
          </tr>
        </thead>
        <tbody>
          {ordered.map((r, i) => (
            <tr key={i} className="border-b border-border align-top" data-testid={r.passed ? 'assertion-passed' : 'assertion-failed'}>
              <th scope="row" className="py-1 pr-2 font-normal">
                {r.label}
              </th>
              <td className={r.passed ? 'py-1 pr-2 text-success-foreground' : 'py-1 pr-2 font-semibold text-danger'}>{r.passed ? 'Passed' : 'Failed'}</td>
              <td className="py-1 pr-2 break-words">{r.expected}</td>
              <td className="py-1 break-words">
                {r.actual}
                {r.detail ? <p className="mt-1 text-muted-foreground">{r.detail}</p> : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
