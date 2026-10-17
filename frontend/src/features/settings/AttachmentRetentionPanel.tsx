import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getAttachmentRetention, setAttachmentRetention } from '@/api/attachments'
import { isProblemError } from '@/api/problem'
import type { AttachmentRetention } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

const retentionKey = (ws: string) => ['ws', ws, 'attachment-retention'] as const

/**
 * How long request files are kept after the request ends (DOC08, docs/build/S52). Empty keeps files until someone
 * removes them. Quarantined files are never removed by this setting.
 */
export function AttachmentRetentionPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: retentionKey(ws), queryFn: () => getAttachmentRetention(ws), retry: false })
  if (query.isPending) return <LoadingBlock rows={2} label="Loading the file retention setting" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="the file retention setting" />
  return <RetentionForm key={query.data.version} ws={ws} setting={query.data} />
}

function RetentionForm({ ws, setting }: { ws: string; setting: AttachmentRetention }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const [days, setDays] = useState(setting.retentionDays == null ? '' : String(setting.retentionDays))
  const [conflict, setConflict] = useState(false)
  const [localError, setLocalError] = useState<string>()
  const save = useMutation({
    mutationFn: (value: number | null) => setAttachmentRetention(ws, value, setting.version),
    onSuccess: (next) => {
      setConflict(false)
      client.setQueryData(retentionKey(ws), next)
      announce(next.retentionDays == null ? 'Files are now kept until someone removes them.' : `Files are now removed ${next.retentionDays} days after their request ends.`)
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
    if (!Number.isInteger(value) || value < 1 || value > 3650) return setLocalError('Enter a whole number of days from 1 to 3650, or leave it empty to keep files.')
    setLocalError(undefined)
    save.mutate(value)
  }
  return (
    <section aria-labelledby="retention-title" className="max-w-3xl space-y-4">
      <div>
        <h2 id="retention-title">File retention</h2>
        <p className="text-sm text-muted-foreground">
          Remove the files of a request this many days after it finishes. The request keeps a record that the file existed; the content is deleted and cannot be restored. Files of running requests and quarantined files are never removed.
        </p>
      </div>
      <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); submit() }}>
        <FormField label="Days to keep files after a request ends" id="retention-days" description="Leave empty to keep files until someone removes them." error={localError}>
          {(ids) => <Input id={ids.input} inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} aria-invalid={localError ? true : undefined} aria-describedby={ids.describedBy} className="max-w-40" />}
        </FormField>
        {setting.retentionDays != null ? <p className="text-sm" data-testid="retention-eligible">{setting.eligibleNow} {setting.eligibleNow === 1 ? 'file is' : 'files are'} past this period and will be removed at the next hourly sweep.</p> : null}
        {conflict ? <Alert tone="warning">The setting changed while you were editing. The current value is shown; check it and save again.</Alert> : null}
        {save.isError && !conflict ? <ProblemAlert error={toProblem(save.error)} /> : null}
        <Button type="submit" variant="primary" loading={save.isPending}>Save retention</Button>
      </form>
    </section>
  )
}
