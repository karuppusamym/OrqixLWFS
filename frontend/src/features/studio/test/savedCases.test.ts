import { beforeEach, describe, expect, it } from 'vitest'
import { emptyAssertions, emptySettings } from './scenarioModel'
import { exportCases, importCases, loadCases, MAX_CASES, parseCase, storeCases, upsertCase, type SavedCase } from './savedCases'

const make = (name: string, expectOutcome: string | null = 'Fulfilled'): SavedCase => ({ version: 3, id: `id-${name}`, name, values: { amount: '1800', rush: false, items: ['a'] }, settings: emptySettings(), expect: expectOutcome, assertions: emptyAssertions() })

describe('saved test cases', () => {
  beforeEach(() => window.localStorage.clear())

  it('round-trips bounded scalar row dictionaries through export, import and personal storage', () => {
    const rowCase = { ...make('Rows'), values: { lines: [{ name: 'Laptop', quantity: '2', urgent: false }] } }
    const imported = importCases(exportCases('workflow', [rowCase]), [])
    expect(imported.cases[0]?.values).toEqual(rowCase.values)
    storeCases('ws', 'wf', imported.cases, 'designer')
    expect(loadCases('ws', 'wf', 'designer')[0]?.values).toEqual(rowCase.values)
  })

  it('keeps cases per workflow in this browser and survives broken storage', () => {
    storeCases('ws1', 'wf1', [make('Small')], 'person1')
    expect(loadCases('ws1', 'wf1', 'person1').map((c) => c.name)).toEqual(['Small'])
    expect(loadCases('ws1', 'other', 'person1')).toEqual([])
    expect(loadCases('ws1', 'wf1', 'person2')).toEqual([])
    window.localStorage.setItem('orqix.testCases.v2.person1.ws1.wf1', '{ not json')
    expect(loadCases('ws1', 'wf1', 'person1')).toEqual([])
    window.localStorage.setItem('orqix.testCases.v2.person1.ws1.wf1', JSON.stringify([{ name: '' }, 5, make('Ok')]))
    expect(loadCases('ws1', 'wf1', 'person1').map((c) => c.name)).toEqual(['Ok'])
  })

  it('works when storage throws', () => {
    const original = Storage.prototype.getItem
    Storage.prototype.getItem = () => {
      throw new Error('blocked')
    }
    try {
      expect(loadCases('ws1', 'wf1', 'person1')).toEqual([])
    } finally {
      Storage.prototype.getItem = original
    }
  })

  it('allows at most 10 cases and replaces a case with the same name', () => {
    let cases: SavedCase[] = []
    for (let i = 0; i < MAX_CASES; i++) cases = upsertCase(cases, make(`Case ${i}`)) as SavedCase[]
    expect(cases).toHaveLength(MAX_CASES)
    expect(upsertCase(cases, make('One more'))).toBeNull()
    const replaced = upsertCase(cases, { ...make('case 3'), expect: 'Rejected' }) as SavedCase[]
    expect(replaced).toHaveLength(MAX_CASES)
    expect(replaced[3]).toMatchObject({ id: 'id-Case 3', expect: 'Rejected' })
  })

  it('exports and imports as JSON, skipping malformed cases and what does not fit', () => {
    const text = exportCases('purchase-approval', [make('A'), make('B')])
    expect(JSON.parse(text)).toMatchObject({ format: 'orqix-test-cases', workflow: 'purchase-approval' })
    const imported = importCases(text, [make('C')])
    expect(imported.cases.map((c) => c.name)).toEqual(['C', 'A', 'B'])
    expect(imported.skipped).toBe(0)
    expect(importCases('nope', []).error).toMatch(/not a list/)
    expect(importCases(JSON.stringify({ format: 'something-else' }), []).error).toMatch(/not a list/)
    const mixed = importCases(JSON.stringify({ format: 'orqix-test-cases', cases: [make('Good'), { name: 'Bad', values: 5 }] }), [])
    expect(mixed.cases.map((c) => c.name)).toEqual(['Good'])
    expect(mixed.skipped).toBe(1)
    const full: SavedCase[] = Array.from({ length: MAX_CASES }, (_, i) => make(`F${i}`))
    expect(importCases(text, full).skipped).toBe(2)
  })

  it('parses only well-formed settings', () => {
    const parsed = parseCase({ name: ' X ', values: { a: 'b', n: 5 }, settings: { approvals: { m: { outcome: 'boom' }, f: { outcome: 'reject', reason: 'r' } }, calls: { p: { result: 'rejected', answers: { k: 'v', z: 1 } } } }, expect: 7 })
    expect(parsed?.name).toBe('X')
    expect(parsed?.values).toEqual({ a: 'b' })
    expect(parsed?.settings.approvals).toEqual({ f: { outcome: 'reject', reason: 'r' } })
    expect(parsed?.settings.calls.p).toEqual({ result: 'rejected', answers: { k: 'v' } })
    expect(parsed?.expect).toBeNull()
  })
})

