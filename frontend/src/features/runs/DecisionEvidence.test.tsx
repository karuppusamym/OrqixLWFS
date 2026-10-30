import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { api, call } from '@/api/client'
import type { RunEvent } from '@/api/types'
import { claim, decide } from '@/mocks/engine'
import { setCurrentUser } from '@/mocks/handlers'
import { db, userByName } from '@/mocks/state'
import { signInAs, WS } from '@/test/utils'
import { DecisionEvidence } from './DecisionEvidence'
const event=(data:Record<string,unknown>):RunEvent => ({seq:1,type:'DECISION_EVALUATED',occurredAt:'2026-01-01T00:00:00Z',data})
const evidence={inputs:[{fieldRef:'/input/amount',value:5000}],rows:[{rowId:'r1',condition:{fieldRef:'/input/amount',operator:'gte',value:5000},matched:true,selected:true},{rowId:'r2',condition:{fieldRef:'/input/amount',operator:'lt',value:5000},matched:false,selected:false}],defaultSelected:false}
describe('automated decision evidence', () => {
  it('shows typed input and each evaluated candidate with match and selection',async () => {
    render(<DecisionEvidence event={event({evidence})} />)
    await userEvent.setup().click(screen.getByText('Decision evidence'))
    expect(screen.getByText('5000')).toBeInTheDocument()
    const rules=screen.getByRole('table',{name:'Evaluated candidate rules'})
    expect(within(rules).getByText('Amount is at least 5000')).toBeInTheDocument()
    expect(within(rules).getByText('Amount is less than 5000')).toBeInTheDocument()
    expect(within(rules).getAllByText('Yes')).toHaveLength(2)
    expect(within(rules).getAllByText('No')).toHaveLength(2)
    expect(screen.getByText('Default rule not selected.')).toBeInTheDocument()
  })
  it('shows All/Any rows, lists, ranges and several selected rows under COLLECT (docs/build/S121)', async () => {
    const collect={hitPolicy:'COLLECT',aggregation:'SUM',inputs:[{fieldRef:'/input/amount',value:2000},{fieldRef:'/input/costCenter',value:'HR'}],rows:[
      {rowId:'r1',condition:{fieldRef:'/input/costCenter',operator:'in',value:['IT','HR']},matched:true,selected:true},
      {rowId:'r2',condition:{all:[{fieldRef:'/input/amount',operator:'between',value:{from:1000,to:5000,includeTo:false}},{fieldRef:'/input/costCenter',operator:'ne',value:'OPS'}]},matched:true,selected:true},
      {rowId:'r3',condition:{any:[{fieldRef:'/input/amount',operator:'lt',value:10},{fieldRef:'/input/amount',operator:'gte',value:9000}]},matched:false,selected:false}],defaultSelected:false}
    render(<DecisionEvidence event={event({evidence:collect})} />)
    await userEvent.setup().click(screen.getByText('Decision evidence'))
    const rules=screen.getByRole('table',{name:'Evaluated candidate rules'})
    expect(within(rules).getByText('Cost center is one of IT, HR')).toBeInTheDocument()
    expect(within(rules).getByText('Amount is between from 1000 to below 5000 and Cost center does not equal OPS')).toBeInTheDocument()
    expect(within(rules).getByText('Amount is less than 10 or Amount is at least 9000')).toBeInTheDocument()
    expect(within(rules).getAllByText('Yes')).toHaveLength(4)
    expect(screen.getByText(/Combined as the sum/)).toBeInTheDocument()
  })
  it('shows fallback selection and safely handles restricted, legacy and malformed events', () => {
    const {rerender}=render(<DecisionEvidence event={event({evidence:{...evidence,defaultSelected:true}})} />)
    expect(screen.getByText('Default rule selected.')).toBeInTheDocument()
    rerender(<DecisionEvidence event={event({evidenceRedacted:true})} />)
    expect(screen.getByText(/restricted to request participants/)).toBeInTheDocument()
    expect(screen.queryByText('5000')).not.toBeInTheDocument()
    rerender(<DecisionEvidence event={event({})} />)
    expect(screen.queryByText('Decision evidence')).not.toBeInTheDocument()
    rerender(<DecisionEvidence event={event({evidence:{...evidence,inputs:[{fieldRef:'/input/amount',value:{secret:'not scalar'}}]}})} />)
    expect(screen.queryByText('Decision evidence')).not.toBeInTheDocument()
  })
  it('mock exposes evidence to requester and approver but hides values and outputs for metadata viewers',async () => {
    signInAs('mani.manager')
    const run=[...db.runs.values()].find(r=>r.input.title==='Replacement laptop')!
    const task=run.tasks.find(t=>t.status==='open')!
    const manager=userByName('mani.manager')
    claim(run,task,manager); decide(run,task,manager,'approve',null,null)
    for(const name of ['rhea.requester','mani.manager','olga.operator']) {
      setCurrentUser(name)
      const page=await call(api.GET('/workspaces/{workspaceId}/runs/{runId}/events',{params:{path:{workspaceId:WS,runId:run.id}}}))
      const decision=page.items.find(e=>e.type==='DECISION_EVALUATED')!
      expect(decision.data.evidence).toMatchObject({defaultSelected:true,rows:[{matched:false,selected:false}]})
      expect(decision.data.outputs).toEqual({route:'standard'})
    }
    setCurrentUser('max.manager')
    await expect(call(api.GET('/workspaces/{workspaceId}/runs/{runId}/events',{params:{path:{workspaceId:WS,runId:run.id}}}))).rejects.toMatchObject({code:'NOT_FOUND'})
    setCurrentUser('vic.viewer')
    const page=await call(api.GET('/workspaces/{workspaceId}/runs/{runId}/events',{params:{path:{workspaceId:WS,runId:run.id}}}))
    const decision=page.items.find(e=>e.type==='DECISION_EVALUATED')!
    expect(decision.data).toEqual({decisionKey:'amount-policy',version:1,evidenceRedacted:true})
  })
})
