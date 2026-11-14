import { http, HttpResponse, type HttpHandler } from 'msw'
import type {
  AlertDestination,
  AlertIncident,
  AlertRule,
  AlertRuleRequest,
  LegalHold,
  RetentionPolicy,
  RetentionSweep,
  RunArchive,
} from '@/api/types'
import type { components } from '@/api/schema'
import { alertManagers, clock, db, isMember, newId, permissionsOf, WORKSPACES, type MockUser } from './state'

type S = components['schemas']

/**
 * S144 mock backend: alert destinations, rules and incidents (CG-O06) and run retention policies, legal holds,
 * sweeps and archives (CG-O07), for mock mode and unit tests. Everything starts empty. A dry run reports the
 * requests seeded with seedRetentionEligible; a purge sweep turns them into archive records.
 */
const B = '*/api/v1/workspaces/:ws'

const destinations = new Map<string, AlertDestination[]>()
const rules = new Map<string, AlertRule[]>()
const incidents = new Map<string, AlertIncident[]>()
const policies = new Map<string, RetentionPolicy[]>()
const holds = new Map<string, LegalHold[]>()
const sweeps = new Map<string, RetentionSweep[]>()
const archives = new Map<string, RunArchive[]>()
const eligible = new Map<string, Array<{ runId: string; reference: string; workflowId: string }>>()

export function resetAlertsRetentionMock() {
  for (const m of [destinations, rules, incidents, policies, holds, sweeps, archives, eligible]) m.clear()
  alertManagers.clear()
}

export function grantAlertManage(ws: string, userId: string) {
  alertManagers.add(`${ws}:${userId}`)
}

export function seedAlertIncidents(ws: string, items: AlertIncident[]) {
  incidents.set(ws, items)
}

