import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ChevronRight, FilePlus2, LayoutTemplate, ListChecks, ShieldCheck, Sparkles } from 'lucide-react'
import { useId, useState, type ComponentType } from 'react'
import { useNavigate } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { useTemplates } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { useAiEnabled } from '@/features/ai/api'
import { DescribeItDialog } from '@/features/ai/DescribeItDialog'
import { fieldErrorMap } from '@/lib/problemMessages'
import { humanize, KEY_PATTERN, toKey } from '@/lib/text'
import { GuidedWorkflowDialog } from './GuidedWorkflowDialog'
import { QuickGuideDialog } from './QuickGuideDialog'

type Path = 'choose' | 'template' | 'empty' | 'questions' | 'ai' | 'governed'

/**
 * New workflow: first "how do you want to start?", then one focused screen for that choice. Template, questions and
 * empty are deterministic; AI is one option among them, and is also available later in the studio (Ask AI). Every
 * path ends in an ordinary draft; nothing runs until it is published.
 */
export function NewWorkflowDialog({ onClose }: { onClose: () => void }) {
  const [path, setPath] = useState<Path>('choose')
  const choose = () => setPath('choose')
  if (path === 'questions') return <QuickGuideDialog onClose={onClose} onBack={choose} />
  if (path === 'ai') return <DescribeItDialog onClose={onClose} onBack={choose} />
  if (path === 'governed') return <GuidedWorkflowDialog onClose={onClose} onBack={choose} />
  if (path === 'template' || path === 'empty') return <DetailsDialog fromTemplate={path === 'template'} onClose={onClose} onBack={choose} />
  return <ChooseDialog onClose={onClose} onChoose={setPath} />
}

function ChooseDialog({ onClose, onChoose }: { onClose: () => void; onChoose: (path: Path) => void }) {
  const ai = useAiEnabled()
  return (
    <Dialog
      open
      onClose={onClose}
      size="lg"
      title="New workflow"
      description="How do you want to start? Each way gives you a draft you can change. Nothing runs until you publish."
      footer={<Button variant="secondary" onClick={onClose}>Cancel</Button>}
    >
      <div className="space-y-3" data-testid="new-workflow-choices">
        <Choice icon={LayoutTemplate} title="Start from a template" badge="Quickest" onClick={() => onChoose('template')}>
          Ready-made workflows such as purchase approval. Pick one, give it a name, then adjust it.
        </Choice>
        <Choice icon={ListChecks} title="Answer a few questions" onClick={() => onChoose('questions')}>
          Three short steps: a name, what the requester fills in, and who approves. No AI involved.
        </Choice>
        {ai ? (
          <div data-testid="describe-it-choice">
            <Choice icon={Sparkles} title="Describe it to AI" onClick={() => onChoose('ai')}>
              Say in plain words what should happen. AI drafts the steps; you review each one before publishing.
            </Choice>
          </div>
        ) : null}
        <Choice icon={FilePlus2} title="Start empty" onClick={() => onChoose('empty')}>
          Just a request form and a finish step. Add steps on the canvas yourself
          {ai ? ', and open Ask AI in the studio whenever you want help.' : '.'}
        </Choice>
        <p className="pt-1 text-sm text-muted-foreground" data-testid="new-workflow-ai-note">
          {ai
            ? 'AI is optional: start any way you like and ask AI for help in the studio only when you need it.'
            : 'AI drafting is turned off in this workspace, so none of these use AI. An administrator can turn it on in Settings.'}
        </p>
        <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3 text-sm text-muted-foreground">
          <ShieldCheck className="size-4 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1">Need working-hour deadlines, email templates and a Tier-1 sign-off from the start?</span>
          <Button size="sm" variant="ghost" onClick={() => onChoose('governed')}>Use the governed questionnaire</Button>
        </div>
      </div>
    </Dialog>
  )
}

