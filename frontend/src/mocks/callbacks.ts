import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { newId } from './state'

type S = components['schemas']

/**
 * Simplified UI double for EX10 callback waits and IN05 outbound webhooks (docs/build/S98, S99), mock mode and unit
 * tests only. Lists live in memory; token hashing, expiry, delivery, signing and permissions are the backend's and are
 * tested there.
 */
const B = '/api/v1/workspaces/:ws'
let waits: S['CallbackWait'][] = []
let subscriptions: S['WebhookSubscription'][] = []
let deliveries: S['WebhookDelivery'][] = []
let kafkaMode: S['KafkaTriggerList']['mode'] = 'stub'
let kafkaTriggers: S['KafkaTrigger'][] = []
let kafkaDead: S['KafkaDeadLetter'][] = []
const KAFKA_CLUSTERS: S['KafkaCluster'][] = [{ name: 'main', allowedTopics: ['orders.created', 'payments.received'] }]
const emptyStatus = (): S['KafkaTriggerStatus'] => ({ lag: 0, handled: 0, duplicates: 0, dead: 0, lastError: null, lastErrorAt: null, replicas: [] })

export function resetCallbackMocks(): void {
  waits = []
  subscriptions = []
  deliveries = []
  kafkaMode = 'stub'
  kafkaTriggers = []
  kafkaDead = []
}

export function seedKafkaMocks(seed: { mode?: S['KafkaTriggerList']['mode']; triggers?: S['KafkaTrigger'][]; dead?: S['KafkaDeadLetter'][] }): void {
  if (seed.mode) kafkaMode = seed.mode
  if (seed.triggers) kafkaTriggers = [...seed.triggers]
  if (seed.dead) kafkaDead = [...seed.dead]
}

export function mockKafkaTriggers(): S['KafkaTrigger'][] {
  return kafkaTriggers
}

export function seedCallbackMocks(seed: { waits?: S['CallbackWait'][]; deliveries?: S['WebhookDelivery'][]; subscriptions?: S['WebhookSubscription'][] }): void {
  if (seed.waits) waits = [...seed.waits]
  if (seed.deliveries) deliveries = [...seed.deliveries]
  if (seed.subscriptions) subscriptions = [...seed.subscriptions]
}

export function mockCallbackWaits(): S['CallbackWait'][] {
  return waits
}

export function mockWebhookDeliveries(): S['WebhookDelivery'][] {
  return deliveries
}

const problem = (status: number, code: string, title: string) =>
  HttpResponse.json({ code, title, status, type: 'about:blank' }, { status, headers: { 'Content-Type': 'application/problem+json' } })

function token(): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
  let t = 'ocb_'
  for (let i = 0; i < 43; i++) t += alphabet[Math.floor(Math.random() * alphabet.length)]
  return t
}

const MS = { D: 86_400_000, H: 3_600_000, M: 60_000 } as const
function durationMs(iso: string): number {
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(iso)
  if (!m) return 7 * MS.D
  return Number(m[1] ?? 0) * MS.D + Number(m[2] ?? 0) * MS.H + Number(m[3] ?? 0) * MS.M
}

