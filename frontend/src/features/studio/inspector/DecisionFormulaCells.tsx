import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { safeReferences } from '@/lib/expr/decision'
import { type ExprType } from '@/lib/expr'
import { conditionToExpression } from '../model/conditionText'
import { chipLabel, type FieldInfo } from '../model/fieldRefs'
import type { StudioScope } from '../model/expressionScope'
import {
  decisionTerms, isExpressionTerm, isOutputExpression, type AtomicCondition, type DecisionCondition, type DecisionTerm, type Literal, type OutputEntry,
} from '../model/types'
import { AtomicConditionEditor, defaultLiteral, LiteralInput } from './ConditionEditor'
import { ExpressionEditor } from './ExpressionEditor'

/**
 * Formulas in decision rows (CG-D04, docs/build/S146): each test of a row is a comparison of its column or a formula
 * that gives true or false and uses the column's field; each result is a value or a formula of the result's type.
 */

/** Shown next to a cell that is a formula. */
export function FormulaBadge() {
  return <Badge tone="info" className="font-mono">Formula</Badge>
}

const comparable = (f: FieldInfo) => f.type !== 'array' && f.type !== 'date'

/** A comparison test written as the equivalent formula, so switching keeps what the test checks. */
function toFormula(term: AtomicCondition): DecisionTerm {
  return { fieldRef: term.fieldRef, expression: conditionToExpression(term) }
}

function toComparison(term: DecisionTerm, fields: FieldInfo[]): DecisionTerm {
  const info = fields.find(f => f.ref === term.fieldRef && comparable(f)) ?? fields.find(comparable)
  return { fieldRef: info?.ref ?? term.fieldRef, operator: 'eq', value: defaultLiteral(info?.type, info?.enumValues) }
}

