import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import type { StageEmail, WorkflowNode } from '../model/types'
import { cfg } from '../model/types'
import { useStudio, useStudioContext } from '../StudioContext'
import { CommitInput, CommitNumber, CommitTextarea, InspectorSection } from './controls'

const sample = (recipients: StageEmail['recipients']): StageEmail => ({ subject: '{{reference}}: {{stage}}', body: '{{workflow}} — {{stage}}\nStatus: {{outcome}}\nDue: {{dueAt}}\n{{link}}', recipients })
export function EmailTemplateFields({ value, onChange, prefix, readOnly, terminal = false }: { value: StageEmail; onChange: (value: StageEmail) => void; prefix: string; readOnly: boolean; terminal?: boolean }) {
  return <div className="space-y-3">
    <FormField label="Subject" id={`${prefix}-subject`}>{ids => <CommitInput id={ids.input} maxLength={200} value={value.subject} disabled={readOnly} onCommit={subject => onChange({ ...value, subject })} />}</FormField>
    <FormField label="Body" id={`${prefix}-body`} description="Plain text. Allowed fields: {{reference}}, {{workflow}}, {{stage}}, {{outcome}}, {{dueAt}}, {{link}}.">{ids => <CommitTextarea id={ids.input} maxLength={4000} value={value.body} disabled={readOnly} onCommit={body => onChange({ ...value, body })} />}</FormField>
    <fieldset disabled={readOnly}><legend className="text-sm font-medium">Recipients</legend>
      {(['requester', 'assignee', 'candidateGroup', 'supervisor', 'operators'] as const).filter(v => !terminal || !['assignee', 'candidateGroup'].includes(v)).map(kind => <label className="mr-3 inline-flex gap-1 text-sm" key={kind}>
        <input type="checkbox" checked={value.recipients.includes(kind)} onChange={e => onChange({ ...value, recipients: e.target.checked ? [...value.recipients, kind] : value.recipients.filter(v => v !== kind) })} />
        {{ requester: 'Requester', assignee: 'Eligible assignee', candidateGroup: 'Eligible approver group', supervisor: 'Supervisor group', operators: 'Operators' }[kind]}
      </label>)}
    </fieldset>
    {value.recipients.includes('supervisor') ? <FormField label="Supervisor group key" id={`${prefix}-supervisor`} description="Existing workspace group with configured members; recipients also need request read permission.">{ids => <CommitInput id={ids.input} value={value.supervisorGroupRef ?? ''} disabled={readOnly} onCommit={supervisorGroupRef => onChange({ ...value, supervisorGroupRef })} />}</FormField> : null}
  </div>
}
export function StageEmailFields({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const templates = cfg<Record<string, StageEmail>>(node, 'notifications')
  return <InspectorSection title="Stage email">
    <p className="text-xs text-muted-foreground">Durable delivery uses the configured mail server. Server acceptance does not establish receipt. Overdue email requires supervisor or operator recipients.</p>
    {!templates ? <Button disabled={readOnly} onClick={() => store.getState().updateNodeConfig(node.id, { notifications: { assignment: sample(['candidateGroup']), completion: sample(['requester']), rejection: sample(['requester']), overdue: sample(['operators']) } })}>Configure stage email</Button> :
      Object.entries(templates).map(([event, value]) => <details key={event} className="rounded border p-2"><summary className="cursor-pointer capitalize">{event}</summary>
        <EmailTemplateFields prefix={`mail-${node.id}-${event}`} value={value} readOnly={readOnly} onChange={value => store.getState().updateNodeConfig(node.id, { notifications: { ...templates, [event]: value } })} />
      </details>)}
  </InspectorSection>
}
export function Tier1Settings() {
  const { store, readOnly } = useStudioContext(); const def = useStudio(s => s.definition)
  const assessment = def.tier1Assessment ?? { businessOwner: '', requirementsReviewed: false, regulatoryClassification: 'unclassified' as const, expectedDurationDays: 30, peakConcurrentUsers: 50, storedCases: 10000 }
  const assess = (changes: Partial<typeof assessment>) => store.getState().apply(d => ({ ...d, tier1Assessment: { ...assessment, ...changes } }))
  return <InspectorSection title="Lightweight workflow profile">
    <FormField label="Acceptance profile" id="tier1-profile" description="Tier-1 limits work to 10 states and 5 routing groups, with calendar SLAs and stage email.">{ids => <Select id={ids.input} value={def.acceptanceProfile ?? ''} disabled={readOnly} onChange={e => store.getState().apply(d => ({ ...d, acceptanceProfile: e.target.value === 'tier1' ? 'tier1' : undefined }))}><option value="">General platform</option><option value="tier1">Tier-1 hackathon</option></Select>}</FormField>
    <FormField label="Manual closure policy" id="tier1-closure">{ids => <Select id={ids.input} value={def.closurePolicy ?? (def.acceptanceProfile ? 'REQUIRE_NO_OPEN_TASKS' : 'CANCEL_OPEN_TASKS')} disabled={readOnly} onChange={e => store.getState().apply(d => ({ ...d, closurePolicy: e.target.value as typeof d.closurePolicy }))}><option value="REQUIRE_NO_OPEN_TASKS">Require all tasks resolved</option><option value="CANCEL_OPEN_TASKS">Cancel remaining tasks with a reason</option></Select>}</FormField>
    <FormField label="Reviewed source commit" id="tier1-git" description="Full Git hash required for Tier-1 publishing. Another person must publish the reviewed draft.">{ids => <CommitInput id={ids.input} value={def.provenance?.gitCommit ?? ''} disabled={readOnly} onCommit={gitCommit => store.getState().apply(d => ({ ...d, provenance: gitCommit ? { ...d.provenance, gitCommit } : undefined }))} />}</FormField>
    {def.acceptanceProfile === 'tier1' ? <details><summary className="cursor-pointer">Eligibility assessment</summary>
      <FormField label="Business process owner" id="tier1-owner">{ids => <CommitInput id={ids.input} value={assessment.businessOwner} disabled={readOnly} onCommit={businessOwner => assess({ businessOwner })} />}</FormField>
      <FormField label="Classification" id="tier1-classification">{ids => <Select id={ids.input} value={assessment.regulatoryClassification} disabled={readOnly} onChange={e => assess({ regulatoryClassification: e.target.value as typeof assessment.regulatoryClassification })}><option value="unclassified">Needs owner review</option><option value="standard">Standard Tier-1 suitability confirmed</option><option value="requiresPega">Special controls: keep on PEGA</option></Select>}</FormField>
      <FormField label="Expected duration (calendar days)" id="tier1-duration">{ids => <CommitNumber id={ids.input} integer value={assessment.expectedDurationDays} disabled={readOnly} onCommit={v => assess({ expectedDurationDays: v ?? 30 })} />}</FormField>
      <FormField label="Peak concurrent users" id="tier1-users">{ids => <CommitNumber id={ids.input} integer value={assessment.peakConcurrentUsers} disabled={readOnly} onCommit={v => assess({ peakConcurrentUsers: v ?? 50 })} />}</FormField>
      <FormField label="Stored cases" id="tier1-volume">{ids => <CommitNumber id={ids.input} integer value={assessment.storedCases} disabled={readOnly} onCommit={v => assess({ storedCases: v ?? 10000 })} />}</FormField>
      <label className="flex gap-2 text-sm"><input type="checkbox" disabled={readOnly} checked={assessment.requirementsReviewed} onChange={e => assess({ requirementsReviewed: e.target.checked })} />Requirements and eligibility reviewed with the business owner</label>
    </details> : null}
    <details><summary className="cursor-pointer">Case terminal email</summary>{def.terminalNotification ? <EmailTemplateFields prefix="terminal-mail" value={def.terminalNotification} terminal readOnly={readOnly} onChange={value => store.getState().apply(d => ({ ...d, terminalNotification: value }))} /> : <Button disabled={readOnly} onClick={() => store.getState().apply(d => ({ ...d, terminalNotification: sample(['requester']) }))}>Configure terminal email</Button>}</details>
  </InspectorSection>
}
