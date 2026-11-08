import type { SkillMode } from './api'

/**
 * The structured form of an AI skill document (contracts/ai-skill-v1.schema.json, kind `skill`) and the cheap checks of
 * that contract and of AiSkillContracts.checkSkill (duplicates, members that do not fit the mode, score range, recall
 * targets on unknown labels, samples need selfConsistency). The server checks the full contract again on every save;
 * these checks only spare a round trip and put the message next to the field.
 *
 * Numbers are kept as text in the form so a half-typed value never disappears; an empty text omits the member.
 */
export type ScalarType = 'string' | 'number' | 'integer' | 'boolean' | 'date'
export type Classification = 'public' | 'internal' | 'customer-text' | 'personal' | 'sensitive'

export interface InputRow { name: string; type: ScalarType; required: boolean; maxLength: string; classification: Classification | ''; description: string }
export interface LabelRow { key: string; description: string; risk: 'low' | 'medium' | 'high' | ''; adverse: boolean }
export interface FieldRow { name: string; type: ScalarType; required: boolean; description: string }
export interface OutcomeRow { key: string; description: string; adverse: boolean }
export interface ExampleRow { input: string; output: string }
export interface RecallRow { label: string; min: string }

export interface SkillForm {
  key: string
  name: string
  description: string
  mode: SkillMode
  instructions: string
  modelProfile: string
  input: InputRow[]
  labels: LabelRow[]
  fields: FieldRow[]
  score: { min: string; max: string; description: string }
  outcomes: OutcomeRow[]
  summaryMaxLength: string
  examples: ExampleRow[]
  confidence: { method: 'single' | 'selfConsistency'; samples: string }
  evaluation: { suite: string; minAccuracy: string; minCases: string; minRecall: RecallRow[] }
  limits: { maxInputTokens: string; maxOutputTokens: string; maxCostUsd: string; timeoutSeconds: string }
  promotion: { minShadowCases: string; targetAgreement: string }
  drift: { maxAgreementDrop: string; fallbackToSuggest: boolean }
}

/** JSON-pointer (into the document) to message. */
export type Problems = Record<string, string>

export const KEY_PATTERN = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/
export const NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,39}$/
export const LABEL_PATTERN = /^[a-z][A-Za-z0-9]{0,39}$/
const PROFILE_PATTERN = /^[a-z][a-z0-9-]{0,39}$/
export const SCALAR_TYPES: ScalarType[] = ['string', 'number', 'integer', 'boolean', 'date']
export const CLASSIFICATIONS: Classification[] = ['public', 'internal', 'customer-text', 'personal', 'sensitive']
export const MODES: SkillMode[] = ['classify', 'extract', 'score', 'recommend', 'summarize']

/** The member each mode needs (summarize needs none). */
const MODE_MEMBER: Record<SkillMode, 'labels' | 'fields' | 'score' | 'outcomes' | 'summary'> = {
  classify: 'labels', extract: 'fields', score: 'score', recommend: 'outcomes', summarize: 'summary',
}

const TOP_LEVEL = new Set(['schemaVersion', 'kind', 'key', 'name', 'description', 'mode', 'instructions', 'input', 'labels', 'fields',
  'score', 'outcomes', 'summary', 'examples', 'modelProfile', 'confidence', 'evaluation', 'limits', 'promotion', 'drift'])

export function emptyForm(mode: SkillMode = 'classify'): SkillForm {
  return {
    key: '', name: '', description: '', mode, instructions: '', modelProfile: 'economy',
    input: [{ name: 'text', type: 'string', required: true, maxLength: '', classification: 'customer-text', description: '' }],
    labels: [{ key: '', description: '', risk: 'low', adverse: false }, { key: '', description: '', risk: 'low', adverse: false }],
    fields: [{ name: '', type: 'string', required: true, description: '' }],
    score: { min: '0', max: '100', description: '' },
    outcomes: [{ key: '', description: '', adverse: false }, { key: '', description: '', adverse: false }],
    summaryMaxLength: '',
    examples: [],
    confidence: { method: 'single', samples: '' },
    evaluation: { suite: '', minAccuracy: '', minCases: '', minRecall: [] },
    limits: { maxInputTokens: '', maxOutputTokens: '', maxCostUsd: '', timeoutSeconds: '' },
    promotion: { minShadowCases: '', targetAgreement: '' },
    drift: { maxAgreementDrop: '', fallbackToSuggest: true },
  }
}

const text = (v: unknown) => (v === undefined || v === null ? '' : String(v))
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : {})
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : [])

