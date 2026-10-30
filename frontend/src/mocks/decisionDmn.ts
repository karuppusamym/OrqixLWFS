import type { DecisionAssetContent } from '@/api/types'
import { conditionTerms, isRangeValue, type AtomicCondition, type DecisionTable, type Literal } from '@/features/studio/model/types'

/**
 * Mock double of standard DMN download and upload (docs/build/S121). Export writes a readable DMN 1.3 file in the
 * server's style and embeds the table so that the mock can read its own files back; import reads only such files
 * (anything else is refused with a problem, as the strict server reader would for unsupported content).
 */
const MARK = 'orqix-mock-table:'

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

const feelName = (ref: string) => (/^\/input\/[^/]+$/.test(ref) ? ref.slice(7) : ref.slice(1).split('/').join('.'))

export function exportDmn(table: Pick<DecisionTable, 'hitPolicy' | 'rows' | 'default' | 'aggregation' | 'priority'>, key: string, name?: string): string {
  const fields = [...new Set(table.rows.flatMap((r) => conditionTerms(r.when).map((t) => t.fieldRef)))].sort()
  const outputs = Object.keys(table.default)
  const payload = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify({ hitPolicy: table.hitPolicy, aggregation: table.aggregation, priority: table.priority, rows: table.rows, default: table.default }))))
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<!--${MARK}${payload}-->`,
    `<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions_${key}" name="${escape(name ?? key)}" namespace="https://orqix.example/decisions" exporter="Orqix" exporterVersion="S121">`,
    `  <decision id="${key}" name="${escape(name ?? key)}">`,
    `    <decisionTable id="${key}_table" hitPolicy="${table.hitPolicy}"${table.hitPolicy === 'COLLECT' && table.aggregation ? ` aggregation="${table.aggregation}"` : ''}>`,
    ...fields.map((f, i) => `      <input id="${key}_in${i + 1}" label="${feelName(f)}"><inputExpression typeRef="string"><text>${feelName(f)}</text></inputExpression></input>`),
    ...outputs.map((o) => `      <output id="${key}_out_${o}" name="${o}"><defaultOutputEntry><text>${escape(literal(table.default[o] as Literal))}</text></defaultOutputEntry></output>`),
    ...table.rows.map((r, i) => {
      const byField = new Map(conditionTerms(r.when).map((t) => [t.fieldRef, test(t)]))
      return `      <rule id="${key}_r${i + 1}">${fields.map((f) => `<inputEntry><text>${escape(byField.get(f) ?? '-')}</text></inputEntry>`).join('')}${outputs.map((o) => `<outputEntry><text>${escape(literal(r.then[o] as Literal))}</text></outputEntry>`).join('')}</rule>`
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

