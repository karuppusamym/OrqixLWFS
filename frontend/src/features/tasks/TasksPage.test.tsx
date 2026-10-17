import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { claim, currentRelease } from '@/mocks/engine'
import { db, userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('task inbox priority and aging', () => {
  it('offers work queues without disguising loaded-page filtering as a global query', async () => {
    signInAs('mani.manager')
    const user=userEvent.setup()
    renderApp(`/w/${WS}/tasks?view=claimable`)
    const queue=await screen.findByLabelText('Work queue')
    await screen.findByRole('option',{name:'Managers'})
    expect(screen.getByText('Filters loaded tasks; Load more includes older work.')).toBeInTheDocument()
    await user.selectOptions(queue,'managers')
    expect(queue).toHaveValue('managers')
    expect(await screen.findByText('Replacement laptop')).toBeInTheDocument()
  })
  it('hides tasks requiring a missing skill from the available queue', async () => {
    signInAs('mani.manager')
    const run=[...db.runs.values()].find(run=>run.input.title==='Replacement laptop')!
    const manager=currentRelease(run).definition.nodes.find(node=>node.id==='manager')!
    manager.config.requiredSkills=['finance-approvers']
    renderApp(`/w/${WS}/tasks?view=claimable`)
    await screen.findByText('Nothing is available to claim')
    expect(screen.queryByText('Replacement laptop')).not.toBeInTheDocument()
  })
  it('provides accessible server sort/filter controls and real creation age alongside due information', async () => {
    signInAs('mani.manager')
    const run = [...db.runs.values()].find(r => r.input.title === 'Replacement laptop')!
    const task = run.tasks.find(t => t.status === 'open')!
    claim(run,task,userByName('mani.manager')); task.priority=80
    const user = userEvent.setup()
    renderApp(`/w/${WS}/tasks?view=assigned`)
    expect(await screen.findByLabelText('Sort tasks')).toHaveValue('priority')
    expect(await screen.findByText('Priority 80')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Sort tasks'),'oldest')
    expect(screen.getByLabelText('Sort tasks')).toHaveValue('oldest')
    await user.selectOptions(screen.getByLabelText('Priority filter'),'50')
    expect(screen.getByLabelText('Priority filter')).toHaveValue('50')
    expect(await screen.findByText('Replacement laptop')).toBeInTheDocument()
    expect(screen.getByText(/Open for .*Created/)).toBeInTheDocument()
  })
})