/** A stored document as the form (unknown members are dropped; the JSON view shows the document as it is). */
export function documentToForm(document: Record<string, unknown>): SkillForm {
  const base = emptyForm((MODES as string[]).includes(String(document.mode)) ? document.mode as SkillMode : 'classify')
  const score = obj(document.score)
  const confidence = obj(document.confidence)
  const evaluation = obj(document.evaluation)
  const limits = obj(document.limits)
  const promotion = obj(document.promotion)
  const drift = obj(document.drift)
  return {
    ...base,
    key: text(document.key),
    name: text(document.name),
    description: text(document.description),
    instructions: text(document.instructions),
    modelProfile: text(document.modelProfile),
    input: Object.entries(obj(document.input)).map(([name, raw]) => {
      const d = obj(raw)
      return { name, type: (d.type as ScalarType) ?? 'string', required: d.required === true, maxLength: text(d.maxLength),
        classification: (d.classification as Classification | undefined) ?? '', description: text(d.description) }
    }),
    labels: document.labels === undefined ? base.labels : arr(document.labels).map((raw) => {
      const l = obj(raw)
      return { key: text(l.key), description: text(l.description), risk: (l.risk as LabelRow['risk']) ?? '', adverse: l.adverse === true }
    }),
    fields: document.fields === undefined ? base.fields : arr(document.fields).map((raw) => {
      const f = obj(raw)
      return { name: text(f.name), type: (f.type as ScalarType) ?? 'string', required: f.required === true, description: text(f.description) }
    }),
    score: document.score === undefined ? base.score : { min: text(score.min), max: text(score.max), description: text(score.description) },
    outcomes: document.outcomes === undefined ? base.outcomes : arr(document.outcomes).map((raw) => {
      const o = obj(raw)
      return { key: text(o.key), description: text(o.description), adverse: o.adverse === true }
    }),
    summaryMaxLength: text(obj(document.summary).maxLength),
    examples: arr(document.examples).map((raw) => {
      const e = obj(raw)
      return { input: JSON.stringify(e.input ?? {}, null, 2), output: JSON.stringify(e.output ?? {}, null, 2) }
    }),
    confidence: { method: confidence.method === 'selfConsistency' ? 'selfConsistency' : 'single', samples: text(confidence.samples) },
    evaluation: {
      suite: text(evaluation.suite), minAccuracy: text(evaluation.minAccuracy), minCases: text(evaluation.minCases),
      minRecall: Object.entries(obj(evaluation.minRecall)).map(([label, min]) => ({ label, min: text(min) })),
    },
    limits: { maxInputTokens: text(limits.maxInputTokens), maxOutputTokens: text(limits.maxOutputTokens), maxCostUsd: text(limits.maxCostUsd), timeoutSeconds: text(limits.timeoutSeconds) },
    promotion: { minShadowCases: text(promotion.minShadowCases), targetAgreement: text(promotion.targetAgreement) },
    drift: { maxAgreementDrop: text(drift.maxAgreementDrop), fallbackToSuggest: drift.fallbackToSuggest !== false },
  }
}

/** Number text to a number; NaN when the text is not a number (the check reports it). */
function num(value: string): number | undefined {
  const t = value.trim()
  if (t === '') return undefined
  return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : Number.NaN
}

function put(target: Record<string, unknown>, key: string, value: unknown) {
  if (value !== undefined && value !== '') target[key] = value
}

function nonEmpty(o: Record<string, unknown>): Record<string, unknown> | undefined {
  return Object.keys(o).length > 0 ? o : undefined
}

/**
 * The form as a document. JSON that does not parse in examples becomes a problem at that example and is left out;
 * everything else is checked by {@link checkSkillDocument}.
 */
