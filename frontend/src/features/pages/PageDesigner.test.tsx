import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it } from 'vitest'
import { renderAppReady, signInAs, WS } from '@/test/utils'

const DESK = '77777777-7777-4777-8777-777777777773'
const axeOptions = { rules: { 'color-contrast': { enabled: false } } }

describe('application page designer with mock API (S111)', () => {
  it('composes from the palette, reorders, undoes, previews responsively, inspects problems and saves', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { container } = await renderAppReady(`/w/${WS}/views/${DESK}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Purchase desk page' })).toBeInTheDocument()
    const problems = screen.getByRole('region', { name: 'Bindings and problems' })
    await waitFor(() => expect(problems).toHaveTextContent('view Purchase form v1 (request) · submits to a workflow'))
    expect(within(problems).getByText('Local checks passed')).toBeInTheDocument()

    const outline = screen.getByRole('region', { name: 'Page outline' })
    await user.click(within(screen.getByRole('region', { name: 'Component palette' })).getByRole('button', { name: 'Text' }))
    expect(within(outline).getByRole('button', { name: /Text: Explain what this page is for/ })).toBeInTheDocument()
    await user.click(within(outline).getByRole('button', { name: 'Move text1 up' }))
    const mainIds = () => within(within(outline).getAllByRole('group')[0]!).getAllByRole('listitem').map(item => item.textContent)
    expect(mainIds()[2]).toContain('Text:')
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(within(outline).queryByRole('button', { name: /Text: Explain/ })).not.toBeInTheDocument()

    expect(screen.getByTestId('page-preview')).toHaveAttribute('data-columns', '2')
    await user.selectOptions(screen.getByLabelText('Preview width'), 'mobile')
    expect(screen.getByTestId('page-preview')).toHaveAttribute('data-columns', '1')

    const stop = screen.getAllByLabelText('Stop at the first failed step (required)')[0]!
    await user.click(stop)
    expect(problems).toHaveTextContent('must stop at the first failed step')
    const actions = screen.getByRole('group', { name: 'Designer actions' })
    expect(within(actions).getByRole('button', { name: 'Save draft' })).toBeDisabled()
    await user.click(stop)

    const title = screen.getByLabelText('Page title')
    await user.clear(title)
    await user.type(title, 'Purchase desk v2')
    await user.click(within(actions).getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText(/Draft revision 2\./)).toBeInTheDocument()
    expect((await axe.run(container, axeOptions)).violations).toEqual([])
  }, 30_000)

  it('lets a publisher review, publish a new version and pin it to the route', async () => {
    signInAs('paul.publisher')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/views/${DESK}`)
    await screen.findByRole('heading', { level: 1, name: 'Purchase desk page' })
    expect(within(screen.getByRole('group', { name: 'Designer actions' })).queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Component palette' })).getByRole('button', { name: 'Text' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Review and publish' }))
    const dialog = await screen.findByRole('dialog', { name: 'Review publication' })
    expect(dialog).toHaveTextContent('Chain “Submit and view my requests”: Submit request → Go to page, stops at the first failure')
    await user.click(within(dialog).getByRole('button', { name: 'Publish version 2' }))
    expect(await screen.findByText(/Published version 2\./)).toBeInTheDocument()

    const routes = screen.getByRole('region', { name: 'Routes' })
    expect(await within(routes).findByText('/purchase-desk')).toBeInTheDocument()
    expect(routes).toHaveTextContent('pins version 1')
    await user.type(within(routes).getByLabelText('Route'), 'purchase-desk')
    expect(within(routes).getByLabelText('Version')).toHaveValue('2')
    await user.click(within(routes).getByRole('button', { name: 'Pin to route' }))
    await waitFor(() => expect(routes).toHaveTextContent('pins version 2'))
  })

  it('creates a new application page from the library', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(`/w/${WS}/views`)
    await user.click(await screen.findByRole('button', { name: 'New view' }))
    const dialog = await screen.findByRole('dialog', { name: 'New view' })
    await user.type(within(dialog).getByLabelText('Name'), 'Team page')
    await user.selectOptions(within(dialog).getByLabelText('View type'), 'page')
    await user.click(within(dialog).getByRole('button', { name: 'Create view' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Team page' })).toBeInTheDocument()
    expect(screen.getByText(/Application page\. Draft revision 1\./)).toBeInTheDocument()
  })
})
