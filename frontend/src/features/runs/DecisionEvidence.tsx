import type { RunEvent } from '@/api/types'
import { humanize } from '@/lib/text'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'

type Scalar = string | number | boolean
type Input = {fieldRef:string;value:Scalar}
type Range = {from:number;to:number;includeFrom?:boolean;includeTo?:boolean}
type Comparison = {fieldRef:string;operator:string;value:Scalar|Scalar[]|Range}
/** A formula test (S146) and what it gave: true, false or null (no value, which does not match). */
type Formula = {fieldRef:string;expression:string;result?:boolean|null}
type Term = Comparison | Formula
type OutputFormula = {output:string;expression:string;value:Scalar|null}
type Row = {rowId:string;matched:boolean;selected:boolean;group:'all'|'any'|null;terms:Term[];outputs:OutputFormula[]}
const MAX_EXPRESSION = 2000
const expressionText = (value:unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= MAX_EXPRESSION
const isFormula = (t:Term): t is Formula => 'expression' in t
const record = (value:unknown): Record<string,unknown> | null => typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string,unknown> : null
const scalar = (value:unknown): value is Scalar => typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value)
const label = (ref:string) => humanize(ref.split('/').at(-1) ?? ref)
const operators:Record<string,string> = {eq:'equals',ne:'does not equal',gt:'is greater than',gte:'is at least',lt:'is less than',lte:'is at most',in:'is one of',between:'is between'}
const policies:Record<string,string> = {UNIQUE:'Exactly one rule may apply.',FIRST:'The first matching rule applies.',ANY:'Any matching rule applies; overlapping rules give the same result.',PRIORITY:'The matching rule with the highest priority applies.',COLLECT:'Every matching rule counts; their results are combined.'}
const aggregations:Record<string,string> = {SUM:'sum',MIN:'smallest value',MAX:'largest value',COUNT:'number of matching rules'}

/**
 * One comparison, checked strictly: a value, a list of 1 to 20 values for "is one of", a range for "is between"; or a
 * formula test of the column (S146): exactly a field and an expression.
 */
function term(raw:unknown): Term | null {
  const t=record(raw)
  if(t && typeof t.fieldRef === 'string' && 'expression' in t) return expressionText(t.expression) && Object.keys(t).length === 2 ? {fieldRef:t.fieldRef,expression:t.expression} : null
  if(!t || typeof t.fieldRef !== 'string' || typeof t.operator !== 'string' || !operators[t.operator]) return null
  if(t.operator === 'in') return Array.isArray(t.value) && t.value.length >= 1 && t.value.length <= 20 && t.value.every(scalar) ? {fieldRef:t.fieldRef,operator:'in',value:t.value} : null
  if(t.operator === 'between') {
    const r=record(t.value)
    return r && typeof r.from === 'number' && typeof r.to === 'number' ? {fieldRef:t.fieldRef,operator:'between',value:{from:r.from,to:r.to,includeFrom:r.includeFrom !== false,includeTo:r.includeTo !== false}} : null
  }
  return scalar(t.value) ? {fieldRef:t.fieldRef,operator:t.operator,value:t.value} : null
}

/** Reads the evaluated formula tests of a row (term index, column, expression, true/false/null); false when malformed. */
function formulaResults(raw:unknown, terms:Term[]): boolean {
  if(raw === undefined) return true
  if(!Array.isArray(raw) || raw.length > 8) return false
  for(const item of raw) {
    const e=record(item)
    if(!e || typeof e.term !== 'number' || !Number.isInteger(e.term) || typeof e.fieldRef !== 'string' || !expressionText(e.expression) || !(e.result === null || typeof e.result === 'boolean')) return false
    const t=terms[e.term]
    if(!t || !isFormula(t) || t.fieldRef !== e.fieldRef || t.expression !== e.expression) return false
    t.result=e.result
  }
  return true
}

/** The computed results of a selected row (S146): result name, expression and value. */
function outputFormulas(raw:unknown): OutputFormula[] | null {
  if(raw === undefined) return []
  if(!Array.isArray(raw) || raw.length > 50) return null
  const out:OutputFormula[]=[]
  for(const item of raw) {
    const e=record(item)
    if(!e || typeof e.output !== 'string' || !expressionText(e.expression) || !(e.value === null || scalar(e.value))) return null
    out.push({output:e.output,expression:e.expression,value:e.value as Scalar|null})
  }
  return out
}

const resultText = (v:boolean|null|undefined) => v === true ? 'true' : v === false ? 'false' : v === null ? 'no value' : 'not evaluated'

function valueText(t:Comparison): string {
  if(Array.isArray(t.value)) return t.value.map(String).join(', ')
  if(typeof t.value === 'object') return `${t.value.includeFrom === false ? 'above' : 'from'} ${t.value.from} ${t.value.includeTo === false ? 'to below' : 'to'} ${t.value.to}`
  return String(t.value)
}

const termText = (t:Term) => isFormula(t) ? `${label(t.fieldRef)}: formula ${t.expression} gave ${resultText(t.result)}` : `${label(t.fieldRef)} ${operators[t.operator]} ${valueText(t)}`
const outputText = (o:OutputFormula) => `${humanize(o.output)}: formula ${o.expression} gave ${o.value === null ? 'no value' : String(o.value)}`

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
    if(!formulaResults(row.expressions, terms as Term[])) return null
    const outputs = outputFormulas(row.outputExpressions)
    if(!outputs) return null
    rows.push({rowId:row.rowId,matched:row.matched,selected:row.selected,group,terms:terms as Term[],outputs})
  }
  // A table with formulas may read no field with a value (fields without a value are left out of the inputs).
  const formulas = rows.some(row => row.outputs.length > 0 || row.terms.some(isFormula))
  if (!rows.length || (!inputs.length && !formulas)) return null
  return <details className="mt-2 text-sm"><summary className="cursor-pointer font-medium">Decision evidence</summary>
    <p className="mt-2 text-xs text-muted-foreground">Inputs and candidate rules from this request's pinned release. Selection is confirmed against the executed decision.{typeof evidence.hitPolicy === 'string' && policies[evidence.hitPolicy] ? ` ${policies[evidence.hitPolicy]}` : ''}{evidence.hitPolicy === 'COLLECT' && typeof evidence.aggregation === 'string' && aggregations[evidence.aggregation] ? ` Combined as the ${aggregations[evidence.aggregation]}.` : ''}</p>
    <dl className="my-2">{inputs.map(input => <div key={input.fieldRef}><dt className="inline font-medium">{label(input.fieldRef)}: </dt><dd className="inline whitespace-pre-wrap wrap-anywhere">{String(input.value)}</dd></div>)}</dl>
    <div className="overflow-x-auto"><Table><caption className="sr-only">Evaluated candidate rules</caption><THead><TR><TH>Rule</TH><TH>Condition</TH><TH>Matched</TH><TH>Selected</TH></TR></THead><TBody>
      {rows.map(row => <TR key={row.rowId}><TD>{row.rowId}</TD><TD className="wrap-anywhere">{row.terms.map(termText).join(row.group === 'any' ? ' or ' : ' and ')}{row.outputs.length ? <ul aria-label={`Computed results of ${row.rowId}`} className="mt-1 text-xs text-muted-foreground">{row.outputs.map(o => <li key={o.output} className="font-mono">{outputText(o)}</li>)}</ul> : null}</TD><TD>{row.matched?'Yes':'No'}</TD><TD>{row.selected?'Yes':'No'}</TD></TR>)}
    </TBody></Table></div><p className="mt-2">Default rule {evidence.defaultSelected?'selected':'not selected'}.</p>
  </details>
}
