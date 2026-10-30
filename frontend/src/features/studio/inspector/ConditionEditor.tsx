import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { chipLabel, type FieldInfo } from '../model/fieldRefs'
import { OPERATOR_LABEL } from '../model/summary'
import { Checkbox } from '@/components/ui/checkbox'
import { conditionTerms, isRangeValue, isSetOperator, type AtomicCondition, type Condition, type ConditionValue, type Literal, type Operator, type RangeValue } from '../model/types'
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
  allowSets,
}: {
  idBase: string
  condition: AtomicCondition
  fields: FieldInfo[]
  onChange: (next: AtomicCondition) => void
  disabled?: boolean
  dataFieldBase: string
  /** Rule table rows all test the same field, so the row does not offer a field choice. */
  fixedField?: boolean
  /** Decision rows only: offer "is one of" (a list) and "is between" (a number range), docs/build/S121. */
  allowSets?: boolean
}) {
  const info = fields.find((f) => f.ref === condition.fieldRef)
  const numeric = info?.type === 'number' || info?.type === 'integer'
  const scalar: Operator[] = numeric || !info ? ['eq', 'ne', 'gte', 'gt', 'lt', 'lte'] : ['eq', 'ne']
  const sets: Operator[] = !allowSets || info?.type === 'boolean' ? [] : numeric || !info ? ['in', 'between'] : ['in']
  const operators: Operator[] = [...scalar, ...sets]
  const changeOperator = (operator: Operator) => onChange({ ...condition, operator, value: convertValue(condition.value, operator, info) })
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
              const keep = nextNumeric || (condition.operator === 'in' && next?.type === 'string') || condition.operator === 'eq' || condition.operator === 'ne'
              const operator = keep ? condition.operator : 'eq'
              const sample = defaultLiteral(next?.type, next?.enumValues)
              const sameKind = (v: Literal) => typeof v === typeof sample
              const value: ConditionValue = isSetOperator(operator)
                ? operator === 'in' && Array.isArray(condition.value) && condition.value.every(sameKind) ? condition.value : convertValue(sample, operator, next)
                : !Array.isArray(condition.value) && !isRangeValue(condition.value) && sameKind(condition.value) ? condition.value : sample
              onChange({ fieldRef: e.target.value, operator, value })
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
      {/* A list or a range needs the full width of the panel, so it goes below the comparison. */}
      <div className={condition.operator === 'in' || condition.operator === 'between' ? 'grid gap-3' : 'grid grid-cols-[8.5rem_1fr] gap-3'}>
        <div className="space-y-1.5">
          <Label htmlFor={`${idBase}-op`}>Comparison</Label>
          <Select id={`${idBase}-op`} data-field={`${dataFieldBase}.operator`} value={condition.operator} disabled={disabled} onChange={(e) => changeOperator(e.target.value as Operator)}>
            {operators.map((op) => (
              <option key={op} value={op}>
                {OPERATOR_LABEL[op]}
              </option>
            ))}
          </Select>
        </div>
        {condition.operator === 'in' ? (
          <ListValueEditor idBase={idBase} info={info} values={Array.isArray(condition.value) ? condition.value : []} disabled={disabled} dataField={`${dataFieldBase}.value`} onChange={(value) => onChange({ ...condition, value })} />
        ) : condition.operator === 'between' ? (
          <RangeValueEditor idBase={idBase} integer={info?.type === 'integer'} range={isRangeValue(condition.value) ? condition.value : { from: 0, to: 1 }} disabled={disabled} dataField={`${dataFieldBase}.value`} onChange={(value) => onChange({ ...condition, value })} />
        ) : (
          <div className="space-y-1.5">
            <Label htmlFor={`${idBase}-value`}>Value</Label>
            <LiteralInput id={`${idBase}-value`} info={info} value={Array.isArray(condition.value) ? (condition.value[0] ?? '') : isRangeValue(condition.value) ? condition.value.from : condition.value} disabled={disabled} dataField={`${dataFieldBase}.value`} label="Value" onCommit={(value) => onChange({ ...condition, value })} />
          </div>
        )}
      </div>
    </div>
  )
}

/** Moves a value to the shape an operator needs: a list for "is one of", a range for "is between", one value otherwise. */
export function convertValue(value: ConditionValue, operator: Operator, info: Pick<FieldInfo, 'type' | 'enumValues'> | undefined): ConditionValue {
  const first: Literal = Array.isArray(value) ? (value[0] ?? defaultLiteral(info?.type, info?.enumValues)) : isRangeValue(value) ? value.from : value
  if (operator === 'in') return Array.isArray(value) ? value : [first]
  if (operator === 'between') {
    if (isRangeValue(value)) return value
    const from = typeof first === 'number' ? first : 0
    return { from, to: from + 1 }
  }
  return first
}

