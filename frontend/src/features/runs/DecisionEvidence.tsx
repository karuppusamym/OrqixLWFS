import type { RunEvent } from '@/api/types'
import { humanize } from '@/lib/text'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'

type Scalar = string | number | boolean
type Input = {fieldRef:string;value:Scalar}
type Range = {from:number;to:number;includeFrom?:boolean;includeTo?:boolean}
type Term = {fieldRef:string;operator:string;value:Scalar|Scalar[]|Range}
type Row = {rowId:string;matched:boolean;selected:boolean;group:'all'|'any'|null;terms:Term[]}
const record = (value:unknown): Record<string,unknown> | null => typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string,unknown> : null
const scalar = (value:unknown): value is Scalar => typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value)
const label = (ref:string) => humanize(ref.split('/').at(-1) ?? ref)
const operators:Record<string,string> = {eq:'equals',ne:'does not equal',gt:'is greater than',gte:'is at least',lt:'is less than',lte:'is at most',in:'is one of',between:'is between'}
const policies:Record<string,string> = {UNIQUE:'Exactly one rule may apply.',FIRST:'The first matching rule applies.',ANY:'Any matching rule applies; overlapping rules give the same result.',PRIORITY:'The matching rule with the highest priority applies.',COLLECT:'Every matching rule counts; their results are combined.'}
const aggregations:Record<string,string> = {SUM:'sum',MIN:'smallest value',MAX:'largest value',COUNT:'number of matching rules'}

/** One comparison, checked strictly: a value, a list of 1 to 20 values for "is one of", a range for "is between". */
function term(raw:unknown): Term | null {
  const t=record(raw)
  if(!t || typeof t.fieldRef !== 'string' || typeof t.operator !== 'string' || !operators[t.operator]) return null
  if(t.operator === 'in') return Array.isArray(t.value) && t.value.length >= 1 && t.value.length <= 20 && t.value.every(scalar) ? {fieldRef:t.fieldRef,operator:'in',value:t.value} : null
  if(t.operator === 'between') {
    const r=record(t.value)
    return r && typeof r.from === 'number' && typeof r.to === 'number' ? {fieldRef:t.fieldRef,operator:'between',value:{from:r.from,to:r.to,includeFrom:r.includeFrom !== false,includeTo:r.includeTo !== false}} : null
  }
  return scalar(t.value) ? {fieldRef:t.fieldRef,operator:t.operator,value:t.value} : null
}

function valueText(t:Term): string {
  if(Array.isArray(t.value)) return t.value.map(String).join(', ')
  if(typeof t.value === 'object') return `${t.value.includeFrom === false ? 'above' : 'from'} ${t.value.from} ${t.value.includeTo === false ? 'to below' : 'to'} ${t.value.to}`
  return String(t.value)
}

const termText = (t:Term) => `${label(t.fieldRef)} ${operators[t.operator]} ${valueText(t)}`

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
    if(!row || !condition || typeof row.rowId !== 'string' || typeof row.matched !== 'boolean' || typeof row.selected !== 'boolean') return null
    // A row is one comparison, or All (AND) / Any (OR) of 2 to 8 comparisons (docs/build/S121).
    const group = Array.isArray(condition.all) ? 'all' : Array.isArray(condition.any) ? 'any' : null
    const list = group ? condition[group] as unknown[] : [condition]
    if(group && (list.length < 2 || list.length > 8)) return null
    const terms = list.map(term)
    if(terms.some(t => t === null)) return null
    rows.push({rowId:row.rowId,matched:row.matched,selected:row.selected,group,terms:terms as Term[]})
  }
  if (!rows.length || !inputs.length) return null
  return <details className="mt-2 text-sm"><summary className="cursor-pointer font-medium">Decision evidence</summary>
    <p className="mt-2 text-xs text-muted-foreground">Inputs and candidate rules from this request's pinned release. Selection is confirmed against the executed decision.{typeof evidence.hitPolicy === 'string' && policies[evidence.hitPolicy] ? ` ${policies[evidence.hitPolicy]}` : ''}{evidence.hitPolicy === 'COLLECT' && typeof evidence.aggregation === 'string' && aggregations[evidence.aggregation] ? ` Combined as the ${aggregations[evidence.aggregation]}.` : ''}</p>
    <dl className="my-2">{inputs.map(input => <div key={input.fieldRef}><dt className="inline font-medium">{label(input.fieldRef)}: </dt><dd className="inline whitespace-pre-wrap wrap-anywhere">{String(input.value)}</dd></div>)}</dl>
    <div className="overflow-x-auto"><Table><caption className="sr-only">Evaluated candidate rules</caption><THead><TR><TH>Rule</TH><TH>Condition</TH><TH>Matched</TH><TH>Selected</TH></TR></THead><TBody>
      {rows.map(row => <TR key={row.rowId}><TD>{row.rowId}</TD><TD className="wrap-anywhere">{row.terms.map(termText).join(row.group === 'any' ? ' or ' : ' and ')}</TD><TD>{row.matched?'Yes':'No'}</TD><TD>{row.selected?'Yes':'No'}</TD></TR>)}
    </TBody></Table></div><p className="mt-2">Default rule {evidence.defaultSelected?'selected':'not selected'}.</p>
  </details>
}
