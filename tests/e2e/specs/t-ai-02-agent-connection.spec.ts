import { expect, test } from '../support/fixtures'
import { createPurchaseWorkflow, selectStep, workspaceId } from '../support/app'

// Demo 2 (docs/build/S13-agents-mcp.md): an AI agent connection is listed with a badge, shows what it may do and can be
// tested with a sample input. Mock mode answers like the real backend with the fixtures of the demo vendor registry.
test('administrator sees the AI agent connection, its tools, and tests it with a sample input', async ({ sessions }) => {
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  await admin.goto(`/w/${ws}/settings`)
  await admin.getByRole('tab', { name: 'Connections' }).click()

  const agent = admin.getByRole('region', { name: /^Vendor risk assistant/ })
  await expect(agent.getByTestId('ai-agent-badge').first()).toHaveText(/AI agent/)
  await expect(agent.getByText('Uses AI (cost varies, read-only)').first()).toBeVisible()
  await expect(agent.getByTestId('agent-instructions')).toContainText('vendor_risk_lookup')
  await expect(agent.getByTestId('agent-tools')).toContainText('Vendor registry')
  // The ordinary connection carries no AI badge.
  await expect(admin.getByRole('region', { name: /^Procurement/ }).getByTestId('ai-agent-badge')).toHaveCount(0)

  await agent.getByLabel(/Vendor name/).fill('Acme Ltd')
  await agent.getByLabel(/Country/).fill('United Kingdom')
  await agent.getByLabel(/Annual spend/).fill('12000')
  await agent.getByRole('button', { name: 'Test the agent' }).click()
  await expect(agent.getByTestId('agent-test-summary')).toContainText(
    "Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)",
  )
  await expect(agent.getByTestId('agent-test-output')).toContainText('high')
})

test('a designer can choose the AI agent in a Call a system step and sees what it may do', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflow = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflow}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'procure')
  await designer.getByLabel(/^Connection/).selectOption({ label: 'Vendor risk assistant · AI agent' })
  await designer.getByLabel(/^Operation/).selectOption({ label: 'Assess vendor risk' })
  const spec = designer.getByTestId('agent-spec')
  await expect(spec).toContainText('Uses AI (cost varies, read-only)')
  await expect(spec.getByTestId('agent-tools')).toContainText('vendor_risk_lookup')
  await expect(spec.getByRole('textbox')).toHaveCount(0)
})
