import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { describe, expect, it, vi } from 'vitest'
import { renderInWorkspace, signInAs, WS } from '@/test/utils'
import { CaseModelDesigner } from './CaseModelDesigner'
import { CasePlan } from './CasePlan'

describe('drawable case canvas', () => {
  it('draws the plan, adds items from the toolbar, edits the selected item and generates a diagram', async () => {
    signInAs('dana.designer')
    const apply = vi.fn(), user = userEvent.setup()
    const { container } = renderInWorkspace(<CaseModelDesigner ws={WS} caseKey="canvas-case" name="Canvas case" onApply={apply} disabled={false} />)
    expect(await screen.findByTestId('case-canvas')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Canvas view' })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findByTestId('canvas-task1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add milestone' }))
    await user.click(screen.getByRole('button', { name: 'Add stage' }))
    expect(await screen.findByTestId('canvas-item1')).toBeInTheDocument()
    // Selecting a stage makes the toolbar add inside it.
    fireEvent.click(screen.getByTestId('canvas-item2'))
    await user.click(await screen.findByRole('button', { name: 'Add human task to New stage' }))
    expect(await screen.findByTestId('canvas-item3')).toBeInTheDocument()
    const chooseGroup = async (id: string) => {
      fireEvent.click(screen.getByTestId(`canvas-${id}`))
      const group = within(screen.getByRole('complementary', { name: 'Selected plan item' })).getByLabelText(`Eligible group for ${id}`)
      await waitFor(() => expect(group.querySelectorAll('option').length).toBeGreaterThan(1))
      await user.selectOptions(group, group.querySelectorAll('option')[1]!.value)
    }
    await chooseGroup('task1')
    await chooseGroup('item3')
    fireEvent.click(screen.getByTestId('canvas-item1'))
    await user.selectOptions(within(screen.getByRole('complementary', { name: 'Selected plan item' })).getByLabelText('Start after for item1'), 'task1')
    await user.click(screen.getByRole('button', { name: 'Use generated CMMN' }))
    const xml = apply.mock.lastCall?.[0] as string
    expect(xml).toContain('<cmmndi:CMMNDI>')
    expect(xml).toContain('sourceCMMNElementRef="node_task1_item" targetCMMNElementRef="node_item1_entry"')
    expect(xml).toContain('<stage id="node_item2" name="New stage" autoComplete="false"><planItem id="node_item3_item"')
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map(violation => violation.id)).toEqual([])
  })

  it('adds a workflow task that needs a workflow and a discretionary item that needs planners', async () => {
    signInAs('dana.designer')
    const apply = vi.fn(), user = userEvent.setup()
    renderInWorkspace(<CaseModelDesigner ws={WS} caseKey="wf-case" name="Workflow case" onApply={apply} disabled={false} dataFields={[{ key: 'title', label: 'Title', type: 'text', required: false }]} />)
    await user.click(await screen.findByRole('button', { name: 'Form view' }))
    await user.click(screen.getByLabelText(/Discretionary task1/))
    expect(screen.getByRole('group', { name: /Who may plan/ })).toBeInTheDocument()
    expect(screen.queryByLabelText('Start after for task1')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Remove task1' }))
    await user.click(screen.getByRole('button', { name: 'Add workflow task' }))
    expect(await screen.findByLabelText('Workflow for item1')).toBeInTheDocument()
    expect(screen.queryByLabelText('Manual activation item1')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use generated CMMN' }))
    expect(await screen.findByText(/Choose the workflow that/)).toBeInTheDocument()
  })

  it('imports a CMMN file into the designer and reports what was left out', async () => {
    signInAs('dana.designer')
    const imported = vi.fn(), user = userEvent.setup()
    renderInWorkspace(<CaseModelDesigner ws={WS} caseKey="" name="" onApply={vi.fn()} onImported={imported} disabled={false} />)
    const file = new File([`<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn"><case id="foreign-case" name="Foreign"><casePlanModel id="plan"><planItem id="first" name="First" definitionRef="t1"/><planItem id="clock" definitionRef="timer"/><humanTask id="t1" flowable:candidateGroups="managers"/><timerEventListener id="timer"/></casePlanModel></case></definitions>`], 'foreign.cmmn', { type: 'application/xml' })
    await user.upload(await screen.findByLabelText('CMMN file to import into the designer'), file)
    const report = await screen.findByRole('region', { name: 'Import report' })
    expect(within(report).getByText(/1 left out/)).toBeInTheDocument()
    expect(within(report).getByText('case[foreign-case]/casePlanModel[plan]/timerEventListener[timer]')).toBeInTheDocument()
    expect(imported).toHaveBeenCalledWith('foreign-case', 'Foreign')
    expect(await screen.findByTestId('canvas-first')).toBeInTheDocument()
  })
})

describe('case plan planning and workflow status', () => {
  it('lets a manager plan the discretionary item and shows the workflow task run', async () => {
    signInAs('mani.manager')
    const user = userEvent.setup()
    renderInWorkspace(<CasePlan ws={WS} instanceId="77777777-7777-4777-8777-777777777702" version={1} busy={false} onPending={() => {}} />)
    await user.click(await screen.findByRole('button', { name: 'Show case plan' }))
    expect(await screen.findByText('Discretionary item')).toBeInTheDocument()
    expect(screen.getByText(/Workflow running/)).toHaveTextContent('ORQ-1042')
    await user.click(screen.getByRole('button', { name: 'Plan into case' }))
    await user.type(screen.getByLabelText('Reason'), 'Specialist view needed')
    await user.click(screen.getByRole('button', { name: 'Confirm plan change' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Confirm plan change' })).not.toBeInTheDocument())
  })
})
