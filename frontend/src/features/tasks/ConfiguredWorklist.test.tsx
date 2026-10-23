import { screen,waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http,HttpResponse } from 'msw'
import { describe,it,expect } from 'vitest'
import { server } from '@/test/server'
import { renderAppReady,signInAs,WS } from '@/test/utils'
import { blankView,validateView } from '@/features/views/model'

describe('published worklist presentation',()=>{
  it('renders renamed columns over the authorized list and opens the existing task panel',async()=>{
    signInAs('max.manager')
    const definition=blankView('worklist');definition.sections[0]!.fields[0]!.label='Work request';definition.actions[0]!.label='Review work'
    definition.sections[0]!.fields.push({id:'priority',label:'Urgency',binding:'task.priority',type:'number'})
    server.use(http.get('*/api/v1/workspaces/:ws/worklist-view',()=>HttpResponse.json({revision:1,binding:{id:'view',version:1,definition}})))
    const user=userEvent.setup();const app=await renderAppReady(`/w/${WS}/tasks?view=claimable`)
    expect(await screen.findByRole('columnheader',{name:'Work request'})).toBeInTheDocument()
    expect(screen.getByRole('columnheader',{name:'Urgency'})).toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'Review work: Replacement laptop'}))
    await waitFor(()=>expect(app.router.state.location.pathname).toMatch(/\/tasks\/.+/))
    expect(await screen.findByRole('button',{name:'Claim task'})).toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'Approve'})).not.toBeInTheDocument()
  })
  it('rejects unsupported bindings, missing titles and write actions before saving',()=>{
    const definition=blankView('worklist')
    expect(validateView(definition)).toEqual([])
    definition.sections[0]!.fields[0]!.binding='task.input'
    expect(validateView(definition)).toContain('Use supported read-only task summary columns with matching types.')
    definition.actions[0]!.type='approve'
    expect(validateView(definition)).toContain('A worklist needs a request title column and one Open task action.')
  })
})
