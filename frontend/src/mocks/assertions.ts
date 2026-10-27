/**
 * Mock-side twin of com.orqix.compiler.SimulationAssertions (STU03): evaluates the assertions of a test case over the
 * result of a dry run. examples/fixtures/simulation/assertions.json is evaluated by both and must give the same results.
 */
export const NEEDS_ATTENTION = '__needsAttention__'

export interface Assertions {
  outcome?: string
  outputs?: Array<{ field: string; op: 'equals' | 'contains' | 'range'; value?: string | number | boolean; min?: number; max?: number }>
  path?: { steps: string[]; match?: 'exact' | 'inOrder' }
  rules?: Array<{ stepId: string; rule: number | 'otherwise' }>
  approvals?: Array<{ stepId: string; decision: 'approved' | 'rejected' | 'timedOut' }>
}

export interface AssertionResult {
  kind: 'outcome' | 'output' | 'path' | 'rule' | 'approval'
  label: string
  passed: boolean
  expected: string
  actual: string
  detail?: string
}

export class AssertionError extends Error {}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown) => typeof v === 'string' && v.trim() !== '' && v.length <= 200
const scalar = (v: unknown) => (typeof v === 'string' && v.length <= 200) || typeof v === 'number' || typeof v === 'boolean'
const quote = (t: string) => `"${t}"`

function list(a: Obj, key: string, max: number): unknown[] {
  if (!(key in a) || a[key] === undefined) return []
  const v = a[key]
  if (!Array.isArray(v) || v.length > max) throw new AssertionError(`assertions.${key} must be a list of at most ${max} items.`)
  return v
}

export function validateAssertions(a: unknown): void {
  if (a === undefined || a === null) return
  if (!isObj(a)) throw new AssertionError('The assertions must be an object.')
  for (const key of Object.keys(a)) if (!['outcome', 'outputs', 'path', 'rules', 'approvals'].includes(key)) throw new AssertionError(`Unknown assertion ${key}.`)
  if ('outcome' in a && !text(a.outcome)) throw new AssertionError('assertions.outcome must be text of at most 200 characters.')
  for (const o of list(a, 'outputs', 20)) {
    if (!isObj(o) || !text(o.field) || !['equals', 'contains', 'range'].includes(String(o.op))) throw new AssertionError('Each output assertion needs a field and op equals, contains or range.')
    if (o.op === 'range') {
      const min = typeof o.min === 'number'
      const max = typeof o.max === 'number'
      if ((!min && !max) || ('min' in o && !min) || ('max' in o && !max) || (min && max && (o.min as number) > (o.max as number))) throw new AssertionError('A range assertion needs a number min, max or both, min not above max.')
    } else if (!scalar(o.value)) throw new AssertionError(`An ${String(o.op)} assertion needs a text, number or true/false value.`)
  }
  if ('path' in a) {
    const p = a.path
    if (!isObj(p) || !['exact', 'inOrder'].includes(String(p.match ?? 'exact'))) throw new AssertionError('assertions.path needs steps and match exact or inOrder.')
    const steps = list(p, 'steps', 100)
    if (steps.length === 0 || steps.some((s) => !text(s))) throw new AssertionError('assertions.path.steps must list 1 to 100 step IDs.')
  }
  for (const r of list(a, 'rules', 20)) {
    const ok = isObj(r) && text(r.stepId) && ((typeof r.rule === 'number' && Number.isInteger(r.rule) && r.rule >= 1 && r.rule <= 1000) || r.rule === 'otherwise')
    if (!ok) throw new AssertionError('Each rule assertion needs a stepId and a rule number or otherwise.')
  }
  for (const r of list(a, 'approvals', 20)) {
    if (!isObj(r) || !text(r.stepId) || !['approved', 'rejected', 'timedOut'].includes(String(r.decision))) throw new AssertionError('Each approval assertion needs a stepId and decision approved, rejected or timedOut.')
  }
}

function num(v: number): string {
  // BigDecimal.stripTrailingZeros().toPlainString() for the plain values tests use.
  return Number.isInteger(v) ? String(v) : String(v)
}

