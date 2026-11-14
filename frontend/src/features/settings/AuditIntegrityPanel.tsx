import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download, ShieldCheck } from 'lucide-react'
import * as endpoints from '@/api/endpoints'
import { useAuditChainChecks } from '@/api/queries'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { downloadJson } from '@/lib/download'

type Check = components['schemas']['AuditChainCheck']

const REASON_TEXT: Record<NonNullable<Check['reason']>, string> = {
  hashMismatch: 'an entry was changed after it was written',
  previousHashMismatch: 'an entry does not follow the one before it',
  missingEvent: 'an entry is missing',
  headMismatch: 'the newest entries were removed or replaced',
}

const checkSummary = (c: Check) =>
  c.intact ? `Intact: ${c.checked.toLocaleString()} entries checked` : `Broken at position ${c.brokenSeq ?? '?'}${c.reason ? `: ${REASON_TEXT[c.reason]}` : ''}`

/**
 * Settings → Run retention → Audit log integrity (CG-I06, docs/build/S151). Every audit entry carries the hash of the one
 * before it, so a changed, missing or reordered entry breaks the chain. Auditors check it here and download a signed
 * segment that anyone can verify with the published signing keys.
 */
export function AuditIntegrityPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const checks = useAuditChainChecks(ws)
  const verify = useMutation({
    mutationFn: () => endpoints.verifyAuditChain(ws),
    onSuccess: async (result) => {
      await client.invalidateQueries({ queryKey: ['ws', ws, 'audit', 'chain-verifications'] })
      announce(result.intact ? 'The audit log is intact.' : `The audit log is broken at position ${result.brokenSeq ?? '?'}.`)
    },
  })
  const exportLog = useMutation({
    mutationFn: () => endpoints.exportAuditLog(ws),
    onSuccess: (segment) => {
      downloadJson(segment, `audit-export-${workspace.key}-${segment.firstSeq}-${segment.lastSeq}.json`)
      announce('Signed audit export downloaded.')
    },
  })
  const latest = verify.data

  return (
    <section aria-labelledby="audit-integrity-heading" className="max-w-5xl space-y-4">
      <div>
        <h2 id="audit-integrity-heading">Audit log integrity</h2>
        <p className="text-sm text-muted-foreground">
          Each audit entry is linked to the one before it, so any change, gap or reordering shows up when the log is checked.
          The signed export can be checked outside Orqix with the workspace&apos;s public signing keys.
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" loading={verify.isPending} onClick={() => verify.mutate()}><ShieldCheck aria-hidden="true" /> Verify now</Button>
        <Button variant="secondary" loading={exportLog.isPending} onClick={() => exportLog.mutate()}><Download aria-hidden="true" /> Download signed export</Button>
      </div>
      {verify.isError ? <ProblemAlert error={toProblem(verify.error)} context={{ action: 'check the audit log' }} /> : null}
      {exportLog.isError ? <ProblemAlert error={toProblem(exportLog.error)} context={{ action: 'download the audit export' }} /> : null}
      {latest ? (
        latest.intact
          ? <Alert tone="success" title="The audit log is intact">{latest.checked.toLocaleString()} entries checked, up to position {latest.headSeq}.</Alert>
          : <Alert tone="danger" title={`The audit log is broken at position ${latest.brokenSeq ?? '?'}`}>{latest.reason ? `The check found that ${REASON_TEXT[latest.reason]}. ` : ''}Entries before this position are intact. Keep the signed exports you downloaded earlier as evidence.</Alert>
      ) : null}
      <h3 className="text-sm text-muted-foreground uppercase">Latest checks</h3>
      {checks.isPending ? <LoadingBlock rows={2} label="Loading the latest checks" />
        : checks.isError ? <QueryError error={checks.error} onRetry={() => void checks.refetch()} what="the latest checks" />
          : (
            <div className="overflow-x-auto rounded-card border border-border bg-surface">
              <Table>
                <caption className="sr-only">Latest checks of the audit log</caption>
                <THead><TR><TH>Checked</TH><TH>Result</TH><TH>Newest position</TH></TR></THead>
                <TBody>
                  {checks.data.items.map((c) => (
                    <TR key={`${c.checkedAt}-${c.headSeq}-${c.brokenSeq ?? ''}`}>
                      <TD>{formatDateTime(c.checkedAt)}</TD>
                      <TD><Badge tone={c.intact ? 'success' : 'danger'}>{c.intact ? 'Intact' : 'Broken'}</Badge> <span className="text-sm text-muted-foreground">{checkSummary(c)}</span></TD>
                      <TD className="font-mono">{c.headSeq}</TD>
                    </TR>
                  ))}
                  {checks.data.items.length === 0 ? <TR><TD colSpan={3} className="text-muted-foreground">The audit log has not been checked yet.</TD></TR> : null}
                </TBody>
              </Table>
            </div>
          )}
    </section>
  )
}
