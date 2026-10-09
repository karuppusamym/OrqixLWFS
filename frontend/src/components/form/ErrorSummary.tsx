import { AlertCircle } from 'lucide-react'
import { forwardRef } from 'react'
import type { FieldDef } from '@/lib/form/schema'
import type { FieldErrors } from '@/lib/form/values'
import { fieldDomId } from './FormRenderer'

/**
 * Error summary shown on submit (docs/build/S7-frontend.md section 7). Each entry moves focus to its field.
 * The summary itself takes focus when it appears, so screen reader users hear the list.
 */
export const ErrorSummary = forwardRef<HTMLDivElement, { fields: FieldDef[]; errors: FieldErrors; idPrefix: string }>(function ErrorSummary(
  { fields, errors, idPrefix },
  ref,
) {
  const entries = fields.filter((f) => errors[f.key])
  if (entries.length === 0) return null
  return (
    <div ref={ref} tabIndex={-1} role="alert" className="rounded-card border border-danger-border bg-danger-soft p-4 text-sm outline-offset-2" data-testid="error-summary">
      <p className="flex items-center gap-2 font-semibold text-danger-foreground">
        <AlertCircle className="size-5" aria-hidden="true" />
        {entries.length === 1 ? 'There is 1 problem to fix' : `There are ${entries.length} problems to fix`}
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-9">
        {entries.map((f) => (
          <li key={f.key}>
            <a
              href={`#${fieldDomId(idPrefix, f.key)}`}
              className="font-medium text-danger-foreground"
              onClick={(event) => {
                event.preventDefault()
                const el = document.getElementById(fieldDomId(idPrefix, f.key))
                el?.focus()
                el?.scrollIntoView({ block: 'center' })
              }}
            >
              {f.label}: {errors[f.key]}
            </a>
          </li>
        ))}
      </ul>
    </div>
  )
})

/** Focus the first invalid field (or its first radio) after an error summary is shown. */
export function focusFirstInvalid(fields: FieldDef[], errors: FieldErrors, idPrefix: string): void {
  const first = fields.find((f) => errors[f.key])
  if (!first) return
  const el = document.getElementById(fieldDomId(idPrefix, first.key))
  const target = el?.matches('[role="radiogroup"]') ? el.querySelector<HTMLElement>('[role="radio"]') : el
  target?.focus()
}
