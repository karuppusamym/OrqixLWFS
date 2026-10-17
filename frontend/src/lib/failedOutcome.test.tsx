import { render,screen } from '@testing-library/react'
import { describe,expect,it } from 'vitest'
import { StatusBadge } from '@/components/common/StatusBadge'
import { runStatus } from './status'
import { eventTone } from './events'
import { buildStepViews,statusLabel } from '@/features/runs/diagram/pathModel'
import type { RunPath } from '@/features/runs/diagram/api'

describe('failed business outcomes',()=>{
  it('keeps failure distinct from success, rejection and needs attention',()=>{
    expect(runStatus({lifecycle:'completed',outcomeKind:'failed',outcome:'Unavailable'})).toEqual({tone:'failed',label:'Failed · Unavailable'})
    expect(runStatus({lifecycle:'completed',outcomeKind:'failed'})).toEqual({tone:'failed',label:'Failed'})
    expect(runStatus({lifecycle:'needsAttention',outcomeKind:'failed'}).tone).toBe('attention')
    expect(runStatus({lifecycle:'completed',outcomeKind:'success'}).tone).toBe('success')
    expect(runStatus({lifecycle:'completed',outcomeKind:'rejected'}).tone).toBe('rejected')
  })
  it('uses an explicit failed badge and problem timeline tone',()=>{
    render(<StatusBadge lifecycle="completed" outcomeKind="failed" outcome="Unavailable" />)
    expect(screen.getByText('Failed · Unavailable')).toHaveClass('bg-danger-soft')
    expect(eventTone({type:'RUN_COMPLETED',data:{outcomeKind:'failed'}})).toBe('danger')
  })
  it('marks a visited failed finish as Failed rather than Done or Rejected',()=>{
    const path={runId:'run',lifecycle:'completed',diagram:{nodes:[{id:'end',kind:'end',label:'Finish',outcomeKind:'failed',outcome:'Unavailable'}],transitions:[]},visited:[{nodeId:'end',outcome:'completed',enteredAt:'2026-10-15T12:00:00Z',leftAt:'2026-10-15T12:00:00Z'}],current:[],taken:[]} as unknown as RunPath
    expect(buildStepViews(path).get('end')?.status).toBe('failed')
    expect(statusLabel('failed',true)).toBe('Failed')
    expect(statusLabel('failed',false)).toBe('Failed')
  })
})
