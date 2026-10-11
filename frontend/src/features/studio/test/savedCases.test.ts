import { beforeEach, describe, expect, it } from 'vitest'
import { emptySettings } from './scenarioModel'
import { exportCases, importCases, loadCases, MAX_CASES, parseCase, storeCases, upsertCase, type SavedCase } from './savedCases'

const make = (name: string, expectOutcome: string | null = 'Fulfilled'): SavedCase => ({ id: `id-${name}`, name, values: { amount: '1800', rush: false, items: ['a'] }, settings: emptySettings(), expect: expectOutcome })

describe('saved test cases', () => {
  beforeEach(() => window.localStorage.clear())

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
