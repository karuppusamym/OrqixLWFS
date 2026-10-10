import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const policyKey = (ws: string) => ['ws', ws, 'migration-policy'] as const

/**
 * Workspace setting: require an independent review of migrated workflows before they are published. The setting is
 * versioned (a stale page cannot overwrite a newer one) and every change records a reason and is audited.
 */
export function MigrationPolicyPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: policyKey(ws), queryFn: () => endpoints.getMigrationPolicy(ws), retry: false })
  // Kept here, not in the form: the form restarts from the newer setting when it arrives.
  const [conflict, setConflict] = useState(false)
  if (query.isPending) return <LoadingBlock rows={2} label="Loading the migration review setting" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="the migration review setting" />
  // Re-keyed by version: a reload after a conflict starts the form from the current setting.
  return <PolicyForm key={query.data.version} ws={ws} policy={query.data} conflict={conflict} onConflict={setConflict} />
}

function PolicyForm({ ws, policy, conflict, onConflict }: { ws: string; policy: { required: boolean; version: number; reason: string | null; updatedAt: string | null }; conflict: boolean; onConflict: (value: boolean) => void }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const ids = useId()
  const [required, setRequired] = useState(policy.required)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    setPending(true); setError(null); onConflict(false)
    try {
      const next = await endpoints.setMigrationPolicy(ws, { required, expectedVersion: policy.version, reason: reason.trim() })
      client.setQueryData(policyKey(ws), next)
      announce(next.required ? 'Independent migration review is now required.' : 'Independent migration review is no longer required.')
    } catch (cause) {
      if (isProblemError(cause) && cause.is('WORKFLOW_VERSION_CONFLICT')) {
        onConflict(true)
        void client.invalidateQueries({ queryKey: policyKey(ws) })
      } else setError(isProblemError(cause) ? (cause.detail ?? cause.title) : 'The setting could not be saved.')
    } finally { setPending(false) }
  }

  return <section aria-labelledby={`${ids}-title`} className="max-w-3xl space-y-4">
    <div>
      <h2 id={`${ids}-title`}>Independent review of migrated workflows</h2>
      <p className="mt-1 text-sm text-muted-foreground">When this is on, a workflow created by importing a Camunda or Pega source can only be published after a second person approves that exact draft, and a third person publishes it. The person who mapped the import, the author of the latest draft and the reviewer cannot take each other’s part. Workflows built in the studio are not affected.</p>
    </div>
    <p className="flex flex-wrap items-center gap-2 text-sm" data-testid="migration-policy-state">
      <Badge tone={policy.required ? 'primary' : 'neutral'}>{policy.required ? 'Independent review required' : 'Independent review not required'}</Badge>
      <span className="text-muted-foreground">Setting version {policy.version}{policy.updatedAt ? <>, changed <RelativeTime value={policy.updatedAt} /></> : ', never changed'}.</span>
    </p>
    {policy.reason ? <p className="text-sm"><span className="font-semibold">Last reason:</span> {policy.reason}</p> : null}
    <form className="space-y-4 rounded-card border border-border bg-surface p-5" aria-label="Migration review setting" onSubmit={(event) => { event.preventDefault(); void save() }}>
      <div className="flex items-start gap-3">
        <Checkbox id={`${ids}-required`} checked={required} onCheckedChange={(value) => setRequired(value === true)} />
        <Label htmlFor={`${ids}-required`} className="font-medium">Require an independent review before migrated workflows are published
          <span className="block text-xs font-normal text-muted-foreground">Existing approvals stay valid only while the draft, source and mappings are unchanged.</span></Label>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={`${ids}-reason`}>Reason for this setting</Label>
        <Textarea id={`${ids}-reason`} required maxLength={1000} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} aria-describedby={`${ids}-help`} />
        <p id={`${ids}-help`} className="text-xs text-muted-foreground">Required, up to 1,000 characters. It is kept in the audit trail with your name.</p>
      </div>
      {conflict ? <Alert tone="warning" title="The setting changed while you were editing">Someone else saved a newer version. The current value is shown now; check it, then save again if you still want to change it.</Alert> : null}
      {error ? <Alert tone="danger">{error}</Alert> : null}
      <Button type="submit" variant="primary" loading={pending} disabled={!reason.trim()}>Save migration review setting</Button>
    </form>
  </section>
}
