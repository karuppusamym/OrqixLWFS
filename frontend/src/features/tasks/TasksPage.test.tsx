import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { claim, currentRelease } from '@/mocks/engine'
import { db, userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('task inbox priority and aging', () => {
  it('restores bookmarked filters, explains no matches, and clears filters without dropping sort', async () => {
    signInAs('mani.manager')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/tasks?view=claimable&q=missing&due=overdue&sort=oldest`)
    expect(await screen.findByLabelText('Search tasks')).toHaveValue('missing')
    expect(screen.getByLabelText('Due date')).toHaveValue('overdue')
    expect(await screen.findByText('No tasks match these filters')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText('Replacement laptop')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?view=claimable&sort=oldest')
    await user.type(screen.getByLabelText('Search tasks'), 'laptop')
    expect(router.state.location.search).toContain('q=laptop')
    await user.click(screen.getByRole('link', { name: 'Replacement laptop' }))
    expect(router.state.location.search).toContain('q=laptop')
  })
  it('offers work queues without disguising loaded-page filtering as a global query', async () => {
    signInAs('mani.manager')
    const user=userEvent.setup()
    renderApp(`/w/${WS}/tasks?view=claimable`)
    const queue=await screen.findByLabelText('Work queue')
    await screen.findByRole('option',{name:'Managers'})
    expect(screen.getByText('Filters all eligible tasks, including work on later pages.')).toBeInTheDocument()
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