export function seedRetentionEligible(ws: string, items: Array<{ runId: string; reference: string; workflowId: string }>) {
  eligible.set(ws, items)
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const invalid = (pointer: string, message: string) =>
  problem(422, 'VALIDATION_FAILED', 'The request is invalid', { detail: message, errors: [{ pointer, code: 'invalid', message }] })

const list = <T,>(map: Map<string, T[]>, ws: string): T[] => {
  if (!map.has(ws)) map.set(ws, [])
  return map.get(ws)!
}

export function createAlertsRetentionHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const has = (ws: string, ...permissions: string[]) => permissions.some((p) => (permissionsOf(currentUser(), ws) as string[]).includes(p))
  const refused = (ws: string, ...permissions: string[]) =>
    !visible(ws) ? problem(404, 'NOT_FOUND', 'Not found') : !has(ws, ...permissions) ? problem(403, 'FORBIDDEN', 'You are not allowed to do this') : null
  const now = () => clock.iso()
  const workflow = (id: string) => db.workflows.get(id)

  return [
    // ------------------------------------------------------------ alerts
    http.get(`${B}/alert-destinations`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'alert.manage') ?? HttpResponse.json({ items: list(destinations, ws) })
    }),
    http.post(`${B}/alert-destinations`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'alert.manage')
      if (denied) return denied
      const body = (await request.json()) as S['AlertDestinationCreateRequest']
      if ((body.kind === 'slack' || body.kind === 'pagerDuty') && !body.secretRef) return invalid('/secretRef', 'Name the secret to use.')
      if ((body.kind === 'email' || body.kind === 'teams') && !body.adapterKey) return invalid('/adapterKey', 'Choose the adapter to send through.')
      if (body.kind === 'email' && !body.recipients?.length) return invalid('/recipients', 'Add at least one recipient.')
      if (body.kind === 'webhook' && !body.webhookSubscriptionId) return invalid('/webhookSubscriptionId', 'Choose the webhook subscription.')
      const created: AlertDestination = {
        id: newId(), name: body.name.trim(), kind: body.kind, adapterKey: body.adapterKey ?? null,
        recipients: body.kind === 'email' ? body.recipients ?? null : null, secretRef: body.secretRef ?? null,
        webhookSubscriptionId: body.webhookSubscriptionId ?? null, enabled: body.enabled !== false, version: 1,
        createdAt: now(), updatedAt: null,
      }
      list(destinations, ws).push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.put(`${B}/alert-destinations/:id`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'alert.manage')
      if (denied) return denied
      const items = list(destinations, ws)
      const index = items.findIndex((d) => d.id === params.id)
      if (index < 0) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as S['AlertDestinationUpdateRequest']
      if (body.expectedVersion !== items[index]!.version) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'Changed since you loaded it')
      const updated = { ...items[index]!, name: body.name, enabled: body.enabled, version: items[index]!.version + 1, updatedAt: now() }
      items[index] = updated
      return HttpResponse.json(updated)
    }),
    http.post(`${B}/alert-destinations/:id/test`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'alert.manage')
      if (denied) return denied
      const d = list(destinations, ws).find((x) => x.id === params.id)
      if (!d) return problem(404, 'NOT_FOUND', 'Not found')
      return HttpResponse.json({ destinationId: d.id, status: 'sent', outcome: d.kind === 'webhook' ? 'queued' : 'stubbed', errorCode: null })
    }),
    http.get(`${B}/alert-rules`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'alert.manage') ?? HttpResponse.json({ items: list(rules, ws) })
    }),
    http.post(`${B}/alert-rules`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'alert.manage')
      if (denied) return denied
      const body = (await request.json()) as AlertRuleRequest
      if (!body.destinationIds.length) return invalid('/destinationIds', 'Choose at least one destination.')
      const created: AlertRule = {
        id: newId(), name: body.name, signal: body.signal, threshold: body.threshold, windowMinutes: body.windowMinutes ?? 15,
        windowed: ['slaBreaches', 'tokenRefreshFailing', 'deadLetters'].includes(body.signal), workflowId: body.workflowId ?? null,
        connectionKey: body.connectionKey ?? null, severity: body.severity, quietMinutes: body.quietMinutes ?? 30,
        notifyResolve: body.notifyResolve !== false, enabled: body.enabled !== false, silencedUntil: null, state: 'ok',
        lastValue: null, lastEvaluatedAt: null, openIncidentId: null, destinationIds: body.destinationIds, version: 1,
        createdAt: now(), updatedAt: null,
      }
      list(rules, ws).push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.delete(`${B}/alert-rules/:id`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'alert.manage')
      if (denied) return denied
      rules.set(ws, list(rules, ws).filter((r) => r.id !== params.id))
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${B}/alert-rules/:id/silence`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'alert.manage')
      if (denied) return denied
      const { minutes } = (await request.json()) as { minutes: number }
      const rule = list(rules, ws).find((r) => r.id === params.id)
      if (!rule) return problem(404, 'NOT_FOUND', 'Not found')
      rule.silencedUntil = minutes === 0 ? null : new Date(clock.now().getTime() + minutes * 60_000).toISOString()
      return HttpResponse.json(rule)
    }),
    http.get(`${B}/alert-incidents`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'alert.manage') ?? HttpResponse.json({ items: list(incidents, ws) })
    }),

    // ------------------------------------------------------------ retention
    http.get(`${B}/retention-policies`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access', 'audit.read') ?? HttpResponse.json({ items: list(policies, ws) })
    }),
    http.put(`${B}/workflows/:wf/retention-policy`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const wf = workflow(String(params.wf))
      if (!wf || wf.wsId !== ws) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as S['RetentionPolicyRequest']
      const items = list(policies, ws)
      const current = items.find((p) => p.workflowId === wf.id)
      if ((current?.version ?? 0) !== body.expectedVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'Changed since you loaded it')
      const saved: RetentionPolicy = {
        workflowId: wf.id, workflowKey: wf.key, workflowName: wf.name, keepDays: body.keepDays,
        includeRunData: body.includeRunData !== false, includeFiles: body.includeFiles !== false, enabled: body.enabled !== false,
        version: (current?.version ?? 0) + 1, updatedAt: now(),
        eligibleNow: list(eligible, ws).filter((e) => e.workflowId === wf.id).length,
        workflowHeld: list(holds, ws).some((h) => h.active && h.workflowId === wf.id),
      }
      policies.set(ws, [...items.filter((p) => p.workflowId !== wf.id), saved])
      return HttpResponse.json(saved)
    }),
    http.delete(`${B}/workflows/:wf/retention-policy`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      policies.set(ws, list(policies, ws).filter((p) => p.workflowId !== params.wf))
      return new HttpResponse(null, { status: 204 })
    }),
    http.get(`${B}/legal-holds`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access', 'audit.read') ?? HttpResponse.json({ items: list(holds, ws) })
    }),
    http.post(`${B}/legal-holds`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const body = (await request.json()) as S['LegalHoldRequest']
      if (!body.workflowId === !body.runId) return invalid('/runId', 'Give a workflow or a request, not both.')
      const items = list(holds, ws)
      if (items.some((h) => h.active && (h.workflowId === (body.workflowId ?? undefined) || (body.runId && h.runId === body.runId)))) {
        return problem(409, 'DUPLICATE_KEY', 'An active legal hold already covers this.')
      }
      const wf = body.workflowId ? workflow(body.workflowId) : undefined
      const run = body.runId ? db.runs.get(body.runId) : undefined
      const hold: LegalHold = {
        id: newId(), workflowId: body.workflowId ?? null, workflowName: wf?.name ?? null, runId: body.runId ?? null,
        runReference: (run as { reference?: string } | undefined)?.reference ?? null, reason: body.reason,
        placedBy: currentUser().id, placedAt: now(), releasedBy: null, releasedAt: null, releaseReason: null, active: true,
      }
      items.unshift(hold)
      return HttpResponse.json(hold, { status: 201 })
    }),
    http.post(`${B}/legal-holds/:id/release`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const hold = list(holds, ws).find((h) => h.id === params.id)
      if (!hold) return problem(404, 'NOT_FOUND', 'Not found')
      if (!hold.active) return problem(409, 'RESOURCE_IN_USE', 'Already released')
      const { reason } = (await request.json()) as { reason: string }
      Object.assign(hold, { active: false, releasedAt: now(), releasedBy: currentUser().id, releaseReason: reason })
      return HttpResponse.json(hold)
    }),
    http.get(`${B}/retention-sweeps`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access', 'audit.read') ?? HttpResponse.json({ items: list(sweeps, ws) })
    }),
    http.post(`${B}/retention-sweeps`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const body = (await request.json()) as S['RetentionSweepRequest']
      if (!body.dryRun && body.confirm !== true) return invalid('/confirm', 'Set confirm to true to archive and purge.')
      const covered = new Set(list(policies, ws).filter((p) => p.enabled).map((p) => p.workflowId))
      const heldWorkflows = new Set(list(holds, ws).filter((h) => h.active && h.workflowId).map((h) => h.workflowId))
      const heldRuns = new Set(list(holds, ws).filter((h) => h.active && h.runId).map((h) => h.runId))
      const due = list(eligible, ws).filter((e) => covered.has(e.workflowId))
      const items = due.map((e) => {
        const held = heldWorkflows.has(e.workflowId) || heldRuns.has(e.runId)
        return { ...e, endedAt: now(), outcome: held ? 'held' : body.dryRun ? 'eligible' : 'purged', reason: held ? 'legal_hold' : null, members: 1, keepDays: 1 }
      })
      const purged = items.filter((i) => i.outcome === 'purged')
      if (!body.dryRun) {
        for (const p of purged) {
          list(archives, ws).unshift({
            id: newId(), runId: p.runId, runReference: p.reference, workflowId: p.workflowId, rootRunId: p.runId, sweepId: null,
            lifecycle: 'completed', endedAt: p.endedAt, store: 'filesystem', objectKey: `ws/${p.reference.toLowerCase()}.zip`,
            sha256: 'a'.repeat(64), bytes: 2048, status: 'purged', historyStatus: 'purged', archivedAt: now(), purgedAt: now(),
            historyPurgedAt: now(), purgeCounts: { workflow_run: 1 },
          })
        }
        eligible.set(ws, list(eligible, ws).filter((e) => !purged.some((p) => p.runId === e.runId)))
      }
      const sweep: RetentionSweep = {
        id: newId(), mode: body.dryRun ? 'dryRun' : 'purge', trigger: 'manual', requestedBy: currentUser().id, status: 'completed',
        startedAt: now(), finishedAt: now(), eligible: items.filter((i) => i.outcome !== 'held').length,
        archived: purged.length, purged: purged.length, held: items.filter((i) => i.outcome === 'held').length, skipped: 0, failed: 0,
        detail: { items, truncated: false },
      }
      list(sweeps, ws).unshift(sweep)
      return HttpResponse.json(sweep)
    }),
    http.get(`${B}/run-archives`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access', 'audit.read') ?? HttpResponse.json({ items: list(archives, ws) })
    }),
  ]
}
