import type { FormAssetContent } from '@/api/types'
import { canonical } from '@/lib/canonicalJson'
import type { AssetRef, Condition, Definition, InputUiDoc } from './types'

/**
 * Shared forms in the Studio (docs/build/S127). A shared form is a set of request questions with their widgets, rules and
 * groups. Inserting one copies the questions into this workflow's request form and records a link: the pinned version
 * and, per copied question, the key it has here (renamed when this form already had that key). A later change to the
 * shared form never reaches this workflow until a designer updates the copy. These functions are pure; the server
 * checks the same link at validation and publish (AssetReferenceCheck) and the compiler checks every copied question.
 */

export type FormLink = AssetRef & { kind: 'form'; fields: Record<string, string> }
export type FormContent = FormAssetContent

/** What to do with a shared question whose key this form already uses: copy it under another key, or keep ours. */
export type ClashChoice = { action: 'rename'; to: string } | { action: 'skip' }

export const FORM_TARGET = 'request'
const FIELD_KEY = /^[a-z][A-Za-z0-9]{0,63}$/
const INPUT = '/input/'
const RULES = ['visibleWhen', 'requiredWhen'] as const

const props = (def: Definition) => def.inputSchema.properties

export function formLinks(def: Definition): FormLink[] {
  return (def.dependencies.assets ?? []).filter((a): a is FormLink => a.kind === 'form' && !!a.fields)
}

export function formLink(def: Definition, key: string): FormLink | undefined {
  return formLinks(def).find((l) => l.key === key)
}

/** Request question keys this workflow copied from a shared form, with the form key. */
export function copiedQuestions(def: Definition): Map<string, string> {
  const out = new Map<string, string>()
  for (const link of formLinks(def)) for (const local of Object.values(link.fields)) out.set(local, link.key)
  return out
}

/** Rewrites every /input/<key> reference inside a condition. */
function renameCondition(condition: Condition, rename: (key: string) => string): Condition {
  const walk = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(walk)
    if (node && typeof node === 'object') {
      const out: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
        out[k] = k === 'fieldRef' && typeof v === 'string' && v.startsWith(INPUT) ? INPUT + rename(v.slice(INPUT.length)) : walk(v)
      }
      return out
    }
    return node
  }
  return walk(condition) as Condition
}

/**
 * The request form (or some of its questions) as shared form content: questions, required flags, order, widgets,
 * rules and groups. Workflow-level settings (submit label, currency, help position, a pinned request view) stay here.
 */
export function formContentOf(def: Definition, keys: string[] = Object.keys(props(def))): FormContent {
  const wanted = new Set(keys.filter((k) => k in props(def)))
  const properties = Object.fromEntries([...wanted].map((k) => [k, structuredClone(props(def)[k]!)]))
  const required = (def.inputSchema.required ?? []).filter((k) => wanted.has(k))
  const ui = def.inputUi ?? {}
  const content: FormContent = { inputSchema: { type: 'object', additionalProperties: false, required, properties } as FormContent['inputSchema'] }
  const inputUi: NonNullable<FormContent['inputUi']> = {}
  const order = (ui.order ?? Object.keys(props(def))).filter((k) => wanted.has(k))
  if (order.length > 0) inputUi.order = order
  const widgets = Object.fromEntries(Object.entries(ui.widgets ?? {}).filter(([k]) => wanted.has(k)))
  if (Object.keys(widgets).length > 0) inputUi.widgets = widgets as NonNullable<FormContent['inputUi']>['widgets']
  for (const rule of RULES) {
    const entries = Object.entries(ui[rule] ?? {}).filter(([k]) => wanted.has(k))
    if (entries.length > 0) inputUi[rule] = Object.fromEntries(entries.map(([k, c]) => [k, structuredClone(c)])) as never
  }
  const sections = (ui.layout?.sections ?? []).map((s) => ({ ...structuredClone(s), fields: s.fields.filter((k) => wanted.has(k)) })).filter((s) => s.fields.length > 0)
  if (sections.length > 0) inputUi.layout = { sections: sections as never }
  if (Object.keys(inputUi).length > 0) content.inputUi = inputUi
  return content
}

