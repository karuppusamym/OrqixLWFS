import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

type Case = components['schemas']['WorkflowTestCase']
type Minimum = components['schemas']['TestCoverageMinimum']
/** Shares the cases as the publication suite; `coverageMinimum` (S148) is the least coverage publication needs. */
export async function shareTests(ws: string, wf: string, cases: Case[], coverageMinimum?: Minimum) {
  const params = { path: { workspaceId: ws, workflowId: wf } }
  const current = await call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/test-suite', { params }))
  const minimum = coverageMinimum && Object.keys(coverageMinimum).length > 0 ? { coverageMinimum } : {}
  return call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/test-suite', { params, body: { expectedVersion: current.version, cases, ...minimum } }))
}
export function runSharedTests(ws: string, wf: string, revision: number, suiteVersion: number) {
  return call(api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/test-suite/run', {
    params: { path: { workspaceId: ws, workflowId: wf } }, body: { revision, suiteVersion },
  }))
}
