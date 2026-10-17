import { useState } from 'react'
import { useGroups } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { cfg, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitNumber, CommitTextarea, DurationPreview, InspectorSection } from './controls'
import { WorkManagementFields } from './WorkManagementFields'

/** Rename a step ID in Advanced: IDs are stable references, so the change is validated and applied everywhere. */
export function StepIdField({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const [error, setError] = useState<string>()
  return (
    <FormField label="Step ID" id={`step-id-${node.id}`} error={error} description="Used in links and exports. Changing it updates every place that refers to this step.">
      {(ids) => (
        <CommitInput
          id={ids.input}
          data-field="id"
          value={node.id}
          disabled={readOnly || node.type === 'start'}
          aria-invalid={error ? true : undefined}
          aria-describedby={ids.describedBy}
          spellCheck={false}
          autoComplete="off"
          onCommit={(next) => {
            const result = store.getState().renameNodeId(node.id, next.trim())
            setError(result.ok ? undefined : result.reason)
          }}
        />
      )}
    </FormField>
  )
}

export function ApprovalInspector({ node }: { node: WorkflowNode }) {
  const { store, wsId, readOnly } = useStudioContext()
  const groups = useGroups(wsId)
  const group = cfg<string>(node, 'candidateGroupRef') ?? ''
  const items = groups.data?.items ?? []
  const known = !group || items.some((g) => g.key === group)
  return (
    <>
      <InspectorSection title="Basic">
        <FormField label="Assign to" required id={`approval-group-${node.id}`} description="The group whose members can approve or reject.">
          {(ids) => (
            <Select
              id={ids.input}
              data-field="config.candidateGroupRef"
              value={group}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onChange={(e) => store.getState().updateNodeConfig(node.id, { candidateGroupRef: e.target.value })}
            >
              <option value="">{groups.isPending ? 'Loading groups…' : 'Choose a group'}</option>
              {!known ? <option value={group}>{group} (not found in this workspace)</option> : null}
              {items.map((g) => (
                <option key={g.key} value={g.key}>
                  {g.name}
                </option>
              ))}
            </Select>
          )}
        </FormField>
        <FormField label="Due after (hours)" optionalText id={`approval-due-${node.id}`} description="Shown as a due date; counts working hours if a calendar is configured below.">
          {(ids) => (
            <CommitNumber
              id={ids.input}
              data-field="config.dueAfterHours"
              integer
              value={cfg<number>(node, 'dueAfterHours')}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onCommit={(v) => store.getState().updateNodeConfig(node.id, { dueAfterHours: v === undefined ? undefined : Math.min(720, Math.max(1, v)) })}
            />
          )}
        </FormField>
        <FormField label="Number of approvers" optionalText id={`approval-approvers-${node.id}`} description="Leave empty for one person. With 2 to 5, that many different people from the group must each approve; the first rejection rejects the step. Not combined with a time limit, reminders or corrections.">
          {(ids) => (
            <CommitNumber
              id={ids.input}
              data-field="config.multiInstance"
              integer
              value={cfg<{ approvers: number }>(node, 'multiInstance')?.approvers}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onCommit={(v) => store.getState().updateNodeConfig(node.id, { multiInstance: v === undefined || v < 2 ? undefined : { approvers: Math.min(5, v) } })}
            />
          )}
        </FormField>
        <div className="flex items-start gap-3">
          <Checkbox id={`approval-reason-${node.id}`} checked disabled aria-describedby={`approval-reason-${node.id}-help`} />
          <div>
            <Label htmlFor={`approval-reason-${node.id}`}>Reason required when rejecting</Label>
            <p id={`approval-reason-${node.id}-help`} className="text-xs text-muted-foreground">
              Always on, so the requester knows what to change.
            </p>
          </div>
        </div>
        <FormField label="Time limit" optionalText id={`approval-timeout-${node.id}`} description="For example PT2H or P1D. Cancels the approval and follows Timed out when no decision is made in time.">
          {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'timeoutAfter') ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => store.getState().apply((d) => ({
            ...d,
            nodes: d.nodes.map((n) => {
              if (n.id !== node.id) return n
              const config = { ...n.config }
              if (v.trim()) config.timeoutAfter = v.trim()
              else delete config.timeoutAfter
              return { ...n, config }
            }),
            transitions: v.trim() ? d.transitions : d.transitions.filter((t) => t.from !== node.id || t.on !== 'timedOut'),
          }))} />}
        </FormField>
        {cfg<string>(node, 'timeoutAfter') ? <DurationPreview text={cfg<string>(node, 'timeoutAfter') ?? ''} verb="allows" /> : null}
      </InspectorSection>
      <WorkManagementFields node={node} groups={items} />
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}

export function NotificationInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const message = cfg<string>(node, 'message') ?? ''
  return (
    <>
      <InspectorSection title="Basic">
        <FormField label="Message" required id={`notify-message-${node.id}`} description="Shown to the requester as an in-app notification. At most 200 characters.">
          {(ids) => (
            <>
              <CommitTextarea
                id={ids.input}
                data-field="config.message"
                value={message}
                maxLength={200}
                rows={3}
                disabled={readOnly}
                aria-describedby={ids.describedBy}
                onCommit={(v) => store.getState().updateNodeConfig(node.id, { message: v })}
              />
              <p className="text-right text-xs text-muted-foreground" aria-hidden="true">
                {message.length} / 200
              </p>
            </>
          )}
        </FormField>
        <FormField label="Send to" id={`notify-to-${node.id}`}>
          {(ids) => (
            <Select id={ids.input} value="initiator" disabled>
              <option value="initiator">Requester</option>
            </Select>
          )}
        </FormField>
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}

