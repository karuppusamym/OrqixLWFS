import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, FileDown, Search } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import { decisionContentOf, formContentOfVersion, type SharedAsset, type SharedAssetKind } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { DecisionDmnImport } from './DecisionDmnImport'

export const KIND_LABELS: Record<SharedAssetKind, string> = {
  decision: 'Decision table',
  documentTemplate: 'Document template',
  connection: 'Connection',
  form: 'Form',
}

function messageOf(error: unknown): string {
  const problem = toProblem(error)
  return problem.fieldErrors[0]?.message ?? problem.detail ?? problem.title
}

const assetsKey = (ws: string) => ['ws', ws, 'shared-assets'] as const

const POLICY_WORDS: Record<string, string> = {
  UNIQUE: 'exactly one row applies',
  FIRST: 'first matching row applies',
  ANY: 'any matching row applies (overlapping rows agree)',
  PRIORITY: 'the highest priority row applies',
  COLLECT: 'all matching rows are combined',
}

/**
 * Shared assets (PL01, docs/build/S61, S127): one place to find the decision tables, document templates, connections
 * and forms that workflows reuse, by name, key or owner, and to see where each is used. Only the kinds you may read are searched.
 * Owners are labels: changing one, or a workflow referencing an asset, never gives anyone access.
 */
export function SharedAssetsPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const [q, setQ] = useState('')
  const [applied, setApplied] = useState('')
  const [kind, setKind] = useState<SharedAssetKind | ''>('')
  const [owner, setOwner] = useState('')
  const [showArchived, setShowArchived] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const query = { q: applied || undefined, kind: kind || undefined, owner: owner || undefined, includeArchived: showArchived }
  const list = useQuery({ queryKey: [...assetsKey(ws), query], queryFn: () => endpoints.listSharedAssets(ws, query) })
  // Owner choices come from every asset the caller may read, so the filter does not shrink with the results.
  const everything = useQuery({ queryKey: [...assetsKey(ws), 'owners'], queryFn: () => endpoints.listSharedAssets(ws, { includeArchived: true }) })
  const owners = [...new Map((everything.data?.items ?? []).map((a) => [a.owner.id, a.owner.displayName])).entries()].sort((a, b) => a[1].localeCompare(b[1]))
  const readableKinds = everything.data?.searchedKinds ?? (['decision', 'documentTemplate', 'connection', 'form'] as SharedAssetKind[]).filter((k) => can(k === 'connection' ? 'connection.read' : 'workflow.read'))

  return (
    <section aria-labelledby="shared-assets-title" className="max-w-5xl space-y-4">
      <div>
        <h2 id="shared-assets-title">Shared assets</h2>
        <p className="text-sm text-muted-foreground">
          Decision tables, document templates, connections and forms that workflows reuse. A workflow keeps a copy of the version it uses; validation tells its designers when a newer version exists, and blocks publishing when the asset is archived or no longer matches.
        </p>
      </div>
      <form role="search" aria-label="Search shared assets" className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); setApplied(q.trim()); setSelected(null) }}>
        <FormField label="Name, key or owner" id="asset-q">
          {(ids) => <Input id={ids.input} value={q} maxLength={100} onChange={(e) => setQ(e.target.value)} />}
        </FormField>
        <FormField label="Kind" id="asset-kind">
          {(ids) => (
            <Select id={ids.input} value={kind} onChange={(e) => { setKind(e.target.value as SharedAssetKind | ''); setSelected(null) }}>
              <option value="">All kinds</option>
              {readableKinds.map((k) => <option key={k} value={k}>{KIND_LABELS[k]}</option>)}
            </Select>
          )}
        </FormField>
        <FormField label="Owner" id="asset-owner">
          {(ids) => (
            <Select id={ids.input} value={owner} onChange={(e) => { setOwner(e.target.value); setSelected(null) }}>
              <option value="">Anyone</option>
              {owners.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
            </Select>
          )}
        </FormField>
        <Button type="submit" variant="secondary"><Search className="size-4" aria-hidden="true" /> Search</Button>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} /> Show archived
        </label>
      </form>
      {can('workflow.edit') ? <DecisionDmnImport ws={ws} onSaved={() => void client.invalidateQueries({ queryKey: assetsKey(ws) })} /> : null}
      {list.isPending ? <LoadingBlock rows={3} label="Loading shared assets" /> : list.isError ? (
        <QueryError error={list.error} onRetry={() => void list.refetch()} what="the shared assets" />
      ) : list.data.items.length === 0 ? (
        <EmptyState title="No shared assets found">{applied || kind || owner ? 'Try another name, key, kind or owner.' : 'Share a decision table or a form from the Studio, save a document template, or register a connection.'}</EmptyState>
      ) : (
        <>
          <p className="text-sm text-muted-foreground" role="status" data-testid="asset-count">
            {list.data.items.length} {list.data.items.length === 1 ? 'asset' : 'assets'}{list.data.truncated ? ' (more exist; narrow the search)' : ''}. Searched: {list.data.searchedKinds.map((k) => KIND_LABELS[k]).join(', ')}.
          </p>
          <ul className="space-y-2" aria-label="Shared assets">
            {list.data.items.map((a) => {
              const id = `${a.kind}:${a.key}`
              return (
                <li key={id} className="rounded-control border border-border" data-testid="asset-row">
                  <button type="button" className="flex w-full flex-wrap items-center gap-3 px-4 py-3 text-left" aria-expanded={selected === id} onClick={() => setSelected(selected === id ? null : id)}>
                    <span className="font-semibold">{a.name}</span>
                    <Badge tone="neutral">{KIND_LABELS[a.kind]}</Badge>
                    <code className="text-xs">{a.key}</code>
                    <Badge tone="neutral">Version {a.latestVersion}</Badge>
                    <span className="text-xs text-muted-foreground">Owner: {a.owner.displayName}</span>
                    {a.archived ? <Badge tone="warning">Archived</Badge> : null}
                  </button>
                  {selected === id ? <AssetDetail ws={ws} kind={a.kind} assetKey={a.key} /> : null}
                </li>
              )
            })}
          </ul>
        </>
      )}
    </section>
  )
}

