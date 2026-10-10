import { AlertCircle } from 'lucide-react'
import { FormField } from '@/components/common/FormField'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { buildFields } from '@/lib/form/schema'
import { Button } from '@/components/ui/button'
import type { FieldDef } from '@/lib/form/schema'
import type { FieldErrors, RawValue, RawValues } from '@/lib/form/values'

export function fieldDomId(prefix: string, key: string): string {
  return `${prefix}-${key}`
}

export interface FormRendererProps {
  fields: FieldDef[]
  values: RawValues
  onChange: (key: string, value: RawValue) => void
  errors: FieldErrors
  /** Prefix for generated element ids; keeps ids unique when two forms share a page. */
  idPrefix?: string
  disabled?: boolean
}

/**
 * Schema-driven form (docs/build/S7-frontend.md section 6).
 * `title` is the label, `description` is help text linked with aria-describedby, required fields are marked in text,
 * and client or server errors render on the same field.
 */
export function FormRenderer({ fields, values, onChange, errors, idPrefix = 'f', disabled }: FormRendererProps) {
  return (
    <div className="space-y-5">
      {fields.map((f) => (
        <FieldControl key={f.key} field={f} value={values[f.key]} onChange={(v) => onChange(f.key, v)} error={errors[f.key]} id={fieldDomId(idPrefix, f.key)} disabled={disabled} />
      ))}
    </div>
  )
}

function FieldControl({
  field: f,
  value,
  onChange,
  error,
  id,
  disabled,
}: {
  field: FieldDef
  value: RawValue | undefined
  onChange: (value: RawValue) => void
  error: string | undefined
  id: string
  disabled: boolean | undefined
}) {
  const text = typeof value === 'string' ? value : ''

  if (f.widget === 'collection' && f.schema.items) {
    const entries = Array.isArray(value) ? value : []
    const itemField = buildFields({ type: 'object', additionalProperties: false, required: ['item'], properties: { item: f.schema.items } }, {})[0]!
    return <fieldset className="space-y-3" disabled={disabled} id={id} aria-describedby={error ? `${id}-error` : undefined}>
      <legend className="text-sm font-semibold">{f.label}{f.required ? ' (required)' : ''}</legend>
      <p className="text-sm text-muted-foreground">{f.description} Add {f.schema.minItems} to {f.schema.maxItems} items.</p>
      {entries.map((entry, index) => <div key={index} className="rounded-card border border-border p-3 space-y-2">
        <FieldControl field={{ ...itemField, label: `${f.label}: item ${index + 1}` }} value={entry} onChange={next => {
          if (Array.isArray(next)) return
          onChange(entries.map((current, i) => i === index ? next : current))
        }} error={undefined} id={`${id}-${index}`} disabled={disabled} />
        <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={() => onChange(entries.filter((_, i) => i !== index))} aria-label={`Remove ${f.label} item ${index + 1}`}>Remove item</Button>
      </div>)}
      <Button type="button" variant="secondary" disabled={disabled || entries.length >= (f.schema.maxItems ?? 25)} onClick={() => onChange([...entries, f.schema.items?.type === 'boolean' ? false : ''])}>Add {f.label} item</Button>
      {error ? <FieldError id={`${id}-error`} message={error} /> : null}
    </fieldset>
  }

  if (f.widget === 'checkbox') {
    const descId = `${id}-desc`
    const errId = `${id}-error`
    const describedBy = [f.description ? descId : null, error ? errId : null].filter(Boolean).join(' ') || undefined
    return (
      <div className="space-y-1.5">
        <div className="flex items-start gap-3">
          <Checkbox
            id={id}
            checked={value === true}
            onCheckedChange={(checked) => onChange(checked === true)}
            disabled={disabled}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
            aria-required={f.required || undefined}
          />
          <Label htmlFor={id} className="pt-0.5 font-medium">
            {f.label}
            {f.required ? <span className="ml-1.5 text-xs font-normal text-muted-foreground">(required)</span> : null}
          </Label>
        </div>
        {f.description ? (
          <p id={descId} className="ml-9 text-sm text-muted-foreground">
            {f.description}
          </p>
        ) : null}
        {error ? <FieldError id={errId} message={error} className="ml-9" /> : null}
      </div>
    )
  }

  if (f.widget === 'radio' && f.schema.enum) {
    const descId = `${id}-desc`
    const errId = `${id}-error`
    const describedBy = [f.description ? descId : null, error ? errId : null].filter(Boolean).join(' ') || undefined
    return (
      <fieldset className="space-y-2" aria-describedby={describedBy}>
        <legend className="text-sm font-semibold">
          {f.label}
          {f.required ? <span className="ml-1.5 text-xs font-normal text-muted-foreground">(required)</span> : null}
        </legend>
        {f.description ? (
          <p id={descId} className="text-sm text-muted-foreground">
            {f.description}
          </p>
        ) : null}
        <RadioGroup value={text} onValueChange={onChange} disabled={disabled} aria-invalid={error ? true : undefined} id={id}>
          {f.schema.enum.map((option) => (
            <div key={option} className="flex items-center gap-3">
              <RadioGroupItem value={option} id={`${id}-${option}`} />
              <Label htmlFor={`${id}-${option}`} className="font-medium">
                {option}
              </Label>
            </div>
          ))}
        </RadioGroup>
        {error ? <FieldError id={errId} message={error} /> : null}
      </fieldset>
    )
  }

  const helpExtra = f.isMoney ? <span className="sr-only"> Amount in US dollars.</span> : null
  const description = f.description || helpExtra ? (
    <>
      {f.description}
      {helpExtra}
    </>
  ) : undefined

  return (
    <FormField label={f.label} description={description} error={error} required={f.required} id={id}>
      {(ids) => {
        const common = {
          id: ids.input,
          disabled,
          'aria-invalid': error ? (true as const) : undefined,
          'aria-describedby': ids.describedBy,
          'aria-required': f.required || undefined,
        }
        switch (f.widget) {
          case 'textarea':
            return (
              <>
                <Textarea {...common} value={text} onChange={(e) => onChange(e.target.value)} rows={4} />
                {f.schema.maxLength ? (
                  <p className="text-right text-xs text-muted-foreground" aria-hidden="true">
                    {[...text].length} / {f.schema.maxLength}
                  </p>
                ) : null}
              </>
            )
          case 'select':
            return (
              <Select {...common} value={text} onChange={(e) => onChange(e.target.value)}>
                <option value="">Choose an option</option>
                {(f.schema.enum ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </Select>
            )
          case 'money':
            return (
              <div className="relative">
                <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm font-semibold text-muted-foreground" aria-hidden="true">
                  USD
                </span>
                <Input {...common} className="pl-14 tabular-nums" value={text} inputMode="decimal" autoComplete="off" spellCheck={false} onChange={(e) => onChange(e.target.value)} />
              </div>
            )
          case 'number':
            return <Input {...common} value={text} inputMode={f.schema.type === 'integer' ? 'numeric' : 'decimal'} autoComplete="off" spellCheck={false} onChange={(e) => onChange(e.target.value)} />
          default:
            return <Input {...common} value={text} autoComplete="off" onChange={(e) => onChange(e.target.value)} />
        }
      }}
    </FormField>
  )
}

function FieldError({ id, message, className }: { id: string; message: string; className?: string }) {
  return (
    <p id={id} className={`flex items-start gap-1.5 text-sm font-medium text-danger ${className ?? ''}`} data-field-error>
      <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <span>{message}</span>
    </p>
  )
}