export function EndInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const outcomeKind = cfg<string>(node, 'outcomeKind') ?? 'success'
  const outcome = cfg<string>(node, 'businessOutcome') ?? ''
  const valid = /^[A-Z][A-Za-z ]{0,39}$/.test(outcome)
  return (
    <>
      <InspectorSection title="Basic">
        <FormField
          label="Outcome label"
          required
          id={`end-outcome-${node.id}`}
          error={outcome && !valid ? 'Start with a capital letter and use only letters and spaces (up to 40).' : undefined}
          description="Shown to everyone as the result of the request, for example Fulfilled or Rejected."
        >
          {(ids) => (
            <CommitInput
              id={ids.input}
              data-field="config.businessOutcome"
              value={outcome}
              maxLength={40}
              disabled={readOnly}
              aria-invalid={outcome && !valid ? true : undefined}
              aria-describedby={ids.describedBy}
              onCommit={(v) => store.getState().updateNodeConfig(node.id, { businessOutcome: v })}
            />
          )}
        </FormField>
        <FormField label="Counts as" id={`end-kind-${node.id}`} description="A rejected outcome is a finished request, not a failure.">
          {(ids) => (
            <Select
              id={ids.input}
              data-field="config.outcomeKind"
              value={outcomeKind}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onChange={(e) => store.getState().updateNodeConfig(node.id, { outcomeKind: e.target.value })}
            >
              <option value="success">Successful</option>
              <option value="rejected">Rejected</option>
              <option value="failed">Failed</option>
            </Select>
          )}
        </FormField>
      </InspectorSection>
      <Advanced>
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}

/** Workflow-level settings, shown when nothing is selected. */
export function WorkflowInspector() {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const isTest = useWorkspace().workspace.isTest
  return (
    <div className="space-y-5 p-4">
      <p className="text-sm text-muted-foreground">Select a step on the canvas or in the Steps tab to edit it.</p>
      <InspectorSection title="Workflow">
        <FormField label="Name" id="wf-settings-name">
          {(ids) => <CommitInput id={ids.input} data-field="workflow.name" value={def.name} maxLength={200} disabled={readOnly} onCommit={(v) => store.getState().apply((d) => (d.name === v ? d : { ...d, name: v }))} />}
        </FormField>
        <FormField label="Description" optionalText id="wf-settings-desc">
          {(ids) => (
            <CommitTextarea
              id={ids.input}
              value={def.metadata.description}
              maxLength={2000}
              rows={3}
              disabled={readOnly}
              onCommit={(v) => store.getState().apply((d) => (d.metadata.description === v ? d : { ...d, metadata: { ...d.metadata, description: v } }))}
            />
          )}
        </FormField>
      </InspectorSection>
      <Advanced label="Limits and retries">
        <FormField label="Open requests at once" id="wf-settings-max" description="More requests than this are refused until some finish.">
          {(ids) => (
            <CommitNumber id={ids.input} integer value={def.execution.maxConcurrentRuns} disabled={readOnly} onCommit={(v) => v !== undefined && store.getState().apply((d) => ({ ...d, execution: { ...d.execution, maxConcurrentRuns: Math.min(1000, Math.max(1, v)) } }))} />
          )}
        </FormField>
        <FormField label="Attempts per call to another system" id="wf-settings-attempts" description="Applies to steps that call a system, never to people.">
          {(ids) => (
            <CommitNumber id={ids.input} integer value={def.execution.maxAttempts} disabled={readOnly} onCommit={(v) => v !== undefined && store.getState().apply((d) => ({ ...d, execution: { ...d.execution, maxAttempts: Math.min(5, Math.max(1, v)) } }))} />
          )}
        </FormField>
        <FormField label="Seconds between attempts" id="wf-settings-delay">
          {(ids) => (
            <CommitNumber id={ids.input} integer value={def.execution.retryDelaySeconds} disabled={readOnly} onCommit={(v) => v !== undefined && store.getState().apply((d) => ({ ...d, execution: { ...d.execution, retryDelaySeconds: Math.min(3600, Math.max(5, v)) } }))} />
          )}
        </FormField>
        <div className="flex items-start gap-3">
          <Checkbox
            id="wf-settings-self"
            checked={def.security.selfApproval === 'allow'}
            disabled={readOnly || !isTest}
            onCheckedChange={(v) => store.getState().apply((d) => ({ ...d, security: { selfApproval: v === true ? 'allow' : 'deny' } }))}
          />
          <div>
            <Label htmlFor="wf-settings-self">Let requesters approve their own requests</Label>
            <p className="text-xs text-muted-foreground">{isTest ? 'Test workspace only. Every such decision is flagged and audited.' : 'Not available in this workspace.'}</p>
          </div>
        </div>
      </Advanced>
    </div>
  )
}
