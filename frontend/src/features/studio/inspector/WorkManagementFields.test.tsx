import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition } from '../model/types'
import { WorkManagementFields } from './WorkManagementFields'

function setup(readOnly=false) {
  const store=createStudioStore()
  store.getState().load({ workflowId:'workflow',revision:1,definition:normalizeDefinition(purchase) })
  function Fields() {
    const node=useStudio(state=>state.definition.nodes.find(node=>node.id==='manager')!)
    return <WorkManagementFields node={node} groups={[{key:'finance-approvers',name:'Finance expertise'},{key:'managers',name:'Management'}]} />
  }
  render(<StudioProvider value={{store,saver:{} as DraftSaver,wsId:'workspace',workflowId:'workflow',readOnly,connections:{responseType:()=>undefined}}}><Fields /></StudioProvider>)
  return store
}
describe('work management authoring',()=>{
  it('authors skill intersection, immutable working calendar and bounded notices',async()=>{
    const store=setup(); const user=userEvent.setup()
    await user.click(screen.getByRole('checkbox',{name:'Finance expertise'}))
    await user.click(screen.getByRole('checkbox',{name:'Count working hours for the due date'}))
    await user.clear(screen.getByLabelText('Calendar time zone')); await user.type(screen.getByLabelText('Calendar time zone'),'America/New_York'); await user.tab()
    await user.type(screen.getByLabelText('Holidays'),'2026-12-25'); await user.tab()
    await user.click(screen.getByRole('checkbox',{name:'Repeat overdue notices'}))
    const config=store.getState().definition.nodes.find(node=>node.id==='manager')!.config
    expect(config.requiredSkills).toEqual(['finance-approvers'])
    expect(config.calendar).toMatchObject({zone:'America/New_York',weekdays:[1,2,3,4,5],holidays:['2026-12-25']})
    expect(config.escalation).toEqual({intervalHours:24,maxNotices:3})
    await user.click(screen.getByRole('checkbox',{name:'Count working hours for the due date'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.calendar).toBeUndefined()
  })
  it('keeps an approval apart from earlier approvals and removes the setting again',async()=>{
    const store=setup(); const user=userEvent.setup()
    await user.click(screen.getByRole('checkbox',{name:'Finance review'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.separateFrom).toEqual(['finance'])
    await user.click(screen.getByRole('checkbox',{name:'Finance review'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.separateFrom).toBeUndefined()
  })
  it('declares correctable request fields from the single-value fields only',async()=>{
    const store=setup(); const user=userEvent.setup()
    expect(screen.getByRole('checkbox',{name:'Amount (USD)'})).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox',{name:'Amount (USD)'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.correctable).toEqual(['amount'])
    await user.click(screen.getByRole('checkbox',{name:'Amount (USD)'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.correctable).toBeUndefined()
  })
  it('authors an explicit dispatch policy and removes it again',async()=>{
    const store=setup(); const user=userEvent.setup()
    const select=screen.getByLabelText('Assign new tasks')
    expect(select).toHaveValue('')
    await user.selectOptions(select,'leastLoaded')
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.dispatch).toEqual({policy:'leastLoaded'})
    await user.selectOptions(select,'roundRobin')
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.dispatch).toEqual({policy:'roundRobin'})
    await user.selectOptions(select,'')
    expect(store.getState().definition.nodes.find(node=>node.id==='manager')!.config.dispatch).toBeUndefined()
  })
  it('disables authoring in a published/read-only definition',()=>{
    setup(true)
    expect(screen.getByRole('checkbox',{name:'Finance expertise'})).toBeDisabled()
    expect(screen.getByRole('checkbox',{name:'Repeat overdue notices'})).toBeDisabled()
    expect(screen.getByLabelText('Assign new tasks')).toBeDisabled()
  })
})
