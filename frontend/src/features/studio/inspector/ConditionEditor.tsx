import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { chipLabel, type FieldInfo } from '../model/fieldRefs'
import { OPERATOR_LABEL } from '../model/summary'
import { conditionTerms, type AtomicCondition, type Condition, type Literal, type Operator } from '../model/types'
import { Button } from '@/components/ui/button'
import { CommitInput, CommitNumber } from './controls'

export function defaultLiteral(type: FieldInfo['type'] | undefined, enumValues?: string[]): Literal {
  if (type === 'number' || type === 'integer') return 0
  if (type === 'boolean') return false
  return enumValues?.[0] ?? ''
}

/** Value input that adapts to the field type: choice list, Yes/No, number or text. */
export function LiteralInput({
  id,
  info,
  value,
  onCommit,
  disabled,
  dataField,
  label,
}: {
  id: string
  info: Pick<FieldInfo, 'type' | 'enumValues'> | undefined
  value: Literal
  onCommit: (value: Literal) => void
  disabled?: boolean
  dataField?: string
  label: string
}) {
  const type = info?.type ?? (typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'string')
  if (type === 'boolean') {
    return (
      <Select id={id} aria-label={label} data-field={dataField} value={String(value === true)} disabled={disabled} onChange={(e) => onCommit(e.target.value === 'true')}>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </Select>
    )
  }
  if (info?.enumValues && info.enumValues.length > 0) {
    const known = info.enumValues.includes(String(value))
    return (
      <Select id={id} aria-label={label} data-field={dataField} value={String(value)} disabled={disabled} onChange={(e) => onCommit(e.target.value)}>
        {!known ? <option value={String(value)}>{String(value) || 'Choose a value'}</option> : null}
        {info.enumValues.map((v) => (
          <option key={v} value={v}>
            {v}
          </option>
        ))}
      </Select>
    )
  }
  if (type === 'number' || type === 'integer') {
    return <CommitNumber id={id} aria-label={label} data-field={dataField} value={typeof value === 'number' ? value : undefined} disabled={disabled} integer={type === 'integer'} onCommit={(v) => onCommit(v ?? 0)} />
  }
  return <CommitInput id={id} aria-label={label} data-field={dataField} value={String(value)} disabled={disabled} maxLength={100} onCommit={(v) => onCommit(v)} />
}

/**
 * Condition = field + operator + value, shown with human field chips ("Request -> Amount (USD)").
 * The stored form is a structured reference, never an expression.
 */
function AtomicConditionEditor({
  idBase,
  condition,
  fields,
  onChange,
  disabled,
  dataFieldBase,
  fixedField,
}: {
  idBase: string
  condition: AtomicCondition
  fields: FieldInfo[]
  onChange: (next: AtomicCondition) => void
  disabled?: boolean
  dataFieldBase: string
  /** Rule table rows all test the same field, so the row does not offer a field choice. */
  fixedField?: boolean
}) {
  const info = fields.find((f) => f.ref === condition.fieldRef)
  const numeric = info?.type === 'number' || info?.type === 'integer'
  const operators: Operator[] = numeric || !info ? ['eq', 'ne', 'gte', 'gt', 'lt', 'lte'] : ['eq', 'ne']
  return (
    <div className="grid gap-3">
      {!fixedField ? (
        <div className="space-y-1.5">
          <Label htmlFor={`${idBase}-field`}>Value to look at</Label>
          <Select
            id={`${idBase}-field`}
            data-field={`${dataFieldBase}.fieldRef`}
            value={condition.fieldRef}
            disabled={disabled}
            onChange={(e) => {
              const next = fields.find((f) => f.ref === e.target.value)
              const nextNumeric = next?.type === 'number' || next?.type === 'integer'
              onChange({
                fieldRef: e.target.value,
                operator: nextNumeric ? condition.operator : 'eq',
                value: typeof condition.value === (nextNumeric ? 'number' : next?.type === 'boolean' ? 'boolean' : 'string') ? condition.value : defaultLiteral(next?.type, next?.enumValues),
              })
            }}
          >
            {!info ? <option value={condition.fieldRef}>{condition.fieldRef || 'Choose a value'}</option> : null}
            {fields.filter(f => f.type !== 'array' && f.type !== 'date').map((f) => (
              <option key={f.ref} value={f.ref}>
                {chipLabel(f)}
              </option>
            ))}
          </Select>
        </div>
      ) : null}
      <div className="grid grid-cols-[8.5rem_1fr] gap-3">
        <div className="space-y-1.5">
          <Label htmlFor={`${idBase}-op`}>Comparison</Label>
          <Select id={`${idBase}-op`} data-field={`${dataFieldBase}.operator`} value={condition.operator} disabled={disabled} onChange={(e) => onChange({ ...condition, operator: e.target.value as Operator })}>
            {operators.map((op) => (
              <option key={op} value={op}>
                {OPERATOR_LABEL[op]}
              </option>
            ))}
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor={`${idBase}-value`}>Value</Label>
          <LiteralInput id={`${idBase}-value`} info={info} value={condition.value} disabled={disabled} dataField={`${dataFieldBase}.value`} label="Value" onCommit={(value) => onChange({ ...condition, value })} />
        </div>
      </div>
    </div>
  )
}

