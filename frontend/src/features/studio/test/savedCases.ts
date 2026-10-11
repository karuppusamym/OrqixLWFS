import { readStorage, writeStorage } from '@/lib/storage'
import type { RawValues } from '@/lib/form/values'
import { emptySettings, type TestSettings } from './scenarioModel'

/** A named test case: the answers typed into the sample form, what should happen at each step, what is expected. */
export interface SavedCase {
  id: string
  name: string
  values: RawValues
  settings: TestSettings
  /** A business outcome, {@link NEEDS_ATTENTION}, or null for "no expectation". */
  expect: string | null
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
    else if (Array.isArray(v) && v.length <= 25 && v.every((x) => typeof x === 'string' || typeof x === 'boolean')) values[k] = v as string[]
  }
  const s = isRecord(raw.settings) ? raw.settings : {}
  if (isRecord(s.children) && Object.values(s.children).some(v => isRecord(v) && Array.isArray(v.outcomes) && v.outcomes.length > 25)) return null
  const settings: TestSettings = { ...emptySettings() }
  if (isRecord(s.approvals)) for (const [k, v] of Object.entries(s.approvals)) if (isRecord(v) && (v.outcome === 'approve' || v.outcome === 'reject' || v.outcome === 'timeout')) settings.approvals[k] = { outcome: v.outcome, reason: typeof v.reason === 'string' ? v.reason : '' }
  if (isRecord(s.calls))
    for (const [k, v] of Object.entries(s.calls))
      if (isRecord(v) && (v.result === 'works' || v.result === 'rejected' || v.result === 'unavailable' || v.result === 'unknownOutcome')) {
        const answers: Record<string, string> = {}
        if (isRecord(v.answers)) for (const [a, t] of Object.entries(v.answers)) if (typeof t === 'string') answers[a] = t
        settings.calls[k] = { result: v.result, answers }
      }
  if (isRecord(s.children)) for (const [k, v] of Object.entries(s.children)) if (isRecord(v) && Array.isArray(v.outcomes)) settings.children[k] = { outcomes: v.outcomes.filter((o): o is string => typeof o === 'string') }
  if (isRecord(s.messages)) for (const [k, v] of Object.entries(s.messages)) if (v === 'arrives' || v === 'timeout') settings.messages[k] = v
  settings.simulateChildren = s.simulateChildren === true
  return {
    id: typeof raw.id === 'string' && raw.id !== '' ? raw.id : `case-${Math.random().toString(36).slice(2, 10)}`,
    name: raw.name.trim().slice(0, 80),
    values,
    settings,
    expect: typeof raw.expect === 'string' ? raw.expect : null,
  }
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
  return JSON.stringify({ format: FORMAT, version: 1, workflow: workflowKey, cases }, null, 2)
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
  const list = isRecord(parsed) && parsed.format === FORMAT && (parsed.version === undefined || parsed.version === 1) && Array.isArray(parsed.cases) && parsed.cases.length <= 100 ? parsed.cases : null
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
