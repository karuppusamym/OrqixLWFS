import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

export type Conversation = components['schemas']['Conversation']
export type ConversationMessage = components['schemas']['ConversationMessageRequest']
export const sendMessage = (ws: string, body: ConversationMessage, key: string) => call(api.POST('/workspaces/{workspaceId}/conversations/messages', {
  params: { path: { workspaceId: ws }, header: { 'Idempotency-Key': key } }, body,
}))
export const getConversation = (ws: string, id: string) => call(api.GET('/workspaces/{workspaceId}/conversations/{conversationId}', {
  params: { path: { workspaceId: ws, conversationId: id } },
}))
