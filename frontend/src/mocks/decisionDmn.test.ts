import { describe, expect, it } from 'vitest'
import type { DecisionTable } from '@/features/studio/model/types'
import { exportCsv, parseCsv } from './decisionCsv'
import { exportDmn, readDmn } from './decisionDmn'

const table: DecisionTable = {
  key: 'fees', version: 1, hitPolicy: 'COLLECT', aggregation: 'SUM',
  rows: [
    { when: { fieldRef: '/input/costCenter', operator: 'in', value: ['IT', 'HR'] }, then: { fee: 1.5 } },
    { when: { fieldRef: '/input/amount', operator: 'between', value: { from: 1000, to: 5000, includeTo: false } }, then: { fee: 2 } },
  ],
  default: { fee: 0 },
}

describe('mock decision DMN (docs/build/S121)', () => {
  it('writes standard unary tests and reads its own file back to the same table', () => {
    const xml = exportDmn(table, 'fees', 'Fees')
    expect(xml).toContain('hitPolicy="COLLECT" aggregation="SUM"')
    expect(xml).toContain('<text>"IT","HR"</text>')
    expect(xml).toContain('<text>[1000..5000)</text>')
    const read = readDmn(xml)
    expect(read.problems).toEqual([])
    expect(read.content).toEqual({ hitPolicy: 'COLLECT', aggregation: 'SUM', rows: table.rows, default: table.default })
  })

  it('asks for a decision when the file has several and refuses foreign files', () => {
    const two = exportDmn(table, 'fees').replace('</definitions>', '<decision id="other" name="Other"></decision></definitions>')
    const choose = readDmn(two)
    expect(choose.content).toBeNull()
    expect(choose.decisions.map((d) => d.id)).toEqual(['fees', 'other'])
    expect(readDmn(two, 'fees').content?.hitPolicy).toBe('COLLECT')
    expect(readDmn('<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"><decision id="d"/></definitions>').problems[0]).toMatch(/only DMN files it exported/)
    expect(readDmn('<root/>').problems[0]).toMatch(/not a DMN file/)
  })

  it('CSV refuses lists, ranges, PRIORITY and COLLECT and points to DMN; ANY round-trips', () => {
    const refused = exportCsv(table)
    expect(typeof refused).toBe('object')
    expect((refused as { reasons: string[] }).reasons.every((r) => r.endsWith('Download the table as DMN instead.'))).toBe(true)
    const any: DecisionTable = { key: 'a', version: 1, hitPolicy: 'ANY', rows: [{ when: { fieldRef: '/input/amount', operator: 'lt', value: 5 }, then: { r: 'x' } }], default: { r: 'y' } }
    const back = parseCsv(exportCsv(any) as string)
    expect(back.problems).toEqual([])
    expect(back.table?.hitPolicy).toBe('ANY')
  })
})