const sharedProps = (content: FormContent) => content.inputSchema.properties as Record<string, unknown>
export const questionCount = (content: FormContent) => Object.keys(sharedProps(content)).length

export function questionLabel(content: FormContent, key: string): string {
  const q = sharedProps(content)[key] as { title?: string } | undefined
  return q?.title ?? key
}

/** Shared question keys this form already uses for a question that is not the same copied one. */
export function clashingKeys(def: Definition, content: FormContent, keep: Record<string, string> = {}): string[] {
  return Object.keys(sharedProps(content)).filter((k) => !(k in keep) && k in props(def))
}

/** A free key based on `key` (key2, key3, ...), also avoiding keys already chosen in this insert. */
export function suggestKey(def: Definition, key: string, taken: Iterable<string> = []): string {
  const used = new Set([...Object.keys(props(def)), ...taken])
  for (let i = 2; i < 1000; i++) if (!used.has(`${key}${i}`)) return `${key}${i}`
  return `${key}${Date.now()}`
}

/** Default choices for the clashing keys: rename each to a free key. */
export function defaultChoices(def: Definition, keys: string[], content?: FormContent): Record<string, ClashChoice> {
  const out: Record<string, ClashChoice> = {}
  const chosen = new Set<string>(content ? Object.keys(sharedProps(content)) : [])
  for (const k of keys) {
    const to = suggestKey(def, k, chosen)
    chosen.add(to)
    out[k] = { action: 'rename', to }
  }
  return out
}

/**
 * Problems with the clash choices, by shared key; inserting is allowed only when this is empty. Every clashing key
 * needs a choice; a new key must be a valid question key that is free here and not chosen twice.
 */
export function choiceProblems(def: Definition, content: FormContent, clashes: string[], choices: Record<string, ClashChoice>): Record<string, string> {
  const problems: Record<string, string> = {}
  const incoming = new Set(Object.keys(sharedProps(content)).filter((k) => !clashes.includes(k)))
  const seen = new Map<string, string>()
  for (const k of clashes) {
    const choice = choices[k]
    if (!choice) { problems[k] = 'Choose to rename or skip this question.'; continue }
    if (choice.action === 'skip') continue
    const to = choice.to.trim()
    if (!FIELD_KEY.test(to)) problems[k] = 'Start with a lowercase letter; use letters and digits only.'
    else if (to in props(def)) problems[k] = `This form already has a question with the key "${to}".`
    else if (incoming.has(to)) problems[k] = `The shared form also has a question with the key "${to}".`
    else if (seen.has(to)) problems[k] = `"${to}" is also chosen for ${seen.get(to)}.`
    else seen.set(to, k)
  }
  return problems
}

/** Shared key to the key the question gets here, from the choices (skipped questions are left out). */
export function mappingFor(content: FormContent, choices: Record<string, ClashChoice>, keep: Record<string, string> = {}): Record<string, string> {
  const out: Record<string, string> = {}
  for (const k of Object.keys(sharedProps(content))) {
    if (k in keep) out[k] = keep[k]!
    else {
      const choice = choices[k]
      if (choice?.action === 'skip') continue
      out[k] = choice?.action === 'rename' ? choice.to.trim() : k
    }
  }
  return out
}

function ensureUi(def: Definition): InputUiDoc {
  def.inputUi ??= {}
  return def.inputUi
}

/** Removes copied questions (by local key) with their required flag, order, widget, rules and group membership. */
function dropQuestions(def: Definition, locals: string[]) {
  if (locals.length === 0) return
  const gone = new Set(locals)
  for (const k of gone) delete def.inputSchema.properties[k]
  if (def.inputSchema.required) def.inputSchema.required = def.inputSchema.required.filter((k) => !gone.has(k))
  const ui = def.inputUi
  if (!ui) return
  if (ui.order) ui.order = ui.order.filter((k) => !gone.has(k))
  if (ui.widgets) for (const k of gone) delete ui.widgets[k]
  for (const rule of RULES) if (ui[rule]) for (const k of gone) delete ui[rule]![k]
  if (ui.layout?.sections) ui.layout.sections = ui.layout.sections.map((s) => ({ ...s, fields: s.fields.filter((k) => !gone.has(k)) })).filter((s) => s.fields.length > 0)
}

