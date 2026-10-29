import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { ViewDefinition } from '@/features/views/model'
import { ChainEditor } from './ChainEditor'
import { blankPage, type PageDefinition, type PageLookups } from './model'

const request: ViewDefinition = { schemaVersion: 1, kind: 'request', sections: [{ id: 's', label: 'S', columns: 1, fields: [{ id: 'title', label: 'Title', type: 'text', binding: 'request.title' }] }], actions: [{ id: 'submit', label: 'Submit', type: 'submit' }] }
const approval: ViewDefinition = { schemaVersion: 1, kind: 'approval', sections: [{ id: 'a', label: 'A', columns: 1, fields: [{ id: 'title', label: 'Title', type: 'text', binding: 'request.title' }] }], actions: [{ id: 'approve', label: 'Approve', type: 'approve' }] }
const lookups: PageLookups = { views: new Map([['req:1', request], ['app:1', approval]]), extensions: new Map() }
const page: PageDefinition = { ...blankPage(), context: 'task', regions: [{ id: 'main', label: 'Main', components: [
  { id: 'form', type: 'view', view: { id: 'req', version: 1 }, workflowId: 'wf' },
  { id: 'review', type: 'view', view: { id: 'app', version: 1 } },
] }] }

function Harness() {
  const [current, setCurrent] = useState(page)
  return <><ChainEditor page={current} lookups={lookups} disabled={false} onChange={chains => setCurrent({ ...current, chains })} /><output data-testid="chains">{JSON.stringify(current.chains)}</output></>
}
const chains = () => JSON.parse(screen.getByTestId('chains').textContent!) as PageDefinition['chains']

describe('chain editor (S111)', () => {
  it('builds an ordered chain from the allowlist, offers only compatible targets and reports problems', async () => {
    const user = userEvent.setup()
    const { container } = render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Add chain' }))
    expect(chains()[0]).toMatchObject({ stopOnFailure: true, steps: [{ action: 'submit', component: 'form' }] })

    await user.click(screen.getByRole('button', { name: 'Add step' }))
    expect(chains()[0]!.steps[1]).toEqual({ action: 'navigate', target: '/w' })
    await user.click(screen.getByRole('button', { name: 'Move New action step 2 up' }))
    expect(screen.getByRole('list', { name: 'Problems in New action' })).toHaveTextContent('navigation must be the last step')
    await user.click(screen.getByRole('button', { name: 'Move New action step 1 down' }))
    expect(screen.queryByRole('list', { name: 'Problems in New action' })).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Step 1 action'), 'approve')
    const target = screen.getByLabelText('Target component')
    expect(within(target).getAllByRole('option').map(option => option.textContent)).toEqual(['Choose a component', 'review'])
    expect(chains()[0]!.steps[0]).toEqual({ action: 'approve', component: 'review' })

    await user.click(screen.getByLabelText('Stop at the first failed step (required)'))
    expect(screen.getByRole('list', { name: 'Problems in New action' })).toHaveTextContent('must stop at the first failed step')
    expect((await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Remove chain New action' }))
    expect(chains()).toEqual([])
  })
})
