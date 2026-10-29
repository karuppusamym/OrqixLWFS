import { api, call } from './client'
import type { components } from './schema'

type S = components['schemas']
export type ViewPinInventory = S['ViewPinInventory']
export type ViewPinItem = S['ViewPinItem']
export type ViewPinMigration = S['ViewPinMigration']

/** S114 reviewed version migration of the pins of one view or page. */
export const listViewPins = (ws: string, viewId: string, targetVersion?: number) =>
  call(api.GET('/workspaces/{workspaceId}/views/{viewId}/pins', { params: { path: { workspaceId: ws, viewId }, query: targetVersion ? { targetVersion } : {} } }))
export const listViewPinMigrations = (ws: string, viewId: string) =>
  call(api.GET('/workspaces/{workspaceId}/views/{viewId}/pin-migrations', { params: { path: { workspaceId: ws, viewId } } }))
export const migrateViewPins = (ws: string, viewId: string, body: S['MigrateViewPins']) =>
  call(api.POST('/workspaces/{workspaceId}/views/{viewId}/pin-migrations', { params: { path: { workspaceId: ws, viewId } }, body }))