export function formToDocument(form: SkillForm): { document: Record<string, unknown>; problems: Problems } {
  const problems: Problems = {}
  const d: Record<string, unknown> = { schemaVersion: '1.0', kind: 'skill', key: form.key.trim(), name: form.name.trim() }
  put(d, 'description', form.description.trim())
  d.mode = form.mode
  d.instructions = form.instructions
  const input: Record<string, unknown> = {}
  for (const row of form.input) {
    const spec: Record<string, unknown> = { type: row.type }
    if (row.required) spec.required = true
    put(spec, 'maxLength', num(row.maxLength))
    put(spec, 'classification', row.classification)
    put(spec, 'description', row.description.trim())
    input[row.name.trim()] = spec
  }
  d.input = input
  if (form.mode === 'classify') {
    d.labels = form.labels.map((l) => {
      const label: Record<string, unknown> = { key: l.key.trim(), description: l.description.trim() }
      put(label, 'risk', l.risk)
      if (l.adverse) label.adverse = true
      return label
    })
  }
  if (form.mode === 'extract') {
    d.fields = form.fields.map((f) => {
      const field: Record<string, unknown> = { name: f.name.trim(), type: f.type }
      if (f.required) field.required = true
      put(field, 'description', f.description.trim())
      return field
    })
  }
  if (form.mode === 'score') {
    const score: Record<string, unknown> = { min: num(form.score.min), max: num(form.score.max) }
    put(score, 'description', form.score.description.trim())
    d.score = score
  }
  if (form.mode === 'recommend') {
    d.outcomes = form.outcomes.map((o) => {
      const outcome: Record<string, unknown> = { key: o.key.trim(), description: o.description.trim() }
      if (o.adverse) outcome.adverse = true
      return outcome
    })
  }
  if (form.mode === 'summarize' && form.summaryMaxLength.trim()) d.summary = { maxLength: num(form.summaryMaxLength) }
  const examples: unknown[] = []
  form.examples.forEach((e, i) => {
    const parsed: Record<string, unknown> = {}
    for (const part of ['input', 'output'] as const) {
      try {
        parsed[part] = JSON.parse(e[part] || '{}')
      } catch {
        problems[`/examples/${i}/${part}`] = 'Write a JSON object, such as {"text": "..."}.'
      }
    }
    examples.push(parsed)
  })
  if (examples.length > 0) d.examples = examples
  d.modelProfile = form.modelProfile.trim()
  const confidence: Record<string, unknown> = {}
  if (form.confidence.method === 'selfConsistency') confidence.method = 'selfConsistency'
  put(confidence, 'samples', num(form.confidence.samples))
  put(d, 'confidence', nonEmpty(confidence))
  const evaluation: Record<string, unknown> = {}
  put(evaluation, 'suite', form.evaluation.suite.trim())
  put(evaluation, 'minAccuracy', num(form.evaluation.minAccuracy))
  if (form.evaluation.minRecall.length > 0) {
    evaluation.minRecall = Object.fromEntries(form.evaluation.minRecall.map((r) => [r.label.trim(), num(r.min)]))
  }
  put(evaluation, 'minCases', num(form.evaluation.minCases))
  put(d, 'evaluation', nonEmpty(evaluation))
  const limits: Record<string, unknown> = {}
  put(limits, 'maxInputTokens', num(form.limits.maxInputTokens))
  put(limits, 'maxOutputTokens', num(form.limits.maxOutputTokens))
  put(limits, 'maxCostUsd', num(form.limits.maxCostUsd))
  put(limits, 'timeoutSeconds', num(form.limits.timeoutSeconds))
  put(d, 'limits', nonEmpty(limits))
  const promotion: Record<string, unknown> = {}
  put(promotion, 'minShadowCases', num(form.promotion.minShadowCases))
  put(promotion, 'targetAgreement', num(form.promotion.targetAgreement))
  put(d, 'promotion', nonEmpty(promotion))
  const drift: Record<string, unknown> = {}
  put(drift, 'maxAgreementDrop', num(form.drift.maxAgreementDrop))
  if (!form.drift.fallbackToSuggest) drift.fallbackToSuggest = false
  put(d, 'drift', nonEmpty(drift))
  return { document: d, problems: { ...problems, ...checkSkillDocument(d) } }
}

function checkNumber(problems: Problems, pointer: string, value: unknown, opts: { min?: number; max?: number; exclusiveMin?: number; integer?: boolean; label: string }) {
  if (value === undefined) return
  if (typeof value !== 'number' || Number.isNaN(value)) {
    problems[pointer] = `Enter a number for ${opts.label}.`
    return
  }
  if (opts.integer && !Number.isInteger(value)) problems[pointer] = `Enter a whole number for ${opts.label}.`
  else if (opts.min !== undefined && value < opts.min) problems[pointer] = `${capital(opts.label)} is at least ${opts.min}.`
  else if (opts.exclusiveMin !== undefined && value <= opts.exclusiveMin) problems[pointer] = `${capital(opts.label)} is above ${opts.exclusiveMin}.`
  else if (opts.max !== undefined && value > opts.max) problems[pointer] = `${capital(opts.label)} is at most ${opts.max}.`
}

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

