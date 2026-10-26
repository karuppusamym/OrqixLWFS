import type { AtomicCondition, DecisionTable, Literal } from '@/features/studio/model/types'
import { conditionTerms } from '@/features/studio/model/types'

/**
 * Mock double of decision-table CSV export and import (G3, docs/build/S89-decision-csv-io.md). It implements the same
 * column format and the same escape as the server, but is simpler: it reads the common faults with a line and column,
 * and leaves the compiler-level checks (overlap, field types) to the mock validation of the caller.
 */
export interface CsvProblem {
  line: number
  column: number
  code: string
  message: string
}

const OPERATORS = ['eq', 'ne', 'gte', 'gt', 'lt', 'lte']
const TYPES = ['text', 'number', 'boolean']
const RISKY = "=+-@\t\r'"
const NUMBER = /^-?(0|[1-9][0-9]{0,17})(\.[0-9]{1,17})?$/
const DATE = /^\d{4}-\d{2}-\d{2}$/

const kind = (v: Literal) => (typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : 'text')

export function exportCsv(table: DecisionTable): string | { reasons: string[] } {
  const reasons: string[] = []
  const types = new Map<string, string>()
  table.rows.forEach((row, i) => {
    if ('any' in row.when) reasons.push(`Row ${i + 1} is an OR row (Any comparison). CSV holds one condition per field; split it into one row per alternative under the first-match policy.`)
    for (const t of conditionTerms(row.when)) types.set(t.fieldRef, types.get(t.fieldRef) ?? kind(t.value))
  })
  const outputs = Object.keys(table.default)
  if (reasons.length) return { reasons }
  const inputs = [...types.keys()]
  const cell = (s: string) => {
    const safe = s && RISKY.includes(s[0]!) ? `'${s}` : s
    return /[",]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
  }
  const lines: string[][] = [[`hitPolicy:${table.hitPolicy}`, 'effectiveFrom', 'effectiveTo', ...inputs.map((r) => `in:${types.get(r)}:${r}`), ...outputs.map((k) => `out:${kind(table.default[k]!)}:${k}`)]]
  for (const row of table.rows) {
    const byRef = new Map<string, AtomicCondition>(conditionTerms(row.when).map((t) => [t.fieldRef, t]))
    lines.push(['rule', row.effective?.from ?? '', row.effective?.to ?? '', ...inputs.map((r) => (byRef.has(r) ? `${byRef.get(r)!.operator} ${String(byRef.get(r)!.value)}` : '')), ...outputs.map((k) => String(row.then[k]))])
  }
  lines.push(['default', '', '', ...inputs.map(() => ''), ...outputs.map((k) => String(table.default[k]))])
  return lines.map((l) => l.map(cell).join(',')).join('\n') + '\n'
}

export function templateCsv(table: DecisionTable): string {
  const types = new Map<string, string>()
  for (const row of table.rows) for (const t of conditionTerms(row.when)) types.set(t.fieldRef, types.get(t.fieldRef) ?? kind(t.value))
  const inputs = [...types.keys()]
  const outputs = Object.keys(table.default)
  const example = inputs.map((r, i) => (i > 0 ? '' : types.get(r) === 'number' ? 'gte 0' : types.get(r) === 'boolean' ? 'eq true' : 'eq example'))
  return [
    ['hitPolicy:' + table.hitPolicy, 'effectiveFrom', 'effectiveTo', ...inputs.map((r) => `in:${types.get(r)}:${r}`), ...outputs.map((k) => `out:${kind(table.default[k]!)}:${k}`)],
    ['rule', '', '', ...example, ...outputs.map((k) => String(table.default[k]))],
    ['default', '', '', ...inputs.map(() => ''), ...outputs.map((k) => String(table.default[k]))],
  ].map((l) => l.join(',')).join('\n') + '\n'
}

/** Splits CSV text into records of cells with the line each started on; null plus a problem on a fault. */
function records(text: string, problems: CsvProblem[]): Array<{ line: number; cells: string[] }> {
  const out: Array<{ line: number; cells: string[] }> = []
  let cells: string[] = []
  let cell = ''
  let quoted = false
  let after = false
  let started = false
  let line = 1
  let start = 1
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++ } else { quoted = false; after = true }
      } else if (c === '\n' || c === '\r') {
        problems.push({ line, column: cells.length + 1, code: 'lineBreakInCell', message: 'A cell contains a line break. Cells hold a single line of text.' })
        return []
      } else cell += c
      continue
    }
    if (c === ',' || c === '\n' || c === '\r') {
      if (c === '\r') {
        if (text[i + 1] !== '\n') { problems.push({ line, column: cells.length + 1, code: 'lineBreak', message: 'A lone carriage return was found. Use a normal line break.' }); return [] }
        i++
      }
      cells.push(cell)
      cell = ''; after = false; started = false
      if (c !== ',') {
        if (!(cells.length === 1 && cells[0] === '')) out.push({ line: start, cells })
        else if (i + 1 < text.length) { problems.push({ line, column: 1, code: 'emptyLine', message: 'Empty lines are not allowed.' }); return [] }
        cells = []; line++; start = line
      }
      continue
    }
    if (after || (c === '"' && started)) { problems.push({ line, column: cells.length + 1, code: 'quote', message: 'A quote may only start a cell, and only a comma or the end of the line may follow a closing quote.' }); return [] }
    started = true
    if (c === '"') { quoted = true; continue }
    if (c < ' ') { problems.push({ line, column: cells.length + 1, code: 'controlCharacter', message: 'The cell contains a control character, which is not allowed.' }); return [] }
    cell += c
  }
  if (quoted) { problems.push({ line: start, column: cells.length + 1, code: 'quote', message: 'A quoted cell is never closed.' }); return [] }
  if (started || after || cells.length) { cells.push(cell); out.push({ line: start, cells }) }
  return out
}