export function show(v: unknown): string {
  if (v === null || v === undefined) return 'no value'
  if (typeof v === 'string') return quote(v)
  if (typeof v === 'number') return num(v)
  if (typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

function same(actual: unknown, expected: unknown): boolean {
  if (typeof actual === 'number' && typeof expected === 'number') return actual === expected
  return JSON.stringify(actual) === JSON.stringify(expected)
}

function contains(actual: unknown, expected: unknown): boolean {
  if (Array.isArray(actual)) return actual.some((item) => same(item, expected))
  if (typeof actual === 'string') return actual.includes(typeof expected === 'number' ? num(expected) : String(expected))
  return false
}

type Trace = Array<{ stepId: string; stepLabel?: string; kind: string; details?: Obj; wrote?: Obj }>

export function evaluateAssertions(result: { outcome: Obj; outputs?: Obj; trace: Trace; coverage?: Obj }, assertions: Assertions | null | undefined): { passed: boolean; results: AssertionResult[] } {
  const a = (assertions ?? {}) as Obj
  validateAssertions(a)
  const results: AssertionResult[] = []
  const labels = new Map<string, string>()
  const visited: string[] = []
  for (const e of result.trace) {
    if (!labels.has(e.stepId)) labels.set(e.stepId, e.stepLabel ?? e.stepId)
    if (!visited.includes(e.stepId)) visited.push(e.stepId)
  }
  const notVisited = ((result.coverage?.steps as Obj | undefined)?.notVisited ?? []) as Array<{ id: string; label?: string }>
  for (const n of notVisited) if (!labels.has(n.id)) labels.set(n.id, n.label ?? n.id)
  const label = (id: string) => labels.get(id) ?? id
  const add = (r: AssertionResult) => results.push(r)
  const outcome = result.outcome
  const finished = outcome.status === 'finished'
  if ('outcome' in a) {
    const expected = String(a.outcome)
    const actual = finished ? String(outcome.businessOutcome ?? '') : NEEDS_ATTENTION
    const attention = isObj(outcome.attention) ? outcome.attention : null
    const actualText = finished ? actual : `Needs attention${attention ? ` at ${quote(String(attention.stepLabel ?? ''))}` : ''}`
    add({ kind: 'outcome', label: 'Final outcome', passed: expected === actual, expected: expected === NEEDS_ATTENTION ? 'Needs attention' : expected, actual: actualText })
  }
  for (const raw of list(a, 'outputs', 20)) {
    const o = raw as NonNullable<Assertions['outputs']>[number]
    const actual = outputValue(result, o.field)
    let passed: boolean
    let expected: string
    if (o.op === 'equals') {
      passed = actual !== undefined && same(actual, o.value)
      expected = `equals ${show(o.value)}`
    } else if (o.op === 'contains') {
      passed = actual !== undefined && contains(actual, o.value)
      expected = `contains ${show(o.value)}`
    } else {
      const min = typeof o.min === 'number' ? o.min : null
      const max = typeof o.max === 'number' ? o.max : null
      passed = typeof actual === 'number' && (min === null || actual >= min) && (max === null || actual <= max)
      expected = min !== null && max !== null ? `between ${num(min)} and ${num(max)}` : min !== null ? `at least ${num(min)}` : `at most ${num(max as number)}`
    }
    add({ kind: 'output', label: fieldLabel(o.field, label), passed, expected, actual: actual === undefined ? 'no value' : show(actual) })
  }
  if ('path' in a) {
    const p = a.path as NonNullable<Assertions['path']>
    const exact = (p.match ?? 'exact') === 'exact'
    const detail = exact ? exactDifference(p.steps, visited, label) : orderDifference(p.steps, visited, label)
    const r: AssertionResult = { kind: 'path', label: exact ? 'Steps visited' : 'Steps visited, in this order', passed: detail === null, expected: names(p.steps, label), actual: names(visited, label) }
    if (detail !== null) r.detail = detail
    add(r)
  }
  for (const raw of list(a, 'rules', 20)) {
    const r = raw as NonNullable<Assertions['rules']>[number]
    const entry = last(result.trace, r.stepId, 'decision')
    const expected = typeof r.rule === 'number' ? `rule ${r.rule}` : 'otherwise'
    const row = entry?.details?.matchedRow
    const actual = !entry ? 'not reached' : typeof row === 'number' ? `rule ${row}` : 'otherwise'
    add({ kind: 'rule', label: `Decision ${quote(label(r.stepId))}`, passed: expected === actual, expected, actual })
  }
  for (const raw of list(a, 'approvals', 20)) {
    const r = raw as NonNullable<Assertions['approvals']>[number]
    const entry = last(result.trace, r.stepId, 'approval')
    const actual = !entry ? 'not reached' : String(entry.details?.decision ?? 'not reached')
    add({ kind: 'approval', label: `Approval ${quote(label(r.stepId))}`, passed: r.decision === actual, expected: r.decision, actual })
  }
  return { passed: results.every((r) => r.passed), results }
}

function last(trace: Trace, stepId: string, kind: string) {
  let found: Trace[number] | undefined
  for (const e of trace) if (e.stepId === stepId && e.kind === kind) found = e
  return found
}

function outputValue(result: { outputs?: Obj; trace: Trace }, field: string): unknown {
  if (field.startsWith('/steps/')) {
    const parts = field.slice(1).split('/')
    if (parts.length !== 3) return undefined
    let found: unknown
    for (const e of result.trace) if (e.stepId === parts[1] && e.wrote && (parts[2] as string) in e.wrote) found = e.wrote[parts[2] as string]
    return found === null ? undefined : found
  }
  const v = result.outputs?.[field]
  return v === null ? undefined : v
}

function fieldLabel(field: string, label: (id: string) => string): string {
  if (field.startsWith('/steps/')) {
    const parts = field.slice(1).split('/')
    if (parts.length === 3) return `Value ${parts[2]} of ${quote(label(parts[1] as string))}`
  }
  return `Output ${field}`
}

const names = (ids: string[], label: (id: string) => string) => (ids.length === 0 ? 'no steps' : ids.map(label).join(' → '))

function exactDifference(expected: string[], visited: string[], label: (id: string) => string): string | null {
  const n = Math.max(expected.length, visited.length)
  for (let i = 0; i < n; i++) {
    const want = expected[i]
    const got = visited[i]
    if (want !== undefined && want === got) continue
    if (want === undefined) return `The run went on to ${quote(label(got as string))} after the expected path ended (step ${i + 1}).`
    if (got === undefined) return `The run stopped before ${quote(label(want))} (step ${i + 1}).`
    return `Step ${i + 1}: expected ${quote(label(want))} but the run went to ${quote(label(got))}.`
  }
  return null
}

function orderDifference(expected: string[], visited: string[], label: (id: string) => string): string | null {
  let from = 0
  for (const want of expected) {
    const at = visited.slice(from).indexOf(want)
    if (at < 0) return visited.includes(want) ? `${quote(label(want))} was visited, but not in the expected order.` : `${quote(label(want))} was not visited.`
    from += at + 1
  }
  return null
}
