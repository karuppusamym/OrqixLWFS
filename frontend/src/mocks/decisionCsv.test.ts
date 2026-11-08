import { describe, expect, it } from 'vitest'
import type { DecisionTable } from '@/features/studio/model/types'
import { exportCsv, parseCsv } from './decisionCsv'

const table: DecisionTable = {
  key: 't', version: 1, hitPolicy: 'FIRST',
  rows: [
    { when: { fieldRef: '/input/amount', operator: 'gte', value: 100 }, then: { route: '- dash', rate: -1.5 }, effective: { from: '2026-01-01' } },
    { when: { all: [{ fieldRef: '/input/amount', operator: 'lt', value: 100 }, { fieldRef: '/input/costCenter', operator: 'eq', value: 'IT, Ops' }] }, then: { route: "'quote", rate: 2 } },
  ],
  default: { route: 'standard', rate: 0 },
}

describe('mock decision CSV', () => {
  it('prefixes formula-like cells on export and restores them exactly on import', () => {
    const csv = exportCsv(table) as string
    expect(csv).toContain("'- dash")
    expect(csv).toContain("'-1.5")
    for (const cell of csv.split('\n').flatMap((l) => l.split(','))) expect(cell).not.toMatch(/^[=+\-@]/)
    const back = parseCsv(csv)
    expect(back.problems).toEqual([])
    expect(back.table).toEqual({ hitPolicy: 'FIRST', rows: table.rows, default: table.default })
  })

  it('refuses ambiguous cells with line and column', () => {
    const head = 'hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/a,out:text:r\n'
    for (const bad of ['1,000', '$5', '1e3', '+5', '5.']) {
      const p = parseCsv(`${head}rule,,,"gte ${bad}",x\ndefault,,,,y\n`).problems[0]!
      expect([p.line, p.column, p.code]).toEqual([2, 4, 'number'])
    }
    expect(parseCsv(`${head}rule,,,gte 1,'x\ndefault,,,,y\n`).problems[0]!.code).toBe('apostrophe')
    expect(parseCsv('').problems[0]!.code).toBe('empty')
  })

  it('does not write an OR row', () => {
    const any: DecisionTable = { ...table, rows: [{ when: { any: [{ fieldRef: '/input/a', operator: 'lt', value: 1 }, { fieldRef: '/input/a', operator: 'gt', value: 9 }] }, then: { route: 'x', rate: 1 } }] }
    expect(exportCsv(any)).toHaveProperty('reasons')
  })
})

describe('mock decision CSV with formulas (docs/build/S146)', () => {
  const formulas: DecisionTable = {
    key: 'f', version: 1, hitPolicy: 'FIRST',
    rows: [
      { when: { all: [{ fieldRef: '/input/amount', expression: 'input.amount > input.limit' }, { fieldRef: '/input/due', expression: 'input.due < date("2026-06-30")' }] }, then: { fee: { expression: 'round(input.amount * 0.025, 2)' }, route: 'review' } },
      { when: { fieldRef: '/input/amount', operator: 'gte', value: 0 }, then: { fee: 0, route: 'auto' } },
    ],
    default: { fee: 0, route: 'none' },
  }

  it('writes formula cells as =expression behind the injection escape and reads them back exactly', () => {
    const csv = exportCsv(formulas) as string
    expect(csv.split('\n')[0]).toBe('hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:expression:/input/due,out:number:fee,out:text:route')
    expect(csv).toContain("'=input.amount > input.limit")
    expect(csv).toContain('"\'=input.due < date(""2026-06-30"")"')
    expect(csv).toContain('"\'=round(input.amount * 0.025, 2)"')
    const back = parseCsv(csv)
    expect(back.problems).toEqual([])
    expect(back.table).toEqual({ hitPolicy: 'FIRST', rows: formulas.rows, default: formulas.default })
  })

  it('refuses formulas CSV cannot hold and formula cells where they do not belong', () => {
    const tab: DecisionTable = { ...formulas, rows: [{ when: { fieldRef: '/input/amount', expression: 'input.amount >\t1' }, then: { fee: 1, route: 'x' } }] }
    expect((exportCsv(tab) as { reasons: string[] }).reasons[0]).toMatch(/control character/)
    const spaced: DecisionTable = { ...formulas, rows: [{ when: { fieldRef: '/input/amount', operator: 'gte', value: 1 }, then: { fee: { expression: ' input.amount' }, route: 'x' } }] }
    expect((exportCsv(spaced) as { reasons: string[] }).reasons[0]).toMatch(/space/)
    const head = 'hitPolicy:FIRST,effectiveFrom,effectiveTo,in:expression:/input/due,out:text:r\n'
    expect(parseCsv(`${head}rule,,,eq 2026-01-01,x\ndefault,,,,y\n`).problems[0]!.code).toBe('condition')
    expect(parseCsv(`${head}rule,,,'=input.due != null,x\ndefault,,,,'=input.r\n`).problems[0]!.code).toBe('defaultFormula')
    expect(parseCsv(`${head}rule,,,'= input.due != null,x\ndefault,,,,y\n`).problems[0]!.code).toBe('formula')
    expect(parseCsv(`${head}rule,,,'=input.due != null,x\ndefault,,,,y\n`).table?.rows[0]!.when).toEqual({ fieldRef: '/input/due', expression: 'input.due != null' })
  })
})
