import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { AgentWriteCall, AgentWriteToolApproval, AgentWriteToolDeclared } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'

const STATE_LABEL: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }> = {
  succeeded: { label: 'Delivered', tone: 'success' },
  unknown: { label: 'Outcome unknown', tone: 'danger' },
  notApplied: { label: 'Not delivered', tone: 'neutral' },
  permanentFailure: { label: 'Refused', tone: 'warning' },
  inFlight: { label: 'Sending', tone: 'warning' },
  pending: { label: 'Not sent', tone: 'neutral' },
  retryableFailure: { label: 'Not sent', tone: 'neutral' },
}

const toolId = (t: { connectionKey: string; connectionVersion: number; operation: string; toolName: string }) =>
  `${t.connectionKey}|${t.connectionVersion}|${t.operation}|${t.toolName}`

/**
 * Approved write-capable agent tools (IN04, docs/build/S75). People holding agent.approve-write-tool approve a tool for
 * one workflow and connection version, or revoke it; operators record what they found at the target for a write whose
 * outcome is unknown. Nothing here sends a message, and message text is never shown: Orqix does not keep it.
 */
export function AgentWriteToolsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const allowed = can('agent.approve-write-tool', 'run.operate', 'connection.read')
  const overview = useQuery({ queryKey: ['ws', ws, 'agent-write-tools'], queryFn: () => endpoints.listAgentWriteTools(ws), enabled: allowed })
  const [onlyUnknown, setOnlyUnknown] = useState(true)
  const showCalls = can('agent.approve-write-tool', 'run.operate')
  const calls = useQuery({
    queryKey: ['ws', ws, 'agent-writes', onlyUnknown ? 'unknown' : 'all'],
    queryFn: () => endpoints.listAgentWriteCalls(ws, onlyUnknown ? 'unknown' : undefined),
    enabled: showCalls,
  })
  const [approving, setApproving] = useState<AgentWriteToolDeclared | null>(null)
  const [revoking, setRevoking] = useState<AgentWriteToolApproval | null>(null)
  const [reconciling, setReconciling] = useState<AgentWriteCall | null>(null)

  if (!allowed) {
    return <Alert tone="warning">You need permission to view agent write tools in this workspace.</Alert>
  }
  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ['ws', ws, 'agent-write-tools'] }),
      client.invalidateQueries({ queryKey: ['ws', ws, 'agent-writes'] }),
    ])
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="Agent writes"
        description="Which messages AI agents may send, who approved that, and writes whose outcome someone must check."
      />
      <Alert tone="info">
        An agent can only send a message through a write tool that a person allowed to approve write tools has approved for
        that workflow and connection version. Recipients are fixed by the tool, never chosen by the agent, and the approver
        can never send through it for their own requests. In this build every message goes to the local test stub.
      </Alert>

      <section aria-labelledby="tools-heading">
        <h2 id="tools-heading" className="mb-3">Write tools</h2>
        {overview.isPending ? (
          <LoadingBlock rows={2} label="Loading write tools" />
        ) : overview.isError ? (
          <QueryError error={overview.error} onRetry={() => void overview.refetch()} what="the write tools" />
        ) : overview.data.tools.length === 0 ? (
          <p className="text-sm text-muted-foreground">No agent connection in this workspace declares a write tool.</p>
        ) : (
          <div className="overflow-hidden rounded-card border border-border bg-surface">
            <Table>
              <caption className="sr-only">Declared write tools and their approvals</caption>
              <THead>
                <TR>
                  <TH>Tool</TH>
                  <TH>Sends to</TH>
                  <TH>Approved for</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {overview.data.tools.map((tool) => {
                  const active = overview.data.approvals.filter((a) => a.status === 'active' && toolId(a) === toolId(tool))
                  return (
                    <TR key={toolId(tool)}>
                      <TD>
                        <span className="font-semibold">{tool.label}</span>
                        <span className="block text-xs text-muted-foreground">
                          {tool.connectionName} version {tool.connectionVersion}, {tool.operationLabel} ({tool.toolName})
                        </span>
                      </TD>
                      <TD>
                        {tool.kind === 'email' ? tool.to.join(', ') : 'The Teams channel of the adapter'}
                        <span className="block text-xs text-muted-foreground">
                          through {tool.adapterKey}, at most {tool.maxCalls} per step
                        </span>
                      </TD>
                      <TD>
                        {active.length === 0 ? (
                          <span className="text-sm text-muted-foreground">Not approved</span>
                        ) : (
                          <ul className="space-y-1">
                            {active.map((a) => (
                              <li key={a.id} className="flex flex-wrap items-center gap-2">
                                <span>{a.workflowKey}</span>
                                <span className="text-xs text-muted-foreground">by {a.approvedBy.displayName}</span>
                                {overview.data.canRevoke ? (
                                  <Button size="sm" variant="secondary" onClick={() => setRevoking(a)} aria-label={`Revoke ${tool.label} for ${a.workflowKey}`}>
                                    Revoke
                                  </Button>
                                ) : null}
                              </li>
                            ))}
                          </ul>
                        )}
                      </TD>
                      <TD>
                        {overview.data.canApprove ? (
                          <Button size="sm" variant="primary" onClick={() => setApproving(tool)} aria-label={`Approve ${tool.label} for a workflow`}>
                            Approve for a workflow
                          </Button>
                        ) : null}
                      </TD>
                    </TR>
                  )
                })}
              </TBody>
            </Table>
          </div>
        )}
      </section>

      {showCalls ? (
        <section aria-labelledby="writes-heading">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h2 id="writes-heading">Writes</h2>
            <div className="flex items-center gap-2">
              <Checkbox id="only-unknown" checked={onlyUnknown} onCheckedChange={(v) => setOnlyUnknown(v === true)} />
              <Label htmlFor="only-unknown">Only writes whose outcome is unknown</Label>
            </div>
          </div>
          {calls.isPending ? (
            <LoadingBlock rows={2} label="Loading writes" />
          ) : calls.isError ? (
            <QueryError error={calls.error} onRetry={() => void calls.refetch()} what="the writes" />
          ) : calls.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">{onlyUnknown ? 'No write needs checking.' : 'No agent has written anything yet.'}</p>
          ) : (
            <div className="overflow-hidden rounded-card border border-border bg-surface">
              <Table>
                <caption className="sr-only">Writes made through agent write tools</caption>
                <THead>
                  <TR>
                    <TH>Request</TH>
                    <TH>Tool</TH>
                    <TH>Outcome</TH>
                    <TH>
                      <span className="sr-only">Actions</span>
                    </TH>
                  </TR>
                </THead>
                <TBody>
                  {calls.data.items.map((call) => {
                    const state = STATE_LABEL[call.state ?? 'pending'] ?? STATE_LABEL.pending!
                    return (
                      <TR key={call.id}>
                        <TD>
                          <Link to={`/w/${ws}/runs/${call.runId}`}>Open request</Link>
                          <span className="block text-xs text-muted-foreground">
                            for {call.requestedBy.displayName}, {formatDateTime(call.createdAt)}
                          </span>
                        </TD>
                        <TD>
                          {call.toolName} (use {call.callIndex})
                          <span className="block text-xs text-muted-foreground">
                            approved by {call.approvedBy.displayName}; {call.argumentChars} characters written by the agent
                          </span>
                        </TD>
                        <TD>
                          <Badge tone={state.tone}>{state.label}</Badge>
                          {call.reconciledOutcome ? (
                            <span className="block text-xs text-muted-foreground">
                              recorded by {call.reconciledBy?.displayName}: {call.reconcileReason}
                            </span>
                          ) : null}
                        </TD>
                        <TD>
                          {call.state === 'unknown' && calls.data.canReconcile ? (
                            <Button size="sm" variant="primary" onClick={() => setReconciling(call)} aria-label={`Record the outcome of ${call.toolName} use ${call.callIndex}`}>
                              Record outcome
                            </Button>
                          ) : null}
                        </TD>
                      </TR>
                    )
                  })}
                </TBody>
              </Table>
            </div>
          )}
        </section>
      ) : null}

      {approving ? <ApproveDialog ws={ws} tool={approving} onDone={async () => { setApproving(null); await refresh() }} onCancel={() => setApproving(null)} /> : null}
      {revoking ? <RevokeDialog ws={ws} approval={revoking} onDone={async () => { setRevoking(null); await refresh() }} onCancel={() => setRevoking(null)} /> : null}
      {reconciling ? <ReconcileDialog ws={ws} call={reconciling} onDone={async () => { setReconciling(null); await refresh() }} onCancel={() => setReconciling(null)} /> : null}
    </div>
  )
}

