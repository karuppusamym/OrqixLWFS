import { screen,waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe,it,expect,vi } from 'vitest'
import { renderInWorkspace,signInAs,WS } from '@/test/utils'
import { CaseModelDesigner } from './CaseModelDesigner'

describe('case model authoring',()=>{
  it('requires explicit eligibility, generates a model, invalidates it on edits and supports undo',async()=>{
    signInAs('dana.designer')
    const apply=vi.fn(),user=userEvent.setup()
    renderInWorkspace(<CaseModelDesigner ws={WS} caseKey="visual-case" name="Visual case" onApply={apply} disabled={false}/> )
    await user.click(await screen.findByRole('button',{name:'Form view'}))
    await user.click(await screen.findByRole('button',{name:'Use generated CMMN'}))
    expect(await screen.findByText('Choose an eligible workspace group for every human task.')).toBeInTheDocument()
    const group=screen.getByLabelText('Eligible group for task1')
    await waitFor(()=>expect(group.querySelectorAll('option').length).toBeGreaterThan(1))
    const value=group.querySelectorAll('option')[1]!.value
    await user.selectOptions(group,value)
    await user.click(screen.getByRole('button',{name:'Use generated CMMN'}))
    expect(apply.mock.lastCall?.[0]).toContain(`flowable:candidateGroups="${value}"`)
    expect(screen.getByLabelText('Generated CMMN preview')).toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'Add milestone'}))
    expect(apply.mock.lastCall?.[0]).toBeNull()
    expect(screen.queryByLabelText('Generated CMMN preview')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Start after for item1'),'task1')
    await user.click(screen.getByRole('button',{name:'Use generated CMMN'}))
    expect(apply.mock.lastCall?.[0]).toContain('sourceRef="node_task1_item"')
    await user.click(screen.getByRole('button',{name:'Undo model change'}))
    expect(screen.getByLabelText('Start after for item1')).toHaveValue('')
    expect(apply.mock.lastCall?.[0]).toBeNull()
  })
})
