import type { components } from '@/api/schema'

type S = components['schemas']

/**
 * UI double of "Explain this request" (CG-A07), mock mode and unit tests only. Rule-based like the server's fallback
 * (the mock never calls a model): facts from the run detail (lifecycle, attention, failed calls, current steps) with no
 * request values, and the permitted actions from the run's allowed actions.
 */
export function mockRunExplanation(run: S['RunDetail'], now = new Date().toISOString()): S['RunExplanation'] {
  const facts: S['ExplainFact'][] = []
  const add = (kind: S['ExplainFact']['kind'], text: string, nodeId: string | null = null, at: string | null = null) => {
    const id = `F${facts.length + 1}`
    facts.push({ id, kind, text, nodeId, at, eventSeq: null })
    return id
  }
  const runFact = add('run', `The request is ${run.lifecycle} (version ${run.version}).`, null, run.updatedAt)
  const evidence = [runFact]
  const actions: S['ExplainPermittedAction'][] = []
  const action = (id: string, label: string, description: string, targetId: string | null = null) =>
    actions.push({ id, operationId: id.split(':')[0] ?? null, kind: 'operate', label, description, permission: 'run.operate', nodeId: run.attention?.nodeId ?? null, targetId })
  for (const e of run.effects ?? []) {
    if (e.state === 'succeeded') continue
    evidence.push(add('effect', `Call ${e.operation} on connection ${e.connectionKey}: ${e.state} after ${e.attempts} attempt(s)${e.lastHttpStatus ? `; last answer HTTP ${e.lastHttpStatus}` : ''}.`, e.nodeId, e.updatedAt))
    if (run.allowedActions.reconcile.allowed && (e.state === 'unknown' || e.state === 'permanentFailure')) {
      action(`reconcileEffect:${e.id}`, `Record what happened in ${e.connectionKey}`, 'Check the other system and record the outcome with evidence.', e.id)
    }
  }
  if (run.allowedActions.retry.allowed) action('retryRun', 'Retry the failed step', 'Run the failed step again once its cause is fixed.')
  if (run.allowedActions.resume.allowed) action('resumeRun', 'Resume the request', 'Let the request continue where it was suspended.')
  for (const t of run.tasks.filter((x) => x.status === 'open').slice(0, 3)) {
    evidence.push(add('task', `Task "${t.label ?? t.nodeId}" is ${t.assignee ? 'assigned to a person' : 'unassigned'}.`, t.nodeId, t.createdAt))
    action(`reassignTask:${t.id}`, 'Reassign the task', 'Give the task to another eligible person.', t.id)
  }
  if (run.allowedActions.cancel.allowed) action('cancelRun', 'Cancel the request', 'Stop the request for good. Work already done elsewhere is not undone.')
  for (const s of run.currentSteps) add('step', `Current step "${s.label}" (${s.kind}) since ${s.since}.`, s.nodeId, s.since)

  let summary: string
  let cause: string
  const suggested: S['ExplainSuggestedAction'][] = []
  const suggest = (id: string, why: string) => { if (actions.some((a) => a.id === id)) suggested.push({ actionId: id, why }) }
  if (run.attention) {
    evidence.push(add('attention', `Step stopped with ${run.attention.reason}; the request needs an operator.`, run.attention.nodeId, run.attention.since))
    summary = `This request is stuck at a failed step (${run.attention.reason}).`
    cause = 'The step failed and the request needs an operator.'
    for (const a of actions) if (a.id.startsWith('reconcileEffect:')) suggest(a.id, 'Record the outcome first.')
    suggest('retryRun', 'Run the step again once the cause is fixed.')
    suggest('cancelRun', 'Only if the request is no longer needed.')
  } else if (run.suspension) {
    summary = 'This request is suspended and waits for an operator to resume it.'
    cause = 'An operator suspended it.'
    suggest('resumeRun', 'The request continues only after it is resumed.')
  } else if (run.lifecycle === 'completed' || run.lifecycle === 'cancelled') {
    summary = `This request has ${run.lifecycle === 'completed' ? 'completed' : 'been cancelled'}. Nothing is stuck.`
    cause = 'The request ended.'
  } else {
    const step = run.currentSteps[0]
    summary = step ? `This request is not stuck: it waits at step "${step.label}".` : 'This request is running; nothing is stuck right now.'
    cause = 'Waiting is expected at this step.'
  }
  return {
    runId: run.id, runVersion: run.version, lifecycle: run.lifecycle, source: 'rules', summary, cause,
    evidence: [...new Set(evidence)], suggestedActions: suggested, facts, permittedActions: actions, droppedActions: [],
    fallbackReason: 'notConfigured', ai: null, readOnly: true, stateDigest: `mock-${run.id}-${run.version}`, generatedAt: now,
  }
}