/** Copies the mapped questions of `content` into `def` (in place); existing copies of the same key are replaced. */
function copyQuestions(def: Definition, assetKey: string, content: FormContent, mapping: Record<string, string>) {
  const shared = content.inputSchema
  const sharedUi = content.inputUi ?? {}
  const rename = (k: string) => mapping[k] ?? k
  const ui = ensureUi(def)
  const required = new Set(def.inputSchema.required ?? [])
  const order = (ui.order ?? Object.keys(props(def))).filter((k) => k in props(def))
  for (const [k, local] of Object.entries(mapping)) {
    def.inputSchema.properties[local] = structuredClone((shared.properties as Record<string, never>)[k]!)
    if ((shared.required ?? []).includes(k)) required.add(local)
    else required.delete(local)
    const widget = sharedUi.widgets?.[k]
    if (widget) (ui.widgets ??= {})[local] = widget
    else if (ui.widgets) delete ui.widgets[local]
    for (const rule of RULES) {
      const condition = sharedUi[rule]?.[k] as Condition | undefined
      if (condition) (ui[rule] ??= {})[local] = renameCondition(condition, rename)
      else if (ui[rule]) delete ui[rule]![local]
    }
  }
  def.inputSchema.required = [...required]
  // Questions already on the form keep their place; new ones follow, in the shared form's order.
  const sharedOrder = [...(sharedUi.order ?? []), ...Object.keys(shared.properties)].filter((k, i, all) => k in mapping && all.indexOf(k) === i).map(rename)
  ui.order = [...order, ...sharedOrder.filter((k) => !order.includes(k))]
  if (Object.values(ui.widgets ?? {}).includes('money')) ui.currency = 'USD'
  // Groups come with the form: ids prefixed with the form key; a group keeps only the questions copied here, and a
  // question the designer has moved into one of this form's own groups stays there.
  const prefix = `${assetKey}-`
  const others = (ui.layout?.sections ?? []).filter((s) => !s.id.startsWith(prefix))
  const placed = new Set(others.flatMap((s) => s.fields))
  const copied = (sharedUi.layout?.sections ?? []).map((s) => ({ ...structuredClone(s), id: sectionId(prefix, s.id), fields: s.fields.filter((k) => k in mapping).map(rename).filter((k) => !placed.has(k)) })).filter((s) => s.fields.length > 0)
  const sections = [...others, ...copied] as NonNullable<InputUiDoc['layout']>['sections']
  if (sections && sections.length > 0) ui.layout = { ...ui.layout, sections }
  else if (ui.layout) delete ui.layout.sections
}

function sectionId(prefix: string, id: string): string {
  const full = `${prefix}${id}`
  return full.length <= 64 ? full : full.slice(0, 64).replace(/-+$/, '')
}

function setLink(def: Definition, link: FormLink) {
  const others = (def.dependencies.assets ?? []).filter((a) => !(a.kind === 'form' && a.key === link.key))
  def.dependencies.assets = [...others, { kind: 'form', key: link.key, version: link.version, target: FORM_TARGET, fields: { ...link.fields } }]
}

/**
 * Inserts one version of a shared form: copies its questions (clashing keys renamed or skipped as chosen; nothing is
 * ever overwritten) and pins the version. Call only when {@link choiceProblems} is empty.
 */
export function insertSharedForm(def: Definition, assetKey: string, version: number, content: FormContent, choices: Record<string, ClashChoice>): Definition {
  const next = structuredClone(def)
  const mapping = mappingFor(content, choices)
  for (const local of Object.values(mapping)) if (local in props(next)) throw new Error(`The question key "${local}" is already used.`)
  copyQuestions(next, assetKey, content, mapping)
  setLink(next, { kind: 'form', key: assetKey, version, target: FORM_TARGET, fields: mapping })
  return next
}

/** Links questions that are already in this form to a shared form version (after saving them as one). */
export function linkSharedForm(def: Definition, assetKey: string, version: number, fields: Record<string, string>): Definition {
  const next = structuredClone(def)
  setLink(next, { kind: 'form', key: assetKey, version, target: FORM_TARGET, fields })
  return next
}

