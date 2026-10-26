import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import * as endpoints from '@/api/endpoints'
import { Textarea } from '@/components/ui/input'
import { cn } from '@/lib/cn'
import { activeMention, insertMention, keptMentions, MAX_MENTIONS, type MentionRef } from './model/mentions'

export interface MentionDraft {
  text: string
  mentions: MentionRef[]
}

export const emptyDraft: MentionDraft = { text: '', mentions: [] }

/** The people of a draft that are still mentioned in its text (what the composer sends). */
export const mentionIds = (draft: MentionDraft) => keptMentions(draft.text, draft.mentions).map((m) => m.id)

/**
 * Discussion composer with @mention suggestions (CMP01, docs/build/S87). Typing "@" and part of a name asks the server for
 * people who can read this workflow (never anyone else); Arrow keys move through the suggestions, Enter or Tab picks one,
 * Escape closes the list. The textarea stays a normal text box for screen readers, with aria-autocomplete,
 * aria-controls and aria-activedescendant pointing at the suggestion list, and a polite status of how many people match.
 */
export function MentionTextarea({ wsId, workflowId, label, value, onChange, rows, placeholder }: {
  wsId: string
  workflowId: string
  label: string
  value: MentionDraft
  onChange: (next: MentionDraft) => void
  rows?: number
  placeholder?: string
}) {
  const ref = useRef<HTMLTextAreaElement>(null)
  const listId = useId()
  const hintId = useId()
  const [caret, setCaret] = useState(0)
  const [active, setActive] = useState(0)
  const [dismissedAt, setDismissedAt] = useState<number | null>(null)
  const typed = activeMention(value.text, caret)
  const open = typed !== null && dismissedAt !== typed.start && keptMentions(value.text, value.mentions).length < MAX_MENTIONS
  const query = typed?.query ?? ''
  const [debounced, setDebounced] = useState(query)
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query), 200)
    return () => window.clearTimeout(timer)
  }, [query])

  const candidates = useQuery({
    queryKey: ['ws', wsId, 'workflow', workflowId, 'mention-candidates', debounced],
    queryFn: () => endpoints.listWorkflowMentionCandidates(wsId, workflowId, debounced),
    enabled: open,
    staleTime: 30_000,
    retry: false,
  })
  const items = open ? (candidates.data?.items ?? []) : []
  const highlighted = items.length > 0 ? Math.min(active, items.length - 1) : -1

  const pick = (person: MentionRef) => {
    if (!typed) return
    const next = insertMention(value.text, typed.start, caret, person.displayName)
    onChange({ text: next.text, mentions: [...value.mentions.filter((m) => m.id !== person.id), person] })
    setActive(0)
    setCaret(next.caret)
    window.requestAnimationFrame(() => {
      ref.current?.focus()
      ref.current?.setSelectionRange(next.caret, next.caret)
    })
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!open) return
    if (event.key === 'Escape') {
      event.preventDefault()
      setDismissedAt(typed?.start ?? null)
      return
    }
    if (items.length === 0) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActive((highlighted + 1) % items.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((highlighted - 1 + items.length) % items.length)
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      pick(items[highlighted]!)
    }
  }

  const sync = (target: HTMLTextAreaElement) => setCaret(target.selectionStart ?? target.value.length)
  const optionId = (i: number) => `${listId}-option-${i}`

  return (
    <div className="relative">
      <Textarea
        ref={ref}
        aria-label={label}
        aria-describedby={hintId}
        aria-autocomplete="list"
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open && highlighted >= 0 ? optionId(highlighted) : undefined}
        rows={rows}
        maxLength={2000}
        placeholder={placeholder}
        value={value.text}
        onChange={(e) => {
          onChange({ ...value, text: e.target.value })
          sync(e.target)
          setActive(0)
          if (dismissedAt !== null && activeMention(e.target.value, e.target.selectionStart ?? 0)?.start !== dismissedAt) setDismissedAt(null)
        }}
        onKeyDown={onKeyDown}
        onKeyUp={(e) => {
          if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) sync(e.currentTarget)
        }}
        onClick={(e) => sync(e.currentTarget)}
        onBlur={() => window.setTimeout(() => setDismissedAt(typed?.start ?? null), 150)}
        onFocus={() => setDismissedAt(null)}
      />
      <p id={hintId} className="mt-1 text-xs text-muted">Type @ to mention someone who can read this workflow.</p>
      {open ? (
        <div className="absolute inset-x-0 z-20 mt-1 rounded-md border border-border bg-surface shadow-md">
          <ul id={listId} role="listbox" aria-label="People to mention" className="max-h-56 overflow-y-auto py-1">
            {items.map((person, i) => (
              <li
                key={person.id}
                id={optionId(i)}
                role="option"
                aria-selected={i === highlighted}
                className={cn('cursor-pointer px-3 py-1.5 text-sm', i === highlighted && 'bg-accent-soft font-semibold outline outline-2 -outline-offset-2 outline-accent')}
                onMouseDown={(e) => {
                  e.preventDefault()
                  pick(person)
                }}
              >
                {i === highlighted ? <span aria-hidden="true">&rsaquo; </span> : null}
                {person.displayName}
              </li>
            ))}
          </ul>
          {items.length === 0 ? (
            <p className="px-3 py-2 text-xs text-muted">{candidates.isFetching || debounced !== query ? 'Looking for people…' : 'No one who can read this workflow matches.'}</p>
          ) : null}
        </div>
      ) : null}
      <span className="sr-only" role="status" aria-live="polite">
        {open && !candidates.isFetching && debounced === query ? `${items.length} ${items.length === 1 ? 'person' : 'people'} to mention` : ''}
      </span>
    </div>
  )
}
