import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useGroups } from '@/api/queries'
import * as endpoints from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { FormField } from '@/components/common/FormField'
import { toKey } from '@/lib/text'
import { guidedDefinition, type GuidedAnswers } from './guidedDefinition'

const titles = ['Purpose and questions', 'Approvals and routing', 'Working calendar', 'Messages and closure', 'Review the draft']
export function GuidedWorkflowDialog({ onClose }: { onClose: () => void }) {
  const { workspace } = useWorkspace(); const ws = workspace.id
  const groups = useGroups(ws); const navigate = useNavigate(); const client = useQueryClient()
  const [page, setPage] = useState(0); const [error, setError] = useState(''); const [pending, setPending] = useState(false)
  const [created, setCreated] = useState<string>()
  const [a, setA] = useState<GuidedAnswers>({ businessOwner: '', suitabilityConfirmed: false, name: '', key: '', purpose: '', fields: [{ key: 'title', title: 'Request title', type: 'string', required: true }],
    steps: [{ label: 'Review request', group: '', minutes: 480 }],
    calendar: { zone: 'America/New_York', revision: 'demo-v1', validFrom: `${new Date().getFullYear()}-01-01`, weekdays: [1, 2, 3, 4, 5], start: '09:00', end: '17:00', holidays: [] },
    closure: 'REQUIRE_NO_OPEN_TASKS', subject: '{{reference}}: {{stage}}', body: '{{workflow}} — {{stage}}\nStatus: {{outcome}}\nDue: {{dueAt}}\nOpen the request: {{link}}' })
  const patch = (p: Partial<GuidedAnswers>) => setA(old => ({ ...old, ...p }))
  const calendar = (p: Partial<GuidedAnswers['calendar']>) => patch({ calendar: { ...a.calendar, ...p } })
  const validatePage = () => {
    if (page === 0 && (!a.name.trim() || !a.purpose.trim())) throw new Error('Give the workflow a name and explain its purpose.')
    if (page === 1 && a.steps.some(s => !s.group || !s.label.trim() || s.minutes < 1)) throw new Error('Every approval needs a name, group and positive SLA.')
    if (page === 2) {
      new Intl.DateTimeFormat('en', { timeZone: a.calendar.zone })
      if (!a.calendar.revision || !a.calendar.validFrom || !a.calendar.weekdays.length || a.calendar.start >= a.calendar.end) throw new Error('Supply a calendar revision, valid-from date, working days and opening/closing times.')
      if (a.calendar.holidays.some(v => !/^\d{4}-\d{2}-\d{2}$/.test(v))) throw new Error('Write holidays as YYYY-MM-DD, one per line.')
    }
    if (page === 3 && (!a.subject.trim() || !a.body.trim())) throw new Error('Supply the email subject and body.')
  }
  const next = () => { try { validatePage(); setError(''); setPage(p => p + 1) } catch (e) { setError(e instanceof Error ? e.message : 'Check the answers.') } }
  const save = async () => {
    setError(''); setPending(true)
    try {
      const definition = guidedDefinition({ ...a, key: toKey(a.name) })
      const id = created ?? (await endpoints.createWorkflow(ws, { key: toKey(a.name), name: a.name, description: a.purpose })).id
      setCreated(id)
      await endpoints.saveDraft(ws, id, definition, 1)
      await client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] })
      navigate(`/w/${ws}/workflows/${id}/edit`)
      onClose()
    } catch (e) { setError(e instanceof Error ? e.message : 'The draft could not be saved. Your answers are retained; retry to save the same draft.') }
    finally { setPending(false) }
  }
  return <Dialog open onClose={onClose} title="Create with guided questions" description={`Question group ${page + 1} of 5: ${titles[page]}`} dismissible={!pending}
    footer={<><Button variant="secondary" disabled={pending} onClick={onClose}>Cancel</Button>{page > 0 ? <Button disabled={pending || !!created} onClick={() => setPage(p => p - 1)}>Back</Button> : null}
      {page < 4 ? <Button variant="primary" onClick={next}>Next</Button> : <Button variant="primary" loading={pending} onClick={() => void save()}>Create reviewable draft</Button>}</>}>
    <div className="space-y-4">
      {page === 0 ? <>
        <FormField label="What is the workflow called?" id="guided-name" required>{ids => <Input id={ids.input} value={a.name} maxLength={200} onChange={e => patch({ name: e.target.value })} />}</FormField>
        <FormField label="What should it accomplish?" id="guided-purpose" required>{ids => <Textarea id={ids.input} value={a.purpose} maxLength={2000} onChange={e => patch({ purpose: e.target.value })} />}</FormField>
        <p className="text-sm">What information should the requester provide?</p>
        {a.fields.map((f, i) => <fieldset key={i} className="space-y-2 rounded border p-3"><legend>Question {i + 1}</legend>
          <FormField label="Question label" id={`guided-label-${i}`}>{ids => <Input id={ids.input} value={f.title} onChange={e => patch({ fields: a.fields.map((v, j) => j === i ? { ...v, title: e.target.value } : v) })} />}</FormField>
          <FormField label="Field key" id={`guided-field-${i}`}>{ids => <Input id={ids.input} value={f.key} onChange={e => patch({ fields: a.fields.map((v, j) => j === i ? { ...v, key: e.target.value } : v) })} />}</FormField>
          <FormField label="Answer type" id={`guided-type-${i}`}>{ids => <Select id={ids.input} value={f.type} onChange={e => patch({ fields: a.fields.map((v, j) => j === i ? { ...v, type: e.target.value as typeof f.type } : v) })}><option value="string">Text</option><option value="number">Number</option><option value="boolean">Yes or no</option></Select>}</FormField>
          <label className="flex gap-2"><input type="checkbox" checked={f.required} onChange={e => patch({ fields: a.fields.map((v, j) => j === i ? { ...v, required: e.target.checked } : v) })} />Answer required</label>
          {i > 0 ? <Button onClick={() => patch({ fields: a.fields.filter((_, j) => j !== i) })}>Remove question {i + 1}</Button> : null}
        </fieldset>)}
        <Button disabled={a.fields.length >= 20} onClick={() => patch({ fields: [...a.fields, { key: `field${a.fields.length + 1}`, title: '', type: 'string', required: true }] })}>Add a question</Button>
      </> : null}
      {page === 1 ? <>
        <p className="text-sm">Tasks run in order. Approval continues; rejection closes the request. A conditional step runs only when the chosen number meets its threshold.</p>
        {groups.isError ? <p role="alert">Approver groups could not load. Reopen the guide after restoring access.</p> : null}
        {a.steps.map((s, i) => <fieldset key={i} className="space-y-2 rounded border p-3"><legend>Approval {i + 1}</legend>
          <FormField label="Task name" id={`guided-task-${i}`}>{ids => <Input id={ids.input} value={s.label} onChange={e => patch({ steps: a.steps.map((v, j) => j === i ? { ...v, label: e.target.value } : v) })} />}</FormField>
          <FormField label="Who approves?" id={`guided-group-${i}`}>{ids => <Select id={ids.input} value={s.group} onChange={e => patch({ steps: a.steps.map((v, j) => j === i ? { ...v, group: e.target.value } : v) })}><option value="">Choose a group</option>{groups.data?.items.map(g => <option value={g.key} key={g.key}>{g.name}</option>)}</Select>}</FormField>
          <FormField label="SLA in business minutes" id={`guided-sla-${i}`}>{ids => <Input id={ids.input} type="number" min={1} max={43200} value={s.minutes} onChange={e => patch({ steps: a.steps.map((v, j) => j === i ? { ...v, minutes: Number(e.target.value) } : v) })} />}</FormField>
          <FormField label="Run this step when" id={`guided-condition-${i}`}>{ids => <Select id={ids.input} value={s.conditionField ?? ''} onChange={e => patch({ steps: a.steps.map((v, j) => j === i ? { ...v, conditionField: e.target.value || undefined, threshold: 0 } : v) })}><option value="">Always</option>{a.fields.filter(f => f.required && f.type === 'number').map(f => <option value={f.key} key={f.key}>{f.title} meets a threshold</option>)}</Select>}</FormField>
          {s.conditionField ? <FormField label="At least" id={`guided-threshold-${i}`}>{ids => <Input id={ids.input} type="number" value={s.threshold ?? 0} onChange={e => patch({ steps: a.steps.map((v, j) => j === i ? { ...v, threshold: Number(e.target.value) } : v) })} />}</FormField> : null}
          {i > 0 ? <Button onClick={() => patch({ steps: a.steps.filter((_, j) => j !== i) })}>Remove approval {i + 1}</Button> : null}
        </fieldset>)}
        <Button disabled={a.steps.length >= 7} onClick={() => patch({ steps: [...a.steps, { label: '', group: '', minutes: 480 }] })}>Add approval</Button>
      </> : null}
      {page === 2 ? <>
        <p className="text-sm">These are demo settings. Confirm the business calendar before operational use. Reassignment does not reset or pause the SLA.</p>
        {([['Time zone', 'zone'], ['Calendar revision', 'revision'], ['Valid from (YYYY-MM-DD)', 'validFrom'], ['Opening time', 'start'], ['Closing time', 'end']] as const).map(([label, key]) => <FormField key={key} label={label} id={`guided-${key}`}>{ids => <Input id={ids.input} value={a.calendar[key]} onChange={e => calendar({ [key]: e.target.value })} />}</FormField>)}
        <fieldset><legend>Working days</legend>{['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((day, i) => <label key={day} className="mr-3 inline-flex gap-1"><input type="checkbox" checked={a.calendar.weekdays.includes(i + 1)} onChange={e => calendar({ weekdays: e.target.checked ? [...a.calendar.weekdays, i + 1] : a.calendar.weekdays.filter(d => d !== i + 1) })} />{day}</label>)}</fieldset>
        <FormField label="Holiday dates" id="guided-holidays" description="Optional synthetic holidays, one YYYY-MM-DD per line.">{ids => <Textarea id={ids.input} value={a.calendar.holidays.join('\n')} onChange={e => calendar({ holidays: e.target.value.split('\n').map(v => v.trim()).filter(Boolean) })} />}</FormField>
      </> : null}
      {page === 3 ? <>
        <p className="text-sm">Assignments go to eligible approvers; decisions and case closure go to the requester; overdue notices go to operators. Edit each stage's templates later in Studio.</p>
        <FormField label="Email subject" id="guided-subject">{ids => <Input id={ids.input} maxLength={200} value={a.subject} onChange={e => patch({ subject: e.target.value })} />}</FormField>
        <FormField label="Email body" id="guided-body" description="Allowed fields: {{reference}}, {{workflow}}, {{stage}}, {{outcome}}, {{dueAt}}, {{link}}.">{ids => <Textarea id={ids.input} maxLength={4000} value={a.body} onChange={e => patch({ body: e.target.value })} />}</FormField>
        <FormField label="When an operator closes a case" id="guided-closure">{ids => <Select id={ids.input} value={a.closure} onChange={e => patch({ closure: e.target.value as GuidedAnswers['closure'] })}><option value="REQUIRE_NO_OPEN_TASKS">Require all tasks to be resolved</option><option value="CANCEL_OPEN_TASKS">Cancel remaining tasks, with a reason</option></Select>}</FormField>
      </> : null}
      {page === 4 ? <>
        <p><strong>{a.name}</strong>: {a.purpose}</p>
        <ol className="list-decimal pl-5">{a.steps.map((s, i) => <li key={i}>{s.label} — {s.group}, {s.minutes} business minutes{s.conditionField ? ` when ${s.conditionField} is at least ${s.threshold}` : ''}</li>)}</ol>
        <p className="text-sm">Review the generated draft and validation findings in Studio. This action does not publish or execute it. Requester self-approval is denied.</p>
        <FormField label="Reviewed source commit" optionalText id="guided-commit" description="Full Git hash; required before publishing. You can add it after committing the draft.">{ids => <Input id={ids.input} value={a.gitCommit ?? ''} onChange={e => patch({ gitCommit: e.target.value })} />}</FormField>
        <FormField label="Business process owner" required id="guided-owner">{ids => <Input id={ids.input} value={a.businessOwner} onChange={e => patch({ businessOwner: e.target.value })} />}</FormField>
        <label className="flex gap-2 text-sm"><input type="checkbox" checked={a.suitabilityConfirmed} onChange={e => patch({ suitabilityConfirmed: e.target.checked })} />I have reviewed the requirements and confirmed standard Tier-1 suitability: no regulated lifecycle or special controls, completion within 30 days, and a pilot of at most 50 concurrent users and 10,000 stored cases.</label>
        <p className="text-sm">Test approval, rejection, each routing boundary, an empty approver group and an overdue deadline before publishing.</p>
      </> : null}
      {error ? <p role="alert" className="text-danger">{error}{created ? <> <Link to={`/w/${ws}/workflows/${created}/edit`}>Open the retained draft to recover</Link></> : null}</p> : null}
    </div>
  </Dialog>
}
