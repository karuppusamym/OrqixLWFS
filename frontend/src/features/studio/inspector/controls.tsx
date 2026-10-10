import { durationPreview } from '@/lib/duration'
import { useState, type ComponentProps, type ReactNode } from 'react'
import { Input, Textarea } from '@/components/ui/input'
import { cn } from '@/lib/cn'

/**
 * Text inputs for the inspector. They keep a local draft and commit on blur or Enter, so typing a label creates
 * one undo entry, not one per keystroke, and native text undo keeps working while the field has focus.
 */
type InputProps = Omit<ComponentProps<typeof Input>, 'value' | 'onChange' | 'onBlur' | 'defaultValue'>

export function CommitInput({ value, onCommit, onKeyDown, ...props }: InputProps & { value: string; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState(value)
  const [seen, setSeen] = useState(value)
  // An outside change (undo, rename elsewhere) replaces the draft.
  if (seen !== value) {
    setSeen(value)
    setDraft(value)
  }
  const commit = () => {
    if (draft !== value) onCommit(draft)
  }
  return (
    <Input
      {...props}
      value={draft}
      data-commit=""
      data-dirty={draft !== value ? '' : undefined}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        onKeyDown?.(e)
        if (e.key === 'Enter' && !e.defaultPrevented) {
          e.preventDefault()
          commit()
        }
      }}
    />
  )
}

type TextareaProps = Omit<ComponentProps<typeof Textarea>, 'value' | 'onChange' | 'onBlur' | 'defaultValue'>

export function CommitTextarea({ value, onCommit, ...props }: TextareaProps & { value: string; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState(value)
  const [seen, setSeen] = useState(value)
  if (seen !== value) {
    setSeen(value)
    setDraft(value)
  }
  return (
    <Textarea
      {...props}
      value={draft}
      data-commit=""
      data-dirty={draft !== value ? '' : undefined}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        if (draft !== value) onCommit(draft)
      }}
    />
  )
}

/** Number input that commits a parsed number (or undefined when emptied) on blur or Enter. */
export function CommitNumber({
  value,
  onCommit,
  integer,
  ...props
}: Omit<InputProps, 'type'> & { value: number | undefined; onCommit: (value: number | undefined) => void; integer?: boolean }) {
  const text = value === undefined ? '' : String(value)
  const [draft, setDraft] = useState(text)
  const [seen, setSeen] = useState(text)
  const [invalid, setInvalid] = useState(false)
  if (seen !== text) {
    setSeen(text)
    setDraft(text)
    setInvalid(false)
  }
  const commit = () => {
    const trimmed = draft.trim()
    if (trimmed === text) return
    if (trimmed === '') {
      setInvalid(false)
      onCommit(undefined)
      return
    }
    const ok = integer ? /^-?\d+$/.test(trimmed) : /^-?\d+(\.\d+)?$/.test(trimmed)
    if (!ok) {
      setInvalid(true)
      return
    }
    setInvalid(false)
    onCommit(Number(trimmed))
  }
  return (
    <Input
      {...props}
      value={draft}
      data-commit=""
      data-dirty={draft.trim() !== text ? '' : undefined}
      inputMode={integer ? 'numeric' : 'decimal'}
      aria-invalid={invalid || props['aria-invalid'] ? true : undefined}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          commit()
        }
      }}
    />
  )
}

/** A titled group of inspector fields. */
export function InspectorSection({ title, children, className }: { title?: string; children: ReactNode; className?: string }) {
  return (
    <section className={cn('space-y-4', className)}>
      {title ? <h3 className="text-xs tracking-wide text-muted-foreground uppercase">{title}</h3> : null}
      {children}
    </section>
  )
}

/** Advanced settings stay collapsed until asked for. */
export function Advanced({ children, label = 'Advanced', open }: { children: ReactNode; label?: string; open?: boolean }) {
  return (
    <details className="rounded-control border border-border" open={open}>
      <summary className="cursor-pointer px-3 py-2 text-sm font-semibold text-primary-text">{label}</summary>
      <div className="space-y-4 border-t border-border p-3">{children}</div>
    </details>
  )
}

/** Plain-language reading of a duration, announced politely as it changes ("Waits 2 days."). */
export function DurationPreview({ text, verb }: { text: string; verb: 'waits' | 'allows' }) {
  const preview = durationPreview(text, verb)
  return <p role="status" data-testid="duration-preview" className={preview.ok ? 'text-sm text-muted-foreground' : 'text-sm text-danger'}>{preview.text}</p>
}
