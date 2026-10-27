import { useWorkspace } from '@/app/workspace'
import { useQuery } from '@tanstack/react-query'
import { FlaskConical, Play, RotateCcw, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { SimulationAssertionResult, SimulationResult, ValidationIssue } from '@/api/types'
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
import { AssertionResults, AssertionsEditor } from './AssertionsEditor'
import { DecisionGrid } from './DecisionGrid'
import { Playback } from './Playback'
import { playbackAt, NO_PLAYBACK, useSetPlayback } from './PlaybackContext'
import { CASE_VERSION, exportCases, importCases, loadCases, MAX_CASES, MAX_IMPORT_BYTES, storeCases, upsertCase, type SavedCase } from './savedCases'
import { countAssertions, emptyAssertions, emptySettings, expectationLabel, finishOutcomes, NEEDS_ATTENTION, stepControls, toAssertions, toScenario, type CallControl, type CaseAssertions, type TestSettings } from './scenarioModel'
import { sampleValues } from './sampleValues'
import { StepSettings } from './StepSettings'
import { shareTests, runSharedTests } from './sharedTests'

type Stage = 'setup' | 'result'

/** A new id for a saved case (called from event handlers only). */
const newCaseId = () => `case-${Date.now().toString(36)}`

interface CaseRun {
  id: string
  name: string
  pass: boolean
  text: string
  results: SimulationAssertionResult[]
}

/**
 * The Test tab ("Test this workflow", STU03): a sample request, what should happen at each step including mocked
 * answers of calls, adapters, workers, agents and messages, then a step-by-step playback over the canvas; saved cases
 * with assertions, run all in one action; the shared suite; and a test grid for decision tables. Nothing is started,
 * called or stored: every run is a dry run of the saved draft (S7 section 5, S4 section 13, docs/build/S96).
 * Everyone who can read the workflow can run and look; saving, changing and sharing cases needs workflow.edit.
 */
export function TestPanel({ groupNames, onClose, onGoToStep, onCheck, active = true }: { groupNames: Record<string, string>; onClose?: () => void; onGoToStep: (stepId: string) => void; onCheck: () => void; active?: boolean }) {
  const { store, saver, wsId, workflowId, connections } = useStudioContext()
  const { me, can } = useWorkspace()
  const subjectId = me.subject.id
  const canEdit = can('workflow.edit')
  const def = useStudio((s) => s.definition)
  const announce = useAnnounce()
  const setPlayback = useSetPlayback()

  const schema = useMemo(() => parseFormSchema(def.inputSchema), [def.inputSchema])
  const ui = useMemo(() => parseFormUi(def.inputUi), [def.inputUi])
  const fields = useMemo(() => buildFields(schema, ui), [schema, ui])
  const controls = useMemo(() => stepControls(def), [def])
  const outcomes = useMemo(() => finishOutcomes(def), [def])
  const outputKeys = useMemo(() => Object.keys(def.outputMapping ?? {}), [def.outputMapping])
  const allSteps = useMemo(() => def.nodes.map((n) => ({ id: n.id, label: n.label })), [def.nodes])
  const responseSchema = useCallback((c: CallControl) => connections?.responseSchema?.(c.connection, c.connectionVersion ?? undefined, c.operation), [connections])

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
  const [loadedCase, setLoadedCase] = useState<string | null>(null)
  const [caseName, setCaseName] = useState('')
  const [caseNotice, setCaseNotice] = useState<string | null>(null)
  const [caseRuns, setCaseRuns] = useState<CaseRun[] | null>(null)
  const [runningAll, setRunningAll] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const summary = useQuery({ queryKey: ['ws', wsId, 'workflow', workflowId, 'test-summary'], queryFn: () => endpoints.getWorkflowTestSummary(wsId, workflowId), staleTime: 10_000, retry: false })

  // The canvas follows the playback while the Test tab is shown; leaving clears it.
  useEffect(() => {
    setPlayback(active && stage === 'result' ? playbackAt(result, index) : NO_PLAYBACK)
  }, [active, stage, result, index, setPlayback])
  useEffect(() => () => setPlayback(NO_PLAYBACK), [setPlayback])
  const currentStep = active && stage === 'result' && result ? result.trace[Math.min(index, result.trace.length - 1)]?.stepId : undefined
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

  /** Saves the draft (when the person may edit it), so the run tests what is on screen. */
  const flush = useCallback(async () => (canEdit ? saver.flush() : true), [canEdit, saver])

  /** Saves the draft, then runs it. Returns the trace, or throws the problem the API answered. */
  const runDry = useCallback(
    async (payload: Record<string, unknown>, forSettings: TestSettings, assertions?: ReturnType<typeof toAssertions>) => {
      if (!(await flush())) throw new Error('The draft could not be saved, so it was not tested.')
      const current = store.getState()
      return endpoints.simulateWorkflow(wsId, workflowId, { revision: current.revision, input: payload, scenario: toScenario(current.definition, forSettings), ...(assertions ? { assertions } : {}) })
    },
    [flush, store, wsId, workflowId],
  )

  const loaded = cases.find((c) => c.id === loadedCase) ?? null

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
      const body = await runDry(check.payload, settings, loaded ? toAssertions(loaded.assertions, loaded.expect) : undefined)
      setResult(body)
      setIndex(0)
      setStage('result')
      const checks = body.assertions ? ` ${body.assertions.results.filter((r) => r.passed).length} of ${body.assertions.results.length} checks passed.` : ''
      announce(`Test finished: ${body.outcome.summary} ${body.trace.length} steps.${checks}`)
    } catch (cause) {
      if (isProblemError(cause) && cause.code === 'VALIDATION_FAILED' && cause.fieldErrors.length > 0) {
        setErrors(fieldErrorMap(cause))
        const scenarioError = cause.fieldErrors.find((e) => e.pointer.startsWith('/scenario') || e.pointer.startsWith('/assertions'))
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
    const existing = cases.find((c) => c.name.toLowerCase() === name.toLowerCase())
    const next = upsertCase(cases, {
      version: CASE_VERSION,
      id: newCaseId(),
      name,
      values,
      settings,
      expect: result ? (result.outcome.status === 'finished' ? (result.outcome.businessOutcome ?? null) : NEEDS_ATTENTION) : null,
      assertions: existing?.assertions ?? loaded?.assertions ?? emptyAssertions(),
    })
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
    setLoadedCase(c.id)
    setErrors({})
    setStage('setup')
    setResult(null)
    announce(`Loaded the test case ${c.name}. Its checks run with it.`)
  }

  const setAssertions = (id: string, assertions: CaseAssertions) => updateCases(cases.map((x) => (x.id === id ? { ...x, assertions } : x)))

  /** All saved cases in one dry-run action (simulateWorkflowCases); cases whose answers no longer fit are reported here. */
  const runAll = async () => {
    setRunningAll(true)
    setCaseRuns(null)
    setProblem(null)
    const done = new Map<string, CaseRun>()
    const send: Array<{ c: SavedCase; payload: Record<string, unknown> }> = []
    for (const c of cases) {
      const check = validateForm(schema, ui, carryOverValues(c.values, fields))
      if (!check.valid) done.set(c.id, { id: c.id, name: c.name, pass: false, text: 'The saved answers no longer fit the request form.', results: [] })
      else send.push({ c, payload: check.payload })
    }
    try {
      if (send.length > 0) {
        if (!(await flush())) throw new Error('The draft could not be saved, so it was not tested.')
        const current = store.getState()
        const report = await endpoints.simulateWorkflowCases(wsId, workflowId, {
          revision: current.revision,
          cases: send.map(({ c, payload }) => {
            const assertions = toAssertions(c.assertions, c.expect)
            return { id: c.id, input: payload, scenario: toScenario(current.definition, c.settings), ...(assertions ? { assertions } : {}) }
          }),
        })
        for (const r of report.results) {
          const c = cases.find((x) => x.id === r.id)
          if (!c) continue
          const results = r.assertions?.results ?? []
          const got = r.outcome ? (r.outcome.status === 'finished' ? (r.outcome.businessOutcome ?? '') : 'Needs attention') : ''
          const failed = results.filter((x) => !x.passed)
          const outcomeFailed = failed.find((x) => x.kind === 'outcome')
          const text =
            r.status === 'invalid'
              ? (r.problem ?? 'The case could not run.')
              : r.passed
                ? `Ended as expected: ${got}.${results.length > 1 ? ` All ${results.length} checks passed.` : ''}`
                : outcomeFailed
                  ? `Expected ${expectationLabel(c.expect)} but it ended with ${got}.${failed.length > 1 ? ` ${failed.length - 1} more checks failed.` : ''}`
                  : `${failed.length} of ${results.length} checks failed.`
          done.set(c.id, { id: c.id, name: c.name, pass: r.passed, text, results })
        }
      }
    } catch (cause) {
      const p = toProblem(cause)
      if (p.code === 'WORKFLOW_INVALID') for (const { c } of send) done.set(c.id, { id: c.id, name: c.name, pass: false, text: 'The workflow has problems to fix first.', results: [] })
      else setProblem(cause)
    }
    const list = cases.map((c) => done.get(c.id)).filter((r): r is CaseRun => r !== undefined)
    setCaseRuns(list)
    setRunningAll(false)
    announce(`${list.filter((d) => d.pass).length} of ${list.length} saved test cases passed.`)
  }

  const shareAndTest = async () => {
    setRunningAll(true)
    setProblem(null)
    try {
      const prepared = cases.map((c) => {
        const check = validateForm(schema, ui, carryOverValues(c.values, fields))
        if (!check.valid || !c.expect) throw new Error('Every shared test needs valid answers and an expected outcome.')
        if (c.settings.simulateChildren) throw new Error('Shared publication tests require explicit child outcomes. Turn off recursive child simulation.')
        const assertions = toAssertions(c.assertions, null)
        return { id: c.id, name: c.name, input: check.payload, scenario: toScenario(def, c.settings), expect: c.expect, ...(assertions ? { assertions } : {}) }
      })
      if (!(await saver.flush())) throw new Error('Save the draft before sharing its tests.')
      const suite = await shareTests(wsId, workflowId, prepared)
      const report = await runSharedTests(wsId, workflowId, store.getState().revision, suite.version)
      const failures = report.results.flatMap((r) => (r.failures ?? []).map((f) => `${cases.find((c) => c.id === r.id)?.name ?? r.id}: ${f.label} expected ${f.expected}, got ${f.actual}`))
      setCaseNotice(`Shared suite ${report.suiteVersion}: ${report.results.filter((r) => r.passed).length} of ${report.results.length} passed. ${report.passed ? 'This exact draft satisfies the publication test gate.' : 'Publication is blocked until the shared tests pass.'}${failures.length > 0 ? ` ${failures.slice(0, 3).join('; ')}.` : ''}`)
      void summary.refetch()
    } catch (cause) {
      setProblem(cause)
    } finally {
      setRunningAll(false)
    }
  }

  const onImport = async (file: File | undefined) => {
    if (!file) return
    if (file.size > MAX_IMPORT_BYTES) {
      setCaseNotice('The test-case file exceeds 1 MB.')
      return
    }
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
  const lastPath = result ? [...new Set(result.trace.map((e) => e.stepId))] : null
  const s = summary.data

  return (
    <div className="flex h-full min-h-0 flex-col" data-testid="test-panel">
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
        <h2 className="flex items-center gap-2 text-base font-semibold">
          <FlaskConical className="size-5" aria-hidden="true" /> Test this workflow
        </h2>
        {onClose ? (
          <Button variant="ghost" size="icon" onClick={onClose} aria-label="Close the test panel">
            <X aria-hidden="true" />
          </Button>
        ) : null}
      </div>
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-3">
        <Alert tone="info" live="off">
          <p>This runs a sample request through the workflow as designed. Nothing is started, sent or saved: calls, adapters, workers, agents and messages get the answers you choose here.</p>
          <details className="mt-1">
            <summary className="cursor-pointer font-semibold">What this test cannot show</summary>
            <p className="mt-1 text-sm">It is not the workflow engine. It does not check that connections work, that people are in the groups, or how long anything takes, so a good test is not a promise that a real request will go the same way.</p>
          </details>
        </Alert>
        {!canEdit ? (
          <p className="text-sm text-muted-foreground" data-testid="test-read-only">
            You can run tests and see the results. Saving, changing and sharing test cases needs permission to edit workflows.
          </p>
        ) : null}

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

        {loaded ? (
          <p className="text-sm" data-testid="loaded-case">
            Testing the saved case <span className="font-semibold">{loaded.name}</span> with its {countAssertions(loaded.assertions, loaded.expect)} checks.{' '}
            <Button variant="link" onClick={() => setLoadedCase(null)}>
              Run without its checks
            </Button>
          </p>
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
              <StepSettings controls={controls} settings={settings} onChange={setSettings} groupNames={groupNames} responseSchema={responseSchema} />
            </section>
            <Button variant="primary" onClick={() => void run()} loading={busy} data-testid="run-test">
              {busy ? null : <Play aria-hidden="true" />} Run test
            </Button>
          </>
        ) : result ? (
          <>
            <Playback result={result} index={index} onIndex={setIndex} />
            {result.assertions ? (
              <section aria-labelledby="test-checks-title" data-testid="test-checks">
                <h3 id="test-checks-title" className="text-sm font-semibold">
                  Checks of {loaded?.name ?? 'this case'}: {result.assertions.results.filter((r) => r.passed).length} of {result.assertions.results.length} passed
                </h3>
                <AssertionResults results={result.assertions.results} caption={`Checks of ${loaded?.name ?? 'this case'}`} />
              </section>
            ) : null}
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
            {canEdit ? (
              <div>
                <Label htmlFor="test-case-name">Keep this case to run again later</Label>
                <div className="mt-1 flex gap-2">
                  <Input id="test-case-name" value={caseName} maxLength={80} placeholder="Name, for example Over USD 5,000" onChange={(e) => setCaseName(e.target.value)} />
                  <Button variant="secondary" onClick={saveCase} disabled={caseName.trim() === ''}>
                    Save case
                  </Button>
                </div>
              </div>
            ) : null}
          </>
        ) : null}

        <section aria-labelledby="test-cases-title" className="border-t border-border pt-3" data-testid="saved-cases">
          <h3 id="test-cases-title" className="text-sm font-semibold">
            Saved test cases ({cases.length} of {MAX_CASES})
          </h3>
          <p className="text-sm text-muted-foreground">Kept in this browser for you only. Export them to share with a colleague.</p>
          {canEdit ? <p className="text-sm text-muted-foreground">Share and test replaces the workflow's shared suite. Samples are visible to designers; publishing then requires all shared tests to pass for the exact draft and dependency versions. Dry runs use simulated systems.</p> : null}
          {caseNotice ? (
            <p role="status" className="mt-1 text-sm" data-testid="case-notice">
              {caseNotice}
            </p>
          ) : null}
          {cases.length > 0 ? (
            <ul className="mt-2 space-y-2">
              {cases.map((c) => {
                const runOf = caseRuns?.find((r) => r.id === c.id)
                return (
                  <li key={c.id} className="rounded border border-border p-2 text-sm">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="font-semibold">{c.name}</span>
                      <span className="flex gap-1">
                        <Button variant="secondary" size="sm" onClick={() => loadCase(c)} aria-label={`Load the test case ${c.name}`}>
                          Load
                        </Button>
                        {canEdit ? (
                          <Button variant="secondary" size="sm" aria-label={`Delete the test case ${c.name}`} onClick={() => updateCases(cases.filter((x) => x.id !== c.id))}>
                            Delete
                          </Button>
                        ) : null}
                      </span>
                    </div>
                    <div className="mt-1 flex items-center gap-2">
                      <Label htmlFor={`expect-${c.id}`} className="font-normal">
                        Expect
                      </Label>
                      <Select id={`expect-${c.id}`} value={c.expect ?? ''} disabled={!canEdit} onChange={(e) => updateCases(cases.map((x) => (x.id === c.id ? { ...x, expect: e.target.value === '' ? null : e.target.value } : x)))}>
                        <option value="">No expectation</option>
                        {outcomes.map((o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ))}
                        <option value={NEEDS_ATTENTION}>Needs attention</option>
                      </Select>
                    </div>
                    <details className="mt-1">
                      <summary className="cursor-pointer">Checks ({countAssertions(c.assertions, c.expect)})</summary>
                      <div className="mt-2">
                        <AssertionsEditor idPrefix={`case-${c.id}`} value={c.assertions} onChange={(a) => setAssertions(c.id, a)} controls={controls} outputKeys={outputKeys} steps={allSteps} lastPath={lastPath} readOnly={!canEdit} />
                      </div>
                    </details>
                    {runOf ? (
                      <div className="mt-1">
                        <p className="font-semibold" data-testid={`case-result-${c.name}`}>
                          {runOf.pass ? 'Passed' : 'Failed'}: <span className="font-normal">{runOf.text}</span>
                        </p>
                        {!runOf.pass && runOf.results.length > 0 ? <AssertionResults results={runOf.results} caption={`Checks of ${c.name}`} /> : null}
                      </div>
                    ) : null}
                  </li>
                )
              })}
            </ul>
          ) : null}
          <div className="mt-2 flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => void runAll()} loading={runningAll} disabled={cases.length === 0}>
              Run all saved cases
            </Button>
            {canEdit ? (
              <Button variant="secondary" onClick={() => void shareAndTest()} disabled={cases.length === 0 || runningAll}>
                Share and test for publication
              </Button>
            ) : null}
            <Button variant="secondary" onClick={() => downloadJson(JSON.parse(exportCases(def.workflowKey, cases)), `${def.workflowKey || 'workflow'}-test-cases.json`)} disabled={cases.length === 0}>
              Export
            </Button>
            {canEdit ? (
              <>
                <Button variant="secondary" onClick={() => fileInput.current?.click()}>
                  Import
                </Button>
                <input ref={fileInput} type="file" accept="application/json,.json" className="sr-only" aria-label="Import test cases from a file" tabIndex={-1} onChange={(e) => void onImport(e.target.files?.[0])} />
              </>
            ) : null}
          </div>
          {caseRuns ? (
            <p role="status" className="mt-2 text-sm font-semibold" data-testid="case-summary">
              {caseRuns.filter((r) => r.pass).length} of {caseRuns.length} passed
            </p>
          ) : null}
        </section>

        <section aria-labelledby="test-shared-title" className="border-t border-border pt-3" data-testid="shared-summary">
          <h3 id="test-shared-title" className="text-sm font-semibold">
            Shared tests of this workflow
          </h3>
          <p className="text-sm">
            {!s
              ? summary.isError
                ? 'The shared tests could not be loaded.'
                : 'Loading the shared tests.'
              : s.cases === 0
                ? 'No shared tests yet.'
                : s.lastRun
                  ? `${s.lastRun.passedCases} of ${s.lastRun.totalCases} shared tests passed on draft revision ${s.lastRun.revision}${s.lastRun.revision === store.getState().revision ? ' (this draft)' : ''}.`
                  : `${s.cases} shared tests, not run yet.`}
          </p>
        </section>

        <section aria-labelledby="test-grid-title" className="border-t border-border pt-3">
          <h3 id="test-grid-title" className="mb-2 text-sm font-semibold">
            Test a decision table
          </h3>
          <DecisionGrid def={def} wsId={wsId} workflowId={workflowId} revision={() => store.getState().revision} flush={flush} />
        </section>
      </div>
    </div>
  )
}
