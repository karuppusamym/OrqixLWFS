import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { ViewDefinition, ViewField } from './model'

function FieldPreview({ field }: { field: ViewField }) {
  const id = `preview-${field.id}`
  return <div className="min-w-0 space-y-1.5">
    <Label htmlFor={id}>{field.label || 'Untitled question'}{field.required ? ' *' : ''}</Label>
    {field.type === 'boolean' ? <Select id={id} disabled><option>Yes or no</option></Select> : field.type === 'choice' ? <Select id={id} disabled defaultValue=""><option value="">Choose an option</option>{field.options?.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</Select>
      : field.type === 'row' ? <Textarea id={id} disabled placeholder="Rows will appear here" />
        : field.type === 'file' ? <Input id={id} type="file" disabled />
          : <Input id={id} type={field.type} disabled />}
    <p className="text-xs text-muted-foreground">{field.binding || 'Unbound'}</p>
  </div>
}

export function ViewPreview({ definition, narrow = false }: { definition: ViewDefinition; narrow?: boolean }) {
  return <div className={`mx-auto w-full rounded-card border border-border bg-surface p-4 shadow-sm ${narrow ? 'max-w-sm' : 'max-w-3xl'}`} aria-label="View preview">
    {definition.sections.map(section => <section key={section.id} className="mb-6 space-y-3">
      <h3 className="text-base font-semibold">{section.label || 'Untitled section'}</h3>
      <div className={section.columns === 2 && !narrow ? 'grid gap-4 md:grid-cols-2' : 'grid gap-4'}>{section.fields.length ? section.fields.map(field => <FieldPreview key={field.id} field={field} />) : <p className="text-sm text-muted-foreground">Add questions to this section.</p>}</div>
    </section>)}
    <div className="flex flex-wrap gap-2 border-t border-border pt-4">{definition.actions.map(action => <Button key={action.id} disabled variant={action.type === 'submit' || action.type === 'approve' ? 'primary' : 'secondary'}>{action.label || 'Untitled action'}</Button>)}</div>
    <p className="mt-4 text-xs text-muted-foreground">Preview only. Actions and bindings are checked again at runtime.</p>
  </div>
}
