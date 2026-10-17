import { useWorkspace } from '@/app/workspace'
import { FlaskConical, Play, RotateCcw, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { SimulationResult, ValidationIssue } from '@/api/types'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { FormRenderer } from '@/components/form/FormRenderer'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { downloadJson } from '@/lib/download'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { carryOverValues, validateForm, type FieldErrors, type RawValue, type RawValues } from '@/lib/form/values'
import { fieldErrorMap } from '@/lib/problemMessages'
import { useStudio, useStudioContext } from '../StudioContext'
import { Playback } from './Playback'
import { playbackAt, NO_PLAYBACK, useSetPlayback } from './PlaybackContext'
import { exportCases, importCases, loadCases, MAX_CASES, MAX_IMPORT_BYTES, storeCases, upsertCase, type SavedCase } from './savedCases'
import { emptySettings, expectationLabel, finishOutcomes, meetsExpectation, NEEDS_ATTENTION, stepControls, toScenario, type TestSettings } from './scenarioModel'
import { sampleValues } from './sampleValues'
import { StepSettings } from './StepSettings'
import { shareTests, runSharedTests } from './sharedTests'

type Stage = 'setup' | 'result'

interface CaseRun {
  id: string
  name: string
  pass: boolean
  text: string
}

/**
 * "Test this workflow": a sample request, what should happen at each step, then a step-by-step playback over the
 * canvas. Nothing is started, called or stored: it is a dry run of the saved draft (S7 section 5, S4 section 13).
 */
export function TestPanel({ groupNames, onClose, onGoToStep, onCheck }: { groupNames: Record<string, string>; onClose: () => void; onGoToStep: (stepId: string) => void; onCheck: () => void }) {
  const { store, saver, wsId, workflowId } = useStudioContext()
  const subjectId = useWorkspace().me.subject.id
  const def = useStudio((s) => s.definition)
  const announce = useAnnounce()
  const setPlayback = useSetPlayback()

  const schema = useMemo(() => parseFormSchema(def.inputSchema), [def.inputSchema])
  const ui = useMemo(() => parseFormUi(def.inputUi), [def.inputUi])
  const fields = useMemo(() => buildFields(schema, ui), [schema, ui])
  const controls = useMemo(() => stepControls(def), [def])
  const outcomes = useMemo(() => finishOutcomes(def), [def])

  const [stage, setStage] = useState<Stage>('setup')
  const [typed, setTyped] = useState<RawValues>(() => sampleValues(fields))
  const values = useMemo(() => carryOverValues(typed, fields), [typed, fields])
  const [errors, setErrors] = useState<FieldErrors>({})
  const [settings, setSettings] = useState<TestSettings>(emptySettings)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<unknown>(null)
  const [blockers, setBlockers] = useState<ValidationIssue[]>([])
  const [result, setResult] = useState<SimulationResult | null>(null)
  const [index, setIndex] = useState(0)
  const [cases, setCases] = useState<SavedCase[]>(() => loadCases(wsId, workflowId, subjectId))
  const [caseName, setCaseName] = useState('')
  const [caseNotice, setCaseNotice] = useState<string | null>(null)
  const [caseRuns, setCaseRuns] = useState<CaseRun[] | null>(null)
  const [runningAll, setRunningAll] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  // The canvas follows the playback while the panel is open; leaving clears it.
  useEffect(() => {
    setPlayback(stage === 'result' ? playbackAt(result, index) : NO_PLAYBACK)
  }, [stage, result, index, setPlayback])
  useEffect(() => () => setPlayback(NO_PLAYBACK), [setPlayback])
  const currentStep = stage === 'result' && result ? result.trace[Math.min(index, result.trace.length - 1)]?.stepId : undefined
  useEffect(() => {
    if (currentStep) store.getState().select({ type: 'node', id: currentStep })
  }, [currentStep, store])

  const updateCases = useCallback(
    (next: SavedCase[]) => {
      setCases(next)
      storeCases(wsId, workflowId, next, subjectId)
    },
    [wsId, workflowId, subjectId],
  )

  /** Saves the draft, then runs it. Returns the trace, or throws the problem the API answered. */
  const runDry = useCallback(
    async (payload: Record<string, unknown>, forSettings: TestSettings) => {
      const saved = await saver.flush()
      if (!saved) throw new Error('The draft could not be saved, so it was not tested.')
      const current = store.getState()
      return endpoints.simulateWorkflow(wsId, workflowId, { revision: current.revision, input: payload, scenario: toScenario(current.definition, forSettings) })
    },
    [saver, store, wsId, workflowId],
  )

  const run = async () => {
    setProblem(null)
    setBlockers([])
    const check = validateForm(schema, ui, values)
    setErrors(check.errors)
    if (!check.valid) {
      announce('Some answers in the sample request need fixing before the test can run.')
      return
    }
    setBusy(true)
    try {
      const body = await runDry(check.payload, settings)
      setResult(body)
      setIndex(0)
      setStage('result')
      announce(`Test finished: ${body.outcome.summary} ${body.trace.length} steps.`)
    } catch (cause) {
      if (isProblemError(cause) && cause.code === 'VALIDATION_FAILED' && cause.fieldErrors.length > 0) {
        setErrors(fieldErrorMap(cause))
        const scenarioError = cause.fieldErrors.find((e) => e.pointer.startsWith('/scenario'))
        if (scenarioError) setProblem(cause)
      } else if (isProblemError(cause) && cause.code === 'WORKFLOW_INVALID') {
        setBlockers(cause.issues)
      } else setProblem(toProblem(cause))
    } finally {
      setBusy(false)
    }
  }

  const tryAnother = () => {
    setStage('setup')
    setResult(null)
    announce('Change the answers or the choices, then run the test again.')
  }

  const saveCase = () => {
    const name = caseName.trim()
    if (name === '') return
    const next = upsertCase(cases, { id: `case-${Date.now().toString(36)}`, name, values, settings, expect: result ? (result.outcome.status === 'finished' ? (result.outcome.businessOutcome ?? null) : NEEDS_ATTENTION) : null })
    if (!next) {
      setCaseNotice(`You can keep ${MAX_CASES} test cases of at most 64 KB each. Delete one or reduce the case to save another.`)
      return
    }
    updateCases(next)
    setCaseName('')
    setCaseNotice(`Saved “${name}”. It expects what this run did.`)
  }

  const loadCase = (c: SavedCase) => {
    setTyped(c.values)
    setSettings(c.settings)
    setErrors({})
    setStage('setup')
    setResult(null)
    announce(`Loaded the test case ${c.name}.`)
  }

  const runAll = async () => {
    setRunningAll(true)
    setCaseRuns(null)
    const done: CaseRun[] = []
    for (const c of cases) {
      const merged = carryOverValues(c.values, fields)
      const check = validateForm(schema, ui, merged)
      if (!check.valid) {
        done.push({ id: c.id, name: c.name, pass: false, text: 'The saved answers no longer fit the request form.' })
        continue
      }
      try {
        const body = await runDry(check.payload, c.settings)
        const pass = meetsExpectation(body.outcome, c.expect)
        const got = body.outcome.status === 'finished' ? (body.outcome.businessOutcome ?? '') : 'Needs attention'
        done.push({ id: c.id, name: c.name, pass, text: pass ? `Ended as expected: ${got}.` : `Expected ${expectationLabel(c.expect)} but it ended with ${got}.` })
      } catch (cause) {
        const p = toProblem(cause)
        done.push({ id: c.id, name: c.name, pass: false, text: p.code === 'WORKFLOW_INVALID' ? 'The workflow has problems to fix first.' : (p.detail ?? p.title) })
      }
    }
    setCaseRuns(done)
    setRunningAll(false)
    announce(`${done.filter((d) => d.pass).length} of ${done.length} saved test cases passed.`)
  }

  const shareAndTest = async () => {
    setRunningAll(true)
    setProblem(null)
    try {
      const prepared = cases.map(c => {
        const check = validateForm(schema, ui, carryOverValues(c.values, fields))
        if (!check.valid || !c.expect) throw new Error('Every shared test needs valid answers and an expected outcome.')
        if (c.settings.simulateChildren) throw new Error('Shared publication tests require explicit child outcomes. Turn off recursive child simulation.')
        return { id: c.id, name: c.name, input: check.payload, scenario: toScenario(def, c.settings), expect: c.expect }
      })
      if (!await saver.flush()) throw new Error('Save the draft before sharing its tests.')
      const suite = await shareTests(wsId, workflowId, prepared)
      const report = await runSharedTests(wsId, workflowId, store.getState().revision, suite.version)
      setCaseNotice(`Shared suite ${report.suiteVersion}: ${report.results.filter(r => r.passed).length} of ${report.results.length} passed. ${report.passed ? 'This exact draft satisfies the publication test gate.' : 'Publication is blocked until the shared tests pass.'}`)
    } catch (cause) { setProblem(cause) }
    finally { setRunningAll(false) }
  }

  const onImport = async (file: File | undefined) => {
    if (!file) return
    if (file.size > MAX_IMPORT_BYTES) { setCaseNotice("The test-case file exceeds 1 MB."); return }
    const merged = importCases(await file.text(), cases)
    if (merged.error) setCaseNotice(merged.error)
    else {
      updateCases(merged.cases)
      setCaseNotice(merged.skipped > 0 ? `Imported. ${merged.skipped} could not be added (not valid, or the list is full).` : 'Imported.')
    }
    if (fileInput.current) fileInput.current.value = ''
  }

  const routes = result?.coverage.routes
  const untested = result ? (routes?.notTaken ?? []) : []

  return (
    <div className="flex h-full min-h-0 flex-col" data-testid="test-panel">
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
        <h2 className="flex items-center gap-2 text-base font-semibold">
          <FlaskConical className="size-5" aria-hidden="true" /> Test this workflow
        </h2>
        <Button variant="ghost" size="icon" onClick={onClose} aria-label="Close the test panel">
          <X aria-hidden="true" />
        </Button>
      </div>
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-3">
        <Alert tone="info" live="off">
          <p>This runs a sample request through the workflow as designed. Nothing is started, sent or saved.</p>
          <details className="mt-1">
            <summary className="cursor-pointer font-semibold">What this test cannot show</summary>
            <p className="mt-1 text-sm">It is not the workflow engine. It does not check that connections work, that people are in the groups, or how long anything takes, so a good test is not a promise that a real request will go the same way.</p>
          </details>
        </Alert>

        {problem ? <ProblemAlert error={problem} /> : null}
        {blockers.length > 0 ? (
          <Alert tone="warning" title="Fix these problems first" live="polite">
            <ul className="list-disc pl-5">
              {blockers.slice(0, 5).map((b, i) => (
                <li key={i}>{b.message}</li>
              ))}
            </ul>
            <Button variant="secondary" size="sm" className="mt-2" onClick={onCheck}>
              Check workflow
            </Button>
          </Alert>
        ) : null}

        {stage === 'setup' ? (
          <>
            <section aria-labelledby="test-sample-title">
              <h3 id="test-sample-title" className="mb-2 text-sm font-semibold">
                Sample request
              </h3>
              {fields.length === 0 ? <p className="text-sm text-muted-foreground">The request form has no questions yet.</p> : <FormRenderer fields={fields} values={values} errors={errors} idPrefix="test" onChange={(key: string, value: RawValue) => setTyped((v) => ({ ...v, [key]: value }))} />}
            </section>
            <section aria-labelledby="test-steps-title">
              <h3 id="test-steps-title" className="mb-2 text-sm font-semibold">
                What should happen at each step
              </h3>
              <StepSettings controls={controls} settings={settings} onChange={setSettings} groupNames={groupNames} />
            </section>
            <Button variant="primary" onClick={() => void run()} loading={busy} data-testid="run-test">
              {busy ? null : <Play aria-hidden="true" />} Run test
            </Button>
          </>
        ) : result ? (
          <>
            <Playback result={result} index={index} onIndex={setIndex} />
            <section aria-labelledby="test-coverage-title" data-testid="test-coverage">
              <h3 id="test-coverage-title" className="text-sm font-semibold">
                What this run covered
              </h3>
              <p className="text-sm">{result.coverage.summary}</p>
              <p className="text-sm font-semibold" data-testid="routes-tested">
                {routes ? `${routes.taken} of ${routes.total} routes tested` : ''}
              </p>
              {untested.length > 0 ? (
                <details className="mt-1">
                  <summary className="cursor-pointer text-sm">Routes not tried yet ({untested.length})</summary>
                  <ul className="mt-1 list-disc pl-5 text-sm">
                    {untested.map((r, i) => (
                      <li key={i}>{String((r as Record<string, unknown>).description)}</li>
                    ))}
                  </ul>
                </details>
              ) : null}
            </section>
            {result.warnings.length > 0 ? (
              <section aria-labelledby="test-warnings-title">
                <h3 id="test-warnings-title" className="text-sm font-semibold">
                  Things to look at ({result.warnings.length})
                </h3>
                <ul className="mt-1 space-y-1 text-sm">
                  {result.warnings.map((w, i) => (
                    <li key={i}>
                      {w.message}
                      {w.stepId ? (
                        <Button variant="link" className="ml-2" onClick={() => onGoToStep(w.stepId as string)}>
                          Go to step
                        </Button>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            <div className="flex flex-wrap items-end gap-2">
              <Button variant="secondary" onClick={tryAnother} data-testid="try-another">
                <RotateCcw aria-hidden="true" /> Try another case
              </Button>
            </div>
            <div>
              <Label htmlFor="test-case-name">Keep this case to run again later</Label>
              <div className="mt-1 flex gap-2">
                <Input id="test-case-name" value={caseName} maxLength={80} placeholder="Name, for example Over USD 5,000" onChange={(e) => setCaseName(e.target.value)} />
                <Button variant="secondary" onClick={saveCase} disabled={caseName.trim() === ''}>
                  Save case
                </Button>
              </div>
            </div>
          </>
        ) : null}

        <section aria-labelledby="test-cases-title" className="border-t border-border pt-3" data-testid="saved-cases">
          <h3 id="test-cases-title" className="text-sm font-semibold">
            Saved test cases ({cases.length} of {MAX_CASES})
          </h3>
          <p className="text-sm text-muted-foreground">Kept in this browser for you only. Export them to share with a colleague.</p>
          <p className="text-sm text-muted-foreground">Share and test replaces the workflow's shared suite. Samples are visible to designers; publishing then requires all shared tests to pass for the exact draft and dependency versions. Dry runs use simulated systems.</p>
          {caseNotice ? (
            <p role="status" className="mt-1 text-sm" data-testid="case-notice">
              {caseNotice}
            </p>
          ) : null}
          {cases.length > 0 ? (
            <ul className="mt-2 space-y-2">
              {cases.map((c) => (
                <li key={c.id} className="rounded border border-border p-2 text-sm">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-semibold">{c.name}</span>
                    <span className="flex gap-1">
                      <Button variant="secondary" size="sm" onClick={() => loadCase(c)}>
                        Load
                      </Button>
                      <Button variant="secondary" size="sm" aria-label={`Delete the test case ${c.name}`} onClick={() => updateCases(cases.filter((x) => x.id !== c.id))}>
                        Delete
                      </Button>
                    </span>
                  </div>
                  <div className="mt-1 flex items-center gap-2">
                    <Label htmlFor={`expect-${c.id}`} className="font-normal">
                      Expect
                    </Label>
                    <Select id={`expect-${c.id}`} value={c.expect ?? ''} onChange={(e) => updateCases(cases.map((x) => (x.id === c.id ? { ...x, expect: e.target.value === '' ? null : e.target.value } : x)))}>
                      <option value="">No expectation</option>
                      {outcomes.map((o) => (
                        <option key={o} value={o}>
                          {o}
                        </option>
                      ))}
                      <option value={NEEDS_ATTENTION}>Needs attention</option>
                    </Select>
                  </div>
                  {caseRuns?.find((r) => r.id === c.id) ? (
                    <p className="mt-1 font-semibold" data-testid={`case-result-${c.name}`}>
                      {caseRuns.find((r) => r.id === c.id)?.pass ? 'Passed' : 'Failed'}: <span className="font-normal">{caseRuns.find((r) => r.id === c.id)?.text}</span>
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          ) : null}
          <div className="mt-2 flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => void runAll()} loading={runningAll} disabled={cases.length === 0}>
              Run all saved cases
            </Button>
            <Button variant="secondary" onClick={() => void shareAndTest()} disabled={cases.length === 0 || runningAll}>
              Share and test for publication
            </Button>
            <Button variant="secondary" onClick={() => downloadJson(JSON.parse(exportCases(def.workflowKey, cases)), `${def.workflowKey || 'workflow'}-test-cases.json`)} disabled={cases.length === 0}>
              Export
            </Button>
            <Button variant="secondary" onClick={() => fileInput.current?.click()}>
              Import
            </Button>
            <input ref={fileInput} type="file" accept="application/json,.json" className="sr-only" aria-label="Import test cases from a file" tabIndex={-1} onChange={(e) => void onImport(e.target.files?.[0])} />
          </div>
          {caseRuns ? (
            <p className="mt-2 text-sm font-semibold" data-testid="case-summary">
              {caseRuns.filter((r) => r.pass).length} of {caseRuns.length} passed
            </p>
          ) : null}
        </section>
      </div>
    </div>
  )
}
