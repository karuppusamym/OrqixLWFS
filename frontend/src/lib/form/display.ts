import { formatNumber, formatUsd } from '../money'
import type { FieldDef } from './schema'

/** Human display of a JSON value of a form field: money as USD, booleans as Yes/No, empty as an em dash. */
export function displayJsonValue(field: FieldDef | undefined, value: unknown): string {
  if (value === undefined || value === null || value === '') return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return field?.isMoney ? formatUsd(value) : formatNumber(value)
  if (typeof value === 'string') return field?.isMoney ? formatUsd(value) : value
  return JSON.stringify(value)
}

/** Fallback when no schema is available: money is recognized from the label ("Amount (USD)"). */
export function looksLikeMoneyLabel(label: string | undefined): boolean {
  return !!label && /\b(USD|US\$)\b|\bamount\b/i.test(label)
}

export function displayLooseValue(label: string | undefined, value: unknown): string {
  if (typeof value === 'number' && looksLikeMoneyLabel(label)) return formatUsd(value)
  return displayJsonValue(undefined, value)
}