function unescape(cell: string, line: number, column: number, problems: CsvProblem[]): string {
  let v = cell
  if (v.startsWith("'")) {
    if (v.length < 2 || !RISKY.includes(v[1]!)) { problems.push({ line, column, code: 'apostrophe', message: 'A leading apostrophe is only allowed before = + - @ or another apostrophe. Remove it.' }); return v }
    v = v.slice(1)
  }
  if (v !== v.trim()) problems.push({ line, column, code: 'spaces', message: 'The cell starts or ends with a space. Remove it.' })
  return v
}

function literal(type: string, text: string, line: number, column: number, problems: CsvProblem[]): Literal | undefined {
  if (!text || text !== text.trim()) { problems.push({ line, column, code: 'spaces', message: 'Use exactly one space after the comparison and no space at the end.' }); return undefined }
  if (type === 'number') {
    if (!NUMBER.test(text)) { problems.push({ line, column, code: 'number', message: `"${text}" is not a plain number. Use digits with an optional minus sign and decimal point (no thousands separator, no exponent, no currency).` }); return undefined }
    return Number(text)
  }
  if (type === 'boolean') {
    if (text !== 'true' && text !== 'false') { problems.push({ line, column, code: 'boolean', message: `"${text}" is not true or false (lowercase).` }); return undefined }
    return text === 'true'
  }
  return text
}

export interface ParsedCsv {
  table?: Pick<DecisionTable, 'hitPolicy' | 'rows' | 'default'>
  problems: CsvProblem[]
  /** CSV line of rule row `i` (zero-based) and of the default line. */
  ruleLines: number[]
}