describe('saved case versions (STU03)', () => {
  it('migrates a version 2 case from storage: same answers, settings and outcome, no extra checks', () => {
    const v2 = { id: 'old', name: 'Old case', values: { amount: '1800' }, settings: { approvals: { manager: { outcome: 'reject', reason: 'r' } }, calls: { procure: { result: 'unavailable', answers: {} } }, children: {}, messages: { wait: 'timeout' }, simulateChildren: false }, expect: 'Rejected' }
    window.localStorage.setItem('orqix.testCases.v2.p.ws.wf', JSON.stringify([v2]))
    const [c] = loadCases('ws', 'wf', 'p')
    expect(c).toMatchObject({ version: 3, id: 'old', name: 'Old case', expect: 'Rejected', values: { amount: '1800' } })
    expect(c?.settings.approvals.manager).toEqual({ outcome: 'reject', reason: 'r' })
    expect(c?.settings.calls.procure).toEqual({ result: 'unavailable', answers: {} })
    expect(c?.settings.messages.wait).toBe('timeout')
    expect(c?.settings.workers).toEqual({})
    expect(c?.assertions).toEqual(emptyAssertions())
  })

  it('keeps mocks and assertions through storage, export and import of version 1 and 2 files', () => {
    const c: SavedCase = {
      ...make('Mocked'),
      settings: { ...emptySettings(), calls: { procure: { result: 'works', answers: {}, mock: { status: '201', body: '{"id":"PO-1","status":"CREATED"}', delaySeconds: '3' } } }, workers: { w: { result: 'failed', answers: {}, delaySeconds: '' } }, adapters: { a: { result: 'works', rowCount: '2' } }, messageMocks: { m: { answers: { ref: 'X' }, delaySeconds: '10' } } },
      assertions: { outputs: [{ field: 'procurementId', op: 'equals', value: 'PO-1', min: '', max: '' }], path: { steps: ['start', 'procure'], match: 'inOrder' }, rules: [{ stepId: 'amountPolicy', rule: 'otherwise' }], approvals: [{ stepId: 'manager', decision: 'approved' }] },
    }
    storeCases('ws', 'wf', [c], 'p')
    expect(loadCases('ws', 'wf', 'p')[0]).toEqual(c)
    const text = exportCases('purchase', [c])
    expect(JSON.parse(text).version).toBe(2)
    expect(importCases(text, []).cases[0]).toMatchObject({ settings: c.settings, assertions: c.assertions })
    const v1 = JSON.stringify({ format: 'orqix-test-cases', version: 1, cases: [{ name: 'V1', values: {}, settings: {}, expect: 'Fulfilled' }] })
    expect(importCases(v1, []).cases[0]).toMatchObject({ version: 3, name: 'V1', assertions: emptyAssertions() })
  })
})

describe('saved case resource bounds', () => {
  it('rejects oversized cases and collections before storing them', () => {
    const large = { ...make('Large'), values: { text: 'x'.repeat(65536) } }
    expect(parseCase(large)).toBeNull()
    expect(upsertCase([], large)).toBeNull()
    expect(parseCase({ ...make('Items'), values: { items: Array(26).fill('x') } })).toBeNull()
    storeCases('w', 'f', [large, make('Good')], 'person1')
    expect(loadCases('w', 'f', 'person1').map((c) => c.name)).toEqual(['Good'])
  })
  it('rejects oversized imports, unsupported versions and excessive case counts', () => {
    expect(importCases(' '.repeat(1048577), []).error).toBeTruthy()
    expect(importCases(JSON.stringify({ format: 'orqix-test-cases', version: 99, cases: [] }), []).error).toBeTruthy()
    expect(importCases(JSON.stringify({ format: 'orqix-test-cases', cases: Array.from({ length: 101 }, (_, i) => make(String(i))) }), []).error).toBeTruthy()
  })
})
