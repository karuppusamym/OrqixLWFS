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
