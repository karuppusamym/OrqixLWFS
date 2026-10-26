import { describe, expect, it } from 'vitest'
import cases from '../../../../examples/fixtures/form-pattern-cases.json'
import { FormPattern, MAX_INPUT_LENGTH, PatternError, patternMatches } from './pattern'
import { parseFormSchema } from './schema'
import { validateForm } from './values'

/** G5 (docs/build/S90): the browser engine on the cases the Java engine also runs. */
describe('safe form pattern engine', () => {
  it.each(cases.valid.map(c => [c.pattern, c] as const))('accepts and rejects exactly as the shared cases say: %s', (_, c) => {
    const pattern = FormPattern.compile(c.pattern)
    for (const a of c.accept) expect(pattern.matches(a), `accepts ${JSON.stringify(a)}`).toBe(true)
    for (const r of c.reject) expect(pattern.matches(r), `rejects ${JSON.stringify(r)}`).toBe(false)
    expect(pattern.example()).toBe(c.example)
  })

  it('means the same as the native ECMAScript RegExp with the u flag on every shared case', () => {
    for (const c of cases.valid) {
      const native = new RegExp(c.pattern, 'u')
      const pattern = FormPattern.compile(c.pattern)
      for (const input of [...c.accept, ...c.reject, c.example]) expect(pattern.matches(input), `${c.pattern} on ${JSON.stringify(input)}`).toBe(native.test(input))
    }
  })

  it.each(cases.invalid.map(c => [c.pattern, c.reason] as const))('refuses %j with the reason', (pattern, reason) => {
    expect(() => FormPattern.compile(pattern)).toThrow(PatternError)
    expect(FormPattern.problem(pattern)).toContain(reason)
  })

  it.each(cases.slow.map(c => [c.pattern, c] as const))('finishes catastrophic backtracking attempts quickly: %s', (_, c) => {
    const pattern = FormPattern.compile(c.pattern)
    const input = c.input.repeat(c.repeat) + c.suffix
    const started = performance.now()
    expect(pattern.matches(input)).toBe(false)
    expect(performance.now() - started).toBeLessThan(2000)
  })

  it('never matches answers above the input limit and fails closed on unusable patterns', () => {
    expect(patternMatches('^.*$', 'a'.repeat(MAX_INPUT_LENGTH))).toBe(true)
    expect(patternMatches('^.*$', 'a'.repeat(MAX_INPUT_LENGTH + 1))).toBe(false)
    expect(patternMatches('^(a)\\1$', 'aa')).toBe(false)
  })
})

describe('request form with a pattern question', () => {
  const schema = parseFormSchema({
    type: 'object', additionalProperties: false, required: ['code'],
    properties: {
      code: { type: 'string', title: 'Cost code', maxLength: 20, pattern: '^[A-Z]{2}-\\d{4}$', patternMessage: 'Enter a code like <b>AB-1234</b>.' },
      plain: { type: 'string', title: 'Plain', pattern: '^\\d+$' },
      choice: { type: 'string', title: 'Choice', enum: ['a'], pattern: '^b$' },
    },
  })

  it('parses the pattern only on plain text questions', () => {
    expect(schema.properties.code?.pattern).toBe('^[A-Z]{2}-\\d{4}$')
    expect(schema.properties.choice?.pattern).toBeUndefined()
  })

  it('shows the author message for a mismatch and a neutral one without a message', () => {
    const result = validateForm(schema, {}, { code: 'ab-1234', plain: 'x', choice: '' })
    expect(result.errors.code).toBe('Enter a code like <b>AB-1234</b>.')
    expect(result.errors.plain).toBe('Enter the answer in the expected format.')
    expect(validateForm(schema, {}, { code: 'AB-1234', plain: '12', choice: '' }).valid).toBe(true)
  })
})
