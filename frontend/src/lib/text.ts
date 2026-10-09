/** Small text helpers shared by features. */

/** "amountPolicy" -> "Amount policy"; "finance-approvers" -> "Finance approvers" */
export function humanize(value: string): string {
  const spaced = value
    .replace(/[-_]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .trim()
    .toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** First eight characters of a UUID, used as a short request ID. */
export function shortId(id: string): string {
  return id.replace(/-/g, '').slice(0, 8).toUpperCase()
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0]?.charAt(0) ?? ''
  const last = parts.length > 1 ? (parts[parts.length - 1]?.charAt(0) ?? '') : ''
  return (first + last).toUpperCase()
}

/** "What are you buying?" -> "whatAreYouBuying" (matches the fieldKey pattern ^[a-z][A-Za-z0-9]{0,63}$). */
export function toFieldKey(label: string): string {
  const words = label
    .normalize('NFKD')
    .replace(/[^A-Za-z0-9\s]/g, ' ')
    .split(/\s+/)
    .filter(Boolean)
  if (words.length === 0) return 'field'
  const camel = words
    .map((w, i) => (i === 0 ? w.toLowerCase() : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join('')
  const key = /^[a-z]/.test(camel) ? camel : `field${camel.charAt(0).toUpperCase()}${camel.slice(1)}`
  return key.slice(0, 64)
}

/** "Purchase approval" -> "purchase-approval" (matches ^[a-z][a-z0-9-]{0,62}[a-z0-9]$ when long enough). */
export function toKey(name: string): string {
  const slug = name
    .normalize('NFKD')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
    .replace(/-+$/g, '')
  return /^[a-z]/.test(slug) ? slug : slug ? `w-${slug}` : ''
}

export const KEY_PATTERN = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/

/** Join with commas and "and": ["a","b","c"] -> "a, b and c". */
export function listInWords(items: string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`
}

/** Create a UUID even in insecure contexts where crypto.randomUUID is unavailable. */
export function uuid(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(bytes)
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
