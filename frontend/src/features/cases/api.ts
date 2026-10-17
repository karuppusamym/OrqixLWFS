import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { uuid } from '@/lib/text'

type S = components['schemas']

export const listCaseDefinitions = (ws: string) => call(api.GET('/workspaces/{workspaceId}/cases/definitions', {
  params: { path: { workspaceId: ws } },
}))

export const createCaseDefinition = (ws: string, body: S['CreateCaseDefinition']) => call(api.POST('/workspaces/{workspaceId}/cases/definitions', {
  params: { path: { workspaceId: ws } }, body,
}))

export const listCaseInstances = (ws: string) => call(api.GET('/workspaces/{workspaceId}/cases/instances', {
  params: { path: { workspaceId: ws } },
}))

export const getCaseInstance = (ws: string, instanceId: string) => call(api.GET('/workspaces/{workspaceId}/cases/instances/{instanceId}', {
  params: { path: { workspaceId: ws, instanceId } },
}))

export const startCaseInstance = (ws: string, definitionId: string) => call(api.POST('/workspaces/{workspaceId}/cases/definitions/{definitionId}/instances', {
  params: { path: { workspaceId: ws, definitionId }, header: { 'Idempotency-Key': uuid() } },
}))

export const completeCaseTask = (ws: string, instanceId: string, taskId: string, expectedVersion: number) =>
  call(api.POST('/workspaces/{workspaceId}/cases/instances/{instanceId}/tasks/{taskId}/complete', {
    params: { path: { workspaceId: ws, instanceId, taskId }, header: { 'Idempotency-Key': uuid() } },
    body: { expectedVersion },
  }))
