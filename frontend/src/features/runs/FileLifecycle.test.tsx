import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { seedRunFile } from '@/mocks/caseFiles'
import { db, userByName, type MockRun } from '@/mocks/state'
import { renderApp, signInAs, WS } from '@/test/utils'

function runByKey(key: string): MockRun {
  for (const run of db.runs.values()) if (run.businessKey === key) return run
  throw new Error(`No run ${key}`)
}

function setup(user: string) {
  signInAs(user)
  const run = runByKey('ORQ-1005')
  const owner = userByName('rhea.requester')!.id
  const note = seedRunFile(run, { name: 'note.txt', contentType: 'text/plain', text: 'Invoice 42\nTotal 100', uploadedById: owner })
  const scan = seedRunFile(run, { name: 'scan.pdf', contentType: 'application/pdf', text: '%PDF-1.4', uploadedById: owner })
  return { run, note, scan }
}

const row = async (name: string) => (await screen.findByText(name)).closest('[data-testid="file-row"]') as HTMLElement

describe('file quarantine and text extraction (DOC08)', () => {
  it('lets an operator quarantine with a reason, blocks the download, and release restores it', async () => {
    const { run } = setup('olga.operator')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs/${run.id}`)
    const file = await row('note.txt')
    expect(within(file).getByRole('button', { name: 'Download note.txt' })).toBeEnabled()

    await user.click(within(file).getByRole('button', { name: 'Quarantine note.txt' }))
    const dialog = await screen.findByRole('dialog', { name: 'Quarantine this file' })
    await user.type(within(dialog).getByLabelText('Reason'), 'x')
    await user.click(within(dialog).getByRole('button', { name: 'Quarantine file' }))
    expect(within(dialog).getByText(/at least 3 characters/)).toBeInTheDocument()
    await user.clear(within(dialog).getByLabelText('Reason'))
    await user.type(within(dialog).getByLabelText('Reason'), 'Suspected malware sample')
    await user.click(within(dialog).getByRole('button', { name: 'Quarantine file' }))

    await waitFor(async () => expect(within(await row('note.txt')).getByTestId('quarantine-status')).toBeInTheDocument())
    const quarantined = await row('note.txt')
    expect(within(quarantined).getByTestId('quarantine-reason')).toHaveTextContent('Suspected malware sample')
    expect(within(quarantined).getByRole('button', { name: 'Download note.txt' })).toBeDisabled()
    expect(within(quarantined).queryByRole('button', { name: /Extract text/ })).not.toBeInTheDocument()

    await user.click(within(quarantined).getByRole('button', { name: 'Release note.txt from quarantine' }))
    await waitFor(async () => expect(within(await row('note.txt')).queryByTestId('quarantine-status')).not.toBeInTheDocument())
    expect(within(await row('note.txt')).getByRole('button', { name: 'Download note.txt' })).toBeEnabled()
  })

  it('does not offer quarantine, release or extraction to the requester, who still sees the state', async () => {
    const { run, note } = setup('rhea.requester')
    note.quarantinedAt = new Date().toISOString()
    note.quarantineReason = 'Under review'
    renderApp(`/w/${WS}/runs/${run.id}`)
    const file = await row('note.txt')
    expect(within(file).getByTestId('quarantine-status')).toBeInTheDocument()
    expect(within(file).getByTestId('quarantine-reason')).toHaveTextContent('Under review')
    expect(within(file).queryByRole('button', { name: /Quarantine note.txt|Release|Extract text/ })).not.toBeInTheDocument()
    // The requester cannot remove a quarantined file either.
    expect(within(file).queryByRole('button', { name: 'Remove note.txt' })).not.toBeInTheDocument()
    expect(within(await row('scan.pdf')).getByRole('button', { name: 'Remove scan.pdf' })).toBeInTheDocument()
    expect(within(await row('scan.pdf')).queryByRole('button', { name: /Quarantine/ })).not.toBeInTheDocument()
  })

  it('extracts text with the local stub for text files and reports other types as unsupported', async () => {
    const { run } = setup('olga.operator')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/runs/${run.id}`)
    const note = await row('note.txt')
    await user.click(within(note).getByRole('button', { name: 'Extract text from note.txt' }))
    expect(await within(note).findByTestId('ocr-text')).toHaveTextContent('Invoice 42')
    expect(within(note).getByText(/provider local-stub/)).toBeInTheDocument()

    const pdf = await row('scan.pdf')
    await user.click(within(pdf).getByRole('button', { name: 'Extract text from scan.pdf' }))
    expect(await within(pdf).findByText(/not supported by the text extraction provider/)).toBeInTheDocument()
    expect(within(pdf).queryByTestId('ocr-text')).not.toBeInTheDocument()
  })
})
