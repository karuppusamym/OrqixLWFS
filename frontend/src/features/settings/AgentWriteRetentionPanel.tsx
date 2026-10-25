import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { AgentWriteRetention } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

const retentionKey = (ws: string) => ['ws', ws, 'agent-write-retention'] as const

/**
 * How long agent write records are kept after their request ends (S85), as the DOC08 file retention setting. Records of
 * running requests, and every record of a request with a write whose outcome is unknown or not final, are never removed;
 * the effect journal and audit events are kept.
 */
export function AgentWriteRetentionPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: retentionKey(ws), queryFn: () => endpoints.getAgentWriteRetention(ws), retry: false })
  return (
    <section aria-labelledby="write-retention-heading" className="max-w-3xl space-y-3">
      <div>
        <h2 id="write-retention-heading">Agent write record retention</h2>
        <p className="text-sm text-muted-foreground">
          Remove the record of each write (who approved and requested it, the size and digest of what the agent wrote, any
          recorded outcome) this many days after its request ends. Records of running requests, and all records of a request
          with a write whose outcome is unknown or not final, are never removed. The effect journal and the audit log keep
          their entries.
        </p>
      </div>
      {query.isPending ? (
        <LoadingBlock rows={1} label="Loading the write record retention setting" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="the write record retention setting" />
      ) : (
        <RetentionForm key={query.data.version} ws={ws} setting={query.data} />
      )}
    </section>
  )
}

function RetentionForm({ ws, setting }: { ws: string; setting: AgentWriteRetention }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const [days, setDays] = useState(setting.retentionDays == null ? '' : String(setting.retentionDays))
  const [conflict, setConflict] = useState(false)
  const [localError, setLocalError] = useState<string>()
  const save = useMutation({
    mutationFn: (value: number | null) => endpoints.setAgentWriteRetention(ws, value, setting.version),
    onSuccess: (next) => {
      setConflict(false)
      client.setQueryData(retentionKey(ws), next)
      announce(next.retentionDays == null ? 'Write records are now kept.' : `Write records are now removed ${next.retentionDays} days after their request ends.`)
    },
    onError: (error) => {
      if (isProblemError(error) && error.is('WORKFLOW_VERSION_CONFLICT')) {
        setConflict(true)
        void client.invalidateQueries({ queryKey: retentionKey(ws) })
      }
    },
  })
  const submit = () => {
    const trimmed = days.trim()
    if (trimmed === '') {
      setLocalError(undefined)
      return save.mutate(null)
    }
    const value = Number(trimmed)
    if (!Number.isInteger(value) || value < 1 || value > 3650) return setLocalError('Enter a whole number of days from 1 to 3650, or leave it empty to keep write records.')
    setLocalError(undefined)
    save.mutate(value)
  }
  return (
    <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); submit() }}>
      <FormField label="Days to keep write records after a request ends" id="write-retention-days" description="Leave empty to keep write records." error={localError}>
        {(ids) => <Input id={ids.input} inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} aria-invalid={localError ? true : undefined} aria-describedby={ids.describedBy} className="max-w-40" />}
      </FormField>
      {setting.retentionDays != null ? (
        <p className="text-sm" data-testid="write-retention-eligible">
          {setting.eligibleNow} {setting.eligibleNow === 1 ? 'write record is' : 'write records are'} past this period and will be removed at the next hourly sweep.
        </p>
      ) : null}
      {conflict ? <Alert tone="warning">The setting changed while you were editing. The current value is shown; check it and save again.</Alert> : null}
      {save.isError && !conflict ? <ProblemAlert error={toProblem(save.error)} /> : null}
      <Button type="submit" variant="primary" loading={save.isPending}>Save write record retention</Button>
    </form>
  )
}
