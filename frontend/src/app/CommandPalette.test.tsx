import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderAppReady, signInAs, WS } from '@/test/utils'

describe('Ctrl+K Ask Orqix', () => {
  it('opens from the keyboard, lists only pages the person may open and filters them', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}`)
    await user.keyboard('{Control>}k{/Control}')
    const dialog = await screen.findByRole('dialog', { name: 'Ask Orqix' })
    const pages = within(dialog).getByRole('list', { name: 'Pages' })
    expect(within(pages).getByRole('button', { name: 'Workflows' })).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Question or page'), 'work')
    expect(within(pages).queryByRole('button', { name: 'Home' })).not.toBeInTheDocument()
    await user.click(within(pages).getByRole('button', { name: 'Workflows' }))
    expect(await screen.findByRole('heading', { name: 'Workflows' })).toBeInTheDocument()
  })

  it('hands a question to the Assistant message box without sending it', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}`)
    await user.keyboard('{Control>}k{/Control}')
    const dialog = await screen.findByRole('dialog', { name: 'Ask Orqix' })
    await user.type(within(dialog).getByLabelText('Question or page'), 'I need a laptop')
    await user.click(within(dialog).getByRole('button', { name: /Ask the Assistant/ }))
    expect(await screen.findByLabelText('Message')).toHaveValue('I need a laptop')
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled()
    expect(screen.queryByRole('list', { name: 'Conversation' })).not.toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'About this conversation' })).toBeInTheDocument()
  })
})
