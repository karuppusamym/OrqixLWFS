import type { RunEvent } from '@/api/types'
import { humanize } from '@/lib/text'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'

type Scalar = string | number | boolean
type Input = {fieldRef:string;value:Scalar}
type Row = {rowId:string;matched:boolean;selected:boolean;condition:{fieldRef:string;operator:string;value:Scalar}}
const record = (value:unknown): Record<string,unknown> | null => typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string,unknown> : null
const scalar = (value:unknown): value is Scalar => typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value)
const label = (ref:string) => humanize(ref.split('/').at(-1) ?? ref)
const operators:Record<string,string> = {eq:'equals',ne:'does not equal',gt:'is greater than',gte:'is at least',lt:'is less than',lte:'is at most'}

/** Evidence arrives only within the server's request-data permission scope. Old events may have none. */
export function DecisionEvidence({event}:{event:RunEvent}) {
  if (event.type !== 'DECISION_EVALUATED') return null
  if (event.data.evidenceRedacted === true) return <p className="text-xs text-muted-foreground">Decision evidence is restricted to request participants and readers with data permission.</p>
  const evidence=record(event.data.evidence)
  if (!evidence || !Array.isArray(evidence.inputs) || evidence.inputs.length > 50 || !Array.isArray(evidence.rows) || evidence.rows.length > 50 || typeof evidence.defaultSelected !== 'boolean') return null
  const inputs:Input[]=[]; const rows:Row[]=[]
  for(const raw of evidence.inputs) {
    const input=record(raw)
    if (!input || typeof input.fieldRef !== 'string' || !scalar(input.value)) return null
    inputs.push({fieldRef:input.fieldRef,value:input.value})
  }
  for(const raw of evidence.rows) {
    const row=record(raw); const condition=record(row?.condition)
    if(!row || !condition || typeof row.rowId !== 'string' || typeof row.matched !== 'boolean' || typeof row.selected !== 'boolean' || typeof condition.fieldRef !== 'string' || typeof condition.operator !== 'string' || !operators[condition.operator] || !scalar(condition.value)) return null
    rows.push({rowId:row.rowId,matched:row.matched,selected:row.selected,condition:{fieldRef:condition.fieldRef,operator:condition.operator,value:condition.value}})
  }
  if (!rows.length || !inputs.length) return null
  return <details className="mt-2 text-sm"><summary className="cursor-pointer font-medium">Decision evidence</summary>
    <p className="mt-2 text-xs text-muted-foreground">Inputs and candidate rules from this request's pinned release. Selection is confirmed against the executed decision.</p>
    <dl className="my-2">{inputs.map(input => <div key={input.fieldRef}><dt className="inline font-medium">{label(input.fieldRef)}: </dt><dd className="inline whitespace-pre-wrap wrap-anywhere">{String(input.value)}</dd></div>)}</dl>
    <div className="overflow-x-auto"><Table><caption className="sr-only">Evaluated candidate rules</caption><THead><TR><TH>Rule</TH><TH>Condition</TH><TH>Matched</TH><TH>Selected</TH></TR></THead><TBody>
      {rows.map(row => <TR key={row.rowId}><TD>{row.rowId}</TD><TD className="wrap-anywhere">{label(row.condition.fieldRef)} {operators[row.condition.operator]} {String(row.condition.value)}</TD><TD>{row.matched?'Yes':'No'}</TD><TD>{row.selected?'Yes':'No'}</TD></TR>)}
    </TBody></Table></div><p className="mt-2">Default rule {evidence.defaultSelected?'selected':'not selected'}.</p>
  </details>
}