function useSubmit(onDone: () => Promise<void>) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const submit = async (action: () => Promise<unknown>) => {
    setPending(true)
    setError(null)
    try {
      await action()
      await onDone()
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }
  return { pending, error, submit }
}

function ApproveDialog({ ws, tool, onDone, onCancel }: { ws: string; tool: AgentWriteToolDeclared; onDone: () => Promise<void>; onCancel: () => void }) {
  const [workflowKey, setWorkflowKey] = useState('')
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState<string | null>(null)
  const { pending, error, submit } = useSubmit(onDone)
  const confirm = () => {
    if (!/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(workflowKey.trim())) return setMissing('Enter the key of the workflow, such as vendor-onboarding-check.')
    if (!reason.trim()) return setMissing('Say why this agent may send these messages.')
    setMissing(null)
    void submit(() => endpoints.approveAgentWriteTool(ws, {
      workflowKey: workflowKey.trim(), connectionKey: tool.connectionKey, connectionVersion: tool.connectionVersion,
      operation: tool.operation, toolName: tool.toolName, reason: reason.trim(),
    }))
  }
  return (
    <ConfirmDialog open title={`Approve ${tool.label}`} confirmLabel="Approve" pending={pending} onConfirm={confirm} onCancel={onCancel}
      error={error ? <ProblemAlert error={toProblem(error)} context={{ action: 'approve this write tool' }} /> : null}>
      <p>
        The agent of {tool.connectionName} version {tool.connectionVersion} will be able to send messages to{' '}
        {tool.kind === 'email' ? tool.to.join(', ') : 'the Teams channel of the adapter'} in requests of this workflow. You will
        not be able to send through it for requests you start yourself.
      </p>
      <Label htmlFor="approve-workflow">Workflow key</Label>
      <Input id="approve-workflow" value={workflowKey} onChange={(e) => setWorkflowKey(e.target.value)} />
      <Label htmlFor="approve-reason">Reason</Label>
      <Textarea id="approve-reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
      {missing ? <p className="text-sm text-danger-foreground">{missing}</p> : null}
    </ConfirmDialog>
  )
}

