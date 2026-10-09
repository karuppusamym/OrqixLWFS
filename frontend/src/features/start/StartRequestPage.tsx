import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle2 } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { isProblemError, type ProblemError } from '@/api/problem'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { keys, useStartForm } from '@/api/queries'
import * as endpoints from '@/api/endpoints'
import type { Run } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { ErrorSummary, focusFirstInvalid } from '@/components/form/ErrorSummary'
import { FormRenderer } from '@/components/form/FormRenderer'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAnnounce } from '@/components/common/Announcer'
import { buildFields, parseFormSchema, parseFormUi, type FieldDef } from '@/lib/form/schema'
import { carryOverValues, validateForm, type FieldErrors, type RawValue, type RawValues } from '@/lib/form/values'
import { describeProblem, fieldErrorMap } from '@/lib/problemMessages'
import { formatUsd } from '@/lib/money'
import { requestId, runProgressText } from '@/lib/runText'

type Stage = 'edit' | 'review' | 'submitted'
const ID_PREFIX = 'req'

/** Display a typed value the way the review step and the confirmation show it. */
export function displayValue(field: FieldDef, raw: RawValue | undefined): string {
  if (field.schema.type === 'boolean') return raw === true ? 'Yes' : 'No'
  const text = typeof raw === 'string' ? raw : ''
  if (text === '') return '—'
  return field.isMoney ? formatUsd(text) : text
}