/** Removes the link; the copied questions stay as this workflow's own. */
export function unlinkSharedForm(def: Definition, assetKey: string): Definition {
  if (!formLink(def, assetKey)) return def
  const next = structuredClone(def)
  next.dependencies.assets = (next.dependencies.assets ?? []).filter((a) => !(a.kind === 'form' && a.key === assetKey))
  if (next.dependencies.assets.length === 0) delete next.dependencies.assets
  return next
}

/** Keeps form links in step when a request question key is renamed. */
export function renameCopiedQuestion(def: Definition, oldKey: string, newKey: string): void {
  for (const a of def.dependencies.assets ?? []) {
    if (a.kind !== 'form' || !a.fields) continue
    for (const [shared, local] of Object.entries(a.fields)) if (local === oldKey) a.fields[shared] = newKey
  }
}

/**
 * One side reduced to the copied questions in the shared keys: definition, required, widget and rules. Order and groups
 * are presentation and not compared. References to questions outside the copy are marked (same as the server).
 */
function projection(schema: { properties: Record<string, unknown>; required?: string[] }, ui: InputUiDoc | FormContent['inputUi'] | undefined, keys: Record<string, string>) {
  const back = new Map(Object.entries(keys).map(([shared, local]) => [local, shared]))
  const rename = (k: string) => back.get(k) ?? `~${k}`
  const out = { questions: {} as Record<string, unknown>, required: [] as string[], widgets: {} as Record<string, unknown>, visibleWhen: {} as Record<string, unknown>, requiredWhen: {} as Record<string, unknown> }
  for (const shared of Object.keys(keys).sort()) {
    const local = keys[shared]!
    out.questions[shared] = schema.properties[local] ?? null
    if ((schema.required ?? []).includes(local)) out.required.push(shared)
    const widget = (ui?.widgets as Record<string, unknown> | undefined)?.[local]
    if (widget !== undefined) out.widgets[shared] = widget
    for (const rule of RULES) {
      const c = (ui?.[rule] as Record<string, Condition> | undefined)?.[local]
      if (c) out[rule][shared] = renameCondition(c, rename)
    }
  }
  return out
}

/** True when the copied questions still equal the pinned version (the server's WF_ASSET_DIVERGED check). */
export function copyMatches(def: Definition, link: FormLink, content: FormContent): boolean {
  if (Object.keys(link.fields).some((k) => !(k in sharedProps(content)))) return false
  const identity = Object.fromEntries(Object.keys(link.fields).map((k) => [k, k]))
  return canonical(projection(def.inputSchema, def.inputUi, link.fields)) === canonical(projection(content.inputSchema as never, content.inputUi, identity))
}

export interface FormDiff { added: string[]; removed: string[]; changed: string[] }

/** Questions added, removed or changed (definition, required, widget or rule) between two versions, by shared key. */
export function formDiff(from: FormContent, to: FormContent): FormDiff {
  const a = sharedProps(from)
  const b = sharedProps(to)
  const all = Object.fromEntries([...new Set([...Object.keys(a), ...Object.keys(b)])].map((k) => [k, k]))
  const pa = projection(from.inputSchema as never, from.inputUi, all)
  const pb = projection(to.inputSchema as never, to.inputUi, all)
  const added = Object.keys(b).filter((k) => !(k in a))
  const removed = Object.keys(a).filter((k) => !(k in b))
  const part = (p: typeof pa, k: string) => canonical([p.questions[k], p.required.includes(k), p.widgets[k] ?? null, p.visibleWhen[k] ?? null, p.requiredWhen[k] ?? null])
  const changed = Object.keys(b).filter((k) => k in a && part(pa, k) !== part(pb, k))
  return { added, removed, changed }
}

/**
 * Updates the copy to another version: removed questions are deleted here, changed ones replaced in place (keeping their
 * key here), added ones copied with the clash choices. Questions this workflow skipped stay skipped.
 */