function RevokeDialog({ ws, approval, onDone, onCancel }: { ws: string; approval: AgentWriteToolApproval; onDone: () => Promise<void>; onCancel: () => void }) {
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const { pending, error, submit } = useSubmit(onDone)
  const confirm = () => {
    if (!reason.trim()) return setMissing(true)
    void submit(() => endpoints.revokeAgentWriteTool(ws, approval.id, { reason: reason.trim(), expectedVersion: approval.version }))
  }
  return (
    <ConfirmDialog open title={`Revoke ${approval.toolName} for ${approval.workflowKey}?`} confirmLabel="Revoke" destructive pending={pending}
      onConfirm={confirm} onCancel={onCancel}
      error={error ? <ProblemAlert error={toProblem(error)} context={{ action: 'revoke this approval' }} /> : null}>
      <p>The next write through this tool is refused and the request waits for a person.</p>
      <Label htmlFor="revoke-reason">Reason</Label>
      <Textarea id="revoke-reason" value={reason} maxLength={500} onChange={(e) => { setReason(e.target.value); setMissing(false) }} aria-invalid={missing} />
      {missing ? <p className="text-sm text-danger-foreground">Say why the approval is revoked.</p> : null}
    </ConfirmDialog>
  )
}

function ReconcileDialog({ ws, call, onDone, onCancel }: { ws: string; call: AgentWriteCall; onDone: () => Promise<void>; onCancel: () => void }) {
  const [outcome, setOutcome] = useState<'succeeded' | 'notApplied' | null>(null)
  const [reason, setReason] = useState('')
  const [reference, setReference] = useState('')
  const [missing, setMissing] = useState<string | null>(null)
  const { pending, error, submit } = useSubmit(onDone)
  const confirm = () => {
    if (!outcome) return setMissing('Choose what you found at the target.')
    if (!reason.trim()) return setMissing('Say what you checked.')
    setMissing(null)
    void submit(() => endpoints.reconcileAgentWrite(ws, call.id, {
      outcome, reason: reason.trim(), remoteReference: reference.trim() || null, expectedVersion: call.version,
    }))
  }
  return (
    <ConfirmDialog open title={`Record the outcome of ${call.toolName}`} confirmLabel="Record outcome" pending={pending} onConfirm={confirm} onCancel={onCancel}
      error={error ? <ProblemAlert error={toProblem(error)} context={{ action: 'record this outcome' }} /> : null}>
      <p>
        Orqix cannot tell whether this message reached {call.adapterKey}. Check the target, then record what you found. Nothing
        is sent again until you do; afterwards, retrying the request is a separate step.
      </p>
      <fieldset className="space-y-2">
        <legend className="font-semibold">What did you find?</legend>
        <label className="flex items-center gap-2">
          <input type="radio" name="outcome" checked={outcome === 'succeeded'} onChange={() => setOutcome('succeeded')} />
          It was delivered (it will not be sent again)
        </label>
        <label className="flex items-center gap-2">
          <input type="radio" name="outcome" checked={outcome === 'notApplied'} onChange={() => setOutcome('notApplied')} />
          It was not delivered (a retry of the request may send it)
        </label>
      </fieldset>
      <Label htmlFor="reconcile-reason">What you checked</Label>
      <Textarea id="reconcile-reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
      <Label htmlFor="reconcile-reference">Reference at the target (optional)</Label>
      <Input id="reconcile-reference" value={reference} maxLength={200} onChange={(e) => setReference(e.target.value)} />
      {missing ? <p className="text-sm text-danger-foreground">{missing}</p> : null}
    </ConfirmDialog>
  )
}
