import { describe, expect, it } from 'vitest'
import { checkSkillDocument, documentPointer, documentToForm, emptyForm, formToDocument } from './skillDocument'

const valid = {
  schemaVersion: '1.0', kind: 'skill', key: 'dispute-triage', name: 'Dispute triage', mode: 'classify',
  instructions: 'Classify the dispute into exactly one label.', modelProfile: 'economy',
  input: { description: { type: 'string', required: true, classification: 'customer-text' } },
  labels: [{ key: 'pricing', description: 'Price', risk: 'low' }, { key: 'fraudSuspected', description: 'Fraud', risk: 'high', adverse: true }],
  evaluation: { minAccuracy: 0.9, minRecall: { fraudSuspected: 0.95 }, minCases: 50 },
  limits: { maxCostUsd: 0.05, timeoutSeconds: 60 },
}

describe('AI skill document (ai-skill-v1 cheap checks)', () => {
  it('accepts a valid classify skill and round-trips it through the form', () => {
    expect(checkSkillDocument(valid)).toEqual({})
    const { document, problems } = formToDocument(documentToForm(valid))
    expect(problems).toEqual({})
    expect(document).toEqual(valid)
  })

  it('reports the contract and semantic problems at their pointers', () => {
    const problems = checkSkillDocument({
      ...valid, key: 'Bad Key', instructions: 'short', extra: 1,
      labels: [{ key: 'pricing', description: 'a' }, { key: 'pricing', description: '' }],
      fields: [{ name: 'x', type: 'string' }],
      evaluation: { minRecall: { unknown: 0.5 }, minAccuracy: 2 },
      confidence: { samples: 3 },
      limits: { timeoutSeconds: 900 },
    })
    expect(Object.keys(problems).sort()).toEqual([
      '/confidence/samples', '/evaluation/minAccuracy', '/evaluation/minRecall/unknown', '/extra', '/fields', '/instructions', '/key',
      '/labels/1/description', '/labels/1/key', '/limits/timeoutSeconds',
    ])
    expect(problems['/confidence/samples']).toBe('Several samples need the method selfConsistency.')
    expect(problems['/fields']).toBe('A classify skill has no fields.')
    expect(problems['/labels/1/key']).toBe('The label pricing is used more than once.')
  })

  it('checks the score range and keeps the mode members out of other modes', () => {
    const form = { ...emptyForm('score'), key: 'risk-score', name: 'Risk', instructions: 'Score the risk of the request.', score: { min: '10', max: '5', description: '' } }
    const { document, problems } = formToDocument(form)
    expect(document.labels).toBeUndefined()
    expect(problems['/score/max']).toBe('The score maximum must be above the minimum.')
  })

  it('reports example JSON that does not parse', () => {
    const form = { ...documentToForm(valid), examples: [{ input: '{"description": "x"}', output: '{oops' }] }
    expect(formToDocument(form).problems['/examples/0/output']).toMatch(/JSON object/)
  })

  it('maps server pointers into the document', () => {
    expect(documentPointer('/document/labels/0/key')).toBe('/labels/0/key')
    expect(documentPointer('/suiteKey')).toBe('/suiteKey')
  })
})
