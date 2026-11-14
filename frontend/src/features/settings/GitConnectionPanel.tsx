import { useQuery, useQueryClient } from '@tanstack/react-query'
import { GitBranch } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { GitConnectionState } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

type Provider = 'github' | 'gitlab' | 'azure-devops' | 'bitbucket'

const PROVIDERS: Array<{ value: Provider; label: string; shape: string }> = [
  { value: 'github', label: 'GitHub', shape: 'owner/repository' },
  { value: 'gitlab', label: 'GitLab', shape: 'group/project' },
  { value: 'azure-devops', label: 'Azure DevOps', shape: 'organization/project/repository' },
  { value: 'bitbucket', label: 'Bitbucket', shape: 'workspace/repository' },
]

export const gitConnectionKey = (ws: string) => ['ws', ws, 'git-connection'] as const

export function useGitConnection(ws: string, enabled = true) {
  return useQuery({ queryKey: gitConnectionKey(ws), queryFn: () => endpoints.getGitConnection(ws), enabled })
}

/**
 * The workspace's Git connection (CG-S01, docs/build/S147). Connection managers choose the provider and repository
 * and name the secret that holds the access token; the token itself is never entered or shown here.
 */
export function GitConnectionPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const manage = can('connection.manage')
  const state = useGitConnection(ws)
  // Kept here: the form remounts with each saved version, and the outcome must survive that.
  const [message, setMessage] = useState<string | null>(null)

  return (
    <section aria-labelledby="git-connection-heading" className="mt-8">
      <h2 id="git-connection-heading" className="mb-1 flex items-center gap-2">
        <GitBranch className="size-5" aria-hidden="true" /> Git repository
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        Workflows are exported to a review branch with a pull request, imported back as drafts, and published only from an
        approved commit that matches the draft. Bindings and secrets never go to Git.
      </p>
      {state.isPending ? (
        <LoadingBlock rows={2} label="Loading the Git connection" />
      ) : state.isError ? (
        <QueryError error={state.error} onRetry={() => void state.refetch()} what="the Git connection" />
      ) : (
        <>
          {message ? <Alert tone="success" className="mb-3" data-testid="git-connection-message">{message}</Alert> : null}
          <GitConnectionForm key={state.data.connection?.version ?? 0} ws={ws} state={state.data} manage={manage} onMessage={setMessage} />
        </>
      )}
    </section>
  )
}

function GitConnectionForm({ ws, state, manage, onMessage }: { ws: string; state: GitConnectionState; manage: boolean; onMessage: (message: string | null) => void }) {
  const client = useQueryClient()
  const current = state.connection
  const [provider, setProvider] = useState<Provider>((current?.provider as Provider) ?? 'github')
  const [apiUrl, setApiUrl] = useState(current?.apiUrl ?? '')
  const [repository, setRepository] = useState(current?.repository ?? '')
  const [baseBranch, setBaseBranch] = useState(current?.baseBranch ?? 'main')
  const [basePath, setBasePath] = useState(current?.basePath ?? 'orqix')
  const [credentialRef, setCredentialRef] = useState(current?.credentialRef ?? '')
  const [pending, setPending] = useState<'save' | 'check' | 'remove' | null>(null)
  const [error, setError] = useState<unknown>(null)

  const refresh = () => client.invalidateQueries({ queryKey: gitConnectionKey(ws) })
  const run = async (kind: 'save' | 'check' | 'remove', action: () => Promise<string | null>) => {
    setPending(kind)
    setError(null)
    onMessage(null)
    try {
      onMessage(await action())
      await refresh()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(null)
    }
  }

  if (!manage) {
    return current ? (
      <dl className="grid gap-1 rounded-card border border-border bg-surface p-4 text-sm" data-testid="git-connection-summary">
        <div><dt className="inline font-semibold">Provider: </dt><dd className="inline">{providerLabel(current.provider)}</dd></div>
        <div><dt className="inline font-semibold">Repository: </dt><dd className="inline">{current.repository}</dd></div>
        <div><dt className="inline font-semibold">Pull requests into: </dt><dd className="inline">{current.baseBranch}</dd></div>
        <div><dt className="inline font-semibold">Folder: </dt><dd className="inline">{current.basePath}</dd></div>
        {!current.enabled ? <div className="text-warning-foreground">Switched off</div> : null}
      </dl>
    ) : (
      <p className="text-sm text-muted-foreground">No Git repository is connected. A connection manager can connect one.</p>
    )
  }

  const shape = PROVIDERS.find((p) => p.value === provider)?.shape ?? ''
  const save = () =>
    void run('save', async () => {
      await endpoints.saveGitConnection(ws, {
        provider,
        apiUrl: apiUrl.trim() || null,
        repository: repository.trim(),
        baseBranch: baseBranch.trim() || null,
        basePath: basePath.trim() || null,
        credentialRef: credentialRef.trim(),
        enabled: true,
        expectedVersion: current?.version ?? 0,
      })
      return 'Saved.'
    })

  return (
    <div className="grid gap-3 rounded-card border border-border bg-surface p-4">
      {error ? <ProblemAlert error={error} context={{ action: 'save the Git connection' }} /> : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label htmlFor="git-provider">Provider</Label>
          <Select id="git-provider" value={provider} onChange={(e) => setProvider(e.target.value as Provider)}>
            {PROVIDERS.map((p) => (
              <option key={p.value} value={p.value}>{p.label}</option>
            ))}
          </Select>
        </div>
        <div>
          <Label htmlFor="git-repository">Repository</Label>
          <Input id="git-repository" value={repository} placeholder={shape} onChange={(e) => setRepository(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="git-api-url">API address (empty for the public service)</Label>
          <Input id="git-api-url" value={apiUrl} onChange={(e) => setApiUrl(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="git-credential">Token secret reference</Label>
          <Input id="git-credential" value={credentialRef} placeholder="git-token or vault:kv/git#token" autoComplete="off"
            onChange={(e) => setCredentialRef(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="git-base-branch">Pull requests into branch</Label>
          <Input id="git-base-branch" value={baseBranch} onChange={(e) => setBaseBranch(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="git-base-path">Folder in the repository</Label>
          <Input id="git-base-path" value={basePath} onChange={(e) => setBasePath(e.target.value)} />
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        Name the secret that holds the access token (a mounted secret or a vault reference). Never paste the token itself.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" onClick={save} loading={pending === 'save'} disabled={!repository.trim() || !credentialRef.trim()}>
          {current ? 'Save changes' : 'Connect repository'}
        </Button>
        {current ? (
          <>
            <Button variant="secondary" loading={pending === 'check'} onClick={() => void run('check', async () => {
              const result = await endpoints.checkGitConnection(ws)
              return result.reachable ? `${result.message} Default branch: ${result.defaultBranch ?? 'unknown'}.` : `Not reachable: ${result.message}`
            })}>
              Test Git connection
            </Button>
            <Button variant="danger" loading={pending === 'remove'} onClick={() => void run('remove', async () => {
              await endpoints.deleteGitConnection(ws, current.version)
              return 'The Git connection was removed. The sync history is kept.'
            })}>
              Remove
            </Button>
          </>
        ) : null}
      </div>
    </div>
  )
}

export function providerLabel(provider: string) {
  return PROVIDERS.find((p) => p.value === provider)?.label ?? provider
}
