import { Plus, Wand2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { describeProblem, sampleFromSchema, schemaProblems } from '@/lib/jsonSchemaLite'
import { emptySettings, parseBody, type CallControl, type CallMock, type StepControls, type TestSettings } from './scenarioModel'

const NO_MOCK: CallMock = { status: '', body: '', delaySeconds: '' }

/**
 * "What should happen at each step": the choices a person or another system would make during a real request, and the
 * mocked answers of integration points (STU03): what a call, an agent, an adapter, an external worker or a message
 * answers. Everything starts on the happy path with sample answers, so only steps with a choice are listed. Nothing is
 * ever sent: the dry run reads these answers instead.
 */
export function StepSettings({
  controls,
  settings,
  onChange,
  groupNames,
  responseSchema,
  readOnly = false,
}: {
  controls: StepControls
  settings: TestSettings
  onChange: (next: TestSettings) => void
  groupNames: Record<string, string>
  /** The response schema of a call's operation, when the connection is known. */
  responseSchema?: (c: CallControl) => Record<string, unknown> | undefined
  readOnly?: boolean
}) {
  const empty = controls.approvals.length + controls.calls.length + controls.children.length + controls.messages.length + controls.workers.length + controls.adapters.length === 0
  if (empty) return <p className="text-sm text-muted-foreground">This workflow has no step where something has to be decided, so there is nothing to choose.</p>
  const set = (patch: Partial<TestSettings>) => onChange({ ...emptySettings(), ...settings, ...patch })

  return (
    <fieldset className="space-y-4" data-testid="step-settings" disabled={readOnly}>
      <legend className="sr-only">What should happen at each step</legend>
      {controls.approvals.map((c) => {
        const s = settings.approvals[c.id] ?? { outcome: 'approve' as const, reason: '' }
        return (
          <div key={c.id} className="space-y-1.5">
            <Label htmlFor={`test-approval-${c.id}`}>
              {c.label} <span className="font-normal text-muted-foreground">(goes to {groupNames[c.groupRef] ?? (c.groupRef || 'no group yet')})</span>
            </Label>
            <Select id={`test-approval-${c.id}`} value={s.outcome} onChange={(e) => set({ approvals: { ...settings.approvals, [c.id]: { ...s, outcome: e.target.value as typeof s.outcome } } })}>
              <option value="approve">They approve</option>
              <option value="reject">They reject</option>
              {c.hasTimeout ? <option value="timeout">Nobody answers in time (the time limit fires)</option> : null}
            </Select>
            {s.outcome === 'reject' ? (
              <Input aria-label={`Reason for rejecting "${c.label}"`} placeholder="Reason (optional)" value={s.reason} onChange={(e) => set({ approvals: { ...settings.approvals, [c.id]: { ...s, reason: e.target.value } } })} />
            ) : null}
          </div>
        )
      })}

      {controls.calls.map((c) => {
        const s = settings.calls[c.id] ?? { result: 'works' as const, answers: {} }
        const mock = s.mock ?? NO_MOCK
        const schema = responseSchema?.(c)
        const parsed = parseBody(mock.body)
        const problems = parsed.body !== undefined && schema ? schemaProblems(schema, parsed.body) : []
        const statusText = mock.status.trim()
        const statusBad = statusText !== '' && !(/^\d{3}$/.test(statusText) && Number(statusText) >= 200 && Number(statusText) <= 599)
        const delayBad = mock.delaySeconds.trim() !== '' && !/^\d{1,7}$/.test(mock.delaySeconds.trim())
        const setMock = (patch: Partial<CallMock>) => set({ calls: { ...settings.calls, [c.id]: { ...s, mock: { ...mock, ...patch } } } })
        const id = `test-call-${c.id}`
        return (
          <div key={c.id} className="space-y-1.5" data-testid={`mock-call-${c.id}`}>
            <Label htmlFor={id}>
              {c.label} <span className="font-normal text-muted-foreground">(the other system: {c.connection || 'not chosen yet'})</span>
            </Label>
            <Select id={id} value={s.result} onChange={(e) => set({ calls: { ...settings.calls, [c.id]: { ...s, result: e.target.value as typeof s.result } } })}>
              <option value="works">It works and answers</option>
              <option value="rejected">It refuses the request</option>
              <option value="unavailable">It does not answer (down)</option>
              <option value="timeout">It answers too late (the time limit of {c.timeoutSeconds} seconds passes)</option>
              <option value="unknownOutcome">The answer is lost, so nobody knows if it was done</option>
            </Select>
            {s.result === 'works' && c.results.length > 0 ? (
              <details>
                <summary className="cursor-pointer text-sm text-muted-foreground">Choose the values it returns (otherwise a sample answer is used)</summary>
                <div className="mt-2 space-y-2">
                  {c.results.map((r) => (
                    <div key={r.key}>
                      <Label htmlFor={`${id}-${r.key}`} className="font-normal">
                        Answer for {r.key}
                      </Label>
                      <Input id={`${id}-${r.key}`} value={s.answers[r.key] ?? ''} onChange={(e) => set({ calls: { ...settings.calls, [c.id]: { ...s, answers: { ...s.answers, [r.key]: e.target.value } } } })} />
                    </div>
                  ))}
                </div>
              </details>
            ) : null}
            <details open={s.mock !== undefined && (mock.status !== '' || mock.body !== '' || mock.delaySeconds !== '')}>
              <summary className="cursor-pointer text-sm text-muted-foreground">Mock the whole answer (HTTP status, body, delay)</summary>
              <div className="mt-2 space-y-2">
                {s.result === 'works' ? (
                  <>
                    <div>
                      <Label htmlFor={`${id}-status`} className="font-normal">
                        HTTP status (empty: the first success status)
                      </Label>
                      <Input id={`${id}-status`} inputMode="numeric" value={mock.status} aria-invalid={statusBad || undefined} aria-describedby={statusBad ? `${id}-status-error` : undefined} onChange={(e) => setMock({ status: e.target.value })} className="w-28" />
                      {statusBad ? (
                        <p id={`${id}-status-error`} className="mt-1 text-sm text-danger">
                          Use a status from 200 to 599.
                        </p>
                      ) : null}
                    </div>
                    <div>
                      <div className="flex items-center justify-between gap-2">
                        <Label htmlFor={`${id}-body`} className="font-normal">
                          Answer body (JSON)
                        </Label>
                        {schema ? (
                          <Button variant="link" size="sm" type="button" onClick={() => setMock({ body: JSON.stringify(sampleFromSchema(schema), null, 2) })}>
                            <Wand2 aria-hidden="true" /> Fill with a sample from the response schema
                          </Button>
                        ) : null}
                      </div>
                      <Textarea
                        id={`${id}-body`}
                        rows={5}
                        spellCheck={false}
                        className="font-mono text-xs"
                        value={mock.body}
                        aria-invalid={parsed.error !== undefined || problems.length > 0 || undefined}
                        aria-describedby={`${id}-body-help`}
                        onChange={(e) => setMock({ body: e.target.value })}
                      />
                      <div id={`${id}-body-help`} className="mt-1 text-sm">
                        {parsed.error ? (
                          <p className="text-danger">{parsed.error}</p>
                        ) : problems.length > 0 ? (
                          <>
                            <p className="font-semibold text-warning-foreground">Does not match the response schema, so the run will treat it as an unusable answer:</p>
                            <ul className="list-disc pl-5">
                              {problems.map((p) => (
                                <li key={`${p.at}|${p.keyword}`}>{describeProblem(p)}</li>
                              ))}
                            </ul>
                          </>
                        ) : parsed.body !== undefined && schema ? (
                          <p className="text-muted-foreground">Matches the response schema.</p>
                        ) : (
                          <p className="text-muted-foreground">{schema ? 'Checked against the response schema of the operation.' : 'The response schema is not known here; the server checks it when the test runs.'}</p>
                        )}
                      </div>
                    </div>
                  </>
                ) : null}
                <div>
                  <Label htmlFor={`${id}-delay`} className="font-normal">
                    Answers after (seconds)
                  </Label>
                  <Input id={`${id}-delay`} inputMode="numeric" className="w-28" value={mock.delaySeconds} aria-invalid={delayBad || undefined} aria-describedby={`${id}-delay-help`} onChange={(e) => setMock({ delaySeconds: e.target.value })} />
                  <p id={`${id}-delay-help`} className="mt-1 text-sm text-muted-foreground">
                    More than {c.timeoutSeconds} seconds is a timeout. The test does not wait.
                  </p>
                </div>
              </div>
            </details>
          </div>
        )
      })}

      {controls.workers.map((c) => {
        const s = settings.workers[c.id] ?? { result: 'works' as const, answers: {}, delaySeconds: '' }
        const id = `test-worker-${c.id}`
        return (
          <div key={c.id} className="space-y-1.5" data-testid={`mock-worker-${c.id}`}>
            <Label htmlFor={id}>
              {c.label} <span className="font-normal text-muted-foreground">(an external worker)</span>
            </Label>
            <Select id={id} value={s.result} onChange={(e) => set({ workers: { ...settings.workers, [c.id]: { ...s, result: e.target.value as typeof s.result } } })}>
              <option value="works">The worker finishes and returns a result</option>
              <option value="failed">The worker fails on every attempt</option>
            </Select>
            {s.result === 'works' && c.fields.length > 0 ? (
              <details>
                <summary className="cursor-pointer text-sm text-muted-foreground">Choose the result (otherwise sample values are used)</summary>
                <div className="mt-2 space-y-2">
                  {c.fields.map((f) => (
                    <div key={f.name}>
                      <Label htmlFor={`${id}-${f.name}`} className="font-normal">
                        {f.title || f.name} <span className="text-muted-foreground">({f.type})</span>
                      </Label>
                      <Input id={`${id}-${f.name}`} value={s.answers[f.name] ?? ''} onChange={(e) => set({ workers: { ...settings.workers, [c.id]: { ...s, answers: { ...s.answers, [f.name]: e.target.value } } } })} />
                    </div>
                  ))}
                </div>
              </details>
            ) : null}
          </div>
        )
      })}

      {controls.adapters.map((c) => {
        const s = settings.adapters[c.id] ?? { result: 'works' as const, rowCount: '' }
        const id = `test-adapter-${c.id}`
        return (
          <div key={c.id} className="space-y-1.5" data-testid={`mock-adapter-${c.id}`}>
            <Label htmlFor={id}>
              {c.label} <span className="font-normal text-muted-foreground">(adapter {c.adapter || 'not chosen yet'})</span>
            </Label>
            <Select id={id} value={s.result} onChange={(e) => set({ adapters: { ...settings.adapters, [c.id]: { ...s, result: e.target.value as typeof s.result } } })}>
              <option value="works">It is delivered</option>
              <option value="unavailable">It cannot be delivered</option>
              <option value="unknownOutcome">The delivery result is lost</option>
            </Select>
            {s.result === 'works' && c.kind === 'jdbc' ? (
              <div>
                <Label htmlFor={`${id}-rows`} className="font-normal">
                  Rows the database read returns
                </Label>
                <Input id={`${id}-rows`} inputMode="numeric" className="w-28" value={s.rowCount} onChange={(e) => set({ adapters: { ...settings.adapters, [c.id]: { ...s, rowCount: e.target.value } } })} />
              </div>
            ) : null}
          </div>
        )
      })}

      {controls.children.map((c) => {
        const outcomes = settings.children[c.id]?.outcomes ?? ['']
        const unit = c.mode === 'each' ? 'item' : 'attempt'
        return (
          <fieldset key={c.id} className="space-y-1.5">
            <legend className="text-sm font-semibold">
              {c.label} <span className="font-normal text-muted-foreground">(starts {c.workflowRef || 'another workflow'})</span>
            </legend>
            <p className="text-sm text-muted-foreground">
              {c.mode === 'once' ? 'How does it end? Leave empty for the usual successful outcome.' : `How does each ${unit} end, in order? Empty means the usual outcome${c.untilOutcome ? ` (${c.untilOutcome})` : ''}.`}
            </p>
            {outcomes.map((o, i) => (
              <Input key={i} aria-label={c.mode === 'once' ? `Outcome of ${c.label}` : `Outcome of ${unit} ${i + 1} of ${c.label}`} placeholder={c.mode === 'once' ? 'Outcome, for example Accepted' : `Outcome of ${unit} ${i + 1}`} value={o} onChange={(e) => set({ children: { ...settings.children, [c.id]: { outcomes: outcomes.map((x, j) => (j === i ? e.target.value : x)) } } })} />
            ))}
            {c.mode !== 'once' && outcomes.length < c.max ? (
              <Button variant="secondary" size="sm" onClick={() => set({ children: { ...settings.children, [c.id]: { outcomes: [...outcomes, ''] } } })}>
                <Plus aria-hidden="true" /> Add another {unit}
              </Button>
            ) : null}
          </fieldset>
        )
      })}

      {controls.messages.map((c) => {
        const m = settings.messageMocks[c.id] ?? { answers: {}, delaySeconds: '' }
        const id = `test-message-${c.id}`
        const arrives = (settings.messages[c.id] ?? 'arrives') === 'arrives'
        return (
          <div key={c.id} className="space-y-1.5" data-testid={`mock-message-${c.id}`}>
            <Label htmlFor={id}>{c.label}</Label>
            <Select id={id} value={settings.messages[c.id] ?? 'arrives'} onChange={(e) => set({ messages: { ...settings.messages, [c.id]: e.target.value as 'arrives' | 'timeout' } })}>
              <option value="arrives">The message arrives</option>
              <option value="timeout">The message does not arrive (the time limit fires)</option>
            </Select>
            {arrives ? (
              <details>
                <summary className="cursor-pointer text-sm text-muted-foreground">Choose what it carries and when it comes</summary>
                <div className="mt-2 space-y-2">
                  {c.fields.map((f) => (
                    <div key={f.name}>
                      <Label htmlFor={`${id}-${f.name}`} className="font-normal">
                        {f.title || f.name} <span className="text-muted-foreground">({f.type})</span>
                      </Label>
                      <Input id={`${id}-${f.name}`} value={m.answers[f.name] ?? ''} onChange={(e) => set({ messageMocks: { ...settings.messageMocks, [c.id]: { ...m, answers: { ...m.answers, [f.name]: e.target.value } } } })} />
                    </div>
                  ))}
                  <div>
                    <Label htmlFor={`${id}-delay`} className="font-normal">
                      Comes after (seconds; later than the time limit is a timeout)
                    </Label>
                    <Input id={`${id}-delay`} inputMode="numeric" className="w-36" value={m.delaySeconds} onChange={(e) => set({ messageMocks: { ...settings.messageMocks, [c.id]: { ...m, delaySeconds: e.target.value } } })} />
                  </div>
                </div>
              </details>
            ) : null}
          </div>
        )
      })}

      {controls.children.length > 0 ? (
        <div className="flex items-start gap-2">
          <Checkbox id="test-simulate-children" checked={settings.simulateChildren} onCheckedChange={(v) => set({ simulateChildren: v === true })} />
          <Label htmlFor="test-simulate-children" className="font-normal">
            Also walk through the other workflows (uses their published version, up to three levels)
          </Label>
        </div>
      ) : null}
    </fieldset>
  )
}
