import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Ban, CheckCircle2, ServerCrash } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { keys, useConnection, useConnections } from '@/api/queries'
import type { ConnectionBinding, ConnectionSummary, ConnectionTestResult } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { describeProblem, fieldErrorMap } from '@/lib/problemMessages'
import { EFFECT_CLASS_LABEL } from '@/features/runs/operatorText'
import { AgentTest } from './AgentTest'
import { AGENT_USES_AI, AiAgentBadge } from './AgentSpecView'

export function ConnectionsPanel() {
  const { workspace } = useWorkspace()
  const query = useConnections(workspace.id)
  if (query.isPending) return <LoadingBlock rows={2} label="Loading connections" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="connections" />
  if (query.data.items.length === 0) {
    return <p className="rounded-card border border-dashed border-input-border bg-surface p-6 text-sm text-muted-foreground">No connections are registered in this workspace.</p>
  }
  return (
    <div className="space-y-8">
      <div className="rounded-card border border-border bg-surface-muted p-5">
        <h2>Connect a workflow to another system</h2>
        <ol className="mt-3 list-decimal space-y-2 pl-5 text-sm text-muted-foreground">
          <li>Choose an approved connection and configure its workspace address and sign-in method.</li>
          <li>Enable the connection, save the address, then use a read-only operation to test access.</li>
          <li>In the designer, add a Call a system step, choose an operation, map what to send and keep the result fields you need.</li>
        </ol>
        <p className="mt-3 text-sm text-muted-foreground">New systems need a registered operation contract from your platform team. Saving an address does not add new operations.</p>
      </div>
      {query.data.items.map((c) => (
        <ConnectionCard key={c.key} connection={c} />
      ))}
    </div>
  )
}

