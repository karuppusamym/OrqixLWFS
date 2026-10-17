import type { components } from '@/api/schema'

type Field = components['schemas']['FormPreviewField']
type Preview = NonNullable<NonNullable<components['schemas']['CompatibilityReport']['mappingHints']>['formPreviews']>[string]

function control(field: Field, id: string) {
  const common = { id, disabled: true, 'aria-readonly': true as const, className: 'w-full rounded-control border border-border bg-background px-2 py-1 text-sm' }
  switch (field.widget) {
    case 'textarea': return <textarea {...common} rows={2} />
    case 'number': return <input {...common} type="number" />
    case 'checkbox': return <input id={id} type="checkbox" disabled />
    case 'select': return <select {...common}><option>Choose</option>{field.options?.map(option => <option key={option}>{option}</option>)}</select>
    case 'radio': return <div role="radiogroup" aria-labelledby={`${id}-label`} className="flex flex-wrap gap-3">{field.options?.map(option => <label key={option} className="flex items-center gap-1 text-sm"><input type="radio" disabled name={id} />{option}</label>)}</div>
    default: return <input {...common} type={field.type === 'string' && field.component === 'datetime' ? 'date' : 'text'} />
  }
}

/** Read-only preview of an imported Camunda form: the fields that become request fields, and what is left out. */
export function FormPreview({ preview, title }: { preview: Preview; title: string }) {
  const fields = preview.fields ?? []
  const skipped = preview.skipped ?? []
  return <section aria-label={`Preview of ${title}`} className="space-y-3 rounded-card border border-border bg-surface p-4">
    <h4 className="font-semibold">{title}</h4>
    {fields.length === 0 ? <p className="text-sm text-muted-foreground">No input fields become request fields.</p> : null}
    <div className="space-y-3">{fields.map((field, index) => {
      const id = `form-preview-${preview.formId ?? 'form'}-${field.key ?? index}`
      return <div key={id}>
        <label id={`${id}-label`} htmlFor={id} className="block text-sm font-medium">{field.label}{field.required ? <span aria-label="required"> *</span> : null}</label>
        {control(field, id)}
        {field.description ? <p className="text-xs text-muted-foreground">{field.description}</p> : null}
      </div>
    })}</div>
    {skipped.length ? <div><h5 className="text-sm font-semibold">Not imported</h5>
      <ul className="list-disc pl-5 text-sm text-muted-foreground">{skipped.map((field, index) => <li key={`${field.id}-${index}`}>{field.label ?? field.component}: {field.disposition === 'blocker' ? 'cannot be imported' : 'display only, left out'}{field.reasonCode ? ` (${field.reasonCode})` : ''}</li>)}</ul></div> : null}
  </section>
}