function checkDuplicates(problems: Problems, items: unknown[], member: string, path: string, what: string) {
  const seen = new Set<string>()
  items.forEach((raw, i) => {
    const v = String(obj(raw)[member] ?? '')
    if (v && seen.has(v)) problems[`${path}/${i}/${member}`] = `The ${what} ${v} is used more than once.`
    seen.add(v)
  })
}

/** Cheap checks of the skill contract on a document (from the form or from the JSON view). */
export function checkSkillDocument(document: Record<string, unknown>): Problems {
  const p: Problems = {}
  for (const k of Object.keys(document)) if (!TOP_LEVEL.has(k)) p[`/${k}`] = `The skill contract has no member ${k}.`
  if (document.schemaVersion !== '1.0') p['/schemaVersion'] = 'The schema version is 1.0.'
  if (document.kind !== 'skill') p['/kind'] = 'The document must be of kind skill.'
  if (!KEY_PATTERN.test(String(document.key ?? ''))) p['/key'] = 'Use 2 to 64 lowercase letters, digits and dashes, starting with a letter, such as dispute-triage.'
  const name = String(document.name ?? '').trim()
  if (!name) p['/name'] = 'Give the skill a name.'
  else if (name.length > 120) p['/name'] = 'Keep the name to 120 characters.'
  if (typeof document.description === 'string' && document.description.length > 2000) p['/description'] = 'Keep the description to 2000 characters.'
  const mode = document.mode as SkillMode
  if (!MODES.includes(mode)) p['/mode'] = 'Choose a mode.'
  const instructions = String(document.instructions ?? '')
  if (instructions.trim().length < 10) p['/instructions'] = 'Write the instructions (at least 10 characters).'
  else if (instructions.length > 20000) p['/instructions'] = 'Keep the instructions to 20000 characters.'
  if (!PROFILE_PATTERN.test(String(document.modelProfile ?? ''))) p['/modelProfile'] = 'Enter a model profile id, such as economy.'

  const input = obj(document.input)
  const names = Object.keys(input)
  if (names.length === 0) p['/input'] = 'Declare at least one input.'
  if (names.length > 30) p['/input'] = 'Declare at most 30 inputs.'
  names.forEach((n) => {
    const spec = obj(input[n])
    if (!NAME_PATTERN.test(n)) p[`/input/${n || '(empty)'}`] = 'Input names start with a letter and use letters, digits and _ (at most 40).'
    if (!SCALAR_TYPES.includes(spec.type as ScalarType)) p[`/input/${n}/type`] = 'Choose a type.'
    checkNumber(p, `/input/${n}/maxLength`, spec.maxLength, { min: 1, max: 100000, integer: true, label: 'the maximum length' })
    if (spec.classification !== undefined && !CLASSIFICATIONS.includes(spec.classification as Classification)) p[`/input/${n}/classification`] = 'Choose a classification.'
  })

  const memberOfMode = MODES.includes(mode) ? MODE_MEMBER[mode] : null
  for (const m of ['labels', 'fields', 'score', 'outcomes', 'summary'] as const) {
    if (document[m] !== undefined && m !== memberOfMode) p[`/${m}`] = `A ${mode} skill has no ${m}.`
  }
  const labelKeys = new Set<string>()
  if (mode === 'classify') {
    const labels = arr(document.labels)
    if (labels.length < 2) p['/labels'] = 'A classify skill needs at least two labels.'
    if (labels.length > 30) p['/labels'] = 'Use at most 30 labels.'
    labels.forEach((raw, i) => {
      const l = obj(raw)
      if (!LABEL_PATTERN.test(String(l.key ?? ''))) p[`/labels/${i}/key`] = 'Label keys start with a lowercase letter and use letters and digits, such as fraudSuspected.'
      if (!String(l.description ?? '').trim()) p[`/labels/${i}/description`] = 'Say what the label means.'
      labelKeys.add(String(l.key ?? ''))
    })
    checkDuplicates(p, labels, 'key', '/labels', 'label')
  }
  if (mode === 'extract') {
    const fields = arr(document.fields)
    if (fields.length < 1) p['/fields'] = 'An extract skill needs at least one field.'
    if (fields.length > 40) p['/fields'] = 'Use at most 40 fields.'
    fields.forEach((raw, i) => {
      const f = obj(raw)
      if (!NAME_PATTERN.test(String(f.name ?? ''))) p[`/fields/${i}/name`] = 'Field names start with a letter and use letters, digits and _.'
      if (!SCALAR_TYPES.includes(f.type as ScalarType)) p[`/fields/${i}/type`] = 'Choose a type.'
    })
    checkDuplicates(p, fields, 'name', '/fields', 'field')
  }
  if (mode === 'score') {
    const score = obj(document.score)
    if (document.score === undefined) p['/score'] = 'A score skill declares its range.'
    checkNumber(p, '/score/min', score.min, { label: 'the minimum' })
    checkNumber(p, '/score/max', score.max, { label: 'the maximum' })
    if (score.min === undefined) p['/score/min'] = 'Enter the lowest score.'
    if (score.max === undefined) p['/score/max'] = 'Enter the highest score.'
    if (typeof score.min === 'number' && typeof score.max === 'number' && !p['/score/max'] && score.min >= score.max) p['/score/max'] = 'The score maximum must be above the minimum.'
  }
  if (mode === 'recommend') {
    const outcomes = arr(document.outcomes)
    if (outcomes.length < 2) p['/outcomes'] = 'A recommend skill needs at least two outcomes.'
    if (outcomes.length > 10) p['/outcomes'] = 'Use at most 10 outcomes.'
    outcomes.forEach((raw, i) => {
      const o = obj(raw)
      if (!LABEL_PATTERN.test(String(o.key ?? ''))) p[`/outcomes/${i}/key`] = 'Outcome keys start with a lowercase letter and use letters and digits.'
      if (!String(o.description ?? '').trim()) p[`/outcomes/${i}/description`] = 'Say what the outcome means.'
    })
    checkDuplicates(p, outcomes, 'key', '/outcomes', 'outcome')
  }
  if (mode === 'summarize' && document.summary !== undefined) {
    checkNumber(p, '/summary/maxLength', obj(document.summary).maxLength, { min: 50, max: 8000, integer: true, label: 'the summary length' })
  }
  if (arr(document.examples).length > 20) p['/examples'] = 'Use at most 20 examples.'

  const confidence = obj(document.confidence)
  checkNumber(p, '/confidence/samples', confidence.samples, { min: 1, max: 5, integer: true, label: 'the number of samples' })
  if (!p['/confidence/samples'] && typeof confidence.samples === 'number' && confidence.samples > 1 && confidence.method !== 'selfConsistency') {
    p['/confidence/samples'] = 'Several samples need the method selfConsistency.'
  }
  const evaluation = obj(document.evaluation)
  if (evaluation.suite !== undefined && !KEY_PATTERN.test(String(evaluation.suite))) p['/evaluation/suite'] = 'Enter the key of an evaluation suite.'
  checkNumber(p, '/evaluation/minAccuracy', evaluation.minAccuracy, { min: 0, max: 1, label: 'the minimum accuracy' })
  checkNumber(p, '/evaluation/minCases', evaluation.minCases, { min: 1, max: 1000, integer: true, label: 'the minimum number of cases' })
  Object.entries(obj(evaluation.minRecall)).forEach(([label, value]) => {
    const at = `/evaluation/minRecall/${label}`
    checkNumber(p, at, value, { min: 0, max: 1, label: 'the minimum recall' })
    if (!p[at] && !labelKeys.has(label)) p[at] = `There is no label ${label || '(empty)'}.`
  })
  const limits = obj(document.limits)
  checkNumber(p, '/limits/maxInputTokens', limits.maxInputTokens, { min: 100, max: 200000, integer: true, label: 'the input token limit' })
  checkNumber(p, '/limits/maxOutputTokens', limits.maxOutputTokens, { min: 50, max: 16000, integer: true, label: 'the output token limit' })
  checkNumber(p, '/limits/maxCostUsd', limits.maxCostUsd, { exclusiveMin: 0, max: 5, label: 'the cost limit' })
  checkNumber(p, '/limits/timeoutSeconds', limits.timeoutSeconds, { min: 5, max: 600, integer: true, label: 'the timeout' })
  const promotion = obj(document.promotion)
  checkNumber(p, '/promotion/minShadowCases', promotion.minShadowCases, { min: 1, max: 10000, integer: true, label: 'the minimum compared cases' })
  checkNumber(p, '/promotion/targetAgreement', promotion.targetAgreement, { min: 0, max: 1, label: 'the target agreement' })
  checkNumber(p, '/drift/maxAgreementDrop', obj(document.drift).maxAgreementDrop, { min: 0, max: 1, label: 'the largest agreement drop' })
  return p
}

/** Server field errors point into the request body (`/document/...`); the form uses pointers into the document. */
export function documentPointer(pointer: string): string {
  return pointer.startsWith('/document') ? pointer.slice('/document'.length) || '/' : pointer
}
