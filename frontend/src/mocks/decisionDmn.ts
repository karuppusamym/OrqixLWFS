import type { DecisionAssetContent } from '@/api/types'
import {
  decisionTerms, isExpressionTerm, isOutputExpression, isRangeValue, type AtomicCondition, type DecisionRow, type DecisionTable, type DecisionTerm, type Literal,
  type OutputEntry,
} from '@/features/studio/model/types'

/**
 * Mock double of standard DMN download and upload (docs/build/S121). Export writes a readable DMN 1.3 file in the
 * server's style and embeds the table so that the mock can read its own files back; import reads only such files
 * (anything else is refused with a problem, as the strict server reader would for unsupported content).
 *
 * Formulas (S146): an expression entry is written with expressionLanguage EXPRESSION_LANGUAGE and the Orqix expression
 * verbatim; a column used only by formulas has no typeRef. An Any row with a formula is one rule per alternative (so
 * UNIQUE is written as ANY); under COLLECT such a row is refused (dmnRefusals).
 */
const MARK = 'orqix-mock-table:'
export const EXPRESSION_LANGUAGE = 'https://orqix.example/expression'

const literal = (v: Literal) => (typeof v === 'string' ? `"${v}"` : String(v))
const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function test(t: AtomicCondition): string {
  const v = t.value
  switch (t.operator) {
    case 'eq': return literal(v as Literal)
    case 'ne': return `not(${literal(v as Literal)})`
    case 'gte': return `>= ${String(v)}`
    case 'gt': return `> ${String(v)}`
    case 'lt': return `< ${String(v)}`
    case 'lte': return `<= ${String(v)}`
    case 'in': return (Array.isArray(v) ? v : []).map(literal).join(',')
    case 'between': return isRangeValue(v) ? `${v.includeFrom === false ? '(' : '['}${v.from}..${v.to}${v.includeTo === false ? ')' : ']'}` : '-'
  }
}

const hasFormula = (r: DecisionRow) => decisionTerms(r.when).some(isExpressionTerm)
const formulaAny = (r: DecisionRow) => 'any' in r.when && !!r.when.any && hasFormula(r)

/** Why the table cannot be written as DMN (empty when it can). */
export function dmnRefusals(table: Pick<DecisionTable, 'hitPolicy' | 'rows'>): string[] {
  if (table.hitPolicy !== 'COLLECT') return []
  return table.rows.flatMap((r, i) => (formulaAny(r) ? [`Row ${i + 1} is an Any row with a formula, which DMN cannot hold under COLLECT without counting it twice. Split it into exclusive rows.`] : []))
}

const feelName = (ref: string) => (/^\/input\/[^/]+$/.test(ref) ? ref.slice(7) : ref.slice(1).split('/').join('.'))

