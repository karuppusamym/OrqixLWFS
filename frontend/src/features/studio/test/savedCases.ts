import { readStorage, writeStorage } from '@/lib/storage'
import type { RawValues } from '@/lib/form/values'
import { emptyAssertions, emptySettings, type CaseAssertions, type TestSettings } from './scenarioModel'

/**
 * Version of a saved case. 2: answers, step settings and the expected outcome (S7). 3 (STU03): mocks for integration
 * points and assertions. Older cases are migrated when read (no assertions, mocks empty), so they keep working.
 */
export const CASE_VERSION = 3

/** A named test case: the answers typed into the sample form, what should happen at each step, what is expected. */
export interface SavedCase {
  version: number
  id: string
  name: string
  values: RawValues
  settings: TestSettings
  /** A business outcome, {@link NEEDS_ATTENTION}, or null for "no expectation". */
  expect: string | null
  /** What else the case checks (STU03). */
  assertions: CaseAssertions
}

export const MAX_CASES = 10
export const MAX_CASE_BYTES = 64 * 1024
export const MAX_IMPORT_BYTES = 1024 * 1024
const FORMAT = 'orqix-test-cases'

const storageKey = (ws: string, workflowId: string, subjectId: string) => `orqix.testCases.v2.${subjectId}.${ws}.${workflowId}`

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** Accepts only well-formed cases; anything else in storage or in an imported file is dropped. */
export function parseCase(raw: unknown): SavedCase | null {
  try { if (new TextEncoder().encode(JSON.stringify(raw)).length > MAX_CASE_BYTES) return null } catch { return null }
  if (!isRecord(raw) || typeof raw.name !== 'string' || raw.name.trim() === '' || !isRecord(raw.values) || Object.keys(raw.values).length > 100) return null
  if (Object.values(raw.values).some(v => Array.isArray(v) && v.length > 25)) return null
  const values: RawValues = {}
  for (const [k, v] of Object.entries(raw.values)) {
    if (typeof v === 'string' || typeof v === 'boolean') values[k] = v
    else if (Array.isArray(v) && v.length <= 25 && v.every(x => typeof x === 'string' || typeof x === 'boolean' || (isRecord(x) && Object.keys(x).length <= 10 && Object.values(x).every(cell => typeof cell === 'string' || typeof cell === 'boolean')))) values[k] = v as RawValues[string]
  }
  const s = isRecord(raw.settings) ? raw.settings : {}
  if (isRecord(s.children) && Object.values(s.children).some(v => isRecord(v) && Array.isArray(v.outcomes) && v.outcomes.length > 25)) return null
  const settings: TestSettings = { ...emptySettings() }
  if (isRecord(s.approvals)) for (const [k, v] of Object.entries(s.approvals)) if (isRecord(v) && (v.outcome === 'approve' || v.outcome === 'reject' || v.outcome === 'timeout')) settings.approvals[k] = { outcome: v.outcome, reason: typeof v.reason === 'string' ? v.reason : '' }
  if (isRecord(s.calls))
    for (const [k, v] of Object.entries(s.calls))
      if (isRecord(v) && (v.result === 'works' || v.result === 'rejected' || v.result === 'unavailable' || v.result === 'unknownOutcome' || v.result === 'timeout')) {
        const mock = isRecord(v.mock) ? { status: text(v.mock.status, 3), body: text(v.mock.body, 32 * 1024), delaySeconds: text(v.mock.delaySeconds, 7) } : undefined
        settings.calls[k] = { result: v.result, answers: strings(v.answers), ...(mock ? { mock } : {}) }
      }
  if (isRecord(s.children)) for (const [k, v] of Object.entries(s.children)) if (isRecord(v) && Array.isArray(v.outcomes)) settings.children[k] = { outcomes: v.outcomes.filter((o): o is string => typeof o === 'string') }
  if (isRecord(s.messages)) for (const [k, v] of Object.entries(s.messages)) if (v === 'arrives' || v === 'timeout') settings.messages[k] = v
  if (isRecord(s.messageMocks)) for (const [k, v] of Object.entries(s.messageMocks)) if (isRecord(v)) settings.messageMocks[k] = { answers: strings(v.answers), delaySeconds: text(v.delaySeconds, 7) }
  if (isRecord(s.workers)) for (const [k, v] of Object.entries(s.workers)) if (isRecord(v) && (v.result === 'works' || v.result === 'failed')) settings.workers[k] = { result: v.result, answers: strings(v.answers), delaySeconds: text(v.delaySeconds, 7) }
  if (isRecord(s.adapters)) for (const [k, v] of Object.entries(s.adapters)) if (isRecord(v) && (v.result === 'works' || v.result === 'unavailable' || v.result === 'unknownOutcome')) settings.adapters[k] = { result: v.result, rowCount: text(v.rowCount, 5) }
  settings.simulateChildren = s.simulateChildren === true
  return {
    version: CASE_VERSION,
    id: typeof raw.id === 'string' && raw.id !== '' ? raw.id : `case-${Math.random().toString(36).slice(2, 10)}`,
    name: raw.name.trim().slice(0, 80),
    values,
    settings,
    expect: typeof raw.expect === 'string' ? raw.expect : null,
    // Version 2 cases (before STU03) have no assertions: they keep checking only the outcome.
    assertions: parseAssertions(raw.assertions),
  }
}

