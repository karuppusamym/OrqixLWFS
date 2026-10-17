/**
 * Money handling (docs/build/S7-frontend.md section 3).
 *
 * - Inputs keep the typed string.
 * - The typed string is converted to a JSON number only when the request is submitted.
 * - The UI never performs arithmetic on amounts.
 * - Display uses Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).
 */

/** Valid typed amount: up to 9 integer digits and at most 2 decimals. */
export const MONEY_PATTERN = /^\d{1,9}(\.\d{1,2})?$/

export type MoneyParse = { ok: true; value: number } | { ok: false; reason: 'empty' | 'format' }

/** Parse a typed amount. This is a conversion, not arithmetic: the digits are never rounded or summed. */
export function parseMoney(typed: string): MoneyParse {
  const text = typed.trim()
  if (text === '') return { ok: false, reason: 'empty' }
  if (!MONEY_PATTERN.test(text)) return { ok: false, reason: 'format' }
  return { ok: true, value: Number(text) }
}

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })

/** Format an amount (number or typed string) as USD. Returns the input unchanged when it is not numeric. */
export function formatUsd(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return ''
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return String(value)
  return usd.format(n)
}

/** Plain number formatting with grouping, used for non-money numeric answers. */
export function formatNumber(value: number | string): string {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return String(value)
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 20 }).format(n)
}