export function StartRequestPage() {
  const { workflowId = '' } = useParams()
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const form = useStartForm(ws, workflowId)
  const idempotency = useIdempotencyKey()

  const [stage, setStage] = useState<Stage>('edit')
  const [typed, setTyped] = useState<RawValues>({})
  const [errors, setErrors] = useState<FieldErrors>({})
  const [showSummary, setShowSummary] = useState(false)
  const [businessKey, setBusinessKey] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<ProblemError | null>(null)
  const [staleNotice, setStaleNotice] = useState(false)
  const [result, setResult] = useState<Run | null>(null)
  const summaryRef = useRef<HTMLDivElement>(null)

  const schema = useMemo(() => parseFormSchema(form.data?.inputSchema), [form.data?.inputSchema])
  const ui = useMemo(() => parseFormUi(form.data?.inputUi), [form.data?.inputUi])
  const fields = useMemo(() => buildFields(schema, ui), [schema, ui])
  // Values always follow the current form: keys that no longer exist are dropped, existing keys are kept.
  const values = useMemo(() => carryOverValues(typed, fields), [typed, fields])

  // After a failed submit the summary is rendered; move focus to the first invalid field.
  const pendingFocus = useRef(false)
  useEffect(() => {
    if (pendingFocus.current && stage === 'edit') {
      pendingFocus.current = false
      focusFirstInvalid(fields, errors, ID_PREFIX)
    }
  })

  if (form.isPending) return <LoadingBlock label="Loading the request form" rows={4} />
  if (form.isError) {
    const p = isProblemError(form.error) ? form.error : null
    if (p?.code === 'NO_ACTIVE_RELEASE') {
      return (
        <>
          <PageHeader title="Start a request" />
          <Alert tone="warning" title="Not available yet">{describeProblem(p).message}</Alert>
        </>
      )
    }
    return <QueryError error={form.error} onRetry={() => void form.refetch()} what="this request form" backTo={{ to: `/w/${ws}`, label: 'Back to home' }} />
  }

  const startForm = form.data
  const change = (key: string, value: RawValue) => {
    setTyped((prev) => ({ ...prev, [key]: value }))
    if (errors[key]) setErrors((prev) => ({ ...prev, [key]: '' }))
    setSubmitError(null)
  }

  const review = () => {
    const result = validateForm(schema, ui, values)
    if (!result.valid) {
      setErrors(result.errors)
      setShowSummary(true)
      pendingFocus.current = true
      announce(`${Object.keys(result.errors).length} problems to fix`)
      focusFirstInvalid(fields, result.errors, ID_PREFIX)
      return
    }
    setErrors({})
    setShowSummary(false)
    setStaleNotice(false)
    setStage('review')
  }

  const submit = async () => {
    const { payload } = validateForm(schema, ui, values)
    const key = idempotency.keyFor(fingerprintOf(workflowId, startForm.releaseId, payload, businessKey.trim()))
    setSubmitting(true)
    setSubmitError(null)
    try {
      const { data } = await endpoints.startRun(
        ws,
        {
          workflowId,
          expectedReleaseId: startForm.releaseId,
          input: payload,
          ...(businessKey.trim() ? { businessKey: businessKey.trim() } : {}),
        },
        key,
      )
      void client.invalidateQueries({ queryKey: ['ws', ws, 'requests'] })
      void client.invalidateQueries({ queryKey: keys.tasks(ws) })
      setResult(data)
      setStage('submitted')
      announce('Request submitted')
    } catch (cause) {
      const error = toProblem(cause)
      const ui2 = describeProblem(error)
      if (ui2.behavior === 'inline-fields' && error.code === 'VALIDATION_FAILED') {
        // Server validation is authoritative: show its errors on the same fields and go back to editing.
        const mapped = fieldErrorMap(error)
        setErrors(mapped)
        setShowSummary(true)
        pendingFocus.current = true
        setStage('edit')
        idempotency.reset()
        if (!fields.some((f) => mapped[f.key])) setSubmitError(error)
      } else if (ui2.behavior === 'reload-form') {
        setStaleNotice(true)
        setStage('edit')
        idempotency.reset()
        await client.invalidateQueries({ queryKey: keys.startForm(ws, workflowId) })
      } else {
        setSubmitError(error)
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (stage === 'submitted' && result) {
    const first = result.currentSteps[0]
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader key="submitted" title="Request submitted" />
        <Card>
          <CardContent className="space-y-4 pt-5">
            <p className="flex items-center gap-2 text-lg font-semibold text-success">
              <CheckCircle2 className="size-6" aria-hidden="true" /> Your request was received.
            </p>
            <dl className="grid gap-3 text-sm sm:grid-cols-[10rem_1fr]">
              <dt className="font-semibold text-muted-foreground">Request reference</dt>
              <dd data-testid="request-id" className="font-semibold tabular-nums">{requestId(result)}</dd>
              <dt className="font-semibold text-muted-foreground">Workflow</dt>
              <dd>
                {result.workflow.name} (version {result.release.releaseNumber})
              </dd>
              <dt className="font-semibold text-muted-foreground">What happens next</dt>
              <dd>{first ? runProgressText(result) : 'Your request is being routed to the right people.'}</dd>
            </dl>
            <div className="flex flex-wrap gap-3 pt-2">
              <Button asChild variant="primary" size="lg">
                <Link to={`/w/${ws}/runs/${result.id}`}>View your request</Link>
              </Button>
              <Button asChild variant="secondary" size="lg">
                <Link to={`/w/${ws}`}>Back to home</Link>
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (stage === 'review') {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader key="review" title="Check your request" description={`${startForm.workflowName} · Version ${startForm.releaseNumber}`} />
        <Card>
          <CardContent className="space-y-5 pt-5">
            <p className="text-sm text-muted-foreground">Please check the details below. You can go back and change them before you submit.</p>
            <dl className="divide-y divide-border rounded-card border border-border" aria-label="Request summary">
              {fields.map((f) => (
                <div key={f.key} className="grid gap-1 px-4 py-3 sm:grid-cols-[14rem_1fr]">
                  <dt className="text-sm font-semibold text-muted-foreground">{f.label}</dt>
                  <dd className="whitespace-pre-wrap wrap-anywhere">{displayValue(f, values[f.key])}</dd>
                </div>
              ))}
              {businessKey.trim() ? (
                <div className="grid gap-1 px-4 py-3 sm:grid-cols-[14rem_1fr]">
                  <dt className="text-sm font-semibold text-muted-foreground">Your reference</dt>
                  <dd>{businessKey.trim()}</dd>
                </div>
              ) : null}
            </dl>
            {submitError ? (
              <ProblemAlert
                error={submitError}
                onRetry={describeProblem(submitError).retryable ? () => void submit() : undefined}
                retrying={submitting}
              />
            ) : null}
            <div className="flex flex-wrap gap-3">
              <Button variant="secondary" size="lg" onClick={() => { setSubmitError(null); setStage('edit') }} disabled={submitting}>
                Back to edit
              </Button>
              <Button variant="primary" size="lg" onClick={() => void submit()} loading={submitting}>
                {submitting ? 'Submitting' : 'Submit request'}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader key="edit" title={startForm.workflowName} description={`Version ${startForm.releaseNumber}`} />
      {staleNotice ? (
        <Alert tone="warning" className="mb-4" title="This form was updated">
          Review it and submit again. Your answers were kept where the questions still exist.
        </Alert>
      ) : null}
      {submitError ? <ProblemAlert error={submitError} className="mb-4" /> : null}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          review()
        }}
        aria-label={`${startForm.workflowName} request form`}
      >
        <Card>
          <CardContent className="space-y-5 pt-5">
            {showSummary ? <ErrorSummary ref={summaryRef} fields={fields} errors={errors} idPrefix={ID_PREFIX} /> : null}
            <FormRenderer fields={fields} values={values} onChange={change} errors={errors} idPrefix={ID_PREFIX} />
            <details className="rounded-control border border-border px-4 py-2">
              <summary className="cursor-pointer text-sm font-semibold">Add your own reference number (optional)</summary>
              <div className="space-y-1.5 pt-3 pb-2">
                <Label htmlFor="req-businessKey">Your reference</Label>
                <Input id="req-businessKey" value={businessKey} maxLength={100} onChange={(e) => setBusinessKey(e.target.value)} autoComplete="off" />
              </div>
            </details>
            <div className="flex flex-wrap gap-3 pt-2">
              <Button type="submit" variant="primary" size="lg">
                Review request
              </Button>
              <Button asChild variant="secondary" size="lg">
                <Link to={`/w/${ws}`}>Cancel</Link>
              </Button>
            </div>
          </CardContent>
        </Card>
      </form>
    </div>
  )
}