const text = (v: unknown, max: number): string => (typeof v === 'string' ? v.slice(0, max) : '')

function strings(v: unknown): Record<string, string> {
  const out: Record<string, string> = {}
  if (isRecord(v)) for (const [a, t] of Object.entries(v).slice(0, 50)) if (typeof t === 'string') out[a] = t.slice(0, 1000)
  return out
}

function parseAssertions(raw: unknown): CaseAssertions {
  const a = emptyAssertions()
  if (!isRecord(raw)) return a
  if (Array.isArray(raw.outputs))
    for (const o of raw.outputs.slice(0, 20))
      if (isRecord(o) && typeof o.field === 'string' && (o.op === 'equals' || o.op === 'contains' || o.op === 'range')) a.outputs.push({ field: o.field.slice(0, 200), op: o.op, value: text(o.value, 200), min: text(o.min, 30), max: text(o.max, 30) })
  if (isRecord(raw.path) && Array.isArray(raw.path.steps)) {
    const steps = raw.path.steps.filter((x): x is string => typeof x === 'string').slice(0, 100)
    if (steps.length > 0) a.path = { steps, match: raw.path.match === 'inOrder' ? 'inOrder' : 'exact' }
  }
  if (Array.isArray(raw.rules)) for (const r of raw.rules.slice(0, 20)) if (isRecord(r) && typeof r.stepId === 'string' && typeof r.rule === 'string') a.rules.push({ stepId: r.stepId, rule: r.rule })
  if (Array.isArray(raw.approvals))
    for (const r of raw.approvals.slice(0, 20)) if (isRecord(r) && typeof r.stepId === 'string' && (r.decision === 'approved' || r.decision === 'rejected' || r.decision === 'timedOut')) a.approvals.push({ stepId: r.stepId, decision: r.decision })
  return a
}

export function loadCases(ws: string, workflowId: string, subjectId: string): SavedCase[] {
  const text = readStorage(storageKey(ws, workflowId, subjectId))
  if (!text || new TextEncoder().encode(text).length > MAX_CASE_BYTES * MAX_CASES) return []
  try {
    const parsed: unknown = JSON.parse(text)
    if (!Array.isArray(parsed)) return []
    return parsed.slice(0, MAX_CASES).map(parseCase).filter((c): c is SavedCase => c !== null).slice(0, MAX_CASES)
  } catch {
    return []
  }
}

export function storeCases(ws: string, workflowId: string, cases: SavedCase[], subjectId: string): void {
  writeStorage(storageKey(ws, workflowId, subjectId), JSON.stringify(cases.slice(0, MAX_CASES).map(parseCase).filter(Boolean)))
}

/** Adds a case (a new id), or replaces the one with the same name. Returns null when the list is full. */
export function upsertCase(cases: SavedCase[], next: SavedCase): SavedCase[] | null {
  const valid = parseCase(next)
  if (!valid) return null
  next = valid
  const existing = cases.findIndex((c) => c.name.toLowerCase() === next.name.toLowerCase())
  if (existing >= 0) return cases.map((c, i) => (i === existing ? { ...next, id: c.id } : c))
  if (cases.length >= MAX_CASES) return null
  return [...cases, next]
}

export function exportCases(workflowKey: string, cases: SavedCase[]): string {
  return JSON.stringify({ format: FORMAT, version: 2, workflow: workflowKey, cases }, null, 2)
}

export interface ImportResult {
  cases: SavedCase[]
  /** Cases left out because they were malformed or the list was full. */
  skipped: number
  error: string | null
}

/** Merges an exported file into the current list: same name replaces, new names are added while there is room. */
export function importCases(text: string, current: SavedCase[]): ImportResult {
  if (new TextEncoder().encode(text).length > MAX_IMPORT_BYTES) return {cases:current,skipped:0,error:'The test-case file exceeds 1 MB.'}
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { cases: current, skipped: 0, error: 'This file is not a list of saved test cases.' }
  }
  const list = isRecord(parsed) && parsed.format === FORMAT && (parsed.version === undefined || parsed.version === 1 || parsed.version === 2) && Array.isArray(parsed.cases) && parsed.cases.length <= 100 ? parsed.cases : null
  if (!list) return { cases: current, skipped: 0, error: 'This file is not a list of saved test cases.' }
  let cases = current
  let skipped = 0
  for (const raw of list) {
    const c = parseCase(raw)
    const merged = c ? upsertCase(cases, { ...c, id: `case-${Math.random().toString(36).slice(2, 10)}` }) : null
    if (merged) cases = merged
    else skipped++
  }
  return { cases, skipped, error: null }
}
