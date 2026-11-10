import { useQuery, useQueryClient } from '@tanstack/react-query'
import { GitPullRequest } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { keys } from '@/api/queries'
import type { GitImportPreview, GitPushResult } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { providerLabel, useGitConnection } from '@/features/settings/GitConnectionPanel'

const short = (sha: string | null | undefined) => (sha ? sha.slice(0, 10) : '')
const DIRECTION: Record<string, string> = { push: 'Pushed', pull: 'Imported', publish: 'Published' }
const KIND: Record<string, string> = { added: 'Added', removed: 'Removed', changed: 'Changed' }
const AREA: Record<string, string> = { step: 'Step', route: 'Route', ruleTable: 'Rule table', field: 'Request field', setting: 'Setting', test: 'Test case' }

/**
 * Git sync for one workflow (CG-S01, docs/build/S147): push the draft to a review branch with a pull request, check a
 * branch and import it as a draft, and publish only from an approved commit whose content matches the draft.
 */
export function GitSyncPanel({ workflowId, workflowKey, draftRevision }: { workflowId: string; workflowKey: string; draftRevision: number }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const visible = can('connection.read') && can('workflow.read')
  const connection = useGitConnection(ws, visible)
  const eventsKey = ['ws', ws, 'workflow', workflowId, 'git-events'] as const
  const events = useQuery({ queryKey: eventsKey, queryFn: () => endpoints.listGitSyncEvents(ws, workflowId), enabled: visible && connection.data?.configured === true })
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [pushed, setPushed] = useState<GitPushResult | null>(null)
  const [branch, setBranch] = useState(`orqix/${workflowKey}`)
  const [preview, setPreview] = useState<GitImportPreview | null>(null)
  const [imported, setImported] = useState<string | null>(null)
  const [commit, setCommit] = useState('')
  const [pullRequest, setPullRequest] = useState('')
  const [asBundle, setAsBundle] = useState(false)
  const [acknowledge, setAcknowledge] = useState(false)
  const [published, setPublished] = useState<{ releaseNumber: number; commit: string; approvedBy: string[]; bundle: boolean } | null>(null)

  if (!visible) return null
  if (connection.isPending) return <LoadingBlock rows={1} label="Loading Git sync" />
  if (!connection.data?.configured || !connection.data.connection) return null
  const conn = connection.data.connection

  const run = async (kind: string, action: () => Promise<void>) => {
    setPending(kind)
    setError(null)
    try {
      await action()
      await client.invalidateQueries({ queryKey: eventsKey })
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(null)
    }
  }

  const push = () =>
    void run('push', async () => {
      const result = await endpoints.pushWorkflowToGit(ws, workflowId, { draftRevision })
      setPushed(result)
      if (result.pullRequest) setPullRequest(result.pullRequest.id)
      setCommit(result.commit)
      setBranch(result.branch)
    })
  const check = () =>
    void run('preview', async () => {
      setImported(null)
      setPreview(await endpoints.previewGitImport(ws, workflowId, branch.trim()))
    })
  const importDraft = () =>
    void run('import', async () => {
      if (!preview) return
      const result = await endpoints.importWorkflowFromGit(ws, workflowId, {
        branch: preview.branch,
        commit: preview.commit,
        expectedDraftRevision: draftRevision,
        pullRequest: pullRequest.trim() || null,
      })
      setImported(result.unchanged ? 'Nothing to import: the draft already has this content.' : `Imported as draft revision ${result.draftRevision}.`)
      setPreview(null)
      setCommit(result.commit)
      await client.invalidateQueries({ queryKey: keys.workflow(ws, workflowId) })
    })
  const publish = () =>
    void run('publish', async () => {
      const result = await endpoints.publishFromGit(ws, workflowId, {
        draftRevision,
        commit: commit.trim(),
        pullRequest: pullRequest.trim(),
        acknowledgeWarnings: acknowledge,
        activate: true,
        asBundle,
      }, crypto.randomUUID())
      setPublished({ releaseNumber: result.release.releaseNumber, commit: result.gitSource.commit, approvedBy: result.gitSource.approvedBy, bundle: result.bundle !== null })
      await Promise.all([
        client.invalidateQueries({ queryKey: keys.workflow(ws, workflowId) }),
        client.invalidateQueries({ queryKey: keys.releases(ws, workflowId) }),
        client.invalidateQueries({ queryKey: ['ws', ws, 'workflow', workflowId, 'bundles'] }),
      ])
    })

  return (
    <section aria-labelledby="git-sync-heading" className="mt-8">
      <h2 id="git-sync-heading" className="mb-1 flex items-center gap-2">
        <GitPullRequest className="size-5" aria-hidden="true" /> Git
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        {providerLabel(conn.provider)} repository {conn.repository}; pull requests into {conn.baseBranch}.
        {conn.enabled ? '' : ' The connection is switched off.'}
      </p>
      {error ? <ProblemAlert error={error} className="mb-3" context={{ action: 'sync with Git' }} /> : null}
      <div className="grid gap-4 rounded-card border border-border bg-surface p-4">
        {can('workflow.export') ? (
          <div>
            <Button variant="primary" onClick={push} loading={pending === 'push'}>Push draft revision {draftRevision} to Git</Button>
            {pushed ? (
              <p className="mt-2 text-sm" data-testid="git-push-result">
                {pushed.unchanged ? 'Already up to date' : `Committed ${short(pushed.commit)}`} on {pushed.branch}.{' '}
                {pushed.pullRequest?.url ? (
                  <a className="underline" href={pushed.pullRequest.url} target="_blank" rel="noreferrer">Pull request {pushed.pullRequest.id}</a>
                ) : pushed.pullRequest ? `Pull request ${pushed.pullRequest.id}` : null}
              </p>
            ) : null}
          </div>
        ) : null}

        {can('workflow.edit') ? (
          <div>
            <div className="flex flex-wrap items-end gap-2">
              <div>
                <Label htmlFor="git-import-branch">Branch to import</Label>
                <Input id="git-import-branch" value={branch} onChange={(e) => setBranch(e.target.value)} />
              </div>
              <Button onClick={check} loading={pending === 'preview'} disabled={!branch.trim()}>Check branch</Button>
            </div>
            {imported ? <Alert tone="success" className="mt-2">{imported}</Alert> : null}
            {preview ? (
              <div className="mt-3" data-testid="git-import-preview">
                <p className="text-sm">
                  Commit {short(preview.commit)}: {preview.identical ? 'the same content as the current draft.' : `${preview.changes.length} differences from draft revision ${preview.currentDraftRevision}.`}
                </p>
                {preview.changes.length > 0 ? (
                  <Table aria-label="Differences from the draft" className="mt-2">
                    <THead><TR><TH>Change</TH><TH>What</TH><TH>Detail</TH></TR></THead>
                    <TBody>
                      {preview.changes.map((c, i) => (
                        <TR key={i}><TD>{KIND[c.kind] ?? c.kind}</TD><TD>{AREA[c.area] ?? c.area}</TD><TD>{c.title}</TD></TR>
                      ))}
                    </TBody>
                  </Table>
                ) : null}
                {preview.issues.some((i) => i.severity === 'blocker') ? (
                  <Alert tone="warning" className="mt-2">The imported draft has blocking issues; fix them in Studio before publishing.</Alert>
                ) : null}
                {!preview.identical ? (
                  <Button className="mt-2" onClick={importDraft} loading={pending === 'import'}>Import as a new draft</Button>
                ) : null}
              </div>
            ) : null}
          </div>
        ) : null}

        {can('workflow.publish') ? (
          <div>
            <p className="mb-2 text-sm text-muted-foreground">
              Publish only from a commit that a reviewer approved in its pull request. Its files must match draft revision {draftRevision}.
            </p>
            <div className="flex flex-wrap items-end gap-2">
              <div>
                <Label htmlFor="git-publish-commit">Reviewed commit</Label>
                <Input id="git-publish-commit" value={commit} onChange={(e) => setCommit(e.target.value)} className="font-mono" />
              </div>
              <div>
                <Label htmlFor="git-publish-pr">Pull request</Label>
                <Input id="git-publish-pr" value={pullRequest} onChange={(e) => setPullRequest(e.target.value)} className="w-28" />
              </div>
              <div className="flex items-center gap-2 pb-2">
                <Checkbox id="git-publish-ack" checked={acknowledge} onCheckedChange={(v) => setAcknowledge(v === true)} />
                <Label htmlFor="git-publish-ack">I reviewed the warnings</Label>
              </div>
              <div className="flex items-center gap-2 pb-2">
                <Checkbox id="git-publish-bundle" checked={asBundle} onCheckedChange={(v) => setAsBundle(v === true)} />
                <Label htmlFor="git-publish-bundle">Publish as a bundle</Label>
              </div>
              <Button variant="primary" onClick={publish} loading={pending === 'publish'} disabled={!/^[0-9a-f]{40}$/.test(commit.trim()) || !pullRequest.trim()}>
                Publish from reviewed commit
              </Button>
            </div>
            {published ? (
              <Alert tone="success" className="mt-2">
                Published version {published.releaseNumber} from commit {short(published.commit)}, approved by{' '}
                {published.approvedBy.join(', ')}.{published.bundle ? ' Published as a bundle.' : ''}
              </Alert>
            ) : null}
          </div>
        ) : null}

        {events.data && events.data.length > 0 ? (
          <Table aria-label="Git history">
            <THead><TR><TH>When</TH><TH>What</TH><TH>Branch</TH><TH>Commit</TH><TH>By</TH></TR></THead>
            <TBody>
              {events.data.map((e) => (
                <TR key={e.id}>
                  <TD>{formatDateTime(e.occurredAt)}</TD>
                  <TD><Badge tone={e.direction === 'publish' ? 'success' : 'neutral'}>{DIRECTION[e.direction] ?? e.direction}</Badge>{e.draftRevision ? ` revision ${e.draftRevision}` : ''}</TD>
                  <TD>{e.branch}</TD>
                  <TD className="font-mono">{short(e.commit)}</TD>
                  <TD>{e.actor.displayName}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        ) : null}
      </div>
    </section>
  )
}
