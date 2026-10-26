/**
 * @mentions in the step and connection discussion (CMP01, docs/build/S87). Pure text helpers: finding the "@name" being
 * typed, inserting a chosen person, deciding which chosen people are still mentioned in the text, and splitting a posted
 * comment into text and mention parts for display. The server is the authority: it keeps a mention only for a person
 * who can read the workflow and whose "@Display Name" is in the text.
 */

export interface MentionRef {
  id: string
  displayName: string
}

/** At most this many people per comment (the server refuses more). */
export const MAX_MENTIONS = 10
const MAX_QUERY = 40

/**
 * The mention being typed at the caret: an "@" at the start or after whitespace, followed by up to 40 characters on the
 * same line that neither start nor end with a space (a finished name ends the suggestion). Returns where the "@" is and what follows it, or null.
 */
export function activeMention(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret)
  const at = before.lastIndexOf('@')
  if (at < 0) return null
  if (at > 0 && !/\s/.test(before[at - 1]!)) return null
  const query = before.slice(at + 1)
  if (query.length > MAX_QUERY || /[\n\r@]/.test(query) || query.startsWith(' ') || /\s$/.test(query)) return null
  return { start: at, query }
}

/** Replaces the typed "@query" with "@Display Name " and returns the new text and caret position. */
export function insertMention(text: string, start: number, caret: number, name: string): { text: string; caret: number } {
  const inserted = `@${name} `
  const after = text.slice(caret).replace(/^ /, '')
  return { text: text.slice(0, start) + inserted + after, caret: start + inserted.length }
}

/** The chosen people whose "@Display Name" is still in the text (case-insensitive), without duplicates, at most 10. */
export function keptMentions(text: string, chosen: readonly MentionRef[]): MentionRef[] {
  const lower = text.toLowerCase()
  const seen = new Set<string>()
  const kept: MentionRef[] = []
  for (const person of chosen) {
    if (seen.has(person.id) || !lower.includes(`@${person.displayName.toLowerCase()}`)) continue
    seen.add(person.id)
    kept.push(person)
    if (kept.length >= MAX_MENTIONS) break
  }
  return kept
}

export type BodyPart = { kind: 'text'; text: string } | { kind: 'mention'; text: string; person: MentionRef }

/**
 * Splits a posted comment into plain text and the "@Display Name" parts of its stored mentions. Only stored mentions
 * are highlighted, so typing "@someone" by hand never looks like a real mention. Longer names win over shorter ones.
 */
export function splitMentions(body: string, mentions: readonly MentionRef[]): BodyPart[] {
  const people = [...mentions].filter((m) => m.displayName.trim() !== '').sort((a, b) => b.displayName.length - a.displayName.length)
  if (people.length === 0) return body ? [{ kind: 'text', text: body }] : []
  const lower = body.toLowerCase()
  const parts: BodyPart[] = []
  let plain = ''
  let i = 0
  while (i < body.length) {
    const person = body[i] === '@' ? people.find((p) => lower.startsWith(`@${p.displayName.toLowerCase()}`, i)) : undefined
    if (person) {
      if (plain) parts.push({ kind: 'text', text: plain })
      plain = ''
      const length = person.displayName.length + 1
      parts.push({ kind: 'mention', text: body.slice(i, i + length), person })
      i += length
    } else {
      plain += body[i]
      i += 1
    }
  }
  if (plain) parts.push({ kind: 'text', text: plain })
  return parts
}