export function createCallbackHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/callback-waits`, ({ request }) => {
      const runId = new URL(request.url).searchParams.get('runId')
      return HttpResponse.json({ items: runId ? waits.filter((w) => w.runId === runId) : waits })
    }),
    http.post(`${B}/callback-waits/:id/token`, ({ params }) => {
      const wait = waits.find((w) => w.id === params.id)
      if (!wait) return problem(404, 'NOT_FOUND', 'Not found')
      if (wait.state === 'resumed' || wait.state === 'cancelled') return problem(409, 'CALLBACK_NOT_WAITING', 'The wait is no longer open for this token')
      const expiresAt = new Date(Date.now() + durationMs(wait.tokenValidFor)).toISOString()
      waits = waits.map((w) => (w.id === wait.id ? { ...w, state: 'waiting', tokenIssued: true, tokenExpiresAt: expiresAt, tokensIssued: w.tokensIssued + 1 } : w))
      const base = `/api/v1/workspaces/${String(params.ws)}/callbacks/wait`
      return HttpResponse.json({ waitId: wait.id, token: token(), expiresAt, pollPath: base, resumePath: `${base}/resume`, header: 'X-Orqix-Callback-Token' } satisfies S['CallbackTokenIssued'], { status: 201 })
    }),
    http.delete(`${B}/callback-waits/:id/token`, ({ params }) => {
      const wait = waits.find((w) => w.id === params.id)
      if (!wait) return problem(404, 'NOT_FOUND', 'Not found')
      waits = waits.map((w) => (w.id === wait.id ? { ...w, tokenIssued: false, tokenExpiresAt: null } : w))
      return new HttpResponse(null, { status: 204 })
    }),
    http.get(`${B}/kafka-triggers`, () => HttpResponse.json({ mode: kafkaMode, clusters: KAFKA_CLUSTERS, items: kafkaTriggers } satisfies S['KafkaTriggerList'])),
    http.post(`${B}/kafka-triggers`, async ({ request }) => {
      const body = (await request.json()) as S['KafkaTriggerCreateRequest']
      if (!KAFKA_CLUSTERS.some((c) => c.name === body.cluster && c.allowedTopics.includes(body.topic))) return problem(422, 'VALIDATION_FAILED', 'The topic is not on the allow-list of the cluster.')
      const created: S['KafkaTrigger'] = { id: newId(), key: body.key, name: body.name, cluster: body.cluster, topic: body.topic, consumerGroup: body.consumerGroup ?? `orqix-${body.key}`, secretRef: body.secretRef ?? null, action: body.action, workflowId: body.workflowId ?? null, messageName: body.messageName ?? null, correlationPointer: body.correlationPointer ?? null, mapping: body.mapping, batchSize: body.batchSize ?? 50, maxPerSecond: body.maxPerSecond ?? 20, startFrom: body.startFrom ?? 'latest', runAs: newId(), enabled: true, version: 1, createdAt: new Date().toISOString(), updatedAt: null, status: emptyStatus() }
      kafkaTriggers = [...kafkaTriggers, created]
      return HttpResponse.json(created, { status: 201 })
    }),
    http.put(`${B}/kafka-triggers/:id`, async ({ params, request }) => {
      const body = (await request.json()) as S['KafkaTriggerUpdateRequest']
      const current = kafkaTriggers.find((t) => t.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.version !== body.expectedVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The adapter or trigger changed since you loaded it')
      const updated: S['KafkaTrigger'] = { ...current, name: body.name, mapping: body.mapping, batchSize: body.batchSize, maxPerSecond: body.maxPerSecond, enabled: body.enabled, version: current.version + 1, updatedAt: new Date().toISOString() }
      kafkaTriggers = kafkaTriggers.map((t) => (t.id === current.id ? updated : t))
      return HttpResponse.json(updated)
    }),
    http.post(`${B}/kafka-triggers/:id/test-message`, async ({ params, request }) => {
      if (kafkaMode !== 'stub') return problem(422, 'VALIDATION_FAILED', 'Test messages are only accepted in stub mode.')
      const current = kafkaTriggers.find((t) => t.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as S['KafkaTestMessageRequest']
      const mapped = Object.values(current.mapping).every((pointer) => (body.value as Record<string, unknown>)[pointer.replace(/^\//, '')] !== undefined)
      const status = { ...current.status, handled: current.status.handled + (mapped ? 1 : 0), dead: current.status.dead + (mapped ? 0 : 1) }
      kafkaTriggers = kafkaTriggers.map((t) => (t.id === current.id ? { ...t, status } : t))
      return HttpResponse.json((mapped ? { outcome: 'started', code: null, runId: newId() } : { outcome: 'dead', code: 'unmapped', runId: null }) satisfies S['KafkaTestMessageResult'])
    }),
    http.delete(`${B}/kafka-triggers/:id`, ({ params }) => {
      const current = kafkaTriggers.find((t) => t.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.enabled) return problem(409, 'RESOURCE_IN_USE', 'This is still in use')
      kafkaTriggers = kafkaTriggers.filter((t) => t.id !== current.id)
      return new HttpResponse(null, { status: 204 })
    }),
    http.get(`${B}/kafka-dead-letters`, () => HttpResponse.json({ items: kafkaDead })),
    http.get(`${B}/webhook-subscriptions`, () => HttpResponse.json({ items: subscriptions })),
    http.post(`${B}/webhook-subscriptions`, async ({ request }) => {
      const body = (await request.json()) as S['WebhookSubscriptionCreateRequest']
      if (!/^https:\/\//.test(body.url)) return problem(422, 'EGRESS_NOT_ALLOWED', 'The endpoint is not allowed by the egress policy')
      if (!body.secretRef.startsWith('webhook-')) return problem(422, 'VALIDATION_FAILED', 'Webhook secret references start with webhook-.')
      const created: S['WebhookSubscription'] = { id: newId(), name: body.name, url: body.url, secretRef: body.secretRef, events: body.events, enabled: body.enabled ?? true, version: 1, pending: 0, dead: 0, createdAt: new Date().toISOString(), updatedAt: null }
      subscriptions = [...subscriptions, created]
      return HttpResponse.json(created, { status: 201 })
    }),
    http.delete(`${B}/webhook-subscriptions/:id`, ({ params }) => {
      const current = subscriptions.find((s) => s.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.enabled) return problem(409, 'RESOURCE_IN_USE', 'This is still in use')
      subscriptions = subscriptions.filter((s) => s.id !== current.id)
      return new HttpResponse(null, { status: 204 })
    }),
    http.put(`${B}/webhook-subscriptions/:id`, async ({ params, request }) => {
      const body = (await request.json()) as S['WebhookSubscriptionUpdateRequest']
      const current = subscriptions.find((s) => s.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.version !== body.expectedVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The adapter or trigger changed since you loaded it')
      const updated: S['WebhookSubscription'] = { ...current, name: body.name, url: body.url, secretRef: body.secretRef, events: body.events, enabled: body.enabled, version: current.version + 1, updatedAt: new Date().toISOString() }
      subscriptions = subscriptions.map((s) => (s.id === current.id ? updated : s))
      return HttpResponse.json(updated)
    }),
    http.get(`${B}/webhook-deliveries`, ({ request }) => {
      const status = new URL(request.url).searchParams.get('status') ?? 'dead'
      return HttpResponse.json({ items: deliveries.filter((d) => d.status === status) })
    }),
    http.post(`${B}/webhook-deliveries/:id/replay`, ({ params }) => {
      const current = deliveries.find((d) => d.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.status !== 'dead') return problem(422, 'VALIDATION_FAILED', 'Only a dead letter can be replayed.')
      const replayed: S['WebhookDelivery'] = { ...current, status: 'pending', attempts: 0, deadAt: null, nextAttemptAt: new Date().toISOString(), replays: current.replays + 1 }
      deliveries = deliveries.map((d) => (d.id === current.id ? replayed : d))
      return HttpResponse.json(replayed)
    }),
  ]
}