export function parseCsv(text: string): ParsedCsv {
  const problems: CsvProblem[] = []
  const ruleLines: number[] = []
  const body = text.replace(/^﻿/, '')
  if (!body) return { problems: [{ line: 1, column: 1, code: 'empty', message: 'The file is empty.' }], ruleLines }
  if (body.length > 128 * 1024) return { problems: [{ line: 1, column: 1, code: 'tooLarge', message: 'The file is larger than 128 KiB.' }], ruleLines }
  const recs = records(body, problems)
  if (problems.length) return { problems, ruleLines }
  const head = recs[0]!.cells.map((c, i) => unescape(c, 1, i + 1, problems))
  if (head[0] !== 'hitPolicy:UNIQUE' && head[0] !== 'hitPolicy:FIRST') problems.push({ line: 1, column: 1, code: 'header', message: 'The first header cell must be hitPolicy:UNIQUE or hitPolicy:FIRST.' })
  else if (head[1] !== 'effectiveFrom') problems.push({ line: 1, column: 2, code: 'header', message: 'The second header cell must be effectiveFrom.' })
  else if (head[2] !== 'effectiveTo') problems.push({ line: 1, column: 3, code: 'header', message: 'The third header cell must be effectiveTo.' })
  const inputs: Array<{ type: string; name: string }> = []
  const outputs: Array<{ type: string; name: string }> = []
  if (!problems.length) {
    head.slice(3).forEach((h, i) => {
      const input = h.startsWith('in:')
      const output = h.startsWith('out:')
      const parts = h.slice(input ? 3 : 4).split(/:(.*)/s)
      if ((!input && !output) || !TYPES.includes(parts[0]!) || !parts[1]) problems.push({ line: 1, column: i + 4, code: 'header', message: `Unknown column "${h}". Use in:<type>:<field> columns, then out:<type>:<result> columns.` })
      else if (input && outputs.length) problems.push({ line: 1, column: i + 4, code: 'header', message: 'Input columns (in:) must come before output columns (out:).' })
      else (input ? inputs : outputs).push({ type: parts[0]!, name: parts[1]! })
    })
    if (!problems.length && (!inputs.length || !outputs.length)) problems.push({ line: 1, column: 4, code: 'header', message: 'There must be at least one in: column and one out: column.' })
  }
  if (problems.length) return { problems, ruleLines }
  const width = head.length
  const rows: DecisionTable['rows'] = []
  let def: Record<string, Literal> | undefined
  for (const rec of recs.slice(1)) {
    if (def) { problems.push({ line: rec.line, column: 1, code: 'afterDefault', message: 'Nothing may follow the default line.' }); break }
    if (rec.cells.length !== width) { problems.push({ line: rec.line, column: Math.min(rec.cells.length, width) + 1, code: 'cellCount', message: `This line has ${rec.cells.length} cells, but the header has ${width}.` }); continue }
    const before = problems.length
    const cells = rec.cells.map((c, i) => unescape(c, rec.line, i + 1, problems))
    if (problems.length > before) continue
    if (cells[0] !== 'rule' && cells[0] !== 'default') { problems.push({ line: rec.line, column: 1, code: 'rowKind', message: 'The first cell must be rule or default.' }); continue }
    const isDefault = cells[0] === 'default'
    const terms: AtomicCondition[] = []
    inputs.forEach((col, i) => {
      const c = cells[3 + i]!
      if (!c) return
      if (isDefault) { problems.push({ line: rec.line, column: 4 + i, code: 'defaultCell', message: 'The default line has no conditions; leave this cell empty.' }); return }
      const space = c.indexOf(' ')
      const op = space < 0 ? '' : c.slice(0, space)
      if (space < 0) { problems.push({ line: rec.line, column: 4 + i, code: 'condition', message: 'Write a comparison and a value, for example gte 100 or eq approved.' }); return }
      if (!OPERATORS.includes(op)) { problems.push({ line: rec.line, column: 4 + i, code: 'operator', message: `Unknown comparison "${op}". Use eq, ne, gte, gt, lt or lte.` }); return }
      const value = literal(col.type, c.slice(space + 1), rec.line, 4 + i, problems)
      if (value !== undefined) terms.push({ fieldRef: col.name, operator: op as AtomicCondition['operator'], value })
    })
    if (!isDefault && !terms.length && problems.length === before) problems.push({ line: rec.line, column: 4, code: 'noCondition', message: 'A rule needs at least one condition.' })
    const then: Record<string, Literal> = {}
    outputs.forEach((col, i) => {
      const c = cells[3 + inputs.length + i]!
      if (!c) { problems.push({ line: rec.line, column: 4 + inputs.length + i, code: 'emptyResult', message: `Every result needs a value; ${col.name} is empty.` }); return }
      const v = literal(col.type, c, rec.line, 4 + inputs.length + i, problems)
      if (v !== undefined) then[col.name] = v
    })
    const effective: { from?: string; to?: string } = {}
    ;([1, 2] as const).forEach((n) => {
      const d = cells[n]!
      if (!d) return
      if (isDefault) problems.push({ line: rec.line, column: n + 1, code: 'defaultCell', message: 'The default line has no effective dates.' })
      else if (!DATE.test(d)) problems.push({ line: rec.line, column: n + 1, code: 'date', message: `Write the date as YYYY-MM-DD, not "${d}".` })
      else effective[n === 1 ? 'from' : 'to'] = d
    })
    if (problems.length > before) continue
    if (isDefault) { def = then; continue }
    if (rows.length >= 50) { problems.push({ line: rec.line, column: 1, code: 'tooManyRules', message: 'At most 50 rules are allowed.' }); continue }
    rows.push({ when: terms.length === 1 ? terms[0]! : { all: terms }, then, ...(Object.keys(effective).length ? { effective } : {}) })
    ruleLines.push(rec.line)
  }
  if (!problems.length && !rows.length) problems.push({ line: recs.at(-1)!.line, column: 1, code: 'noRules', message: 'There must be at least one rule line before the default line.' })
  if (!problems.length && !def) problems.push({ line: recs.at(-1)!.line + 1, column: 1, code: 'noDefault', message: 'The last line must be the default line (default in the first cell).' })
  if (problems.length) return { problems, ruleLines }
  return { table: { hitPolicy: head[0] === 'hitPolicy:FIRST' ? 'FIRST' : 'UNIQUE', rows, default: def! }, problems, ruleLines }
}
