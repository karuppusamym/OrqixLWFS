import { describe, expect, it } from 'vitest'
import { seedDb } from './seed'
import { db } from './state'

describe('demo seed (matches tools/demo/seed_demo.py)', () => {
  it('has nine requests, ORQ-1001 to ORQ-1009, in every interesting state', () => {
    seedDb({ demo: true })
    const runs = [...db.runs.values()].sort((a, b) => (a.reference ?? '').localeCompare(b.reference ?? ''))
    expect(runs.map((r) => r.reference)).toEqual(Array.from({ length: 9 }, (_, i) => `ORQ-${1001 + i}`))
    const byTitle = (title: string) => runs.find((r) => r.input.title === title)!
    const openStep = (title: string) => byTitle(title).tasks.find((t) => t.status === 'open')?.nodeId

    expect(byTitle('Replacement laptop for onboarding').lifecycle).toBe('waiting')
    expect(openStep('Replacement laptop for onboarding')).toBe('manager')
    expect(openStep('Ergonomic chairs (12)')).toBe('manager')
    expect(openStep('Standing desks for the analytics team')).toBe('finance')
    expect(openStep('Server rack for the new office')).toBe('finance')
    for (const title of ['Conference room camera', 'Monitor arms (20)']) {
      expect(byTitle(title).lifecycle).toBe('completed')
      expect(byTitle(title).outcomeKind).toBe('success')
    }
    const coffee = byTitle('Premium coffee machine')
    expect(coffee.outcomeKind).toBe('rejected')
    expect(coffee.tasks.find((t) => t.decision)?.decision?.reason).toBe("Not in this quarter's budget; please resubmit in January.")
    const licences = byTitle('Annual design software licences')
    expect(licences.outcomeKind).toBe('rejected')
    expect(licences.tasks.filter((t) => t.decision).map((t) => t.decision?.action)).toEqual(['approve', 'reject'])
    const printer = byTitle('Label printer')
    expect(printer.lifecycle).toBe('needsAttention')
    expect(printer.attention?.reason).toBe('EFFECT_REJECTED')
    expect(db.procurementFault).toBe('none')
  })

  it('keeps the small fixture set the tests rely on when demo is not asked for', () => {
    seedDb()
    expect([...db.runs.values()].map((r) => r.businessKey).sort()).toEqual(['ORQ-1001', 'ORQ-1002', 'ORQ-1003', 'ORQ-1004', 'ORQ-1005', 'ORQ-1006'])
  })
})
