import { humanize } from '../text'
import { displayJsonValue, displayLooseValue } from './display'
import type { FieldDef } from './schema'
import { fieldsForInput } from './visibility'

export interface RequestRow {
  key: string
  label: string
  value: string
}

/**
 * Request data in the order of the form: the pinned form's field order (`inputUi.order`, then schema order) when the
 * form is known, otherwise the order of `inputLabels` (the server sends it in form order, because the stored request
 * is a jsonb document whose keys PostgreSQL reorders), then any remaining keys. Never the order of the raw input.
 */
export function orderedRequestRows(input: Record<string, unknown>, fields: FieldDef[], labels: Record<string, string>): RequestRow[] {
  const rows: RequestRow[] = []
  const seen = new Set<string>()
  for (const f of fieldsForInput(fields, input)) {
    seen.add(f.key)
    // A form field the requester left empty still shows, as an em dash, so the request reads like the form did.
    rows.push({ key: f.key, label: f.label, value: displayJsonValue(f, input[f.key]) })
  }
  const extra = [...Object.keys(labels), ...Object.keys(input)].filter((k) => {
    if (seen.has(k) || !(k in input)) return false
    seen.add(k)
    return true
  })
  for (const k of extra) {
    const label = labels[k] ?? humanize(k)
    rows.push({ key: k, label, value: displayLooseValue(label, input[k]) })
  }
  return rows
}
