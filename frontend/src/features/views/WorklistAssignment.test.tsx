import { screen,waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe,it,expect } from 'vitest'
import { createView,publishView } from '@/api/views'
import { getWorklistView } from '@/api/worklists'
import { renderInWorkspace,signInAs,WS } from '@/test/utils'
import { setCurrentUser } from '@/mocks/handlers'
import { restoreMockViews,snapshotMockViews } from '@/mocks/views'
import { blankView } from './model'
import { WorklistAssignment } from './WorklistAssignment'

describe('workspace worklist assignment',()=>{
  it('applies an explicit published version, survives mock storage and restores the standard view',async()=>{
    signInAs('dana.designer')
    const view=await createView(WS,{name:'Review queue',definition:blankView('worklist')})
    setCurrentUser('paul.publisher');await publishView(WS,view.id,1)
    const user=userEvent.setup()
    renderInWorkspace(<WorklistAssignment id={view.id} version={1}/> )
    await waitFor(()=>expect(screen.getByRole('button',{name:'Use selected published version'})).toBeEnabled())
    await user.click(screen.getByRole('button',{name:'Use selected published version'}))
    expect(await screen.findByText('Published version applied to My tasks.')).toBeInTheDocument()
    expect(restoreMockViews(snapshotMockViews())).toBe(true)
    expect(await getWorklistView(WS)).toMatchObject({revision:1,binding:{id:view.id,version:1}})
    await user.click(screen.getByRole('button',{name:'Restore standard worklist'}))
    expect(await screen.findByText('Standard worklist restored.')).toBeInTheDocument()
    expect(await getWorklistView(WS)).toEqual({revision:2,binding:null})
  })
})
