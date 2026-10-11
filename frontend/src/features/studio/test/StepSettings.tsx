import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { emptySettings, type StepControls, type TestSettings } from './scenarioModel'

/**
 * "What should happen at each step": the choices a person or another system would make during a real request.
 * Everything starts on the happy path, so only steps with a choice are listed.
 */
export function StepSettings({ controls, settings, onChange, groupNames }: { controls: StepControls; settings: TestSettings; onChange: (next: TestSettings) => void; groupNames: Record<string, string> }) {
  const empty = controls.approvals.length + controls.calls.length + controls.children.length + controls.messages.length === 0
  if (empty) return <p className="text-sm text-muted-foreground">This workflow has no step where something has to be decided, so there is nothing to choose.</p>
  const set = (patch: Partial<TestSettings>) => onChange({ ...emptySettings(), ...settings, ...patch })

  return (
    <div className="space-y-4" data-testid="step-settings">
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
        return (
          <div key={c.id} className="space-y-1.5">
            <Label htmlFor={`test-call-${c.id}`}>
              {c.label} <span className="font-normal text-muted-foreground">(the other system: {c.connection || 'not chosen yet'})</span>
            </Label>
            <Select id={`test-call-${c.id}`} value={s.result} onChange={(e) => set({ calls: { ...settings.calls, [c.id]: { ...s, result: e.target.value as typeof s.result } } })}>
              <option value="works">It works and answers</option>
              <option value="rejected">It refuses the request</option>
              <option value="unavailable">It does not answer (down or too slow)</option>
              <option value="unknownOutcome">The answer is lost, so nobody knows if it was done</option>
            </Select>
            {s.result === 'works' && c.results.length > 0 ? (
              <details>
                <summary className="cursor-pointer text-sm text-muted-foreground">Choose what it answers (otherwise a sample answer is used)</summary>
                <div className="mt-2 space-y-2">
                  {c.results.map((r) => (
                    <div key={r.key}>
                      <Label htmlFor={`test-call-${c.id}-${r.key}`} className="font-normal">
                        Answer for {r.key}
                      </Label>
                      <Input id={`test-call-${c.id}-${r.key}`} value={s.answers[r.key] ?? ''} onChange={(e) => set({ calls: { ...settings.calls, [c.id]: { ...s, answers: { ...s.answers, [r.key]: e.target.value } } } })} />
                    </div>
                  ))}
                </div>
              </details>
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

      {controls.messages.map((c) => (
        <div key={c.id} className="space-y-1.5">
          <Label htmlFor={`test-message-${c.id}`}>{c.label}</Label>
          <Select id={`test-message-${c.id}`} value={settings.messages[c.id] ?? 'arrives'} onChange={(e) => set({ messages: { ...settings.messages, [c.id]: e.target.value as 'arrives' | 'timeout' } })}>
            <option value="arrives">The message arrives</option>
            <option value="timeout">The message does not arrive (the time limit fires)</option>
          </Select>
        </div>
      ))}

      {controls.children.length > 0 ? (
        <div className="flex items-start gap-2">
          <Checkbox id="test-simulate-children" checked={settings.simulateChildren} onCheckedChange={(v) => set({ simulateChildren: v === true })} />
          <Label htmlFor="test-simulate-children" className="font-normal">
            Also walk through the other workflows (uses their published version, up to three levels)
          </Label>
        </div>
      ) : null}
    </div>
  )
}
