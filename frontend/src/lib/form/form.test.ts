import Ajv2020, { type ErrorObject } from 'ajv/dist/2020'
import { describe, expect, it } from 'vitest'
import scenarios from '../../../../examples/fixtures/purchase-scenarios.json'
import purchase from '../../../../examples/purchase-approval.json'
import { buildFields, parseFormSchema, parseFormUi } from './schema'
import { emptyValues, validateForm, valuesFromJson } from './values'
import { isMultipleOf, toJsonSchema, validateJsonInput, type JsonIssue } from './validate'

const schema = parseFormSchema(purchase.inputSchema)
const ui = parseFormUi(purchase.inputUi)
const fields = buildFields(schema, ui)

const pointers = (issues: JsonIssue[]) => [...new Set(issues.map((i) => i.pointer))].sort()

describe('form schema subset', () => {
  it('reads the purchase form in display order with the right widgets', () => {
    expect(fields.map((f) => [f.key, f.widget, f.required])).toEqual([
      ['title', 'text', true],
      ['purpose', 'textarea', true],
      ['costCenter', 'select', true],
      ['amount', 'money', true],
    ])
    expect(fields.find((f) => f.key === 'amount')?.isMoney).toBe(true)
  })

  it('ignores keywords outside the approved subset instead of trusting them', () => {
    const parsed = parseFormSchema({ type: 'object', properties: { a: { type: 'string', title: 'A', pattern: '.*', format: 'email', $ref: '#/x' }, b: { type: 'array' } } })
    expect(Object.keys(parsed.properties)).toEqual(['a'])
    expect(parsed.properties.a).toEqual({ type: 'string', title: 'A' })
  })
})

describe('input validation fixtures (examples/fixtures/purchase-scenarios.json)', () => {
  for (const c of scenarios.inputValidation) {
    it(`JSON level: ${c.name} -> ${c.valid ? 'valid' : `invalid at ${c.fieldErrors?.join(', ')}`}`, () => {
      const issues = validateJsonInput(schema, c.input)
      if (c.valid) expect(issues).toEqual([])
      else expect(pointers(issues)).toEqual([...(c.fieldErrors ?? [])].sort())
    })
  }

  // The form renderer starts from typed text. Two fixtures cannot be produced by typing:
  //  - "extra-field" needs a key the form does not have,
  //  - "amount-as-string" is a JSON string, but a typed "1800" is converted to the number 1800 on submit.
  // Both are covered at JSON level above (the server is the authority for what a client may send).
  const typeable = scenarios.inputValidation.filter((c) => c.name !== 'extra-field' && c.name !== 'amount-as-string')
  for (const c of typeable) {
    it(`form level: ${c.name} reports the same fields as the server contract`, () => {
      const raw = valuesFromJson(fields, c.input)
      const result = validateForm(schema, ui, raw)
      const errorFields = Object.keys(result.errors)
        .filter((k) => result.errors[k])
        .map((k) => `/${k}`)
        .sort()
      expect(errorFields).toEqual([...(c.fieldErrors ?? [])].sort())
      expect(result.valid).toBe(c.valid)
    })
  }

  it('converts typed money to a JSON number only at submit time, keeping cents exact', () => {
    const raw = { ...emptyValues(fields), title: 'Monitor', purpose: 'Second screen', costCenter: 'IT', amount: '1800.07' }
    const result = validateForm(schema, ui, raw)
    expect(result.valid).toBe(true)
    expect(result.payload.amount).toBe(1800.07)
    expect(JSON.stringify(result.payload)).toContain('"amount":1800.07')
  })

  it('says what is wrong in plain words', () => {
    const base = { ...emptyValues(fields), title: 'x', purpose: 'y', costCenter: 'OPS' }
    expect(validateForm(schema, ui, { ...base, amount: '1800.071' }).errors.amount).toMatch(/two decimal places/)
    expect(validateForm(schema, ui, { ...base, amount: '0' }).errors.amount).toMatch(/at least \$0\.01/)
    expect(validateForm(schema, ui, { ...base, amount: '1,800' }).errors.amount).toMatch(/US dollars/)
    expect(validateForm(schema, ui, { ...base, amount: '' }).errors.amount).toMatch(/required/)
    expect(validateForm(schema, ui, { ...base, amount: '1000001' }).errors.amount).toMatch(/\$1,000,000\.00 or less/)
    expect(validateForm(schema, ui, { ...base, costCenter: '' }).errors.costCenter).toMatch(/Choose an option/)
  })
})

