import { AlertCircle } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/cn'

export interface FieldIds {
  input: string
  description: string
  error: string
  describedBy: string | undefined
}

/**
 * Label + help text + error wiring in one place. Children render the control using the ids:
 * the label points at the control, help and error text are linked through aria-describedby.
 */
export function FormField({
  label,
  description,
  error,
  required,
  optionalText,
  id,
  className,
  children,
  hideLabel,
  descriptionPlacement = 'above',
}: {
  label: ReactNode
  description?: ReactNode
  error?: string | undefined
  /** Marks the field "required" in text, not by color alone. */
  required?: boolean
  optionalText?: boolean
  id?: string
  className?: string
  hideLabel?: boolean
  descriptionPlacement?: 'above' | 'below'
  children: (ids: FieldIds) => ReactNode
}) {
  const generated = useId()
  const base = id ?? generated
  const ids: FieldIds = {
    input: base,
    description: `${base}-desc`,
    error: `${base}-error`,
    describedBy: [description ? `${base}-desc` : null, error ? `${base}-error` : null].filter(Boolean).join(' ') || undefined,
  }
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label htmlFor={ids.input} className={cn(hideLabel && 'sr-only')}>
        {label}
        {required ? <>{' '}<span className="ml-0.5 text-xs font-normal text-muted-foreground">(required)</span></> : null}
        {optionalText ? <span className="ml-1.5 text-xs font-normal text-muted-foreground">(optional)</span> : null}
      </Label>
      {description && descriptionPlacement === 'above' ? (
        <p id={ids.description} className="text-sm text-muted-foreground">
          {description}
        </p>
      ) : null}
      {children(ids)}
      {description && descriptionPlacement === 'below' ? <p id={ids.description} className="text-sm text-muted-foreground">{description}</p> : null}
      {error ? (
        <p id={ids.error} className="flex items-start gap-1.5 text-sm font-medium text-danger" data-field-error>
          <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span>{error}</span>
        </p>
      ) : null}
    </div>
  )
}
