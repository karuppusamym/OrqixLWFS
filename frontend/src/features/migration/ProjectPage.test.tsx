import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderApp, signInAs, WS } from '@/test/utils'
import { db } from '@/mocks/state'
import { planProject } from '@/mocks/migration'
import { bpmn } from '@/mocks/migration.fixtures'
import { projectFileProblem } from './ProjectImportForm'

function bpmnFile(name: string, xml: string): File {
  const file = new File([xml], name, { type: 'application/xml' })
  Object.defineProperty(file, 'text', { value: async () => xml })
  return file
}

async function importProject(user: ReturnType<typeof userEvent.setup>, files: File[], name = 'Order handling') {
  await user.type(await screen.findByLabelText('Project name'), name)
  await user.upload(screen.getByLabelText(/BPMN files/), files)
  await user.click(screen.getByRole('button', { name: 'Analyze project' }))
}

/** Pretend the converted child was reviewed and published, which the server requires before its parent converts. */
function publish(key: string) {
  const workflow = [...db.workflows.values()].find((w) => w.key === key)
  expect(workflow, `workflow ${key}`).toBeDefined()
  workflow!.activeReleaseId = 'published-release'
}

describe('multi-file migration project', () => {
  it('analyzes several files, orders children first and converts them one file at a time with the mapping form', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    const { router } = renderApp(`/w/${WS}/migrations`)
    // Chosen parent-first on purpose: the order comes from the calls between the files.
    await importProject(user, [bpmnFile('parent.bpmn', bpmn('parent-process', 'child-process')), bpmnFile('child.bpmn', bpmn('child-process'))])
    await waitFor(() => expect(router.state.location.pathname).toMatch(/\/migrations\/projects\/[0-9a-f-]+$/))

    expect(await screen.findByRole('heading', { level: 1, name: 'Order handling' })).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Project files' })
    expect(within(table).getByRole('link', { name: 'child.bpmn' })).toBeInTheDocument()
    expect(within(table).getAllByText('Not converted yet')).toHaveLength(2)
    expect(within(table).getAllByText('child-process', { selector: 'td' })).toHaveLength(2) // the child's own process and the parent's call
    const order = within(screen.getByRole('list', { name: 'Conversion order' })).getAllByRole('listitem')
    expect(order.map((item) => item.textContent)).toEqual(['child.bpmn (next)', 'parent.bpmn'])
    expect(screen.queryByRole('list', { name: 'Project findings' })).not.toBeInTheDocument()

    // First the child, through the same report and mapping form as a single import.
    const next = await screen.findByRole('region', { name: 'Next file to convert' })
    expect(within(next).getByRole('heading', { level: 2, name: 'Next file: child.bpmn' })).toBeInTheDocument()
    expect(within(next).getByText(/Step 1 of 2/)).toBeInTheDocument()
    expect(screen.getByLabelText('Workflow key', { exact: true })).toHaveValue('child-process')
    await user.click(screen.getByRole('button', { name: 'Convert next file' }))
    expect(await screen.findByText('Converted child.bpmn')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Project files' })).getByText('Converted')).toBeInTheDocument()
    expect(screen.getByTestId('project-status')).toHaveTextContent('Converting')
    expect(screen.getByRole('link', { name: 'Review and publish' })).toBeInTheDocument()

    // The parent is next and must name the published child.
    expect(await screen.findByRole('heading', { level: 2, name: 'Next file: parent.bpmn' })).toBeInTheDocument()
    const children = screen.getByLabelText('Child workflow mappings (JSON)')
    await user.clear(children)
    await user.click(children)
    await user.paste(JSON.stringify({ call1: { workflowRef: 'child-process', inputMapping: {}, resultMapping: {} } }))
    await user.click(screen.getByRole('button', { name: 'Convert next file' }))
    expect(await screen.findByText("call1: Child workflow 'child-process' has no published release.")).toBeInTheDocument()
    expect(screen.getByTestId('project-status')).toHaveTextContent('Converting')

    publish('child-process')
    await user.click(screen.getByRole('button', { name: 'Convert next file' }))
    expect(await screen.findByText('Every file is converted')).toBeInTheDocument()
    expect(screen.getByTestId('project-status')).toHaveTextContent('All files converted')
    expect(screen.queryByRole('button', { name: 'Convert next file' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Project files' })).getAllByText('Converted')).toHaveLength(2)
    expect([...db.migrations.values()].filter((m) => m.status === 'converted')).toHaveLength(2)
  })

  it('shows project-level findings and offers no conversion while a dependency is missing', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations`)
    await importProject(user, [bpmnFile('parent.bpmn', bpmn('parent-process', 'absent-process'))], 'Incomplete')
    const findings = await screen.findByRole('list', { name: 'Project findings' })
    expect(findings).toHaveTextContent('parent.bpmn')
    expect(findings).toHaveTextContent("Called process 'absent-process' is not included in this project.")
    expect(findings).toHaveTextContent('PROJECT_MISSING_DEPENDENCY')
    expect(screen.getByText('Project findings block conversion')).toBeInTheDocument()
    expect(screen.getByTestId('project-status')).toHaveTextContent('Blocked')
    expect(screen.queryByRole('region', { name: 'Next file to convert' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Convert next file' })).not.toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Conversion order' })).not.toBeInTheDocument()
  })

  it('reports cyclic and ambiguous projects instead of guessing an order', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations`)
    await importProject(user, [bpmnFile('a.bpmn', bpmn('a-process', 'b-process')), bpmnFile('b.bpmn', bpmn('b-process', 'a-process')), bpmnFile('twin.bpmn', bpmn('a-process'))], 'Tangled')
    const findings = await screen.findByRole('list', { name: 'Project findings' })
    expect(findings).toHaveTextContent('PROJECT_DUPLICATE_PROCESS')
    expect(findings).toHaveTextContent('PROJECT_DEPENDENCY_CYCLE')
    expect(screen.queryByRole('button', { name: 'Convert next file' })).not.toBeInTheDocument()
  })

  it('shows the server’s conflict when the conversion order moved on and keeps the form usable', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations`)
    await importProject(user, [bpmnFile('only.bpmn', bpmn('only-process'))], 'Single')
    server.use(http.post('*/api/v1/workspaces/:ws/migrations/projects/:id/convert-next', () => HttpResponse.json({ code: 'WORKFLOW_VERSION_CONFLICT', status: 409, title: 'Conflict', detail: 'Project conversion order changed or every file is already converted.' }, { status: 409 })))
    await user.click(await screen.findByRole('button', { name: 'Convert next file' }))
    expect(await screen.findByText('Project conversion order changed or every file is already converted.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Convert next file' })).toBeEnabled()
  })

  it('lets a viewer read the project but not convert it', async () => {
    signInAs('vic.viewer')
    const project = planProject(WS, 'Viewer test', [{ fileName: 'only.bpmn', content: bpmn('only-process') }])
    renderApp(`/w/${WS}/migrations/projects/${project.id}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Viewer test' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Next file to convert' })).toHaveTextContent('converting needs permission to edit workflows')
    expect(screen.queryByRole('button', { name: 'Convert next file' })).not.toBeInTheDocument()
  })

  it('hides the project form from people who cannot edit workflows', async () => {
    signInAs('vic.viewer')
    renderApp(`/w/${WS}/migrations`)
    await screen.findByRole('heading', { level: 1, name: 'Import workflow' })
    expect(screen.queryByRole('form', { name: 'Import a project' })).not.toBeInTheDocument()
  })

  it('checks the file limits before reading anything', () => {
    const file = (name: string, size = 10) => Object.defineProperty(new File(['x'], name), 'size', { value: size })
    expect(projectFileProblem([])).toBeNull()
    expect(projectFileProblem([file('a.bpmn')])).toBeNull()
    expect(projectFileProblem(Array.from({ length: 11 }, (_, i) => file(`f${i}.bpmn`)))).toBe('Choose at most 10 files.')
    expect(projectFileProblem([file('big.bpmn', 1_048_577)])).toBe('Each file must be at most 1 MiB.')
    expect(projectFileProblem(Array.from({ length: 5 }, (_, i) => file(`f${i}.bpmn`, 1_000_000)))).toBe('The files together must be at most 4 MiB.')
    expect(projectFileProblem([file('a.bpmn'), file('a.bpmn')])).toBe('Each file needs a different name.')
  })

  it('is operable with the keyboard: fields in reading order, the button waits for a name and a file', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup()
    renderApp(`/w/${WS}/migrations`)
    const name = await screen.findByLabelText('Project name')
    await user.click(name)
    await user.keyboard('Keyboard')
    await user.tab()
    expect(screen.getByLabelText(/BPMN files/)).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Analyze project' })).toBeDisabled()
    await user.upload(screen.getByLabelText(/BPMN files/), bpmnFile('only.bpmn', bpmn('only-process')))
    expect(screen.getByRole('list', { name: 'Selected files' })).toHaveTextContent('only.bpmn')
    await user.tab()
    expect(screen.getByRole('button', { name: 'Analyze project' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('heading', { level: 1, name: 'Keyboard' })).toBeInTheDocument()
  })
})