export function updateSharedForm(def: Definition, link: FormLink, from: FormContent, to: FormContent, version: number, choices: Record<string, ClashChoice>): Definition {
  const next = structuredClone(def)
  const diff = formDiff(from, to)
  dropQuestions(next, diff.removed.map((k) => link.fields[k]).filter((k): k is string => !!k))
  const kept = Object.fromEntries(Object.entries(link.fields).filter(([k]) => k in sharedProps(to)))
  const skipped = Object.keys(sharedProps(from)).filter((k) => !(k in link.fields) && k in sharedProps(to))
  const mapping = mappingFor(to, { ...choices, ...Object.fromEntries(skipped.map((k) => [k, { action: 'skip' } as ClashChoice])) }, kept)
  copyQuestions(next, link.key, to, mapping)
  setLink(next, { kind: 'form', key: link.key, version, target: FORM_TARGET, fields: mapping })
  return next
}

/** Added questions of an update whose key this form already uses for another question. */
export function updateClashes(def: Definition, link: FormLink, from: FormContent, to: FormContent): string[] {
  return formDiff(from, to).added.filter((k) => k in props(def) && !Object.values(link.fields).includes(k))
}

/** The copied questions as shared content in the shared keys (to save the edited copy as a new version). */
export function copyAsContent(def: Definition, link: FormLink, base: FormContent): FormContent {
  const local = formContentOf(def, Object.values(link.fields))
  const back = new Map(Object.entries(link.fields).map(([shared, l]) => [l, shared]))
  const rename = (k: string) => back.get(k) ?? k
  const content: FormContent = {
    inputSchema: { type: 'object', additionalProperties: false, required: (local.inputSchema.required ?? []).map(rename), properties: Object.fromEntries(Object.entries(local.inputSchema.properties).map(([k, v]) => [rename(k), v])) } as FormContent['inputSchema'],
  }
  // Questions this workflow skipped keep their shared definition.
  for (const k of Object.keys(sharedProps(base))) {
    if (k in link.fields) continue
    ;(content.inputSchema.properties as Record<string, unknown>)[k] = structuredClone(sharedProps(base)[k])
    if ((base.inputSchema.required ?? []).includes(k)) content.inputSchema.required = [...(content.inputSchema.required ?? []), k]
  }
  const ui = local.inputUi ?? {}
  const baseUi = base.inputUi ?? {}
  const out: NonNullable<FormContent['inputUi']> = {}
  const keys = Object.keys(content.inputSchema.properties)
  out.order = [...(ui.order ?? []).map(rename), ...keys.filter((k) => !(ui.order ?? []).map(rename).includes(k))]
  const widgets = { ...Object.fromEntries(Object.entries(baseUi.widgets ?? {}).filter(([k]) => !(k in link.fields))), ...Object.fromEntries(Object.entries(ui.widgets ?? {}).map(([k, v]) => [rename(k), v])) }
  if (Object.keys(widgets).length) out.widgets = widgets as never
  for (const rule of RULES) {
    const entries = { ...Object.fromEntries(Object.entries(baseUi[rule] ?? {}).filter(([k]) => !(k in link.fields))), ...Object.fromEntries(Object.entries(ui[rule] ?? {}).map(([k, c]) => [rename(k), renameCondition(c as Condition, rename)])) }
    if (Object.keys(entries).length) out[rule] = entries as never
  }
  const prefix = `${link.key}-`
  const sections = (def.inputUi?.layout?.sections ?? []).filter((s) => s.id.startsWith(prefix)).map((s) => ({ ...structuredClone(s), id: s.id.slice(prefix.length), fields: s.fields.filter((k) => back.has(k)).map(rename) })).filter((s) => s.fields.length > 0)
  if (sections.length) out.layout = { sections: sections as never }
  else if (baseUi.layout?.sections?.length) out.layout = { sections: structuredClone(baseUi.layout.sections) }
  content.inputUi = out
  return content
}

/** A shared form key from a name: lowercase words joined by dashes. */
export function assetKeyOf(name: string): string {
  const key = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').replace(/^[^a-z]+/, '').slice(0, 64).replace(/-+$/, '')
  return key.length >= 2 ? key : 'shared-form'
}

export const isValidAssetKey = (key: string) => /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(key)
