import { api, call } from './client'
import type { components } from './schema'

type S = components['schemas']
export type SendBack = S['SendBack']

/** S116: a task holder returns the request to its requester for the step's declared fields. */
export const sendTaskBack = (ws: string, taskId: string, body: S['SendBackRequest'], idempotencyKey: string) =>
  call(api.POST('/workspaces/{workspaceId}/tasks/{taskId}/send-back', { params: { path: { workspaceId: ws, taskId }, header: { 'Idempotency-Key': idempotencyKey } }, body }))
export const withdrawTaskSendBack = (ws: string, taskId: string, body: S['WithdrawSendBackRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/tasks/{taskId}/send-back/withdraw', { params: { path: { workspaceId: ws, taskId } }, body }))
export const listRunSendBacks = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/runs/{runId}/send-backs', { params: { path: { workspaceId: ws, runId } } }))
export const answerSendBack = (ws: string, runId: string, sendBackId: string, body: S['AnswerSendBackRequest'], idempotencyKey: string) =>
  call(api.POST('/workspaces/{workspaceId}/runs/{runId}/send-backs/{sendBackId}/answer', { params: { path: { workspaceId: ws, runId, sendBackId }, header: { 'Idempotency-Key': idempotencyKey } }, body }))