function ConnectionCard({ connection: c }: { connection: ConnectionSummary }) {
  const { can } = useWorkspace()
  return (
    <section aria-labelledby={`conn-${c.key}`} className="rounded-card border border-border bg-surface">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border p-5">
        <div>
          <h2 id={`conn-${c.key}`} className="flex flex-wrap items-center gap-2">
            {c.name}
            {c.kind === 'agent' ? <AiAgentBadge /> : null}
          </h2>
          <p className="font-mono text-xs text-muted-foreground">
            {c.key} &middot; version {c.latestVersion}
          </p>
          {c.kind === 'agent' ? <p className="mt-1 text-sm text-muted-foreground">{AGENT_USES_AI}. The address is the tool server (MCP) the agent may call.</p> : null}
        </div>
        <div className="flex flex-wrap items-center gap-2" data-testid={`binding-status-${c.key}`}>
          {!c.binding.configured ? (
            <Badge tone="danger">
              <AlertTriangle aria-hidden="true" /> No address configured
            </Badge>
          ) : c.binding.enabled ? (
            <Badge tone="success">
              <CheckCircle2 aria-hidden="true" /> Enabled
            </Badge>
          ) : (
            <Badge tone="warning">
              <Ban aria-hidden="true" /> Disabled
            </Badge>
          )}
          {c.binding.host ? <span className="text-sm text-muted-foreground">{c.binding.host}</span> : null}
        </div>
      </div>
      <div className="p-5">
        <h3 className="mb-2 text-sm text-muted-foreground uppercase">Operations</h3>
        <div className="overflow-x-auto rounded-card border border-border">
          <Table>
            <caption className="sr-only">Operations of {c.name}</caption>
            <THead>
              <TR>
                <TH>Operation</TH>
                <TH>Retry behavior</TH>
                <TH>Can be tested</TH>
              </TR>
            </THead>
            <TBody>
              {c.operations.map((op) => (
                <TR key={op.name}>
                  <TD>
                    <span className="font-semibold">{op.label}</span>
                    <span className="block font-mono text-xs text-muted-foreground">{op.name}</span>
                  </TD>
                  <TD className="max-w-md text-muted-foreground">{EFFECT_CLASS_LABEL[op.effectClass]}</TD>
                  <TD>{op.testable ? 'Yes' : 'No'}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </div>
      </div>
      {can('connection.manage') ? <ManageConnection connection={c} /> : null}
    </section>
  )
}

const AUTH_LABELS = { none: 'No sign-in', bearerToken: 'Bearer token', apiKeyHeader: 'API key in a header' } as const

function ManageConnection({ connection }: { connection: ConnectionSummary }) {
  const { workspace } = useWorkspace()
  const detail = useConnection(workspace.id, connection.key)
  if (detail.isPending) return <div className="border-t border-border p-5"><LoadingBlock rows={1} label="Loading the address" /></div>
  if (detail.isError) return <div className="border-t border-border p-5"><QueryError error={detail.error} onRetry={() => void detail.refetch()} what="the connection address" /></div>
  const binding = detail.data.binding
  return (
    <div className="grid gap-8 border-t border-border p-5 lg:grid-cols-2">
      <BindingForm key={binding?.revision ?? 0} connectionKey={connection.key} binding={binding} graph={connection.kind === 'microsoftGraph'} />
      {connection.kind === 'agent' ? <AgentTest connection={connection} /> : <TestConnection connection={connection} />}
    </div>
  )
}

function BindingForm({ connectionKey, binding, graph }: { connectionKey: string; binding: ConnectionBinding | null; graph: boolean }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const [baseUrl, setBaseUrl] = useState(binding?.baseUrl ?? (graph ? 'https://graph.microsoft.com/v1.0' : ''))
  const [authType, setAuthType] = useState<keyof typeof AUTH_LABELS>(binding?.auth.type ?? (graph ? 'bearerToken' : 'none'))
  const [headerName, setHeaderName] = useState(binding?.auth.headerName ?? '')
  const [secretRef, setSecretRef] = useState(binding?.auth.secretRef ?? '')
  const [enabled, setEnabled] = useState(binding?.enabled ?? true)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [saved, setSaved] = useState(false)

  const save = async () => {
    const next: Record<string, string> = {}
    if (!baseUrl.trim()) next.baseUrl = 'Enter the address of the other system, for example https://procurement.example.internal.'
    else {
      try {
        const u = new URL(baseUrl.trim())
        if (u.protocol !== 'https:' && u.protocol !== 'http:') next.baseUrl = 'Use an https:// address.'
      } catch {
        next.baseUrl = 'Enter a full address that starts with https://.'
      }
    }
    if (authType === 'apiKeyHeader' && !/^[A-Za-z][A-Za-z0-9-]{0,63}$/.test(headerName)) next.headerName = 'Enter the header name, for example X-Api-Key.'
    if (authType !== 'none' && !/^[a-z0-9][a-z0-9-]{0,62}$/.test(secretRef)) next.secretRef = 'Enter the name of the secret: lowercase letters, digits and dashes.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setPending(true)
    setError(null)
    setSaved(false)
    try {
      await endpoints.putConnectionBinding(
        ws,
        connectionKey,
        {
          baseUrl: baseUrl.trim(),
          enabled,
          auth: {
            type: authType,
            ...(authType === 'apiKeyHeader' ? { headerName } : {}),
            ...(authType !== 'none' ? { secretRef } : {}),
          },
        },
        binding?.revision ?? null,
      )
      await Promise.all([client.invalidateQueries({ queryKey: keys.connection(ws, connectionKey) }), client.invalidateQueries({ queryKey: keys.connections(ws) })])
      setSaved(true)
      announce('Connection address saved')
    } catch (cause) {
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (Object.keys(mapped).length > 0) setErrors({ baseUrl: mapped.baseUrl ?? '', ...mapped })
      if (problem.code === 'EGRESS_NOT_ALLOWED') setErrors({ baseUrl: describeProblem(problem).message })
      setError(problem)
    } finally {
      setPending(false)
    }
  }

  const stale = error?.code === 'STALE_REVISION'

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        void save()
      }}
      noValidate
      aria-label={`Address of ${connectionKey}`}
      className="space-y-4"
    >
      <h3>Connection address</h3>
      <p className="text-sm text-muted-foreground">
        {binding ? `Last changed ${formatDateTime(binding.updatedAt)} by ${binding.updatedBy.displayName}.` : 'No address has been set for this workspace yet.'} Secret values are never entered or shown here; only the name of a secret that the platform team mounted.
      </p>
      <FormField label="Address" required id={`bind-url-${connectionKey}`} error={errors.baseUrl}>
        {(ids) => <Input id={ids.input} type="url" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} aria-invalid={errors.baseUrl ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" spellCheck={false} />}
      </FormField>
      <div className="space-y-1.5">
        <Label htmlFor={`bind-auth-${connectionKey}`}>Sign-in method</Label>
        <Select id={`bind-auth-${connectionKey}`} value={authType} onChange={(e) => setAuthType(e.target.value as keyof typeof AUTH_LABELS)}>
          {Object.entries(AUTH_LABELS).filter(([value]) => !graph || value === 'bearerToken').map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </Select>
      </div>
      {authType === 'apiKeyHeader' ? (
        <FormField label="Header name" required id={`bind-header-${connectionKey}`} error={errors.headerName}>
          {(ids) => <Input id={ids.input} value={headerName} onChange={(e) => setHeaderName(e.target.value)} aria-invalid={errors.headerName ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" spellCheck={false} />}
        </FormField>
      ) : null}
      {authType !== 'none' ? (
        <FormField label="Secret name" required id={`bind-secret-${connectionKey}`} error={errors.secretRef} description="The name of a secret mounted into Orqix. Do not type the secret itself.">
          {(ids) => <Input id={ids.input} value={secretRef} onChange={(e) => setSecretRef(e.target.value)} aria-invalid={errors.secretRef ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" spellCheck={false} />}
        </FormField>
      ) : null}
      <div className="flex items-center gap-3">
        <Checkbox id={`bind-enabled-${connectionKey}`} checked={enabled} onCheckedChange={(v) => setEnabled(v === true)} />
        <Label htmlFor={`bind-enabled-${connectionKey}`} className="font-medium">
          Enabled (workflows can use this connection)
        </Label>
      </div>
      {error && !Object.keys(errors).some((k) => errors[k]) ? <ProblemAlert error={error} /> : null}
      {stale ? (
        <Alert tone="warning">
          Someone else changed this address.{' '}
          <Button variant="link" onClick={() => void client.invalidateQueries({ queryKey: keys.connection(ws, connectionKey) })}>
            Load the latest
          </Button>
        </Alert>
      ) : null}
      {saved ? <Alert tone="success">Address saved.</Alert> : null}
      <Button type="submit" variant="primary" loading={pending}>
        Save address
      </Button>
    </form>
  )
}

function TestConnection({ connection }: { connection: ConnectionSummary }) {
  const { workspace } = useWorkspace()
  const testable = connection.operations.filter((o) => o.testable)
  const [operation, setOperation] = useState(testable[0]?.name ?? '')
  const [pending, setPending] = useState(false)
  const [result, setResult] = useState<ConnectionTestResult | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const graph = connection.kind === 'microsoftGraph'
  const [documentRef, setDocumentRef] = useState({ driveId: '', itemId: '', versionId: '' })

  const run = async () => {
    setPending(true)
    setError(null)
    setResult(null)
    try {
      setResult(await endpoints.testConnection(workspace.id, connection.key, connection.latestVersion, operation,
        graph ? Object.fromEntries(Object.entries(documentRef).map(([key, value]) => [key, value.trim()])) : undefined))
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="space-y-4">
      <h3>Test connection</h3>
      {testable.length === 0 ? (
        <p className="text-sm text-muted-foreground">None of this connection&apos;s operations can be tested, because they would change data in the other system.</p>
      ) : (
        <>
          <p className="text-sm text-muted-foreground">Runs a read-only operation through the real address and the network policy. Nothing is changed in the other system.</p>
          <div className="space-y-1.5">
            <Label htmlFor={`test-op-${connection.key}`}>Operation to test</Label>
            <Select id={`test-op-${connection.key}`} value={operation} onChange={(e) => setOperation(e.target.value)}>
              {testable.map((o) => (
                <option key={o.name} value={o.name}>
                  {o.label}
                </option>
              ))}
            </Select>
          </div>
          {graph ? (
            <div className="space-y-3">
              <p className="text-sm text-muted-foreground">Choose a specific document version using its drive, item and version identifiers. Text reads support archived UTF-8 text versions up to 64 KiB. Your platform team controls document access.</p>
              {(['driveId', 'itemId', 'versionId'] as const).map((field) => (
                <FormField key={field} label={{ driveId: 'Drive ID', itemId: 'Document item ID', versionId: 'Document version ID' }[field]} required id={`test-${field}-${connection.key}`}>
                  {(ids) => <Input id={ids.input} value={documentRef[field]} onChange={(event) => setDocumentRef({ ...documentRef, [field]: event.target.value })} autoComplete="off" spellCheck={false} />}
                </FormField>
              ))}
            </div>
          ) : null}
          {!connection.binding.configured || !connection.binding.enabled ? <p className="text-sm text-muted-foreground">Save an enabled connection address before testing.</p> : null}
          <Button variant="secondary" onClick={() => void run()} loading={pending} disabled={!connection.binding.configured || !connection.binding.enabled || (graph && Object.values(documentRef).some((value) => !value.trim()))}>
            Test connection
          </Button>
        </>
      )}
      <div aria-live="polite" role="status">
        {result ? (
          result.outcome === 'succeeded' ? (
            <Alert tone="success" live="off" title="The other system answered">
              <span className="inline-flex items-center gap-1.5">
                <CheckCircle2 className="size-4" aria-hidden="true" /> HTTP {result.httpStatus ?? '—'} in {result.durationMs} ms
              </span>
            </Alert>
          ) : (
            <Alert tone="danger" live="off" title="The test failed">
              <span className="inline-flex items-center gap-1.5">
                <ServerCrash className="size-4" aria-hidden="true" /> {result.errorCode ?? 'Failed'} after {result.durationMs} ms
              </span>
              {result.detail ? <span className="mt-1 block">{result.detail}</span> : null}
            </Alert>
          )
        ) : null}
      </div>
      {error ? <ProblemAlert error={error} /> : null}
    </div>
  )
}
