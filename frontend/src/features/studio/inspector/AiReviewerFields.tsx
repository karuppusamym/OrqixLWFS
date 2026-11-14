import { useQuery } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { FormField } from '@/components/common/FormField'
import { Alert } from '@/components/ui/alert'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitInput, InspectorSection } from './controls'

type AiSkill = components['schemas']['AiSkill']
type ReviewerAutonomy = 'suggest' | 'autoWithReview' | 'auto'

/** The AI reviewer of an approval step as stored in the definition (docs/build/S152). */
export interface AiReviewerConfig {
  skill: { key: string; version: number }
  autonomy?: ReviewerAutonomy
  minConfidence?: number
  reviewSamplePercent?: number
  inputs: Record<string, string>
  redaction?: Record<string, string>
}

export const REVIEWER_AUTONOMY_LABEL: Record<ReviewerAutonomy, string> = {
  suggest: 'Suggest: the approver sees the AI suggestion and decides (default)',
  autoWithReview: 'Decide when confident, with a review sample afterwards',
  auto: 'Decide when confident (low-risk answers only)',
}
const AUTONOMY_ORDER: ReviewerAutonomy[] = ['suggest', 'autoWithReview', 'auto']
const REDACTION: Record<string, string> = {
  '': 'Workspace policy only',
  maskCardNumbersAndEmails: 'Mask card numbers and e-mail addresses',
  maskDigits: 'Mask every digit',
  hash: 'Send a hash instead of the value',
  drop: 'Do not send',
}

interface SkillDocument {
  mode?: string
  input?: Record<string, { type: string; required?: boolean; classification?: string }>
  labels?: Array<{ key: string }>
  outcomes?: Array<{ key: string } | string>
}

/** The answers a skill version can give: its labels (classify) or outcomes (recommend). */
export function skillAnswers(document: SkillDocument | undefined): string[] {
  if (!document) return []
  if (document.mode === 'recommend') return (document.outcomes ?? []).map((o) => (typeof o === 'string' ? o : o.key))
  return (document.labels ?? []).map((l) => l.key)
}

/**
 * Skills that can review an approval (mirrors the compiler, docs/build/S152): an approved classify or recommend version
 * that answers both approve and reject.
 */
export function eligibleReviewerSkills(skills: AiSkill[]): AiSkill[] {
  return skills.filter((s) => {
    if (s.mode !== 'classify' && s.mode !== 'recommend') return false
    const approved = s.versions.find((v) => v.version === s.approvedVersion)
    const answers = skillAnswers(approved?.document as SkillDocument | undefined)
    return answers.includes('approve') && answers.includes('reject')
  })
}

/** Why the step cannot have an AI reviewer in this version, or null. */
export function reviewerBlocker(node: WorkflowNode): string | null {
  if (cfg<{ approvers: number }>(node, 'multiInstance')) return 'This step needs several people to approve, so an AI reviewer cannot decide it on its own.'
  if (cfg(node, 'perItem')) return 'This step opens one task per list item, so an AI reviewer cannot decide it on its own.'
  const access = cfg<Record<string, string>>(node, 'fieldAccess') ?? {}
  const capture = cfg<{ required?: string[] }>(node, 'capture')
  if (Object.values(access).includes('required') || (capture?.required?.length ?? 0) > 0) return 'The approver must fill in fields at this step, so an AI reviewer cannot decide it on its own.'
  return null
}

/**
 * "AI reviewer" of an approval step (docs/build/S152): an approved AI skill that answers approve or reject reviews
 * each task. With "suggest" the approver sees the suggestion; with the deciding levels a confident answer decides the
 * task, and anything else stays with people. Same skill picker, threshold and input mapping as the AI task.
 */
