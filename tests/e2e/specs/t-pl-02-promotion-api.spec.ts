import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiSend, createWorkflow, publish, uniq } from '../support/api'

/**
 * PL02 promotion and environment stage (docs/build/S54). The browser has no promote screen by design, so this journey
 * drives the documented API from signed-in pages of the real stack: members read the stage, only an administrator sets
 * it (versioned), promotion goes only to a strictly later stage, a compatible release becomes an unpublished draft in
 * the target and the history lists it.
 */
test.skip(MOCK, 'Promotion and environment stages are not simulated by the mock API.')

interface Env { workspaceId: string; stage: string; explicit: boolean; version: number }

test('environment stage is administrator-managed and promotion only moves forward to an unpublished draft', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const sandbox = await workspaceId(designer, 'purchasing-sandbox')
  const prod = await workspaceId(designer, 'purchasing')

  const sandboxEnv = await apiGet<Env>(designer, `/workspaces/${sandbox}/environment`)
  const prodEnv = await apiGet<Env>(designer, `/workspaces/${prod}/environment`)
  expect(sandboxEnv.stage).toBe('test')
  expect(prodEnv.stage).toBe('production')

  // A designer cannot change the stage; the administrator can, with a version check.
  sessions.allowHttpErrors()
  const denied = await apiSend(designer, 'PUT', `/workspaces/${sandbox}/environment`, { stage: 'staging', expectedVersion: sandboxEnv.version })
  expect(denied.status).toBe(403)
  const admin = await sessions.as('wade.admin')
  const stale = await apiSend(admin, 'PUT', `/workspaces/${sandbox}/environment`, { stage: 'test', expectedVersion: sandboxEnv.version + 7 })
  expect(stale.status).toBe(409)
  const set = await apiSend<Env>(admin, 'PUT', `/workspaces/${sandbox}/environment`, { stage: 'test', expectedVersion: sandboxEnv.version })
  expect(set.status).toBe(200)
  expect(set.body.explicit).toBe(true)

  // Build a published release in the sandbox and check/promote it to production.
  const wf = await createWorkflow(designer, sandbox, uniq('Promoted flow'))
  const publisher = await sessions.as('paul.publisher')
  const releaseId = await publish(publisher, sandbox, wf.id)

  const same = await apiSend(designer, 'POST', `/workspaces/${sandbox}/workflows/${wf.id}/releases/${releaseId}/promotion-check`, { targetWorkspaceId: sandbox })
  expect(same.status, 'same workspace is refused').toBe(422)
  const check = await apiSend<{ compatible: boolean; sourceStage: string; targetStage: string; targetWorkflow: { exists: boolean }; issues: unknown[] }>(
    designer, 'POST', `/workspaces/${sandbox}/workflows/${wf.id}/releases/${releaseId}/promotion-check`, { targetWorkspaceId: prod })
  expect(check.status).toBe(200)
  expect(check.body.sourceStage).toBe('test')
  expect(check.body.targetStage).toBe('production')
  expect(check.body.targetWorkflow.exists).toBe(false)
  console.log(`promotion check compatible=${check.body.compatible} issues=${JSON.stringify(check.body.issues).slice(0, 300)}`)

  const promoted = await apiSend<{ targetWorkflowId?: string; workflowId?: string }>(designer, 'POST', `/workspaces/${sandbox}/workflows/${wf.id}/releases/${releaseId}/promote`, { targetWorkspaceId: prod })
  if (check.body.compatible) {
    expect(promoted.status).toBe(201)
    const targetId = promoted.body.targetWorkflowId ?? promoted.body.workflowId
    expect(targetId, JSON.stringify(promoted.body)).toBeTruthy()
    const target = await apiGet<{ activeRelease: unknown }>(designer, `/workspaces/${prod}/workflows/${targetId}`)
    expect(target.activeRelease, 'promotion never publishes').toBeNull()
    const history = await apiGet<{ items: unknown[] }>(designer, `/workspaces/${sandbox}/workflows/${wf.id}/promotions`)
    expect(history.items.length).toBeGreaterThan(0)
  } else {
    expect(promoted.status, 'an incompatible release is refused and creates nothing').toBe(422)
  }

  // Never backwards: production to the sandbox is refused.
  const back = await apiSend(designer, 'POST', `/workspaces/${prod}/workflows/${wf.id}/releases/${releaseId}/promotion-check`, { targetWorkspaceId: sandbox })
  expect([404, 422]).toContain(back.status)

  // A requester can read the stage but cannot promote.
  const requester = await sessions.as('rhea.requester')
  expect((await apiGet<Env>(requester, `/workspaces/${prod}/environment`)).stage).toBe('production')
  const nope = await apiSend(requester, 'POST', `/workspaces/${sandbox}/workflows/${wf.id}/releases/${releaseId}/promotion-check`, { targetWorkspaceId: prod })
  expect([403, 404]).toContain(nope.status)
})
