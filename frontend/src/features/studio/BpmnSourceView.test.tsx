import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { renderAppReady, signInAs, WS } from '@/test/utils'

/** UI03 BPMN source (docs/build/S56) against the mock double (which only applies name edits). */
const EDIT = `/w/${WS}/workflows/${PURCHASE_WORKFLOW_ID}/edit`
const workflow = () => db.workflows.get(PURCHASE_WORKFLOW_ID)!
const draft = () => workflow().drafts.at(-1)!

async function openBpmn(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText(/Draft revision/)
  await user.click(screen.getByRole('tab', { name: 'Source' }))
  await user.click(screen.getByRole('button', { name: 'BPMN' }))
  return (await screen.findByTestId('bpmn-text')) as HTMLTextAreaElement
}

describe('BPMN source', () => {
  it('shows the compiled BPMN, checks an edit, applies it as the next revision and reloads', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    const box = await openBpmn(user)
    const step = draft().definition.nodes.find((n) => n.type === 'approval')!
    expect(box.value).toContain(`<process id="${draft().definition.workflowKey}"`)
    expect(box).not.toHaveAttribute('readonly')
    expect(screen.getByTestId('bpmn-roundtrip')).toHaveTextContent(/without any difference/)
    expect(screen.getByTestId('bpmn-source')).toHaveTextContent(/Kept from the draft and not in BPMN: .*inputSchema/)
    const revision = draft().revision
    fireEvent.change(box, { target: { value: box.value.replace(`id="${step.id}" name="${step.label}"`, `id="${step.id}" name="Renamed in BPMN"`) } })
    await user.click(screen.getByRole('button', { name: 'Check BPMN' }))
    const ok = await screen.findByTestId('bpmn-ok')
    expect(ok).toHaveTextContent(`Changes: Step '${step.id}' (Renamed in BPMN)`)
    expect(draft().revision).toBe(revision)
    await user.click(screen.getByRole('button', { name: 'Apply BPMN to draft' }))
    await waitFor(() => expect(draft().revision).toBe(revision + 1))
    expect(draft().definition.nodes.find((n) => n.id === step.id)!.label).toBe('Renamed in BPMN')
    await waitFor(() => expect((screen.getByTestId('bpmn-text') as HTMLTextAreaElement).value).toContain('name="Renamed in BPMN"'))
    expect(screen.queryByTestId('bpmn-stale')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('save-state')).toHaveTextContent('Saved'))
  })

  it('refuses what the document cannot hold and leaves the draft untouched', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    const box = await openBpmn(user)
    const before = JSON.stringify(draft())
    const original = box.value
    fireEvent.change(box, { target: { value: original.replace('<startEvent', '<laneSet id="lanes"><lane id="l1"/></laneSet><startEvent') } })
    await user.click(screen.getByRole('button', { name: 'Apply BPMN to draft' }))
    expect(await screen.findByTestId('bpmn-problems')).toHaveTextContent(/Not supported.*Lanes/)
    fireEvent.change(box, { target: { value: original.replace(/<process id="[^"]*"/, '<process id="other"') } })
    await user.click(screen.getByRole('button', { name: 'Check BPMN' }))
    expect(await screen.findByTestId('bpmn-problems')).toHaveTextContent(/Wrong workflow \(other\)/)
    expect(JSON.stringify(draft())).toBe(before)
    await user.click(screen.getByRole('button', { name: 'Discard edits' }))
    expect(screen.queryByTestId('bpmn-problems')).not.toBeInTheDocument()
    expect((screen.getByTestId('bpmn-text') as HTMLTextAreaElement).value).toBe(original)
  })

  it('is read-only for people who cannot edit workflows', async () => {
    signInAs('vic.viewer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    const box = await openBpmn(user)
    expect(box).toHaveAttribute('readonly')
    expect(box.value).toContain('<process id=')
    expect(screen.queryByRole('button', { name: 'Check BPMN' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply BPMN to draft' })).not.toBeInTheDocument()
  })

  it('explains that a draft with blocking issues has no BPMN yet', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    await renderAppReady(EDIT)
    await screen.findByText(/Draft revision/)
    // The stored draft gets a blocker (the mock server compiles what is stored, not the editor's copy).
    const approval = draft().definition.nodes.find((n) => n.type === 'approval')!
    approval.config = { ...approval.config, candidateGroupRef: '' }
    await user.click(screen.getByRole('tab', { name: 'Source' }))
    await user.click(screen.getByRole('button', { name: 'BPMN' }))
    expect(await screen.findByTestId('bpmn-unavailable')).toHaveTextContent(/no BPMN yet/)
    expect(screen.queryByTestId('bpmn-text')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply BPMN to draft' })).not.toBeInTheDocument()
  })
})
