import { useQuery } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { Alert } from '@/components/ui/alert'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, type Definition, type Transition, type WorkflowNode } from '../model/types'
import { CommitInput, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'

type AiSkill = components['schemas']['AiSkill']
type Autonomy = 'shadow' | 'suggest' | 'autoWithReview' | 'auto'
type Mode = 'classify' | 'extract' | 'score' | 'recommend' | 'summarize'

export const AUTONOMY_LABEL: Record<Autonomy, string> = {
  shadow: 'Shadow: runs beside the current decision and never changes the path',
  suggest: 'Suggest: a person decides with the AI suggestion (default)',
  autoWithReview: 'Decide, with a review sample afterwards',
  auto: 'Decide automatically (low-risk labels only)',
}
const AUTONOMY_ORDER: Autonomy[] = ['shadow', 'suggest', 'autoWithReview', 'auto']
const REDACTION: Record<string, string> = {
  '': 'Workspace policy only',
  maskCardNumbersAndEmails: 'Mask card numbers and e-mail addresses',
  maskDigits: 'Mask every digit',
  hash: 'Send a hash instead of the value',
  drop: 'Do not send',
}

interface SkillInput { type: string; required?: boolean; classification?: string }

/** The route slots an AI task offers for its mode and autonomy (mirrors the compiler, docs/build/S134). */
export function aiRouteSlots(mode: Mode, autonomy: Autonomy, labels: string[]): Array<{ on: Transition['on']; aiLabel?: string; title: string; required: boolean }> {
  if (autonomy === 'shadow') return [{ on: 'next', title: 'Next step', required: true }]
  const unavailable = { on: 'aiUnavailable' as const, title: 'When AI is unavailable (a person takes over)', required: true }
  if (mode === 'classify') {
    const routes = autonomy === 'suggest' ? [] : labels.map((l) => ({ on: 'aiLabel' as const, aiLabel: l, title: `When AI says ${l}`, required: true }))
    return [...routes, { on: 'lowConfidence', title: 'When AI is not confident enough', required: true },
      { on: 'needsReview', title: 'When a person must review (optional; otherwise low confidence)', required: false }, unavailable]
  }
  if (mode === 'extract') return [{ on: 'next', title: 'Next step', required: true }, { on: 'needsReview', title: 'When a person must check the values', required: true }, unavailable]
  return [{ on: 'next', title: mode === 'recommend' ? 'Next step (usually the approval that shows the recommendation)' : 'Next step', required: true }, unavailable]
}

function slotTransition(def: Definition, nodeId: string, on: Transition['on'], aiLabel?: string): Transition | undefined {
  return def.transitions.find((t) => t.from === nodeId && t.on === on && (on !== 'aiLabel' || t.aiLabel === aiLabel))
}

/**
 * AI task (docs/build/S134, doc 29 section 7.3): runs an approved, evaluated AI skill on mapped values. The designer
 * picks the skill, how much the AI may decide, the confidence threshold and what is sent; the paths follow the skill's
 * mode. Publishing pins the approved version; a version without a passing evaluation cannot be published except in
 * shadow mode.
 */
export function AiTaskInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const { workspace, can } = useWorkspace()
  const def = useStudio((s) => s.definition)
  const query = useQuery({
    queryKey: ['ws', workspace.id, 'ai-skills'],
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/ai-skills', { params: { path: { workspaceId: workspace.id } } })),
    enabled: can('workflow.read'),
  })
  const skills: AiSkill[] = query.data?.items ?? []
  const key = cfg<string>(node, 'skillRef') ?? ''
  const mode = (cfg<string>(node, 'mode') ?? 'classify') as Mode
  const autonomy = (cfg<string>(node, 'autonomy') ?? 'suggest') as Autonomy
  const skill = skills.find((s) => s.key === key)
  const pinned = skill?.versions.find((v) => v.version === (cfg<number>(node, 'skillVersion') ?? skill.approvedVersion))
  const document = (pinned?.document ?? skill?.versions[0]?.document ?? {}) as { input?: Record<string, SkillInput>; labels?: Array<{ key: string; risk?: string }> }
  const inputs = Object.entries(document.input ?? {})
  const labels = (document.labels ?? []).map((l) => l.key)
  const mapping = (cfg<Record<string, string>>(node, 'inputMapping') ?? {}) as Record<string, string>
  const redaction = (cfg<Record<string, string>>(node, 'redaction') ?? {}) as Record<string, string>
  const candidates = availableFields(def, node.id, connections).filter((f) => f.type !== 'array')
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  const ceiling = pinned?.autonomyCeiling ?? 'suggest'
  const overCeiling = AUTONOMY_ORDER.indexOf(autonomy) > AUTONOMY_ORDER.indexOf(ceiling as Autonomy)

  const choose = (nextKey: string) => {
    const next = skills.find((s) => s.key === nextKey)
    patch({ skillRef: nextKey, mode: next?.mode ?? mode, inputMapping: {}, redaction: undefined, skillVersion: undefined })
  }

  const setRoute = (on: Transition['on'], aiLabel: string | undefined, to: string) => store.getState().apply((current) => {
    const transitions = current.transitions.filter((t) => !(t.from === node.id && t.on === on && (on !== 'aiLabel' || t.aiLabel === aiLabel)))
    if (to) {
      let n = transitions.length + 1
      while (current.transitions.some((t) => t.id === `t${n}`) || current.nodes.some((x) => x.id === `t${n}`)) n++
      transitions.push({ id: `t${n}`, from: node.id, to, on, ...(aiLabel ? { aiLabel } : {}) })
    }
    return { ...current, transitions }
  })

  return <>
    <InspectorSection title="AI skill">
      {query.isError || !can('workflow.read') ? <FormField label="Skill key" required id={`ai-skill-${node.id}`}>
        {(ids) => <CommitInput id={ids.input} value={key} maxLength={64} disabled={readOnly} onCommit={(v) => patch({ skillRef: v.trim() })} />}
      </FormField> : <FormField label="Skill" required id={`ai-skill-${node.id}`} description="AI skills are shared, versioned and approved by a second person.">
        {(ids) => <Select id={ids.input} value={key} disabled={readOnly} onChange={(e) => choose(e.target.value)}>
          <option value="">Choose an AI skill</option>
          {key && !skill ? <option value={key}>{key} (not available)</option> : null}
          {skills.map((s) => <option key={s.key} value={s.key}>{s.name} ({s.mode}){s.approvedVersion ? ` - v${s.approvedVersion}` : ' - not approved'}</option>)}
        </Select>}
      </FormField>}
      {skill && !skill.approvedVersion ? <Alert tone="warning">This skill has no approved version yet. It cannot be published until a second person approves one.</Alert> : null}
      {pinned && !pinned.evaluationGate.passing && autonomy !== 'shadow'
        ? <Alert tone="warning">Version {pinned.version} has no passing evaluation on its model profile in the last 30 days, so this workflow cannot be published. Run its evaluation suite, or start in shadow mode.</Alert> : null}
      {pinned?.driftFallback ? <Alert tone="warning">Agreement with people dropped; this skill decides only as a suggestion until someone clears it.</Alert> : null}
      <p className="text-sm text-muted-foreground">Mode: {mode}. Request values are sent as data, never as instructions; a person always takes over when AI is unavailable.</p>
    </InspectorSection>

    <InspectorSection title="How much AI may decide">
      <FormField label="Autonomy" id={`ai-autonomy-${node.id}`} error={overCeiling ? `This skill version is promoted up to "${ceiling}". Promotion needs shadow evidence and an approved change.` : undefined}>
        {(ids) => <Select id={ids.input} value={autonomy} disabled={readOnly} aria-invalid={overCeiling || undefined} onChange={(e) => patch({ autonomy: e.target.value })}>
          {AUTONOMY_ORDER.filter((a) => mode === 'classify' || mode === 'extract' || a === 'shadow' || a === 'suggest')
            .map((a) => <option key={a} value={a}>{AUTONOMY_LABEL[a]}</option>)}
        </Select>}
      </FormField>
      {mode === 'classify' || mode === 'extract' ? <FormField label="Confidence threshold" id={`ai-threshold-${node.id}`} description="Below this calibrated confidence a person decides.">
        {(ids) => <CommitInput id={ids.input} inputMode="decimal" value={String(cfg<number>(node, 'minConfidence') ?? 0.8)} disabled={readOnly}
          onCommit={(v) => { const n = Number(v); if (Number.isFinite(n) && n >= 0 && n <= 1) patch({ minConfidence: n }) }} />}
      </FormField> : null}
      {autonomy === 'autoWithReview' ? <FormField label="Review sample (%)" id={`ai-sample-${node.id}`} description="Share of automatic decisions a person reviews afterwards.">
        {(ids) => <CommitInput id={ids.input} inputMode="numeric" value={String(cfg<number>(node, 'reviewSamplePercent') ?? 10)} disabled={readOnly}
          onCommit={(v) => { const n = Number(v); if (Number.isInteger(n) && n >= 1 && n <= 100) patch({ reviewSamplePercent: n }) }} />}
      </FormField> : null}
      {autonomy === 'auto' ? <p className="text-sm text-muted-foreground">Only labels the workspace AI policy marks low-risk are decided automatically; in a regulated workspace adverse outcomes always go to a person.</p> : null}
    </InspectorSection>

    <InspectorSection title="What is sent">
      {inputs.length === 0 ? <p className="text-sm text-muted-foreground">Choose a skill to see its inputs.</p> : inputs.map(([name, input]) => (
        <div key={name} className="space-y-2">
          <FormField label={`${name}${input.classification ? ` (${input.classification})` : ''}`} required={input.required} id={`ai-input-${node.id}-${name}`}>
            {(ids) => <Select id={ids.input} value={mapping[name] ?? ''} disabled={readOnly} onChange={(e) => {
              const next = { ...mapping }
              if (e.target.value) next[name] = e.target.value
              else delete next[name]
              patch({ inputMapping: next })
            }}>
              <option value="">{input.required ? 'Choose a value' : 'Not sent'}</option>
              {candidates.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
            </Select>}
          </FormField>
          {mapping[name] ? <FormField label={`Redaction of ${name}`} id={`ai-redaction-${node.id}-${name}`}>
            {(ids) => <Select id={ids.input} value={redaction[name] ?? ''} disabled={readOnly} onChange={(e) => {
              const next = { ...redaction }
              if (e.target.value) next[name] = e.target.value
              else delete next[name]
              patch({ redaction: Object.keys(next).length ? next : undefined })
            }}>
              {Object.entries(REDACTION).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </Select>}
          </FormField> : null}
        </div>
      ))}
    </InspectorSection>

    <InspectorSection title="Paths">
      {aiRouteSlots(mode, autonomy, labels).map((slot) => {
        const current = slotTransition(def, node.id, slot.on, slot.aiLabel)
        const id = `ai-route-${node.id}-${slot.on}-${slot.aiLabel ?? ''}`
        return <FormField key={id} label={slot.title} required={slot.required} id={id}>
          {(ids) => <Select id={ids.input} value={current?.to ?? ''} disabled={readOnly} onChange={(e) => setRoute(slot.on, slot.aiLabel, e.target.value)}>
            <option value="">No path</option>
            {def.nodes.filter((n) => n.id !== node.id && n.type !== 'start').map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
          </Select>}
        </FormField>
      })}
    </InspectorSection>
    <StepIdField node={node} />
  </>
}