export function AiReviewerFields({ node }: { node: WorkflowNode }) {
  const { store, wsId, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const query = useQuery({
    queryKey: ['ws', wsId, 'ai-skills'],
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/ai-skills', { params: { path: { workspaceId: wsId } } })),
    retry: false,
  })
  const reviewer = cfg<AiReviewerConfig>(node, 'aiReviewer')
  const skills = eligibleReviewerSkills(query.data?.items ?? [])
  const skill = skills.find((s) => s.key === reviewer?.skill.key)
  const pinned = skill?.versions.find((v) => v.version === reviewer?.skill.version)
  const document = pinned?.document as SkillDocument | undefined
  const inputs = Object.entries(document?.input ?? {})
  const autonomy = reviewer?.autonomy ?? 'suggest'
  const ceiling = (pinned?.autonomyCeiling ?? 'suggest') as ReviewerAutonomy
  const overCeiling = AUTONOMY_ORDER.indexOf(autonomy) > AUTONOMY_ORDER.indexOf(ceiling)
  const candidates = availableFields(def, node.id, connections).filter((f) => f.type !== 'array')
  const blocker = reviewerBlocker(node)
  const set = (next: AiReviewerConfig | undefined) => store.getState().updateNodeConfig(node.id, { aiReviewer: next })
  const patch = (p: Partial<AiReviewerConfig>) => {
    if (!reviewer) return
    const next: Record<string, unknown> = { ...reviewer, ...p }
    for (const k of Object.keys(next)) if (next[k] === undefined) delete next[k]
    set(next as unknown as AiReviewerConfig)
  }

  const choose = (key: string) => {
    const next = skills.find((s) => s.key === key)
    if (!key || !next?.approvedVersion) {
      set(undefined)
      return
    }
    set({ skill: { key, version: next.approvedVersion }, autonomy: reviewer?.autonomy ?? 'suggest', inputs: {} })
  }

  return (
    <InspectorSection title="AI reviewer">
      <p className="text-sm text-muted-foreground">An approved AI skill reviews each task of this step and suggests approve or reject. It can decide on its own only when it is confident and you allow it; otherwise a person decides with the suggestion.</p>
      <FormField label="Reviewing skill" optionalText id={`ai-reviewer-skill-${node.id}`} description="Approved classify or recommend skills that answer approve or reject.">
        {(ids) => (
          <Select id={ids.input} value={reviewer?.skill.key ?? ''} disabled={readOnly || (!reviewer && blocker !== null)} aria-describedby={ids.describedBy} onChange={(e) => choose(e.target.value)}>
            <option value="">No AI reviewer</option>
            {reviewer && !skill ? <option value={reviewer.skill.key}>{reviewer.skill.key} (not available)</option> : null}
            {skills.map((s) => <option key={s.key} value={s.key}>{s.name} ({s.mode}) - v{s.approvedVersion}</option>)}
          </Select>
        )}
      </FormField>
      {query.isSuccess && skills.length === 0 && !reviewer ? <p className="text-sm text-muted-foreground">No approved skill answers approve and reject yet.</p> : null}
      {blocker ? <Alert tone={reviewer ? 'warning' : 'info'}>{blocker}</Alert> : null}
      {reviewer ? <>
        {pinned && !pinned.evaluationGate.passing ? <Alert tone="warning">Version {pinned.version} has no passing evaluation on its model profile in the last 30 days, so this workflow cannot be published. Run its evaluation suite first.</Alert> : null}
        {pinned?.driftFallback ? <Alert tone="warning">Agreement with people dropped; this skill only suggests until someone clears it.</Alert> : null}
        <FormField label="How much the AI reviewer may decide" id={`ai-reviewer-autonomy-${node.id}`} error={overCeiling ? `This skill version is promoted up to "${ceiling}". Promotion needs shadow evidence and an approved change.` : undefined}>
          {(ids) => <Select id={ids.input} value={autonomy} disabled={readOnly} aria-invalid={overCeiling || undefined} onChange={(e) => patch({ autonomy: e.target.value as ReviewerAutonomy })}>
            {AUTONOMY_ORDER.map((a) => <option key={a} value={a}>{REVIEWER_AUTONOMY_LABEL[a]}</option>)}
          </Select>}
        </FormField>
        <FormField label="Confidence threshold" id={`ai-reviewer-threshold-${node.id}`} description="Below this calibrated confidence a person decides. The workspace AI policy can lower the autonomy, never raise it.">
          {(ids) => <CommitInput id={ids.input} inputMode="decimal" value={String(reviewer.minConfidence ?? 0.85)} disabled={readOnly}
            onCommit={(v) => { const n = Number(v); if (Number.isFinite(n) && n >= 0 && n <= 1) patch({ minConfidence: n }) }} />}
        </FormField>
        {autonomy === 'autoWithReview' ? <FormField label="Review sample (%)" id={`ai-reviewer-sample-${node.id}`} description="Share of AI decisions a person reviews afterwards.">
          {(ids) => <CommitInput id={ids.input} inputMode="numeric" value={String(reviewer.reviewSamplePercent ?? 10)} disabled={readOnly}
            onCommit={(v) => { const n = Number(v); if (Number.isInteger(n) && n >= 1 && n <= 100) patch({ reviewSamplePercent: n }) }} />}
        </FormField> : null}
        {autonomy === 'auto' ? <p className="text-sm text-muted-foreground">Only answers the workspace AI policy marks low-risk are decided automatically; in a regulated workspace a rejection always goes to a person.</p> : null}
        <div className="space-y-2" role="group" aria-label="What the AI reviewer is sent">
          <p className="text-sm font-semibold">What is sent</p>
          {inputs.length === 0 ? <p className="text-sm text-muted-foreground">Choose a skill to see its inputs.</p> : inputs.map(([name, input]) => (
            <div key={name} className="space-y-2">
              <FormField label={`${name}${input.classification ? ` (${input.classification})` : ''}`} required={input.required} id={`ai-reviewer-input-${node.id}-${name}`}>
                {(ids) => <Select id={ids.input} value={reviewer.inputs[name] ?? ''} disabled={readOnly} onChange={(e) => {
                  const next = { ...reviewer.inputs }
                  if (e.target.value) next[name] = e.target.value
                  else delete next[name]
                  const redaction = { ...(reviewer.redaction ?? {}) }
                  if (!e.target.value) delete redaction[name]
                  patch({ inputs: next, redaction: Object.keys(redaction).length ? redaction : undefined })
                }}>
                  <option value="">{input.required ? 'Choose a value' : 'Not sent'}</option>
                  {candidates.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
                </Select>}
              </FormField>
              {reviewer.inputs[name] ? <FormField label={`Redaction of ${name}`} id={`ai-reviewer-redaction-${node.id}-${name}`}>
                {(ids) => <Select id={ids.input} value={reviewer.redaction?.[name] ?? ''} disabled={readOnly} onChange={(e) => {
                  const next = { ...(reviewer.redaction ?? {}) }
                  if (e.target.value) next[name] = e.target.value
                  else delete next[name]
                  patch({ redaction: Object.keys(next).length ? next : undefined })
                }}>
                  {Object.entries(REDACTION).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </Select>}
              </FormField> : null}
            </div>
          ))}
        </div>
        <p className="text-sm text-muted-foreground">When the AI reviewer decides, the step&apos;s outputs say decidedBy ai-reviewer and the reason is its rationale. The AI reviewer is never the requester and never counts as a person for separation of duties.</p>
      </> : null}
    </InspectorSection>
  )
}
