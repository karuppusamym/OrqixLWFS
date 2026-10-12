import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

type Case = components['schemas']['WorkflowTestCase']
export async function shareTests(ws: string, wf: string, cases: Case[]) {
  const params = { path: { workspaceId: ws, workflowId: wf } }
  const current = await call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/test-suite', { params }))
  return call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/test-suite', { params, body: { expectedVersion: current.version, cases } }))
}
export function runSharedTests(ws: string, wf: string, revision: number, suiteVersion: number) {
  return call(api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/test-suite/run', {
    params: { path: { workspaceId: ws, workflowId: wf } }, body: { revision, suiteVersion },
  }))
}
