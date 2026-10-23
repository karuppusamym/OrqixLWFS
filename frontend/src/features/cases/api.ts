import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { uuid } from '@/lib/text'

type S = components['schemas']

export const listCaseDefinitions = (ws: string) => call(api.GET('/workspaces/{workspaceId}/cases/definitions', {
  params: { path: { workspaceId: ws } },
}))

export const getCaseDefinition = (ws: string, definitionId: string) => call(api.GET('/workspaces/{workspaceId}/cases/definitions/{definitionId}', {
  params: { path: { workspaceId: ws, definitionId } },
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

export const startCaseInstance = (ws: string, definitionId: string, data?: S['CaseDataValues']) => call(api.POST('/workspaces/{workspaceId}/cases/definitions/{definitionId}/instances', {
  params: { path: { workspaceId: ws, definitionId }, header: { 'Idempotency-Key': uuid() } }, body: data ? { data } : undefined,
}))

export const completeCaseTask = (ws: string, instanceId: string, taskId: string, expectedVersion: number) =>
  call(api.POST('/workspaces/{workspaceId}/cases/instances/{instanceId}/tasks/{taskId}/complete', {
    params: { path: { workspaceId: ws, instanceId, taskId }, header: { 'Idempotency-Key': uuid() } },
    body: { expectedVersion },
  }))

export const terminateCaseInstance = (ws: string, instanceId: string, expectedVersion: number, reason: string) =>
  call(api.POST('/workspaces/{workspaceId}/cases/instances/{instanceId}/terminate', {
    params: { path: { workspaceId: ws, instanceId }, header: { 'Idempotency-Key': uuid() } },
    body: { expectedVersion, reason },
  }))

export const getCasePlan = (ws: string, instanceId: string) => call(api.GET('/workspaces/{workspaceId}/cases/instances/{instanceId}/plan', {
  params: { path: { workspaceId: ws, instanceId } },
}))

export type CaseLifecycleAction = 'suspend' | 'resume' | 'close'
export type PlanItemAction = 'start' | 'enable' | 'disable' | 'activateStage' | 'completeStage' | 'terminate' | 'occur'

export const changeCaseLifecycle = (ws: string, instanceId: string, action: CaseLifecycleAction, expectedVersion: number, reason: string) => {
  const path = {
    suspend: '/workspaces/{workspaceId}/cases/instances/{instanceId}/suspend',
    resume: '/workspaces/{workspaceId}/cases/instances/{instanceId}/resume',
    close: '/workspaces/{workspaceId}/cases/instances/{instanceId}/close',
  } as const
  return call(api.POST(path[action], { params: { path: { workspaceId: ws, instanceId }, header: { 'Idempotency-Key': uuid() } }, body: { expectedVersion, reason } }))
}

export const changeCasePlanItem = (ws: string, instanceId: string, planItemId: string, action: PlanItemAction, expectedVersion: number, reason: string) => {
  const path = {
    occur: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/occur',
    start: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/start',
    enable: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/enable',
    disable: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/disable',
    activateStage: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/activateStage',
    completeStage: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/completeStage',
    terminate: '/workspaces/{workspaceId}/cases/instances/{instanceId}/plan-items/{planItemId}/terminate',
  } as const
  return call(api.POST(path[action], { params: { path: { workspaceId: ws, instanceId, planItemId }, header: { 'Idempotency-Key': uuid() } }, body: { expectedVersion, reason } }))
}

export const getCaseData = (ws: string, instanceId: string) => call(api.GET('/workspaces/{workspaceId}/cases/instances/{instanceId}/data', {
  params: { path: { workspaceId: ws, instanceId } },
}))

export const updateCaseData = (ws: string, instanceId: string, expectedVersion: number, reason: string, values: S['CaseDataValues']) => call(api.POST('/workspaces/{workspaceId}/cases/instances/{instanceId}/data', {
  params: { path: { workspaceId: ws, instanceId }, header: { 'Idempotency-Key': uuid() } }, body: { expectedVersion, reason, values },
}))