export function exportDmn(table: Pick<DecisionTable, 'hitPolicy' | 'rows' | 'default' | 'aggregation' | 'priority'>, key: string, name?: string): string {
  const fields = [...new Set(table.rows.flatMap((r) => decisionTerms(r.when).map((t) => t.fieldRef)))].sort()
  const compared = new Set(table.rows.flatMap((r) => decisionTerms(r.when).filter((t) => !isExpressionTerm(t)).map((t) => t.fieldRef)))
  // An Any row with a formula is one rule per alternative, which may overlap: UNIQUE is then written as ANY.
  const rules: Array<{ id: string; terms: DecisionTerm[]; then: Record<string, OutputEntry> }> = table.rows.flatMap((r, i) => formulaAny(r)
    ? decisionTerms(r.when).map((t, k) => ({ id: `r${i + 1}_${k + 1}`, terms: [t], then: r.then }))
    : [{ id: `r${i + 1}`, terms: decisionTerms(r.when), then: r.then }])
  const hitPolicy = table.hitPolicy === 'UNIQUE' && table.rows.some(formulaAny) ? 'ANY' : table.hitPolicy
  const entry = (tag: string, text: string, formula: boolean) => `<${tag}${formula ? ` expressionLanguage="${EXPRESSION_LANGUAGE}"` : ''}><text>${escape(text)}</text></${tag}>`
  const outputs = Object.keys(table.default)
  const payload = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify({ hitPolicy: table.hitPolicy, aggregation: table.aggregation, priority: table.priority, rows: table.rows, default: table.default }))))
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<!--${MARK}${payload}-->`,
    `<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions_${key}" name="${escape(name ?? key)}" namespace="https://orqix.example/decisions" exporter="Orqix" exporterVersion="S121">`,
    `  <decision id="${key}" name="${escape(name ?? key)}">`,
    `    <decisionTable id="${key}_table" hitPolicy="${hitPolicy}"${table.hitPolicy === 'COLLECT' && table.aggregation ? ` aggregation="${table.aggregation}"` : ''}>`,
    ...fields.map((f, i) => `      <input id="${key}_in${i + 1}" label="${feelName(f)}"><inputExpression${compared.has(f) ? ' typeRef="string"' : ''}><text>${feelName(f)}</text></inputExpression></input>`),
    ...outputs.map((o) => `      <output id="${key}_out_${o}" name="${o}"><defaultOutputEntry><text>${escape(literal(table.default[o] as Literal))}</text></defaultOutputEntry></output>`),
    ...rules.map((r) => {
      const byField = new Map(r.terms.map((t) => [t.fieldRef, t]))
      const input = (t: DecisionTerm | undefined) => (!t ? entry('inputEntry', '-', false) : isExpressionTerm(t) ? entry('inputEntry', t.expression, true) : entry('inputEntry', test(t), false))
      const output = (v: OutputEntry | undefined) => (isOutputExpression(v) ? entry('outputEntry', v.expression, true) : entry('outputEntry', literal(v as Literal), false))
      return `      <rule id="${key}_${r.id}">${fields.map((f) => input(byField.get(f))).join('')}${outputs.map((o) => output(r.then[o])).join('')}</rule>`
    }),
    '    </decisionTable>',
    '  </decision>',
    '</definitions>',
  ]
  return lines.join('\n') + '\n'
}

export interface DmnRead {
  decisions: Array<{ id: string; name: string; table: boolean }>
  decisionId: string | null
  name: string | null
  content: DecisionAssetContent | null
  problems: string[]
}

export function readDmn(xml: string, decisionId?: string | null): DmnRead {
  const decisions = [...xml.matchAll(/<decision\s+id="([^"]+)"(?:\s+name="([^"]*)")?/g)].map((m) => ({ id: m[1] as string, name: m[2] ?? (m[1] as string), table: true }))
  if (!/<definitions[^>]+xmlns="https?:\/\/www\.omg\.org\/spec\/DMN\//.test(xml)) return { decisions: [], decisionId: null, name: null, content: null, problems: ['file: This is not a DMN file. The root element must be definitions in a DMN namespace.'] }
  if (decisions.length === 0) return { decisions, decisionId: null, name: null, content: null, problems: ['file: The file has no decision.'] }
  if (decisions.length > 1 && !decisionId) return { decisions, decisionId: null, name: null, content: null, problems: [] }
  const chosen = decisions.find((d) => d.id === (decisionId ?? decisions[0]?.id))
  if (!chosen) return { decisions, decisionId: null, name: null, content: null, problems: [`file: The file has no decision with the id "${decisionId}".`] }
  const mark = xml.indexOf(MARK)
  if (mark < 0) return { decisions, decisionId: null, name: null, content: null, problems: [`decision ${chosen.id}: The mock reads back only DMN files it exported; the real server reads standard DMN.`] }
  try {
    const payload = xml.slice(mark + MARK.length, xml.indexOf('-->', mark))
    const parsed = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(payload), (c) => c.charCodeAt(0)))) as DecisionAssetContent
    const content = Object.fromEntries(Object.entries(parsed).filter(([, v]) => v !== undefined && v !== null)) as DecisionAssetContent
    return { decisions, decisionId: chosen.id, name: chosen.name, content, problems: [] }
  } catch {
    return { decisions, decisionId: null, name: null, content: null, problems: [`decision ${chosen.id}: The file cannot be read.`] }
  }
}

