import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import * as download from '@/lib/download'
import { breakAuditChain, grantAuditRead } from '@/mocks/security'
import { userByName } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

async function openIntegrity() {
  signInAs('wade.admin')
  grantAuditRead(WS, userByName('wade.admin').id)
  const user = userEvent.setup()
  renderApp(`/w/${WS}/settings`)
  await user.click(await screen.findByRole('tab', { name: 'Run retention' }))
  await screen.findByRole('heading', { name: 'Audit log integrity' })
  return user
}

describe('Settings → Audit log integrity (CG-I06)', () => {
  it('verifies the chain, lists the checks and reports the first broken position', async () => {
    const user = await openIntegrity()
    expect(await screen.findByText('The audit log has not been checked yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Verify now' }))
    expect(await screen.findByText('The audit log is intact')).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Latest checks of the audit log' })
    expect(await within(table).findByText('Intact')).toBeInTheDocument()

    breakAuditChain(WS, 57)
    await user.click(screen.getByRole('button', { name: 'Verify now' }))
    expect(await screen.findByText('The audit log is broken at position 57')).toBeInTheDocument()
    expect(screen.getByText(/The check found that an entry was changed after it was written/)).toBeInTheDocument()
    expect(await within(table).findByText('Broken')).toBeInTheDocument()
    expect(within(table).getAllByRole('row')).toHaveLength(3)
  })

  it('downloads the signed export as a JSON file named after the workspace and positions', async () => {
    const save = vi.spyOn(download, 'downloadJson').mockImplementation(() => undefined)
    const user = await openIntegrity()
    await user.click(screen.getByRole('button', { name: 'Download signed export' }))
    await vi.waitFor(() => expect(save).toHaveBeenCalled())
    const [segment, filename] = save.mock.calls[0]!
    expect(filename).toBe('audit-export-purchasing-1-120.json')
    expect(segment).toMatchObject({ format: 'orqix.audit-export.v1', algorithm: 'ES256', firstSeq: 1, lastSeq: 120 })
    save.mockRestore()
  })

  it('is hidden from administrators without audit read access', async () => {
    signInAs('wade.admin')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/settings`)
    await user.click(await screen.findByRole('tab', { name: 'Run retention' }))
    await screen.findByRole('heading', { name: 'Run retention' })
    expect(screen.queryByRole('heading', { name: 'Audit log integrity' })).not.toBeInTheDocument()
  })
})
