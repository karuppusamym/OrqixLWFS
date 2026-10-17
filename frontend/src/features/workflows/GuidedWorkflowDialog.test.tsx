import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderAppReady, signInAs, WS } from '@/test/utils'

describe('guided workflow creation', () => {
  it('asks for missing answers and creates a draft without AI or publication', async () => {
    signInAs('dana.designer'); const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/workflows`)
    await user.click(await screen.findByRole('button', { name: 'New workflow' }))
    await user.click(screen.getByRole('button', { name: 'Create with guided questions' }))
    const dialog = screen.getByRole('dialog', { name: 'Create with guided questions' })
    await user.click(within(dialog).getByRole('button', { name: 'Next' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('name and explain')
    await user.type(within(dialog).getByLabelText(/What is the workflow called/), 'Guided approval')
    await user.type(within(dialog).getByLabelText(/What should it accomplish/), 'Approve simple work')
    await user.click(within(dialog).getByRole('button', { name: 'Next' }))
    await user.click(within(dialog).getByRole('button', { name: 'Next' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('group')
    const groups = await within(dialog).findByLabelText('Who approves?')
    await user.selectOptions(groups, 'managers')
    await user.click(within(dialog).getByRole('button', { name: 'Next' }))
    await user.click(within(dialog).getByRole('button', { name: 'Next' }))
    await user.click(within(dialog).getByRole('button', { name: 'Next' }))
    expect(within(dialog).getByText(/does not publish or execute/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/Business process owner/), 'Demo owner')
    await user.click(within(dialog).getByRole('checkbox', { name: /I have reviewed/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Create reviewable draft' }))
    expect(await screen.findByRole('heading', { name: 'Guided approval' })).toBeInTheDocument()
  })
})
