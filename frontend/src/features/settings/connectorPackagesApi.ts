import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'

/** Connector packages, the internal catalog, the Studio palette and package triggers (docs/build/S133). */
export type ConnectorPackageList = components['schemas']['ConnectorPackageList']
export type ConnectorPackageSummary = components['schemas']['ConnectorPackageSummary']
export type ConnectorPackageDetail = components['schemas']['ConnectorPackageDetail']
export type ConnectorPackageReview = components['schemas']['ConnectorPackageReview']
export type ConnectorPackageInstall = components['schemas']['ConnectorPackageInstall']
export type ConnectorCatalog = components['schemas']['ConnectorCatalog']
export type ConnectorPaletteItem = components['schemas']['ConnectorPalette']['items'][number]
export type PackageTrigger = components['schemas']['PackageTrigger']
export type PackageTriggerConfigure = components['schemas']['PackageTriggerConfigure']

export const packageKeys = {
  list: (ws: string) => ['ws', ws, 'connector-packages'] as const,
  one: (ws: string, id: string) => ['ws', ws, 'connector-packages', id] as const,
  catalog: (ws: string) => ['ws', ws, 'connector-catalog'] as const,
  palette: (ws: string, q: string) => ['ws', ws, 'connector-palette', q] as const,
  triggers: (ws: string) => ['ws', ws, 'package-triggers'] as const,
}

/** The file as base64, without the data URL prefix. */
export async function fileToBase64(file: Blob): Promise<string> {
  const buffer = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  for (let i = 0; i < buffer.length; i += 0x8000) binary += String.fromCharCode(...buffer.subarray(i, i + 0x8000))
  return btoa(binary)
}

export function useConnectorPackages(ws: string, enabled = true) {
  return useQuery({
    queryKey: packageKeys.list(ws),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/connector-packages', { params: { path: { workspaceId: ws } } })),
    enabled,
  })
}

export function useConnectorPackage(ws: string, id: string | null) {
  return useQuery({
    queryKey: packageKeys.one(ws, id ?? ''),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/connector-packages/{packageVersionId}', {
      params: { path: { workspaceId: ws, packageVersionId: id as string } },
    })),
    enabled: id !== null,
  })
}

export function useConnectorCatalog(ws: string, enabled = true) {
  return useQuery({
    queryKey: packageKeys.catalog(ws),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/connector-catalog', { params: { path: { workspaceId: ws } } })),
    enabled,
  })
}

/** Palette search by operation and step template; empty query lists everything installed (at most 100). */
export function useConnectorPalette(ws: string, q: string, enabled = true) {
  return useQuery({
    queryKey: packageKeys.palette(ws, q),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/connector-palette', {
      params: { path: { workspaceId: ws }, query: q ? { q } : {} },
    })),
    enabled,
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  })
}

export function usePackageTriggers(ws: string, enabled = true) {
  return useQuery({
    queryKey: packageKeys.triggers(ws),
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/package-triggers', { params: { path: { workspaceId: ws } } })),
    enabled,
    refetchInterval: 30_000,
  })
}

function useRefresh(ws: string) {
  const qc = useQueryClient()
  return () => {
    void qc.invalidateQueries({ queryKey: packageKeys.list(ws) })
    void qc.invalidateQueries({ queryKey: packageKeys.catalog(ws) })
    void qc.invalidateQueries({ queryKey: ['ws', ws, 'connector-packages'] })
    void qc.invalidateQueries({ queryKey: ['ws', ws, 'connector-palette'] })
    void qc.invalidateQueries({ queryKey: ['ws', ws, 'connections'] })
  }
}

export function useImportPackage(ws: string) {
  const refresh = useRefresh(ws)
  return useMutation({
    mutationFn: (body: { archive: string; unsignedReason?: string | null }) =>
      call(api.POST('/workspaces/{workspaceId}/connector-packages', { params: { path: { workspaceId: ws } }, body })),
    onSuccess: refresh,
  })
}

export function useDecidePackage(ws: string, id: string) {
  const refresh = useRefresh(ws)
  return useMutation({
    mutationFn: (body: { decision: 'approve' | 'reject'; sha256: string; note: string; expectedRevision: number }) =>
      call(api.POST('/workspaces/{workspaceId}/connector-packages/{packageVersionId}/decision', {
        params: { path: { workspaceId: ws, packageVersionId: id } }, body,
      })),
    onSuccess: refresh,
  })
}

export function useInstallPackage(ws: string, id: string) {
  const refresh = useRefresh(ws)
  return useMutation({
    mutationFn: () => call(api.POST('/workspaces/{workspaceId}/connector-packages/{packageVersionId}/install', {
      params: { path: { workspaceId: ws, packageVersionId: id } },
    })),
    onSuccess: refresh,
  })
}

export function useRetirePackage(ws: string, id: string) {
  const refresh = useRefresh(ws)
  return useMutation({
    mutationFn: (body: { reason: string; expectedRevision: number }) =>
      call(api.POST('/workspaces/{workspaceId}/connector-packages/{packageVersionId}/retire', {
        params: { path: { workspaceId: ws, packageVersionId: id } }, body,
      })),
    onSuccess: refresh,
  })
}

export function useCreateFromStarter(ws: string, id: string) {
  return useMutation({
    mutationFn: (body: { templateKey: string; key: string; name: string }) =>
      call(api.POST('/workspaces/{workspaceId}/connector-packages/{packageVersionId}/workflow-templates/{templateKey}/workflows', {
        params: { path: { workspaceId: ws, packageVersionId: id, templateKey: body.templateKey } },
        body: { key: body.key, name: body.name },
      })),
  })
}

export function useConfigureTrigger(ws: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: PackageTriggerConfigure }) => id
      ? call(api.PUT('/workspaces/{workspaceId}/package-triggers/{packageTriggerId}', {
        params: { path: { workspaceId: ws, packageTriggerId: id } }, body,
      }))
      : call(api.POST('/workspaces/{workspaceId}/package-triggers', { params: { path: { workspaceId: ws } }, body })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: packageKeys.triggers(ws) }),
  })
}

export const STATUS_LABEL: Record<ConnectorPackageSummary['status'], string> = {
  pending: 'Waiting for approval',
  approved: 'Approved',
  rejected: 'Rejected',
  retired: 'Retired',
}

export const STATUS_TONE = { pending: 'warning', approved: 'success', rejected: 'danger', retired: 'neutral' } as const

export const EFFECT_LABEL: Record<string, string> = {
  readOnly: 'Reads only',
  idempotentWithKey: 'Changes data, safe to repeat (key)',
  idempotentByLookup: 'Changes data, checked by lookup',
  idempotentNatural: 'Changes data, same values on repeat',
  nonIdempotent: 'Changes data, never repeated automatically',
}

export const VERIFICATION_LABEL: Record<string, string> = {
  hmacSha256: 'Signed (HMAC)',
  jwt: 'Signed token (JWT)',
  basic: 'Password only (weaker)',
}
