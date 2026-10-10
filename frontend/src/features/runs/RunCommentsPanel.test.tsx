import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('request collaboration comments', () => {
  it('allows a requester to save an immutable plain-text note', async () => {
    signInAs('rhea.requester'); const user = userEvent.setup()
    const run = [...db.runs.values()].find(value => value.businessKey === 'REQ-1042') ?? [...db.runs.values()][0]!
    renderApp(`/w/${WS}/runs/${run.id}`)
    const input = await screen.findByLabelText('Add a comment')
    await user.type(input, 'Review <script>this quote</script> please.')
    await user.click(screen.getByRole('button', { name: 'Save comment' }))
    expect(await screen.findByText('Review <script>this quote</script> please.')).toBeInTheDocument()
    expect(screen.getByText(/Saved comments are permanent/)).toBeInTheDocument()
    expect(run.comments).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Delete comment' })).not.toBeInTheDocument()
  })
})
