import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { DraftSaver } from '@/stores/draftSaver'
import { createStudioStore } from '@/stores/studioStore'
import purchase from '../../../../../examples/purchase-approval.json'
import { StudioProvider, useStudio } from '../StudioContext'
import { normalizeDefinition, type Definition } from '../model/types'
import { WorkManagementFields } from './WorkManagementFields'

function setup(readOnly=false, nodeId='manager', configure?: (def: Definition) => void) {
  const store=createStudioStore()
  const definition=normalizeDefinition(purchase)
  configure?.(definition)
  store.getState().load({ workflowId:'workflow',revision:1,definition })
  function Fields() {
    const node=useStudio(state=>state.definition.nodes.find(node=>node.id===nodeId)!)
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
    const store=setup(false,'finance'); const user=userEvent.setup()
    await user.click(screen.getByRole('checkbox',{name:'Review request'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='finance')!.config.separateFrom).toEqual(['manager'])
    await user.click(screen.getByRole('checkbox',{name:'Review request'}))
    expect(store.getState().definition.nodes.find(node=>node.id==='finance')!.config.separateFrom).toBeUndefined()
  })
  it('does not offer later approvals',()=>{
    setup()
    expect(screen.queryByRole('checkbox',{name:'Finance review'})).not.toBeInTheDocument()
  })
  it('retains bypassed and missing selections for explicit removal',async()=>{
    const store=setup(false,'finance',def=>{
      def.transitions.push({id:'bypass',from:'start',to:'finance',on:'next'})
      def.nodes.find(node=>node.id==='finance')!.config.separateFrom=['manager','missing']
    })
    const user=userEvent.setup()
    const invalid=screen.getByRole('checkbox',{name:'Review request (not an earlier approval on every path)'})
    expect(invalid).toBeChecked()
    expect(screen.queryByRole('checkbox',{name:'Review request'})).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox',{name:'missing (not an earlier approval on every path)'})).toBeChecked()
    expect(store.getState().definition.nodes.find(node=>node.id==='finance')!.config.separateFrom).toEqual(['manager','missing'])
    await user.click(invalid)
    expect(store.getState().definition.nodes.find(node=>node.id==='finance')!.config.separateFrom).toEqual(['missing'])
  })
  it('keeps the selected approval checked after its ID changes in the store',async()=>{
    const store=setup(false,'finance'); const user=userEvent.setup()
    await user.click(screen.getByRole('checkbox',{name:'Review request'}))
    act(()=>{store.getState().renameNodeId('manager','initialReview')})
    expect(screen.getByRole('checkbox',{name:'Review request'})).toBeChecked()
    expect(store.getState().definition.nodes.find(node=>node.id==='finance')!.config.separateFrom).toEqual(['initialReview'])
  })
  it('does not offer approvals for an unreachable step',()=>{
    setup(false,'finance',def=>{
      def.transitions=def.transitions.filter(route=>route.to!=='finance')
    })
    expect(screen.queryByRole('checkbox',{name:'Review request'})).not.toBeInTheDocument()
  })
  it('keeps invalid stored controls disabled in read-only mode',()=>{
    setup(true,'manager',def=>{
      def.nodes.find(node=>node.id==='manager')!.config.separateFrom=['finance']
    })
    const invalid=screen.getByRole('checkbox',{name:'Finance review (not an earlier approval on every path)'})
    expect(invalid).toBeChecked()
    expect(invalid).toBeDisabled()
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
