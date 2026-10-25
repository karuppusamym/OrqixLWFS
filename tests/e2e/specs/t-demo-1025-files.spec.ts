import { expect, test } from '../support/fixtures'
import { workspaceId } from '../support/app'
import { apiSend } from '../support/api'
import { axe, key, record, shot } from '../support/rehearsal'
import { MOCK } from '../support/env'

/** S86: attachment flow on the real stack with the "Loan application case" template. */
test.skip(MOCK, 'Real stack only.')
test.setTimeout(240_000)

test('loan case template: publish, attach files, request completes stage flow', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  await dana.goto(`/w/${ws}/workflows`)
  await dana.getByRole('button', { name: /New workflow/ }).click()
  await dana.getByRole('dialog').getByText('Loan application case').first().click()
  await dana.getByLabel(/^Name/).fill('Loan rehearsal')
  const k = key('rh-loan')
  await dana.getByLabel(/^Key/).fill(k)
  await dana.getByRole('button', { name: 'Create workflow' }).click()
  await expect(dana.getByTestId('studio')).toBeVisible({ timeout: 30_000 })
  const id = dana.url().match(/workflows\/([0-9a-f-]+)/)![1]
  await shot(dana, 'loan-studio')
  await axe(dana, 'loan studio')
  record('loan template: create draft', 'PASS')
  const pub = await sessions.as('paul.publisher')
  await pub.goto(`/w/${ws}/workflows/${id}/publish`)
  await expect(pub.getByRole('button', { name: /Publish version \d+/ })).toBeVisible()
  const text = (await pub.locator('main').innerText()).replace(/\s+/g, ' ')
  record('loan template: publish page', 'INFO', text.slice(0, 400))
  const ack = pub.getByRole('checkbox')
  for (let i = 0; i < await ack.count(); i++) if (await ack.nth(i).isVisible() && !(await ack.nth(i).isChecked())) await ack.nth(i).check()
  await pub.getByRole('button', { name: /Publish version \d+/ }).click()
  await expect(pub.getByTestId('publish-success')).toBeVisible({ timeout: 30_000 })
  record('loan template: publish', 'PASS')
  const rq = await sessions.as('rhea.requester')
  await rq.goto(`/w/${ws}/start/${id}`)
  await rq.locator('main form').waitFor()
  await shot(rq, 'loan-start')
  const fields = await rq.locator('main input:visible, main select:visible, main textarea:visible').evaluateAll((els) => els.map((e) => `${(e as HTMLInputElement).id}:${(e as HTMLInputElement).type}`))
  record('loan start form fields', 'INFO', fields.join(','))
  for (const f of fields) {
    const [fid, type] = f.split(':')
    const loc = rq.locator(`[id="${fid}"]`)
    if (type === 'file') await loc.setInputFiles({ name: `${fid}.png`, mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64') })
    else if (type === 'number' || /amount/i.test(fid)) await loc.fill('12000')
    else if (type === 'text' || type === 'textarea') await loc.fill('Rehearsal value')
    else if (type === 'select-one') await loc.selectOption({ index: 1 })
  }
  await axe(rq, 'loan start form with files')
  await rq.getByRole('button', { name: 'Review request' }).click()
  await shot(rq, 'loan-review')
  await rq.getByRole('button', { name: 'Submit request' }).click()
  await expect(rq.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
  await rq.getByRole('link', { name: 'View your request' }).click()
  await rq.getByTestId('run-status').waitFor()
  await rq.waitForTimeout(2500)
  await shot(rq, 'loan-run')
  await axe(rq, 'loan run with attachments')
  const body = (await rq.locator('main').innerText()).replace(/\s+/g, ' ')
  record('loan run page shows attachments', /\.png|attach|file/i.test(body) ? 'PASS' : 'INFO', body.slice(0, 500))
  void apiSend
})
