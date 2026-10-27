import { configure, screen } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'

configure({ asyncUtilTimeout: 15000 })

// STU03: "N of M tests pass" next to the publish check is information only; it never disables publishing.
describe('publish review: shared test summary', () => {
  it('says how many shared tests passed on this draft', async () => {
    server.use(
      http.get('*/api/v1/workspaces/:ws/workflows/:wf/test-suite/summary', () =>
        HttpResponse.json({ suiteVersion: 2, cases: 3, lastRun: { revision: 1, passed: false, passedCases: 2, totalCases: 3, testedAt: '2026-01-15T10:00:00Z' } }),
      ),
    )
    signInAs('paul.publisher')
    renderApp(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/publish?revision=1`)
    expect(await screen.findByTestId('test-summary-line')).toHaveTextContent('Tests: 2 of 3 shared tests passed on this draft.')
  }, 60000)

  it('shows nothing when the workflow has no shared tests', async () => {
    signInAs('paul.publisher')
    renderApp(`/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/publish?revision=1`)
    await screen.findByRole('heading', { name: 'Check results' })
    expect(screen.queryByTestId('test-summary-line')).not.toBeInTheDocument()
  }, 60000)
})
