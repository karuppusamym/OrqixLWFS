import { useEffect, useId, useRef, useState } from 'react'
import { Input } from '@/components/ui/input'
import { searchLookupOptions, type LookupOptions } from '@/api/guest'
import { isProblemError } from '@/api/problem'
import type { FieldDef } from '@/lib/form/schema'

export interface LookupContext {
  workspaceId: string
  workflowId: string
  releaseId?: string
}

type Option = LookupOptions['options'][number]

/**
 * Search-as-you-type for a lookup question (docs/build/S140, CG-F02): an ARIA combobox. The value is the option's value;
 * typing clears it until an option is chosen again. The server checks the chosen value again on submit.
 */
export function LookupField({ field, value, onChange, id, describedBy, invalid, disabled, context }: {
  field: FieldDef
  value: string
  onChange: (value: string) => void
  id: string
  describedBy?: string
  invalid?: boolean
  disabled?: boolean
  context?: LookupContext
}) {
  const listId = useId()
  const [text, setText] = useState(value)
  const [, setChosen] = useState<Option | null>(null)
  const [options, setOptions] = useState<Option[]>([])
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const [status, setStatus] = useState('')
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const min = field.schema.lookup?.minSearchLength ?? 2

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  const search = (next: string) => {
    if (timer.current) clearTimeout(timer.current)
    if (!context) { setStatus('Options are loaded when the form is opened to start a request.'); return }
    if ([...next.trim()].length < min) { setOptions([]); setOpen(false); setStatus(min > 0 ? `Type at least ${min} characters to search.` : ''); return }
    const controller = new AbortController()
    timer.current = setTimeout(() => {
      setStatus('Searching…')
      searchLookupOptions(context.workspaceId, context.workflowId, field.key, next.trim(), context.releaseId, controller.signal)
        .then((result) => {
          setOptions(result.options)
          setOpen(true)
          setActive(result.options.length ? 0 : -1)
          setStatus(result.options.length === 0 ? 'No matches.' : `${result.options.length} option${result.options.length === 1 ? '' : 's'}${result.truncated ? '; type more to narrow the list' : ''}.`)
        })
        .catch((error: unknown) => {
          setOptions([]); setOpen(false)
          setStatus(isProblemError(error) && error.status === 429 ? 'Too many searches. Wait a moment, then type again.' : 'The options cannot be loaded right now. Try again shortly.')
        })
    }, 250)
  }

  const choose = (option: Option) => {
    setChosen(option)
    setText(option.detail ? `${option.label} (${option.detail})` : option.label)
    setOpen(false)
    setStatus(`${option.label} chosen.`)
    onChange(option.value)
  }

  return (
    <div className="relative">
      <Input
        id={id}
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={open && active >= 0 ? `${listId}-${active}` : undefined}
        aria-describedby={[describedBy, `${listId}-status`].filter(Boolean).join(' ')}
        aria-invalid={invalid || undefined}
        aria-required={field.required || undefined}
        autoComplete="off"
        disabled={disabled}
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          if (value) onChange('')
          setChosen(null)
          search(e.target.value)
        }}
        onKeyDown={(e) => {
          if (!open || options.length === 0) return
          if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(options.length - 1, a + 1)) }
          if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(0, a - 1)) }
          if (e.key === 'Enter' && active >= 0) { e.preventDefault(); choose(options[active]!) }
          if (e.key === 'Escape') setOpen(false)
        }}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      {open && options.length > 0 ? (
        <ul id={listId} role="listbox" aria-label={`${field.label} options`} className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-card border border-border bg-background shadow">
          {options.map((option, index) => (
            <li key={option.value} id={`${listId}-${index}`} role="option" aria-selected={index === active}
              className={`cursor-pointer px-3 py-2 text-sm ${index === active ? 'bg-muted' : ''}`}
              onMouseDown={(e) => { e.preventDefault(); choose(option) }}>
              <span className="font-medium">{option.label}</span>
              {option.detail ? <span className="block text-xs text-muted-foreground">{option.detail}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
      <p id={`${listId}-status`} className="sr-only" aria-live="polite">{status}</p>
    </div>
  )
}