export function ConditionEditor(props: {
  idBase: string; condition: Condition; fields: FieldInfo[]; onChange: (next: Condition) => void;
  disabled?: boolean; dataFieldBase: string; fixedField?: boolean; conjunctionOnly?: boolean
}) {
  const { condition, onChange, idBase, disabled, fields } = props
  const mode = 'all' in condition ? 'all' : 'any' in condition ? 'any' : 'single'
  const terms = conditionTerms(condition)
  const blank: AtomicCondition = { fieldRef: fields.find(f => f.type !== 'array' && f.type !== 'date')?.ref ?? '', operator: 'eq', value: defaultLiteral(fields.find(f => f.type !== 'array' && f.type !== 'date')?.type) }
  const update = (next: AtomicCondition[]) => onChange(mode === 'single' ? next[0]! : mode === 'all' ? { all: next } : { any: next })
  if (props.fixedField) return <AtomicConditionEditor {...props} condition={terms[0] ?? blank} onChange={onChange} />
  return <div className="space-y-4">
    <Label htmlFor={`${idBase}-mode`}>Condition group</Label>
    <Select id={`${idBase}-mode`} value={mode} disabled={disabled} onChange={event => {
      const next = terms.length >= 2 ? terms : [terms[0] ?? blank, { ...blank }]
      onChange(event.target.value === 'single' ? terms[0] ?? blank : event.target.value === 'all' ? { all: next } : { any: next })
    }}><option value="single">One comparison</option><option value="all">All comparisons (AND)</option>{!props.conjunctionOnly ? <option value="any">Any comparison (OR)</option> : null}</Select>
    {terms.map((term, index) => <div key={index} className={mode === 'single' ? '' : 'rounded-card border border-border p-3'}>
      <AtomicConditionEditor {...props} idBase={mode === 'single' ? idBase : `${idBase}-${index}`} dataFieldBase={mode === 'single' ? props.dataFieldBase : `${props.dataFieldBase}.${mode}.${index}`} condition={term} onChange={value => update(terms.map((old, i) => i === index ? value : old))} />
      {mode !== 'single' ? <Button variant="ghost" disabled={disabled || terms.length <= 2} onClick={() => update(terms.filter((_, i) => i !== index))}>Remove comparison {index + 1}</Button> : null}
    </div>)}
    {mode !== 'single' ? <Button variant="secondary" disabled={disabled || terms.length >= 8} onClick={() => update([...terms, { ...blank }])}>Add comparison</Button> : null}
  </div>
}
