import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import type { ProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { fieldErrorMap } from '@/lib/problemMessages'
import { DESCRIPTION_MAX, DESCRIPTION_MIN, generateAiDraft, jobProblem, seedConversation, useAiStatus, waitForGeneration, type AiProfile } from './api'
import { labelOf, trackJob } from './jobs'
import { saveReviewNotes } from './reviewNotes'

/** Two starting points shown under the description box (S10 section 6). */
export const EXAMPLES = [
  {
    title: 'Purchase approval',
    text: 'Employees request a purchase with a title, a business purpose, a cost center (OPS, IT or HR) and an amount in USD. Their manager approves every request; purchases of USD 5,000 or more also need approval from finance. Tell the requester the outcome.',
  },
  {
    title: 'Access request',
    text: 'Staff ask for access to a business application. They choose the application, the access level (read or write) and give a reason. The application owner approves; write access also needs approval from the security team. The requester is told whether access was granted.',
  },
] as const

function profileHint(profile: AiProfile): string {
  return profile.variablePrice ? 'Picks the model per request; cost varies.' : 'Low-cost model, the default for drafts.'
}

/**
 * "Describe it" (S10 section 6): description, optional name and model profile, then Generate draft. The result opens
 * in the studio with the AI review panel. Nothing is published; generation can take up to a minute.
 */
export function DescribeItDialog({ onClose, onBack }: { onClose: () => void; onBack?: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const navigate = useNavigate()
  const client = useQueryClient()
  const status = useAiStatus()
  const keys = useIdempotencyKey()
  const profiles = status.data?.profiles ?? []
  const [description, setDescription] = useState('')
  const [name, setName] = useState('')
  const [profile, setProfile] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [error, setError] = useState<ProblemError | null>(null)
  const [jobId, setJobId] = useState<string | null>(null)
  const watching = useRef<AbortController | null>(null)
  const chosen = profile || status.data?.defaultProfile || profiles[0]?.id || ''

  useEffect(() => {
    if (!pending) return
    const started = Date.now()
    const timer = window.setInterval(() => setElapsed(Math.round((Date.now() - started) / 1000)), 1000)
    return () => window.clearInterval(timer)
  }, [pending])

  // Leaving the page stops this dialog's polling; a draft left in the background is followed by the toast instead.
  useEffect(() => () => watching.current?.abort(), [])

  const submit = async () => {
    const text = description.trim()
    const next: Record<string, string> = {}
    if (text.length < DESCRIPTION_MIN) next.description = `Describe the workflow in at least ${DESCRIPTION_MIN} characters.`
    if (/\$\{|#\{/.test(name)) next.name = 'The name cannot contain ${ or #{.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    const body = { description: text, ...(name.trim() ? { name: name.trim() } : {}), ...(chosen ? { profile: chosen } : {}) }
    setPending(true)
    setElapsed(0)
    setError(null)
    const controller = new AbortController()
    watching.current = controller
    try {
      // 202: the generation runs on the server; this dialog polls it (and may be closed meanwhile).
      const { data: accepted } = await generateAiDraft(ws, body, keys.keyFor(fingerprintOf(ws, body)))
      setJobId(accepted.id)
      const job = await waitForGeneration(ws, accepted.id, { signal: controller.signal })
      if (job.status === 'failed' || !job.result) {
        // The same key would replay the failed job: a retry is a new command.
        keys.reset()
        setJobId(null)
        setError(jobProblem(job))
        setPending(false)
        return
      }
      saveReviewNotes(ws, job.result)
      await seedConversation(ws, job.result.workflow.id, body.description, job.result)
      await client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] })
      navigate(`/w/${ws}/workflows/${job.result.workflow.id}/edit`)
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (Object.keys(mapped).length > 0) setErrors(mapped)
      else setError(problem)
      setJobId(null)
      setPending(false)
    }
  }

  // Closing while the draft is being generated keeps it going: the toast follows it and offers "Open draft".
  const close = () => {
    if (pending && jobId) {
      watching.current?.abort()
      trackJob({ id: jobId, ws, label: labelOf(description) })
    }
    onClose()
  }

  return (
    <Dialog
      open
      onClose={close}
      size="lg"
      title="Describe your workflow"
      description="Say what should happen in plain words. Orqix drafts the steps; you review every step before anything is published."
      dismissible={!pending || !!jobId}
      footer={
        <>
          {onBack ? (
            <Button variant="ghost" onClick={onBack} disabled={pending} className="mr-auto">
              <ArrowLeft aria-hidden="true" /> Other ways to start
            </Button>
          ) : null}
          <Button variant="secondary" onClick={close} disabled={pending && !jobId}>
            {pending && jobId ? 'Close and keep drafting' : 'Cancel'}
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>
            <Sparkles aria-hidden="true" /> Generate draft
          </Button>
        </>
      }
    >
      <div className="space-y-5" data-testid="describe-it">
        <FormField
          label="What should the workflow do?"
          required
          id="ai-description"
          error={errors.description}
          description="Who asks for what, who approves, the rules (amounts, choices) and how it ends."
        >
          {(ids) => (
            <Textarea
              id={ids.input}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={DESCRIPTION_MAX}
              rows={5}
              disabled={pending}
              aria-invalid={errors.description ? true : undefined}
              aria-describedby={[ids.describedBy, 'ai-description-count'].filter(Boolean).join(' ')}
            />
          )}
        </FormField>
        <p id="ai-description-count" className="-mt-3 text-right text-xs text-muted-foreground">
          {description.length} / {DESCRIPTION_MAX} characters
        </p>
        <div>
          <p className="mb-2 text-sm font-semibold">Or start from an example</p>
          <ul className="grid gap-2 sm:grid-cols-2">
            {EXAMPLES.map((example) => (
              <li key={example.title}>
                <button
                  type="button"
                  className="h-full w-full rounded-control border border-border bg-surface p-3 text-left text-sm hover:bg-surface-muted disabled:opacity-60"
                  onClick={() => setDescription(example.text)}
                  disabled={pending}
                  aria-label={`Use the example: ${example.title}`}
                >
                  <span className="block font-semibold">{example.title}</span>
                  <span className="mt-1 line-clamp-2 block text-muted-foreground">{example.text}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
        <FormField label="Name" optionalText id="ai-name" error={errors.name} description="Leave empty to use the name the draft suggests.">
          {(ids) => (
            <Input
              id={ids.input}
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={200}
              disabled={pending}
              autoComplete="off"
              aria-invalid={errors.name ? true : undefined}
              aria-describedby={ids.describedBy}
            />
          )}
        </FormField>
        {profiles.length > 1 ? (
          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">Model</legend>
            <RadioGroup value={chosen} onValueChange={setProfile} disabled={pending}>
              {profiles.map((p) => (
                <div key={p.id} className="flex items-start gap-3 rounded-control border border-border p-3">
                  <RadioGroupItem value={p.id} id={`ai-profile-${p.id}`} />
                  <Label htmlFor={`ai-profile-${p.id}`} className="font-normal">
                    <span className="font-semibold">
                      {p.label}
                      {p.id === status.data?.defaultProfile ? <span className="ml-1.5 text-xs font-normal text-muted-foreground">(default)</span> : null}
                    </span>
                    <span className="block text-muted-foreground">{profileHint(p)}</span>
                  </Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
        ) : null}
        <div role="status" aria-live="polite" className="text-sm" data-testid="describe-progress">
          {pending ? (
            <p className="rounded-control border border-info-border bg-info-soft p-3 text-info-foreground">
              Generating a draft and checking every step. This can take up to a minute{elapsed >= 5 ? ` (${elapsed} s so far)` : ''}.
              {jobId ? " You can close this window: we'll open the draft when it's ready." : ''}
            </p>
          ) : null}
        </div>
        {error ? (
          <div className="space-y-2">
            <ProblemAlert error={error} onRetry={error.is('AI_PROVIDER_UNAVAILABLE') ? () => void submit() : undefined} />
            {error.is('AI_OUTPUT_INVALID') && error.detail ? <p className="text-sm text-muted-foreground">{error.detail}</p> : null}
          </div>
        ) : null}
      </div>
    </Dialog>
  )
}