describe('parity with ajv (2020-12, multipleOfPrecision 2, allErrors)', () => {
  const ajv = new Ajv2020({ allErrors: true, strict: false, multipleOfPrecision: 2 })
  const validate = ajv.compile(toJsonSchema(schema))

  function viaAjv(input: unknown): string[] {
    validate(input)
    const errors = (validate.errors ?? []) as ErrorObject[]
    return errors
      .map((e) => {
        let pointer = e.instancePath
        if (e.keyword === 'additionalProperties') pointer += `/${(e.params as { additionalProperty: string }).additionalProperty}`
        if (e.keyword === 'required') pointer += `/${(e.params as { missingProperty: string }).missingProperty}`
        return `${pointer}#${e.keyword}`
      })
      .sort()
  }

  const viaOurs = (input: unknown) => validateJsonInput(schema, input).map((i) => `${i.pointer}#${i.keyword}`).sort()

  it('agrees on every shared fixture', () => {
    for (const c of scenarios.inputValidation) expect(viaOurs(c.input), c.name).toEqual(viaAjv(c.input))
  })

  it('agrees on generated inputs around every boundary', () => {
    // Small deterministic PRNG so failures reproduce.
    let seed = 20261007
    const rand = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0
      return seed / 2 ** 32
    }
    const pick = <T,>(items: T[]): T => items[Math.floor(rand() * items.length)] as T
    const amounts: unknown[] = [0, 0.01, 0.005, 1, 1799.99, 1800.07, 1800.071, 1800.001, 4999.99, 5000, 5000.0, 999999.99, 1000000, 1000000.01, -5, 12.3456, 1e21, '5', null, true, [], {}, NaN, undefined]
    const strings: unknown[] = ['', 'a', 'x'.repeat(120), 'x'.repeat(121), 'é'.repeat(120), '\u{1F600}'.repeat(120), '\u{1F600}'.repeat(121), 5, null, false, undefined, 'y'.repeat(1000), 'y'.repeat(1001)]
    const centers: unknown[] = ['OPS', 'IT', 'HR', 'FIN', '', 'ops', 3, null, undefined]
    for (let i = 0; i < 600; i++) {
      const input: Record<string, unknown> = { title: pick(strings), purpose: pick(strings), costCenter: pick(centers), amount: pick(amounts) }
      for (const k of Object.keys(input)) if (input[k] === undefined) delete input[k]
      if (rand() < 0.15) input.extra = 'x'
      if (rand() < 0.1) delete input.title
      // JSON.parse(JSON.stringify()) is what the wire does: NaN becomes null, undefined disappears.
      const wire = JSON.parse(JSON.stringify(input)) as unknown
      expect(viaOurs(wire), JSON.stringify(wire)).toEqual(viaAjv(wire))
    }
  })

  it('rejects non-objects like ajv does', () => {
    for (const bad of [null, 5, 'x', [], undefined]) expect(viaOurs(bad)).toEqual(viaAjv(bad))
  })

  it('implements ajv multipleOfPrecision for cents', () => {
    expect(isMultipleOf(1800.07, 0.01)).toBe(true)
    expect(isMultipleOf(1800.071, 0.01)).toBe(false)
    expect(isMultipleOf(0.3, 0.01)).toBe(true)
    expect(isMultipleOf(5, 0)).toBe(true)
  })
})
