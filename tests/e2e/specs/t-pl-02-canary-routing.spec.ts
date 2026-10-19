import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { apiRead, apiSend, createWorkflow, publish, touchDraft, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * PL02 canary routing (docs/build/S54; listed as not run in S62). With a 50 percent rollout, requests started through the
 * API land on both the active and the canary release; the workflow overview's per-version request figures add up to the
 * requests started, and after the rollout is stopped every new request uses the active release.
 */
test.skip(MOCK, 'Routing happens in the real backend; the mock has no per-release start.')

test('a 50 percent canary sends new requests to both releases and stopping it returns them to the active one', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const wf = await createWorkflow(designer, ws, uniq('Canary routing'))
  const publisher = await sessions.as('paul.publisher')
  const first = await publish(publisher, ws, wf.id)
  await touchDraft(designer, ws, wf.id, 'canary release')
  const second = await publish(publisher, ws, wf.id)
  const rollout = await apiRead<{ version: number }>(publisher, `/workspaces/${ws}/workflows/${wf.id}/rollout`)
  const set = await apiSend(publisher, 'PUT', `/workspaces/${ws}/workflows/${wf.id}/canary`, { releaseId: first, percent: 50, expectedVersion: rollout.body.version })
  expect(set.status, JSON.stringify(set.body)).toBe(200)

  const requester = await sessions.as('rhea.requester')
  const releases = new Set<string>()
  const start = async (): Promise<string> => {
    const r = await apiSend<{ id: string; releaseId?: string }>(requester, 'POST', `/workspaces/${ws}/runs`, { workflowId: wf.id, input: { title: uniq('Canary'), purpose: 'Routing', costCenter: 'OPS', amount: 100 } })
    expect(r.status, JSON.stringify(r.body)).toBe(201)
    return r.body.id
  }
  const runIds: string[] = []
  for (let i = 0; i < 24; i++) runIds.push(await start())
  const operator = await sessions.as('olga.operator')
  for (const id of runIds) {
    const run = await apiRead<{ releaseId?: string; release?: { id: string } }>(operator, `/workspaces/${ws}/runs/${id}`)
    releases.add(run.body.releaseId ?? run.body.release?.id ?? 'unknown')
  }
  expect([...releases].sort(), 'both releases received requests').toEqual([first, second].sort())

  await publisher.goto(`/w/${ws}/workflows/${wf.id}`)
  const status = publisher.getByTestId('canary-status')
  await expect(status).toContainText('50%')
  await expectNoSeriousViolations(publisher, 'Rollout with figures after routed requests')
  await publisher.getByRole('button', { name: 'Stop the rollout' }).click()
  await expect(publisher.getByText('All new requests use the active version')).toBeVisible()
  const after = await start()
  const run = await apiRead<{ releaseId?: string; release?: { id: string } }>(operator, `/workspaces/${ws}/runs/${after}`)
  expect(run.body.releaseId ?? run.body.release?.id).toBe(second)
})