/** "Is one of": choice fields tick their values; other fields list 1 to 20 values. */
function ListValueEditor({ idBase, info, values, onChange, disabled, dataField }: {
  idBase: string; info: Pick<FieldInfo, 'type' | 'enumValues'> | undefined; values: Literal[]; onChange: (next: Literal[]) => void; disabled?: boolean; dataField: string
}) {
  if (info?.enumValues && info.enumValues.length > 0) {
    const choices = info.enumValues
    return (
      <fieldset className="space-y-1.5" data-field={dataField} disabled={disabled}>
        <legend className="text-sm font-medium">Values</legend>
        {choices.map((choice, i) => {
          const id = `${idBase}-in-${i}`
          const checked = values.includes(choice)
          return (
            <div key={choice} className="flex items-center gap-2">
              <Checkbox id={id} checked={checked} disabled={disabled || (checked && values.length <= 1)} onCheckedChange={(v) => onChange(v === true ? choices.filter((c) => c === choice || values.includes(c)) : values.filter((x) => x !== choice))} />
              <Label htmlFor={id}>{choice}</Label>
            </div>
          )
        })}
      </fieldset>
    )
  }
  const blank = defaultLiteral(info?.type, info?.enumValues)
  return (
    <fieldset className="space-y-1.5" data-field={dataField} disabled={disabled}>
      <legend className="text-sm font-medium">Values</legend>
      {values.map((v, i) => (
        <div key={i} className="flex items-center gap-2">
          <LiteralInput id={`${idBase}-in-${i}`} info={info} value={v} disabled={disabled} label={`Value ${i + 1}`} onCommit={(next) => onChange(values.map((old, j) => (j === i ? next : old)))} />
          <Button variant="ghost" size="sm" disabled={disabled || values.length <= 1} onClick={() => onChange(values.filter((_, j) => j !== i))} aria-label={`Remove value ${i + 1}`}>Remove</Button>
        </div>
      ))}
      <Button variant="secondary" size="sm" disabled={disabled || values.length >= 20} onClick={() => onChange([...values, blank])}>Add value</Button>
    </fieldset>
  )
}

/** "Is between": two numbers, each bound included unless unticked. */
function RangeValueEditor({ idBase, range, onChange, disabled, dataField, integer }: {
  idBase: string; range: RangeValue; onChange: (next: RangeValue) => void; disabled?: boolean; dataField: string; integer?: boolean
}) {
  const set = (change: Partial<RangeValue>) => {
    const next = { ...range, ...change }
    if (next.includeFrom !== false) delete next.includeFrom
    if (next.includeTo !== false) delete next.includeTo
    onChange(next)
  }
  return (
    <fieldset className="grid grid-cols-2 gap-2" data-field={dataField} disabled={disabled}>
      <legend className="sr-only">Range</legend>
      <div className="space-y-1.5">
        <Label htmlFor={`${idBase}-from`}>From</Label>
        <CommitNumber id={`${idBase}-from`} integer={integer} value={range.from} disabled={disabled} onCommit={(v) => set({ from: v ?? 0 })} />
        <div className="flex items-center gap-2">
          <Checkbox id={`${idBase}-from-incl`} checked={range.includeFrom !== false} disabled={disabled} onCheckedChange={(v) => set({ includeFrom: v === true })} />
          <Label htmlFor={`${idBase}-from-incl`}>Including</Label>
        </div>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={`${idBase}-to`}>To</Label>
        <CommitNumber id={`${idBase}-to`} integer={integer} value={range.to} disabled={disabled} onCommit={(v) => set({ to: v ?? 0 })} />
        <div className="flex items-center gap-2">
          <Checkbox id={`${idBase}-to-incl`} checked={range.includeTo !== false} disabled={disabled} onCheckedChange={(v) => set({ includeTo: v === true })} />
          <Label htmlFor={`${idBase}-to-incl`}>Including</Label>
        </div>
      </div>
      {range.from >= range.to ? <p className="col-span-2 text-xs text-danger" role="alert">From must be smaller than To.</p> : null}
    </fieldset>
  )
}

export function ConditionEditor(props: {
  idBase: string; condition: Condition; fields: FieldInfo[]; onChange: (next: Condition) => void;
  disabled?: boolean; dataFieldBase: string; fixedField?: boolean; conjunctionOnly?: boolean
  /** Decision rows only: lists and ranges (docs/build/S121). */
  allowSets?: boolean
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
