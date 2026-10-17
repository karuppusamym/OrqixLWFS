import { describe, expect, it } from 'vitest'
import { nodeSubtitle } from './summary'
import type { Definition, WorkflowNode } from './types'

const end = (config: Record<string, unknown>) => ({ id: 'end', type: 'end', label: 'Done', config }) as unknown as WorkflowNode

describe('Finish step subtitle', () => {
  const def = { nodes: [], transitions: [] } as unknown as Definition
  it('identifies failed business outcomes without relabelling them rejected',()=>{
    expect(nodeSubtitle(def,end({businessOutcome:'Unavailable',outcomeKind:'failed'}),{})).toBe('Finish · Unavailable · Failed')
    expect(nodeSubtitle(def,end({businessOutcome:'Payment failed',outcomeKind:'failed'}),{})).toBe('Finish · Payment failed')
  })
  it('reads "Finish · Rejected" when the outcome already says rejected', () => {
    expect(nodeSubtitle(def, end({ businessOutcome: 'Rejected', outcomeKind: 'rejected' }), {})).toBe('Finish · Rejected')
  })
  it('marks a rejected outcome with another name', () => {
    expect(nodeSubtitle(def, end({ businessOutcome: 'Declined by manager', outcomeKind: 'rejected' }), {})).toBe('Finish · Declined by manager · Rejected')
  })
  it('shows just the outcome for a successful finish', () => {
    expect(nodeSubtitle(def, end({ businessOutcome: 'Approved', outcomeKind: 'success' }), {})).toBe('Finish · Approved')
  })
})