function Choice({ icon: Icon, title, badge, onClick, children }: { icon: ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' }>; title: string; badge?: string; onClick: () => void; children: React.ReactNode }) {
  const id = useId()
  return (
    <button
      type="button"
      onClick={onClick}
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-text`}
      className="flex w-full items-start gap-3 rounded-control border border-border bg-surface p-4 text-left hover:border-primary hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2"
    >
      <Icon className="mt-0.5 size-5 shrink-0 text-primary-text" aria-hidden="true" />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2 font-semibold">
          <span id={`${id}-title`}>{title}</span>
          {badge ? <Badge tone="success">{badge}</Badge> : null}
        </span>
        <span id={`${id}-text`} className="mt-0.5 block text-sm text-muted-foreground">{children}</span>
      </span>
      <ChevronRight className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
    </button>
  )
}

/** Filter value for templates without a region or industry (the general templates). */
const GENERAL = '__general__'
const LANGUAGES: Record<string, string> = { hi: 'Hindi', de: 'German', sw: 'Swahili', fr: 'French' }

function distinct(values: Array<string | undefined>): string[] {
  return [...new Set(values.filter((v): v is string => !!v))].sort((a, b) => a.localeCompare(b))
}

function matches(value: string | undefined, filter: string): boolean {
  return filter === '' || (filter === GENERAL ? !value : value === filter)
}

function languageName(code: string): string {
  return LANGUAGES[code] ?? code
}

/** Template or empty: choose the template (if any), name it, create. Regional packs can be filtered by region and industry. */
function DetailsDialog({ fromTemplate, onClose, onBack }: { fromTemplate: boolean; onClose: () => void; onBack: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const navigate = useNavigate()
  const client = useQueryClient()
  const templates = useTemplates(ws, fromTemplate)
  const [templateId, setTemplateId] = useState('')
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyTouched, setKeyTouched] = useState(false)
  const [description, setDescription] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)

  const [region, setRegion] = useState('')
  const [industry, setIndustry] = useState('')

  const effectiveKey = keyTouched ? key : toKey(name)
  const all = templates.data?.items ?? []
  const regions = distinct(all.map((t) => t.region))
  const industries = distinct(all.map((t) => t.industry))
  const items = all.filter((t) => matches(t.region, region) && matches(t.industry, industry))
  const chosenTemplate = fromTemplate ? (items.some((t) => t.id === templateId) ? templateId : items[0]?.id || '') : ''

  const submit = async () => {
    const next: Record<string, string> = {}
    if (fromTemplate && !chosenTemplate) next.template = 'Choose a template.'
    if (!name.trim()) next.name = 'Give the workflow a name.'
    if (!KEY_PATTERN.test(effectiveKey)) next.key = 'Use 2 or more lowercase letters, digits and dashes, starting with a letter.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setPending(true)
    setError(null)
    try {
      const created = await endpoints.createWorkflow(ws, {
        key: effectiveKey,
        name: name.trim(),
        ...(description.trim() ? { description: description.trim() } : {}),
        ...(chosenTemplate ? { templateId: chosenTemplate } : {}),
      })
      await client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] })
      navigate(`/w/${ws}/workflows/${created.id}/edit`)
    } catch (cause) {
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (problem.code === 'DUPLICATE_KEY') setErrors({ key: 'A workflow with this key already exists. Choose a different key.' })
      else if (Object.keys(mapped).length > 0) setErrors(mapped)
      else setError(problem)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      size={fromTemplate ? 'lg' : 'md'}
      title={fromTemplate ? 'Start from a template' : 'Start empty'}
      description={fromTemplate ? 'Pick a template and name your copy. You can change every step afterwards.' : 'Name the workflow; it opens in the studio with a request form and a finish step.'}
      dismissible={!pending}
      footer={
        <>
          <Button variant="ghost" onClick={onBack} disabled={pending} className="mr-auto">
            <ArrowLeft aria-hidden="true" /> Other ways to start
          </Button>
          <Button variant="secondary" onClick={onClose} disabled={pending}>Cancel</Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>Create workflow</Button>
        </>
      }
    >
      <div className="space-y-5">
        {fromTemplate ? (
          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">Template</legend>
            {regions.length > 0 || industries.length > 0 ? (
              <div className="flex flex-wrap items-end gap-3" data-testid="template-filters">
                {regions.length > 0 ? (
                  <div className="min-w-40 flex-1 space-y-1">
                    <Label htmlFor="tpl-region">Region</Label>
                    <Select id="tpl-region" value={region} onChange={(e) => setRegion(e.target.value)}>
                      <option value="">All regions</option>
                      <option value={GENERAL}>No specific region</option>
                      {regions.map((r) => <option key={r} value={r}>{r}</option>)}
                    </Select>
                  </div>
                ) : null}
                {industries.length > 0 ? (
                  <div className="min-w-40 flex-1 space-y-1">
                    <Label htmlFor="tpl-industry">Industry</Label>
                    <Select id="tpl-industry" value={industry} onChange={(e) => setIndustry(e.target.value)}>
                      <option value="">All industries</option>
                      <option value={GENERAL}>General</option>
                      {industries.map((i) => <option key={i} value={i}>{i}</option>)}
                    </Select>
                  </div>
                ) : null}
                <p className="pb-2 text-sm text-muted-foreground" aria-live="polite">{items.length} of {all.length} templates</p>
              </div>
            ) : null}
            {templates.isPending ? (
              <p className="text-sm text-muted-foreground">Loading templates…</p>
            ) : all.length === 0 ? (
              <p className="text-sm">There are no templates in this workspace yet. Go back and start empty or answer a few questions.</p>
            ) : items.length === 0 ? (
              <p className="text-sm">No template matches this region and industry. Choose another region or industry.</p>
            ) : (
              <RadioGroup value={chosenTemplate} onValueChange={setTemplateId}>
                {items.map((t) => (
                  <div key={t.id} className="flex items-start gap-3 rounded-control border border-border p-3">
                    <RadioGroupItem value={t.id} id={`tpl-${t.id}`} />
                    <Label htmlFor={`tpl-${t.id}`} className="font-normal">
                      <span className="font-semibold">{t.name}</span>
                      {t.region || t.industry ? (
                        <span className="mt-1 flex flex-wrap gap-1">
                          {t.region ? <Badge tone="info">{t.region}</Badge> : null}
                          {t.industry ? <Badge tone="neutral">{t.industry}</Badge> : null}
                          {(t.languages ?? []).length > 0 ? <Badge tone="neutral">English and {(t.languages ?? []).map(languageName).join(', ')} labels</Badge> : null}
                        </span>
                      ) : null}
                      <span className="block text-muted-foreground">{t.description}</span>
                      <span className="mt-1 block text-xs text-muted-foreground">
                        Needs approver groups: {(t.requiredGroups ?? []).map(humanize).join(', ') || 'none'} &middot; connections: {(t.requiredConnections ?? []).map(humanize).join(', ') || 'none'}{(t.requiredWorkflows ?? []).length > 0 ? <> &middot; starts workflows: {(t.requiredWorkflows ?? []).map(humanize).join(', ')}</> : null}
                      </span>
                    </Label>
                  </div>
                ))}
              </RadioGroup>
            )}
            {errors.template ? <p role="alert" className="text-sm text-danger">{errors.template}</p> : null}
          </fieldset>
        ) : null}
        <FormField label="Name" required id="wf-name" error={errors.name}>
          {(ids) => <Input id={ids.input} value={name} onChange={(e) => setName(e.target.value)} maxLength={200} aria-invalid={errors.name ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" />}
        </FormField>
        <FormField label="Key" required id="wf-key" error={errors.key} description="Filled in from the name. Used in links and exports.">
          {(ids) => (
            <Input
              id={ids.input}
              value={effectiveKey}
              onChange={(e) => {
                setKey(e.target.value)
                setKeyTouched(true)
              }}
              maxLength={64}
              aria-invalid={errors.key ? true : undefined}
              aria-describedby={ids.describedBy}
              autoComplete="off"
              spellCheck={false}
            />
          )}
        </FormField>
        <FormField label="Description" optionalText id="wf-desc">
          {(ids) => <Textarea id={ids.input} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} rows={2} />}
        </FormField>
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}
