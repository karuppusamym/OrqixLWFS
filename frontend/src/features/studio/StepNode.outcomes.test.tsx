import { render,screen } from '@testing-library/react'
import { describe,expect,it,vi } from 'vitest'
import type { NodeProps } from '@xyflow/react'
import { StepNode,type StepFlowNode } from './StepNode'
vi.mock('@xyflow/react',async importOriginal=>({...await importOriginal<object>(),Handle:()=>null}))
describe('finish cards with failed outcomes',()=>{
  it('uses an explicitly failed fallback and negative styling',()=>{
    render(<StepNode {...({id:'end',selected:false,data:{kind:'end',label:'Finish request',subtitle:'Finish · Failed',outcomeKind:'failed',blockers:0,warnings:0}} as NodeProps<StepFlowNode>)} />)
    expect(screen.getByText('Failed')).toBeInTheDocument()
    expect(screen.queryByText('Rejected')).not.toBeInTheDocument()
    expect(screen.getByTestId('node-card-end')).not.toHaveClass('bg-status-success-bg')
  })
})
