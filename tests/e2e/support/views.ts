import { type Page } from '@playwright/test'
import { apiSend } from './api'

/** Fixtures for the UI02 view-library, application page and extension specs (S111, S114). */

type Field = { id: string; label: string; type: 'text' | 'number' | 'choice'; binding: string; required: boolean; options?: Array<{ value: string; label: string }> }

const choice = { options: ['OPS', 'IT', 'HR'].map((value) => ({ value, label: value })) }

/** The purchase template's four request fields, bound once each, in the given order of labels. */
export function purchaseFields(prefix = 'request'): Field[] {
  return [
    { id: 'title', label: 'What are you buying?', type: 'text', binding: `${prefix}.title`, required: true },
    { id: 'purpose', label: 'Business purpose', type: 'text', binding: `${prefix}.purpose`, required: true },
    { id: 'costCenter', label: 'Cost center', type: 'choice', binding: `${prefix}.costCenter`, required: true, ...choice },
    { id: 'amount', label: 'Amount (USD)', type: 'number', binding: `${prefix}.amount`, required: true },
  ]
}

/** A request view a page can submit and a workflow form can pin: one section, every purchase field. */
export function requestView(section = 'Purchase', actions: Array<'submit' | 'saveDraft'> = ['submit']) {
  return {
    schemaVersion: 1, kind: 'request',
    sections: [{ id: 'details', label: section, columns: 2, fields: purchaseFields() }],
    actions: actions.map((type) => ({ id: type, label: type === 'submit' ? 'Submit' : 'Save draft', type })),
  }
}

/** A read-only approval view with Approve and Reject. */
export function approvalView() {
  return {
    schemaVersion: 1, kind: 'approval',
    sections: [{ id: 'summary', label: 'Request summary', columns: 2, fields: [
      { id: 'title', label: 'Item', type: 'text', binding: 'request.title', required: true },
      { id: 'amount', label: 'Amount', type: 'number', binding: 'request.amount', required: true },
    ] }],
    actions: [{ id: 'approve', label: 'Approve', type: 'approve' }, { id: 'reject', label: 'Reject', type: 'reject' }],
  }
}

/** Create a library view or page as the signed-in designer; returns its id. */
export async function createView(page: Page, ws: string, name: string, definition: unknown): Promise<string> {
  const created = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/views`, { name, definition })
  if (created.status !== 201) throw new Error(`create view ${name}: ${created.status} ${JSON.stringify(created.body)}`)
  return created.body.id
}

/** Save a new draft revision as the signed-in designer. */
export async function saveViewDraft(page: Page, ws: string, id: string, expectedRevision: number, name: string, definition: unknown): Promise<void> {
  const saved = await apiSend(page, 'PUT', `/workspaces/${ws}/views/${id}/draft`, { expectedRevision, name, definition })
  if (saved.status !== 200) throw new Error(`save view ${id}: ${saved.status} ${JSON.stringify(saved.body)}`)
}

/** Publish a draft revision as the signed-in publisher; returns the new version. */
export async function publishView(page: Page, ws: string, id: string, expectedRevision: number): Promise<number> {
  const published = await apiSend<{ publishedVersion: number }>(page, 'POST', `/workspaces/${ws}/views/${id}/publish`, { expectedRevision })
  if (published.status !== 200) throw new Error(`publish view ${id}: ${published.status} ${JSON.stringify(published.body)}`)
  return published.body.publishedVersion
}

/** Pin a published page version to a route as the signed-in publisher; returns the route revision. */
export async function routePage(page: Page, ws: string, slug: string, expectedRevision: number, pageId: string, version: number): Promise<number> {
  const routed = await apiSend<{ revision: number }>(page, 'PUT', `/workspaces/${ws}/app-pages/${slug}`, { expectedRevision, viewRef: { id: pageId, version } })
  if (routed.status !== 200) throw new Error(`route ${slug}: ${routed.status} ${JSON.stringify(routed.body)}`)
  return routed.body.revision
}

/** A route slug: lower case, starts with a letter, at most 40 characters. */
export const slugOf = (name: string): string => name.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/^[^a-z]+/, 'p').slice(0, 38).replace(/-+$/, '') + 'x'
