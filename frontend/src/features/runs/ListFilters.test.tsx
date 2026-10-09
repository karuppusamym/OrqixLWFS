import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('workspace list filters', () => {
  it('restores run filters from a shared URL and clears them without losing unrelated parameters', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/runs?lifecycle=waiting&businessKey=PO-1042&source=bookmark`)
    expect(await screen.findByLabelText('Status')).toHaveValue('waiting')
    expect(screen.getByLabelText('Request reference')).toHaveValue('PO-1042')
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(router.state.location.search).toBe('?source=bookmark'))
    expect(screen.getByLabelText('Status')).toHaveValue('')
    expect(screen.getByLabelText('Request reference')).toHaveValue('')
  })

  it('applies a trimmed search on submit and restores it through browser history', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/runs`)
    const input = await screen.findByLabelText('Request reference')
    await user.type(input, '  PO-1042  ')
    expect(router.state.location.search).toBe('')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(router.state.location.search).toBe('?businessKey=PO-1042'))
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await user.type(screen.getByLabelText('Request reference'), 'unfinished search')
    await act(async () => { await router.navigate(-1) })
    expect(screen.getByLabelText('Request reference')).toHaveValue('PO-1042')
  })

  it('ignores an invalid status and supports attention filtering', async () => {
    signInAs('olga.operator')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/runs?lifecycle=toString`)
    expect(await screen.findByLabelText('Status')).toHaveValue('')
    await user.click(screen.getByRole('button', { name: 'Needs attention' }))
    expect(router.state.location.search).toBe('?lifecycle=needsAttention')
    expect(screen.getByLabelText('Status')).toBeDisabled()
  })

  it('restores request status from the URL', async () => {
    signInAs('rhea.requester')
    const { router } = renderApp(`/w/${WS}/requests?lifecycle=completed`)
    expect(await screen.findByLabelText('Show')).toHaveValue('completed')
    await userEvent.setup().selectOptions(screen.getByLabelText('Show'), 'waiting')
    expect(router.state.location.search).toBe('?lifecycle=waiting')
  })

  it('restores workflow search and offers recovery from no results', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/workflows?q=does-not-exist`)
    expect(await screen.findByLabelText('Search workflows')).toHaveValue('does-not-exist')
    await user.click(await screen.findByRole('button', { name: 'Show all workflows' }))
    expect(router.state.location.search).toBe('')
    expect(screen.getByLabelText('Search workflows')).toHaveValue('')
  })
})
