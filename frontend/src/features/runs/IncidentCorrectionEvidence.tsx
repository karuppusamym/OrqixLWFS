import type { RunEvent } from '@/api/types'
import { Table,THead,TBody,TR,TH,TD } from '@/components/ui/table'
import { displayJsonValue } from '@/lib/form/display'

export function IncidentCorrectionEvidence({event}:{event:RunEvent}) {
  if (event.type!=='INPUT_CORRECTED') return null
  if (event.data.detailsRedacted) return <p className="text-xs text-muted-foreground">Corrected values are restricted to people allowed to see request data.</p>
  const before=event.data.before
  const after=event.data.after
  if (!before || typeof before!=='object' || Array.isArray(before) || !after || typeof after!=='object' || Array.isArray(after)) return null
  return <details className="my-2"><summary className="cursor-pointer text-sm font-medium">Corrected values</summary>
    <Table aria-label="Corrected request values"><THead><TR><TH>Field</TH><TH>Before</TH><TH>After</TH></TR></THead><TBody>
      {Object.entries(after).map(([field,value])=><TR key={field}><TD>{field}</TD><TD className="whitespace-pre-wrap wrap-anywhere">{displayJsonValue(undefined,(before as Record<string,unknown>)[field])}</TD><TD className="whitespace-pre-wrap wrap-anywhere">{displayJsonValue(undefined,value)}</TD></TR>)}
    </TBody></Table>
  </details>
}
