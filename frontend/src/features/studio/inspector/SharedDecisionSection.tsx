import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link2, Link2Off } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { DecisionAssetContent } from '@/api/types'
import { FormField } from '@/components/common/FormField'
import { toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { canonical } from '@/lib/canonicalJson'
import { useStudio, useStudioContext } from '../StudioContext'
import { applySharedDecision, assetRef, linkAsset, unlinkAsset } from '../model/ops'
import type { DecisionTable } from '../model/types'
import { InspectorSection } from './controls'

export function decisionContent(table: DecisionTable): DecisionAssetContent {
  return { hitPolicy: table.hitPolicy, rows: structuredClone(table.rows) as unknown as DecisionAssetContent['rows'], default: structuredClone(table.default) }
}

/** A shared version's content as the table fields the Studio edits (the server checks the copy in full). */
export const asTable = (content: DecisionAssetContent) => content as unknown as Pick<DecisionTable, 'hitPolicy' | 'rows' | 'default'>

export const sameDecision = (table: DecisionTable, content: DecisionAssetContent | null | undefined) =>
  !!content && canonical(decisionContent(table)) === canonical({ hitPolicy: content.hitPolicy, rows: content.rows, default: content.default })

function messageOf(error: unknown): string {
  const problem = toProblem(error)
  return problem.fieldErrors[0]?.message ?? problem.detail ?? problem.title
}

/**
 * Shared decision link for one rule table (PL01, docs/build/S57). The table always holds its own copy; the link pins
 * the shared version it came from. Validation and publish compare the copy with that version, report a newer version
 * and block an archived or missing asset, so a change to the shared decision never reaches this workflow by itself.
 */
export function SharedDecisionSection({ table }: { table: DecisionTable }) {
  const { store, wsId, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const queryClient = useQueryClient()
  const linked = assetRef(def, 'decision', table.key)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [sharing, setSharing] = useState(false)
  const [name, setName] = useState('')
  const [key, setKey] = useState(table.key)
  const [busy, setBusy] = useState(false)

  const asset = useQuery({
    queryKey: ['ws', wsId, 'shared-asset', 'decision', linked?.key],
    queryFn: () => endpoints.getSharedAsset(wsId, 'decision', linked!.key),
    enabled: !!linked,
    retry: false,
  })
  const choices = useQuery({
    queryKey: ['ws', wsId, 'shared-assets', 'decision-choices'],
    queryFn: () => endpoints.listSharedAssets(wsId, { kind: 'decision' }),
    enabled: !linked && !readOnly,
    retry: false,
  })

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['ws', wsId, 'shared-asset'] })
  const run = async (work: () => Promise<void>) => {
    setError(null)
    setStatus(null)
    setBusy(true)
    try {
      await work()
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

  const use = (assetKey: string) => run(async () => {
    if (!assetKey) return
    const detail = await endpoints.getSharedAsset(wsId, 'decision', assetKey)
    const latest = detail.versions[0]
    const content = latest?.content
    if (!latest || !content) throw new Error('The shared decision has no content.')
    store.getState().apply((d) => applySharedDecision(d, table.key, asTable(content), assetKey, latest.version))
    setStatus(`Copied version ${latest.version} of ${detail.asset.name} into this table and linked it.`)
  })

  const share = () => run(async () => {
    const created = await endpoints.createDecisionAsset(wsId, { key: key.trim(), name: name.trim(), content: decisionContent(table) })
    store.getState().apply((d) => linkAsset(d, { kind: 'decision', key: created.asset.key, version: 1, target: table.key }))
    setSharing(false)
    setStatus(`Shared as ${created.asset.name}, version 1. This table is linked to it.`)
    refresh()
  })

  if (!linked) {
    if (readOnly) return null
    const items = choices.data?.items ?? []
    return (
      <InspectorSection title="Shared decision">
        <p className="text-sm text-muted-foreground">Reuse a decision table kept for the whole workspace, or share this one. The workflow keeps its own copy of the version you choose.</p>
        {items.length > 0 ? (
          <FormField label="Use a shared decision" id={`shared-decision-${table.key}`} description="Replaces the rows, the Otherwise result and which row applies with the latest shared version.">
            {(ids) => (
              <Select id={ids.input} value="" disabled={busy} onChange={(e) => void use(e.target.value)}>
                <option value="">Choose a shared decision</option>
                {items.map((a) => <option key={a.key} value={a.key}>{a.name} (version {a.latestVersion})</option>)}
              </Select>
            )}
          </FormField>
        ) : null}
        {sharing ? (
          <form aria-label="Share this rule table" className="space-y-3" onSubmit={(e) => { e.preventDefault(); void share() }}>
            <FormField label="Shared name" required id={`share-name-${table.key}`}>
              {(ids) => <Input id={ids.input} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}
            </FormField>
            <FormField label="Shared key" required id={`share-key-${table.key}`} description="Lowercase letters, digits and dashes. Workflows refer to the shared decision by this key.">
              {(ids) => <Input id={ids.input} value={key} maxLength={64} spellCheck={false} onChange={(e) => setKey(e.target.value)} />}
            </FormField>
            <div className="flex gap-2">
              <Button type="submit" variant="primary" disabled={busy || !name.trim() || !key.trim()}>Share</Button>
              <Button type="button" variant="ghost" onClick={() => setSharing(false)}>Cancel</Button>
            </div>
          </form>
        ) : (
          <Button type="button" variant="secondary" onClick={() => { setSharing(true); setName(''); setKey(table.key) }}>
            <Link2 className="size-4" aria-hidden="true" /> Share this rule table
          </Button>
        )}
        {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
        {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
      </InspectorSection>
    )
  }

  const detail = asset.data
  const pinned = detail?.versions.find((v) => v.version === linked.version)
  const latest = detail?.versions[0]
  const differs = !!pinned && !sameDecision(table, pinned.content)
  const newer = !!latest && latest.version > linked.version
  return (
    <InspectorSection title="Shared decision">
      <p className="text-sm" data-testid="shared-decision-link">
        Linked to <strong>{detail?.asset.name ?? linked.key}</strong> ({linked.key}), version {linked.version}.
      </p>
      {asset.isError ? <p role="alert" className="text-sm text-danger">The shared decision could not be loaded. It may have been removed; validation reports it before publishing.</p> : null}
      {detail?.asset.archived ? <p role="alert" className="text-sm text-danger">This shared decision is archived. Publishing is blocked until it is restored or you unlink this table.</p> : null}
      {differs ? <p role="alert" className="text-sm text-danger">This table no longer matches version {linked.version}. Publishing is blocked until you use a shared version, save it as a new shared version, or unlink.</p> : null}
      {newer && !differs ? <p className="text-sm text-warning-foreground">Version {latest!.version} is available. This workflow keeps version {linked.version} until you choose to update.</p> : null}
      {!readOnly ? (
        <div className="flex flex-wrap gap-2">
          {newer || differs ? (
            <Button type="button" variant="secondary" disabled={busy || !latest?.content} onClick={() => run(async () => {
              store.getState().apply((d) => applySharedDecision(d, table.key, asTable(latest!.content!), linked.key, latest!.version))
              setStatus(`Copied version ${latest!.version} into this table.`)
            })}>
              Use version {latest?.version}
            </Button>
          ) : null}
          {differs && detail && !detail.asset.archived ? (
            <Button type="button" variant="secondary" disabled={busy} onClick={() => run(async () => {
              const saved = await endpoints.addDecisionAssetVersion(wsId, linked.key, { expectedVersion: detail.asset.latestVersion, content: decisionContent(table) })
              store.getState().apply((d) => linkAsset(d, { ...linked, version: saved.asset.latestVersion }))
              setStatus(`Saved as version ${saved.asset.latestVersion} of ${saved.asset.name}. Other workflows keep their versions.`)
              refresh()
            })}>
              Save as shared version {detail.asset.latestVersion + 1}
            </Button>
          ) : null}
          <Button type="button" variant="ghost" disabled={busy} onClick={() => {
            store.getState().apply((d) => unlinkAsset(d, 'decision', table.key))
            setStatus('Unlinked. This table keeps its rows and is no longer checked against the shared decision.')
          }}>
            <Link2Off className="size-4" aria-hidden="true" /> Unlink
          </Button>
        </div>
      ) : null}
      {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
      {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
    </InspectorSection>
  )
}