function DecisionTermEditor({ idBase, dataFieldBase, term, fields, formulaFields, context, onChange, disabled, fixedField }: {
  idBase: string; dataFieldBase: string; term: DecisionTerm; fields: FieldInfo[]; formulaFields: FieldInfo[]; context: StudioScope
  onChange: (next: DecisionTerm) => void; disabled?: boolean; fixedField?: boolean
}) {
  if (!isExpressionTerm(term)) {
    return (
      <div className="space-y-2">
        <AtomicConditionEditor idBase={idBase} dataFieldBase={dataFieldBase} condition={term} fields={fields} disabled={disabled} fixedField={fixedField} allowSets onChange={onChange} />
        {!disabled ? <Button type="button" variant="ghost" size="sm" onClick={() => onChange(toFormula(term))}>Write a formula instead (dates, calculations)</Button> : null}
      </div>
    )
  }
  const info = formulaFields.find(f => f.ref === term.fieldRef)
  const refs = safeReferences(term.expression)
  const missesColumn = term.expression.trim() !== '' && refs.length > 0 && !refs.includes(term.fieldRef)
  return (
    <div className="space-y-2" data-testid={`${idBase}-formula`}>
      <div className="flex items-center gap-2"><FormulaBadge /><span className="text-xs text-muted-foreground">The test matches when the formula gives true.</span></div>
      <div className="space-y-1.5">
        <Label htmlFor={`${idBase}-column`}>Column</Label>
        <Select id={`${idBase}-column`} data-field={`${dataFieldBase}.fieldRef`} value={term.fieldRef} disabled={disabled} onChange={e => onChange({ ...term, fieldRef: e.target.value })}>
          {!info ? <option value={term.fieldRef}>{term.fieldRef || 'Choose a value'}</option> : null}
          {formulaFields.map(f => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
        </Select>
      </div>
      <ExpressionEditor id={`${idBase}-expr`} label="Formula (true or false)" dataField={`${dataFieldBase}.expression`} value={term.expression} context={context}
        expected="boolean" disabled={disabled} onCommit={expression => onChange({ ...term, expression })}
        description={'False or no value does not match. Use the column\'s field in it, for example input.amount > input.limit or input.due < date("2026-06-30").'} />
      {missesColumn ? <p className="text-xs text-danger" role="alert">The formula must use its column, {term.fieldRef}.</p> : null}
      {!disabled ? <Button type="button" variant="ghost" size="sm" onClick={() => onChange(toComparison(term, fields))}>Use a comparison instead</Button> : null}
    </div>
  )
}

/** The condition of a decision row: one test, or All (AND) / Any (OR) of 2 to 8 tests, each a comparison or a formula. */
export function DecisionConditionEditor({ idBase, dataFieldBase, condition, fields, formulaFields, context, onChange, disabled }: {
  idBase: string; dataFieldBase: string; condition: DecisionCondition; fields: FieldInfo[]; formulaFields: FieldInfo[]; context: StudioScope
  onChange: (next: DecisionCondition) => void; disabled?: boolean
}) {
  const mode = 'all' in condition && condition.all ? 'all' : 'any' in condition && condition.any ? 'any' : 'single'
  const terms = decisionTerms(condition)
  const first = fields.find(comparable)
  const blank: AtomicCondition = { fieldRef: first?.ref ?? '', operator: 'eq', value: defaultLiteral(first?.type, first?.enumValues) }
  const update = (next: DecisionTerm[]) => onChange(mode === 'single' ? next[0]! : mode === 'all' ? { all: next } : { any: next })
  return (
    <div className="space-y-4">
      <Label htmlFor={`${idBase}-mode`}>Condition group</Label>
      <Select id={`${idBase}-mode`} value={mode} disabled={disabled} onChange={event => {
        const next = terms.length >= 2 ? terms : [terms[0] ?? blank, { ...blank }]
        onChange(event.target.value === 'single' ? terms[0] ?? blank : event.target.value === 'all' ? { all: next } : { any: next })
      }}>
        <option value="single">One comparison</option><option value="all">All comparisons (AND)</option><option value="any">Any comparison (OR)</option>
      </Select>
      {terms.map((term, index) => (
        <div key={index} className={mode === 'single' ? '' : 'rounded-card border border-border p-3'}>
          <DecisionTermEditor idBase={mode === 'single' ? idBase : `${idBase}-${index}`} dataFieldBase={mode === 'single' ? dataFieldBase : `${dataFieldBase}.${mode}.${index}`}
            term={term} fields={fields} formulaFields={formulaFields} context={context} disabled={disabled}
            onChange={value => update(terms.map((old, i) => (i === index ? value : old)))} />
          {mode !== 'single' ? <Button variant="ghost" disabled={disabled || terms.length <= 2} onClick={() => update(terms.filter((_, i) => i !== index))}>Remove comparison {index + 1}</Button> : null}
        </div>
      ))}
      {mode !== 'single' ? <Button variant="secondary" disabled={disabled || terms.length >= 8} onClick={() => update([...terms, { ...blank }])}>Add comparison</Button> : null}
    </div>
  )
}

/** A result of a row: a value, or a formula that must give `expected` (the type of the Otherwise value). */
export function DecisionResultCell({ id, label, value, expected, fallback, context, onCommit, disabled, dataField, formulaAllowed = true }: {
  id: string; label: string; value: OutputEntry | undefined; expected: ExprType | null; fallback: Literal; context: StudioScope
  onCommit: (value: OutputEntry) => void; disabled?: boolean; dataField: string
  /** False for the priority result of a PRIORITY table, which ranks rows and must stay a value. */
  formulaAllowed?: boolean
}) {
  if (isOutputExpression(value)) {
    return (
      <div className="space-y-2" data-testid={`${id}-formula`}>
        <div className="flex items-center gap-2"><Label htmlFor={id}>{label}</Label><FormulaBadge /></div>
        <ExpressionEditor id={id} label={`${label}: formula`} dataField={`${dataField}.expression`} value={value.expression} context={context}
          expected={expected ?? undefined} disabled={disabled} onCommit={expression => onCommit({ expression })}
          description="Computed for each request this row applies to, for example round(input.amount * 0.025, 2)." />
        {!disabled ? <Button type="button" variant="ghost" size="sm" onClick={() => onCommit(fallback)}>Use a value instead</Button> : null}
      </div>
    )
  }
  const literal = value ?? ''
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <LiteralInput id={id} info={undefined} value={literal} disabled={disabled} label={label} dataField={dataField} onCommit={onCommit} />
      {!disabled && formulaAllowed ? (
        <Button type="button" variant="ghost" size="sm" aria-label={`Compute ${label} with a formula`} onClick={() => onCommit({ expression: typeof literal === 'string' ? JSON.stringify(literal) : String(literal) })}>Use a formula</Button>
      ) : null}
    </div>
  )
}
