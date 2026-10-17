import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { conditionTerms, type Condition, type InputUiDoc } from '../model/types'
import { ConditionEditor, defaultLiteral } from './ConditionEditor'
import { inputControllers } from './formControllers'
import { CommitInput, InspectorSection } from './controls'

export function FieldConditionsEditor({ fieldKey }: { fieldKey: string }) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio(s => s.definition)
  const label = def.inputSchema.properties[fieldKey]?.title ?? fieldKey
  const fields = inputControllers(def, [fieldKey])
  const set = (name: 'visibleWhen' | 'requiredWhen', condition: Condition | undefined) => store.getState().apply(d => {
    const rules = { ...d.inputUi?.[name] }
    if (condition) rules[fieldKey] = condition
    else delete rules[fieldKey]
    return { ...d, inputUi: { ...d.inputUi, [name]: rules } }
  })
  const allRules = [...Object.values(def.inputUi?.visibleWhen ?? {}), ...Object.values(def.inputUi?.requiredWhen ?? {}), ...(def.inputUi?.layout?.sections ?? []).flatMap(s => s.visibleWhen ? [s.visibleWhen] : [])]
  const controlsAnother = allRules.some(c => conditionTerms(c).some(t => t.fieldRef === `/input/${fieldKey}`))
  return <fieldset className="space-y-3 border-t border-border pt-3"><legend className="text-sm font-semibold">Conditions for {label}</legend>
    <p className="text-xs text-muted-foreground">Use unconditional scalar questions as controllers. Hidden answers are omitted from the submitted request.</p>
    {(['visibleWhen', 'requiredWhen'] as const).map(name => {
      const condition = def.inputUi?.[name]?.[fieldKey]
      const id = `${fieldKey}-${name}`
      return <div key={name} className="space-y-3"><div className="flex items-center gap-2"><Checkbox id={id} checked={!!condition} disabled={readOnly || (!condition && (fields.length === 0 || controlsAnother))} onCheckedChange={checked => {
        const field = fields[0]
        set(name, checked && field ? { fieldRef: field.ref, operator: 'eq', value: defaultLiteral(field.type, field.enumValues) } : undefined)
      }} /><Label htmlFor={id}>{name === 'visibleWhen' ? 'Show only when a condition matches' : 'Require an answer when a condition matches'}</Label></div>
      {condition ? <ConditionEditor idBase={id} dataFieldBase={`inputUi.${name}.${fieldKey}`} condition={condition} fields={fields} disabled={readOnly} onChange={next => set(name, next)} /> : null}</div>
    })}
    {controlsAnother ? <p className="text-xs text-muted-foreground">This question controls another field. Remove those references before making this question conditional.</p> : null}
  </fieldset>
}

export function FormLayoutEditor() {
  const { store, readOnly } = useStudioContext()
  const def = useStudio(s => s.definition)
  const sections = def.inputUi?.layout?.sections ?? []
  type Section = NonNullable<NonNullable<InputUiDoc['layout']>['sections']>[number]
  const save = (next: Section[]) => store.getState().apply(d => ({ ...d, inputUi: { ...d.inputUi, layout: { ...d.inputUi?.layout, sections: next } } }))
  const keys = Object.keys(def.inputSchema.properties)
  return <InspectorSection title="Form layout">
    <Label htmlFor="form-help-position">Help text position</Label><Select id="form-help-position" value={def.inputUi?.layout?.helpText ?? 'above'} disabled={readOnly} onChange={e => store.getState().apply(d => ({ ...d, inputUi: { ...d.inputUi, layout: { ...d.inputUi?.layout, helpText: e.target.value as 'above' | 'below' } } }))}><option value="above">Above the control</option><option value="below">Below the control</option></Select>
    <p className="text-xs text-muted-foreground">Group questions into up to 10 sections. Ungrouped questions follow the sections.</p>
    {sections.map((section, index) => {
      const controllers = inputControllers(def, section.fields, true)
      const set = (patch: Partial<Section>) => save(sections.map((old, i) => i === index ? { ...old, ...patch } : old))
      return <fieldset key={section.id} className="space-y-3 rounded border border-border p-3"><legend>Section {index + 1}</legend>
        <Label htmlFor={`${section.id}-title`}>Section title</Label><CommitInput id={`${section.id}-title`} value={section.title} disabled={readOnly} maxLength={100} onCommit={title => set({ title })} />
        <Label htmlFor={`${section.id}-columns`}>Section columns</Label><Select id={`${section.id}-columns`} value={section.columns ?? 1} disabled={readOnly} onChange={e => set({ columns: e.target.value === '2' ? 2 : 1 })}><option value="1">One column</option><option value="2">Two columns on larger screens</option></Select>
        {keys.map(key => <div key={key} className="flex items-center gap-2"><Checkbox id={`${section.id}-${key}`} checked={section.fields.includes(key)} disabled={readOnly || sections.some((other, i) => i !== index && other.fields.includes(key)) || (!section.fields.includes(key) && !!section.visibleWhen && inputControllers(def, [...section.fields, key], true).length === 0)} onCheckedChange={checked => set({ fields: checked ? [...section.fields, key] : section.fields.filter(old => old !== key) })} /><Label htmlFor={`${section.id}-${key}`}>Include {def.inputSchema.properties[key]!.title}</Label></div>)}
        <div className="flex items-center gap-2"><Checkbox id={`${section.id}-conditional`} checked={!!section.visibleWhen} disabled={readOnly || (!section.visibleWhen && !controllers.length)} onCheckedChange={checked => {
          const field = controllers[0]
          set({ visibleWhen: checked && field ? { fieldRef: field.ref, operator: 'eq', value: defaultLiteral(field.type, field.enumValues) } : undefined })
        }} /><Label htmlFor={`${section.id}-conditional`}>Show section only when a condition matches</Label></div>
        {section.visibleWhen ? <ConditionEditor idBase={`${section.id}-show`} dataFieldBase={`inputUi.layout.sections.${index}.visibleWhen`} fields={controllers} condition={section.visibleWhen} disabled={readOnly} onChange={visibleWhen => set({ visibleWhen })} /> : null}
        <Button variant="ghost" disabled={readOnly} onClick={() => save(sections.filter((_, i) => i !== index))}>Remove section {index + 1}</Button>
      </fieldset>
    })}
    <Button variant="secondary" disabled={readOnly || sections.length >= 10 || !keys.length} onClick={() => {
      let n = sections.length + 1
      while (sections.some(s => s.id === `section${n}`)) n++
      save([...sections, { id: `section${n}`, title: `Section ${n}`, fields: [], columns: 1 }])
    }}>Add form section</Button>
  </InspectorSection>
}
