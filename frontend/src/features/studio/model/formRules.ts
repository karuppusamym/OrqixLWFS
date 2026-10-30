import { conditionTerms, isAtomicCondition, type AtomicCondition, type Condition, type Definition, type Literal } from './types'

/** Plain-language rules of one request question (docs/build/S120): what the Form tab shows without opening an editor. */
export interface QuestionRules {
  /** "Shown only when Needs review is Yes" (own rule and group rule joined). */
  shown?: string
  /** "Required when Amount (USD) is at least 5000". */
  required?: string
  /** Title of the form group the question belongs to. */
  group?: string
}

/**
 * Reads as a sentence after the question's label: "Amount (USD) is at least 5000". Form rules use the first six;
 * "in" and "between" belong to decision rows (DMN parity, S121) and are listed so a shared operator type always reads.
 */
const OPERATOR_PHRASE: Record<string, string> = { eq: 'is', ne: 'is not', gte: 'is at least', gt: 'is more than', lt: 'is less than', lte: 'is at most', in: 'is one of', between: 'is between' }

function literalText(value: Literal): string {
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return typeof value === 'string' ? `"${value}"` : String(value)
}

/** A single value, a list ("EU", "UK") or a range ("1000 and 5000"). */
function valueText(value: unknown): string {
  if (Array.isArray(value)) return value.map((v) => literalText(v as Literal)).join(', ')
  if (value !== null && typeof value === 'object' && 'from' in value && 'to' in value) {
    const range = value as { from: Literal; to: Literal }
    return `${literalText(range.from)} and ${literalText(range.to)}`
  }
  return literalText(value as Literal)
}

function termText(def: Definition, term: AtomicCondition): string {
  const key = term.fieldRef.startsWith('/input/') ? term.fieldRef.slice('/input/'.length) : ''
  const label = (key && def.inputSchema.properties[key]?.title) || key || 'a removed question'
  return `${label} ${OPERATOR_PHRASE[term.operator] ?? term.operator} ${valueText(term.value)}`
}

/** "Amount (USD) is at least 5000", "Type is "Laptop" and Urgent is Yes". */
export function describeFormCondition(def: Definition, condition: Condition): string {
  if (isAtomicCondition(condition)) return termText(def, condition)
  return conditionTerms(condition).map((term) => termText(def, term)).join('all' in condition ? ' and ' : ' or ')
}

/** Rules for every question, keyed by field key; questions without rules or group are absent. */
export function questionRules(def: Definition): Record<string, QuestionRules> {
  const result: Record<string, QuestionRules> = {}
  const ui = def.inputUi
  for (const key of Object.keys(def.inputSchema.properties)) {
    const own = ui?.visibleWhen?.[key]
    const required = ui?.requiredWhen?.[key]
    const section = ui?.layout?.sections?.find((s) => s.fields.includes(key))
    const shownParts = [own ? describeFormCondition(def, own) : null, section?.visibleWhen ? describeFormCondition(def, section.visibleWhen) : null].filter((p): p is string => !!p)
    const rules: QuestionRules = {}
    if (shownParts.length) rules.shown = `Shown only when ${shownParts.join(', and when ')}`
    if (required) rules.required = `Required when ${describeFormCondition(def, required)}`
    if (section) rules.group = section.title
    if (rules.shown || rules.required || rules.group) result[key] = rules
  }
  return result
}
