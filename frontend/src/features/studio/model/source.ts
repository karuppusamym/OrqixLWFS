import { dump, JSON_SCHEMA, load } from 'js-yaml'
import { normalizeDefinition, type Definition } from './types'

/**
 * Source mode (UI03, docs/build/S53): the canonical workflow document as YAML, with checks that run before any text
 * reaches the draft. The text is parsed with the JSON-compatible YAML schema (no dates, binary or custom tags),
 * anchors, aliases and merge keys are refused, and the result must serialize back to the same document.
 */
export const MAX_SOURCE_CHARS = 400_000
const MAX_DEPTH = 40
const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

export interface SourceProblem {
  message: string
  /** 1-based line when the parser knows it. */
  line?: number
}

export type ParsedSource = { ok: true; definition: Definition } | { ok: false; problems: SourceProblem[] }

export function definitionToYaml(def: Definition): string {
  return dump(def, { schema: JSON_SCHEMA, noRefs: true, lineWidth: -1, noCompatMode: true, indent: 2 })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Stable text of a JSON value with object keys sorted, so two documents compare by meaning, not key order. */
export function canonicalText(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalText).join(',')}]`
  if (isRecord(value)) {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonicalText(value[k])}`).join(',')}}`
  }
  return JSON.stringify(value) ?? 'undefined'
}

function structuralProblems(value: unknown, path: string, depth: number, out: SourceProblem[]): void {
  if (out.length >= 5) return
  if (depth > MAX_DEPTH) {
    out.push({ message: `The document is nested deeper than ${MAX_DEPTH} levels at ${path || '/'}.` })
    return
  }
  if (typeof value === 'number' && !Number.isFinite(value)) out.push({ message: `${path || '/'} is not a finite number.` })
  else if (value === undefined) out.push({ message: `${path || '/'} has no value.` })
  else if (Array.isArray(value)) value.forEach((item, i) => structuralProblems(item, `${path}/${i}`, depth + 1, out))
  else if (isRecord(value)) {
    for (const key of Object.keys(value)) {
      if (FORBIDDEN_KEYS.has(key)) out.push({ message: `The key "${key}" is not allowed (${path || '/'}).` })
      else structuralProblems(value[key], `${path}/${key}`, depth + 1, out)
    }
  }
}

/** Parse YAML text into a normalized definition, or explain what is wrong. Never throws. */
export function parseSource(text: string): ParsedSource {
  if (text.length > MAX_SOURCE_CHARS) return { ok: false, problems: [{ message: `The source is larger than ${MAX_SOURCE_CHARS.toLocaleString('en-US')} characters.` }] }
  if (text.trim() === '') return { ok: false, problems: [{ message: 'The source is empty.' }] }
  // Anchors (&a), aliases (*a) and merge keys (<<) can expand a small text into a huge document; the canonical document never needs them.
  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const withoutComment = lines[i]!.replace(/(^|\s)#.*$/, '')
    if (/(^|[\s[{,:-])[&*][^\s,\]}]+/.test(withoutComment) || /^\s*<<\s*:/.test(withoutComment)) {
      return { ok: false, problems: [{ message: 'Anchors, aliases and merge keys are not supported. Write the values out in full.', line: i + 1 }] }
    }
  }
  let raw: unknown
  try {
    raw = load(text, { schema: JSON_SCHEMA })
  } catch (cause) {
    const mark = (cause as { mark?: { line?: number } }).mark
    const reason = (cause as { reason?: string }).reason ?? (cause instanceof Error ? cause.message.split('\n')[0]! : 'The text is not valid YAML.')
    return { ok: false, problems: [{ message: reason, line: typeof mark?.line === 'number' ? mark.line + 1 : undefined }] }
  }
  if (!isRecord(raw)) return { ok: false, problems: [{ message: 'The document must be a mapping with the workflow fields (schemaVersion, workflowKey, nodes, ...).' }] }
  const problems: SourceProblem[] = []
  structuralProblems(raw, '', 0, problems)
  if (raw.schemaVersion !== '0.1') problems.push({ message: 'schemaVersion must be "0.1" (write it in quotes).' })
  if (typeof raw.workflowKey !== 'string') problems.push({ message: 'workflowKey must be text.' })
  for (const list of ['nodes', 'transitions', 'decisions'] as const) {
    if (raw[list] !== undefined && !Array.isArray(raw[list])) problems.push({ message: `${list} must be a list.` })
  }
  if (Array.isArray(raw.nodes)) {
    const ids = new Set<string>()
    for (const [i, n] of raw.nodes.entries()) {
      if (!isRecord(n) || typeof n.id !== 'string' || typeof n.type !== 'string' || typeof n.label !== 'string') problems.push({ message: `nodes[${i}] needs text id, type and label.` })
      else if (ids.has(n.id)) problems.push({ message: `Step id "${n.id}" is used twice.` })
      else ids.add(n.id)
    }
  }
  if (Array.isArray(raw.transitions)) {
    const ids = new Set<string>()
    for (const [i, t] of raw.transitions.entries()) {
      if (!isRecord(t) || typeof t.id !== 'string' || typeof t.from !== 'string' || typeof t.to !== 'string' || typeof t.on !== 'string') problems.push({ message: `transitions[${i}] needs text id, from, to and on.` })
      else if (ids.has(t.id)) problems.push({ message: `Connection id "${t.id}" is used twice.` })
      else ids.add(t.id)
    }
  }
  if (problems.length > 0) return { ok: false, problems: problems.slice(0, 5) }
  return { ok: true, definition: normalizeDefinition(raw) }
}

export interface RoundTrip {
  stable: boolean
  /** Why it is not stable (empty when stable). */
  detail: string
}

/** The definition survives YAML -> text -> YAML unchanged (same meaning, key order ignored). */
export function roundTrip(def: Definition): RoundTrip {
  const parsed = parseSource(definitionToYaml(def))
  if (!parsed.ok) return { stable: false, detail: parsed.problems[0]?.message ?? 'The serialized text could not be read back.' }
  if (canonicalText(parsed.definition) !== canonicalText(normalizeDefinition(def))) return { stable: false, detail: 'Reading the serialized text back produced a different document.' }
  return { stable: true, detail: '' }
}

export interface SourceChange {
  kind: 'added' | 'removed' | 'changed'
  what: string
}

function byId<T extends { id: string }>(items: T[]): Map<string, T> {
  return new Map(items.map((item) => [item.id, item]))
}

/** Plain-language list of what applying `next` would change compared with `current` (bounded to 30 lines). */
export function describeChanges(current: Definition, next: Definition): SourceChange[] {
  const out: SourceChange[] = []
  const compare = (label: string, a: Array<{ id: string; label?: string }>, b: Array<{ id: string; label?: string }>) => {
    const before = byId(a)
    const after = byId(b)
    for (const [id, item] of after) {
      const name = item.label ? `${label} "${item.label}" (${id})` : `${label} ${id}`
      const old = before.get(id)
      if (!old) out.push({ kind: 'added', what: name })
      else if (canonicalText(old) !== canonicalText(item)) out.push({ kind: 'changed', what: name })
    }
    for (const [id, item] of before) if (!after.has(id)) out.push({ kind: 'removed', what: item.label ? `${label} "${item.label}" (${id})` : `${label} ${id}` })
  }
  compare('Step', current.nodes, next.nodes)
  compare('Connection', current.transitions, next.transitions)
  const rest = (d: Definition) => {
    const { nodes: _n, transitions: _t, uiLayout: _u, ...others } = d
    void _n
    void _t
    void _u
    return others as Record<string, unknown>
  }
  const a = rest(current)
  const b = rest(next)
  for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
    if (canonicalText(a[key]) !== canonicalText(b[key])) out.push({ kind: 'changed', what: `Section ${key}` })
  }
  if (canonicalText(current.uiLayout) !== canonicalText(next.uiLayout)) out.push({ kind: 'changed', what: 'Canvas layout' })
  return out.slice(0, 30)
}

export type SourceCheck =
  | { ok: false; problems: SourceProblem[] }
  | { ok: true; definition: Definition; changes: SourceChange[]; roundTrip: RoundTrip }

/**
 * Everything the Source tab verifies before it offers Apply: the text parses, the workflow identity is unchanged,
 * and the parsed document is stable through another serialize/parse cycle.
 */
export function checkSource(text: string, current: Definition): SourceCheck {
  const parsed = parseSource(text)
  if (!parsed.ok) return parsed
  if (parsed.definition.workflowKey !== current.workflowKey) {
    return { ok: false, problems: [{ message: `workflowKey cannot be changed here (it is "${current.workflowKey}").` }] }
  }
  const trip = roundTrip(parsed.definition)
  return { ok: true, definition: parsed.definition, changes: describeChanges(current, parsed.definition), roundTrip: trip }
}