function AssetDetail({ ws, kind, assetKey }: { ws: string; kind: SharedAssetKind; assetKey: string }) {
  const { can } = useWorkspace()
  const client = useQueryClient()
  const canManage = can(kind === 'connection' ? 'connection.manage' : 'workflow.edit')
  const detailKey = [...assetsKey(ws), 'detail', kind, assetKey]
  const detail = useQuery({ queryKey: detailKey, queryFn: () => endpoints.getSharedAsset(ws, kind, assetKey) })
  const usage = useQuery({ queryKey: [...assetsKey(ws), 'usage', kind, assetKey], queryFn: () => endpoints.getSharedAssetUsage(ws, kind, assetKey), enabled: can('workflow.read') })
  const candidates = useQuery({ queryKey: [...assetsKey(ws), 'owner-candidates', kind], queryFn: () => endpoints.listSharedAssetOwnerCandidates(ws, kind), enabled: canManage })
  const [owner, setOwner] = useState('')
  const [stale, setStale] = useState(false)
  const done = async (next: SharedAsset) => {
    setStale(false)
    setOwner('')
    client.setQueryData(detailKey, next)
    await client.invalidateQueries({ queryKey: assetsKey(ws) })
  }
  const failed = (error: unknown) => {
    if (isProblemError(error) && error.is('WORKFLOW_VERSION_CONFLICT')) {
      setStale(true)
      void client.invalidateQueries({ queryKey: detailKey })
    }
  }
  const changeOwner = useMutation({ mutationFn: (a: SharedAsset) => endpoints.setSharedAssetOwner(ws, kind, assetKey, owner, a.asset.revision), onSuccess: done, onError: failed })
  const archive = useMutation({ mutationFn: (a: SharedAsset) => endpoints.setSharedAssetArchived(ws, kind, assetKey, !a.asset.archived, a.asset.revision), onSuccess: done, onError: failed })

  if (detail.isPending) return <div className="px-4 pb-4"><LoadingBlock rows={2} label="Loading asset" /></div>
  if (detail.isError) return <div className="px-4 pb-4"><QueryError error={detail.error} onRetry={() => void detail.refetch()} what="the asset" /></div>
  const a = detail.data
  return (
    <div className="space-y-4 border-t border-border px-4 py-4" data-testid="asset-detail">
      {a.asset.description ? <p className="text-sm">{a.asset.description}</p> : null}
      <section aria-label="Where it is used" className="space-y-2">
        <h3 className="text-sm font-semibold">Where it is used</h3>
        {usage.isPending && can('workflow.read') ? <LoadingBlock rows={1} label="Loading where it is used" /> : usage.isError ? (
          <QueryError error={usage.error} onRetry={() => void usage.refetch()} what="where it is used" />
        ) : !usage.data || usage.data.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">No current draft or active release uses this asset.</p>
        ) : (
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-muted-foreground"><th className="py-1">Workflow</th><th>Draft uses</th><th>Active release uses</th><th>Status</th></tr></thead>
            <tbody>
              {usage.data.items.map((u) => (
                <tr key={u.workflowId} className="border-t border-border" data-testid="asset-usage">
                  <td className="py-1"><Link to={`/w/${ws}/workflows/${u.workflowId}`}>{u.workflowName}</Link></td>
                  <td>{u.draftVersion ? `Version ${u.draftVersion}` : 'Not used'}</td>
                  <td>{u.activeReleaseVersion ? `Version ${u.activeReleaseVersion} (release ${u.activeReleaseNumber})` : 'Not used'}</td>
                  <td>{u.stale ? <Badge tone="warning">Older version</Badge> : <Badge tone="neutral">Latest</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {canManage ? (
        <section aria-label="Manage" className="space-y-3">
          <h3 className="text-sm font-semibold">Owner and status</h3>
          <form className="flex flex-wrap items-end gap-3" aria-label={`Change owner of ${a.asset.name}`} onSubmit={(e) => { e.preventDefault(); changeOwner.mutate(a) }}>
            <FormField label="New owner" id={`asset-owner-${kind}-${assetKey}`} description="Only people who can manage this kind of asset now. The owner gains no extra access.">
              {(ids) => (
                <Select id={ids.input} value={owner} onChange={(e) => setOwner(e.target.value)}>
                  <option value="">Choose a person</option>
                  {(candidates.data?.items ?? []).filter((p) => p.id !== a.asset.owner.id).map((p) => <option key={p.id} value={p.id}>{p.displayName}</option>)}
                </Select>
              )}
            </FormField>
            <Button type="submit" variant="secondary" loading={changeOwner.isPending} disabled={!owner}>Change owner</Button>
          </form>
          <Button type="button" variant="secondary" size="sm" loading={archive.isPending} onClick={() => archive.mutate(a)}>
            {a.asset.archived ? <ArchiveRestore className="size-4" aria-hidden="true" /> : <Archive className="size-4" aria-hidden="true" />}
            {a.asset.archived ? 'Restore' : 'Archive'}
          </Button>
          {a.asset.archived ? null : <p className="text-xs text-muted-foreground">Archiving keeps every version and changes no published workflow. Workflows that still use it cannot be published until it is restored or unlinked.</p>}
          {stale ? <Alert tone="warning">Someone changed this asset. The latest state is shown; try again.</Alert> : null}
          {changeOwner.isError && !stale ? <Alert tone="danger">{messageOf(changeOwner.error)}</Alert> : null}
          {archive.isError && !stale ? <Alert tone="danger">{messageOf(archive.error)}</Alert> : null}
        </section>
      ) : null}
      {kind === 'decision' && canManage && !a.asset.archived ? (
        <DecisionDmnImport ws={ws} asset={a} onSaved={done} />
      ) : null}
      <section aria-label="Versions" className="space-y-2">
        <h3 className="text-sm font-semibold">Versions</h3>
        <ol className="space-y-2">
          {a.versions.map((v) => (
            <li key={v.version} className="rounded-control border border-border p-3" data-testid="asset-version">
              <p className="text-sm font-semibold">Version {v.version} <span className="font-normal text-muted-foreground">by {v.createdBy.displayName} on {formatDateTime(v.createdAt)}</span></p>
              {v.changeNote ? <p className="text-xs text-muted-foreground">{v.changeNote}</p> : null}
              <VersionContent content={v.content} />
              {kind === 'decision' && v.content ? (
                <Button type="button" variant="ghost" size="sm" onClick={() => void endpoints.exportSharedDecisionDmn(ws, assetKey, v.version).then(({ blob, filename }) => downloadBlob(blob, filename))}>
                  <FileDown className="size-4" aria-hidden="true" /> Download DMN
                </Button>
              ) : null}
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}

/** One line about a version's content: rows of a decision table, questions of a form. */
function VersionContent({ content }: { content: SharedAsset['versions'][number]['content'] | undefined }) {
  const table = decisionContentOf(content)
  if (table) return <p className="text-xs text-muted-foreground">{table.rows.length} {table.rows.length === 1 ? 'row' : 'rows'}; {POLICY_WORDS[table.hitPolicy]}</p>
  const form = formContentOfVersion(content)
  if (!form) return null
  const titles = Object.entries(form.inputSchema.properties).map(([key, q]) => (q as { title?: string }).title ?? key)
  const rules = Object.keys(form.inputUi?.visibleWhen ?? {}).length + Object.keys(form.inputUi?.requiredWhen ?? {}).length
  return (
    <p className="text-xs text-muted-foreground" data-testid="form-version-summary">
      {titles.length} {titles.length === 1 ? 'question' : 'questions'}{rules ? `, ${rules} ${rules === 1 ? 'rule' : 'rules'}` : ''}: {titles.join(', ')}
    </p>
  )
}
