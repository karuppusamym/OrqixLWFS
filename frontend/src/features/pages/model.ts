import type { ViewDefinition } from '@/features/views/model'

/** UI02 / S111 application page model. The server repeats every check here and is authoritative. */
export type PageContext = 'none' | 'task' | 'case'
export type PageLayout = 'single' | 'sidebar' | 'split'
export type RegionId = 'main' | 'side' | 'start' | 'end'
export type ComponentType = 'heading' | 'text' | 'view' | 'list' | 'details' | 'actions' | 'extension'
export type ListSource = 'assignedTasks' | 'claimableTasks' | 'myRequests' | 'cases'
export type ChainAction = 'submit' | 'saveDraft' | 'claim' | 'approve' | 'reject' | 'saveCaseData' | 'navigate'

export interface Pin { id: string; version: number }
export interface PageField { field: string; label: string }
export interface PageCondition { binding: string; equals: string | number | boolean }

export interface PageComponent {
  id: string
  type: ComponentType
  text?: string
  label?: string
  view?: Pin
  workflowId?: string
  source?: ListSource | 'task' | 'case'
  columns?: PageField[]
  fields?: PageField[]
  limit?: number
  chains?: string[]
  extension?: Pin
  bindings?: string[]
  height?: number
  visibleWhen?: PageCondition
}

export interface PageRegion { id: RegionId; label: string; components: PageComponent[] }
export interface ChainStep { action: ChainAction; component?: string; target?: string }
export interface ActionChain { id: string; label: string; stopOnFailure: boolean; steps: ChainStep[] }

export interface PageDefinition {
  schemaVersion: 1
  kind: 'page'
  title: string
  context: PageContext
  layout: PageLayout
  breakpoint: 'sm' | 'md' | 'lg'
  regions: PageRegion[]
  chains: ActionChain[]
}

export interface ExtensionInfo {
  id: string
  version: number
  name: string
  status: 'pending' | 'approved' | 'rejected' | 'revoked'
  capabilities: { bindings: string[]; actions: ChainAction[] }
  height: number
}

export const LAYOUT_REGIONS: Record<PageLayout, Array<{ id: RegionId; label: string }>> = {
  single: [{ id: 'main', label: 'Main' }],
  sidebar: [{ id: 'main', label: 'Main' }, { id: 'side', label: 'Side panel' }],
  split: [{ id: 'start', label: 'Left' }, { id: 'end', label: 'Right' }],
}
export const TASK_FIELDS = ['requestTitle', 'reference', 'label', 'workflowName', 'requester', 'createdAt', 'dueAt', 'status', 'priority'] as const
export const LIST_COLUMNS: Record<ListSource, readonly string[]> = {
  assignedTasks: TASK_FIELDS,
  claimableTasks: TASK_FIELDS,
  myRequests: ['requestTitle', 'reference', 'workflowName', 'lifecycle', 'createdAt', 'updatedAt'],
  cases: ['status', 'definitionVersion', 'version', 'createdAt'],
}
export const LIST_LABELS: Record<ListSource, string> = { assignedTasks: 'My assigned tasks', claimableTasks: 'Tasks I can claim', myRequests: 'My requests', cases: 'Cases' }
export const STEP_ACTIONS: ChainAction[] = ['submit', 'saveDraft', 'claim', 'approve', 'reject', 'saveCaseData', 'navigate']
export const STEP_LABELS: Record<ChainAction, string> = { submit: 'Submit request', saveDraft: 'Save draft', claim: 'Claim task', approve: 'Approve', reject: 'Reject', saveCaseData: 'Save case data', navigate: 'Go to page' }
/** The view kind each step needs, and the view action it must declare. */
export const STEP_KIND: Record<Exclude<ChainAction, 'navigate'>, ViewDefinition['kind']> = { submit: 'request', saveDraft: 'request', claim: 'approval', approve: 'approval', reject: 'approval', saveCaseData: 'case' }
const STEP_VIEW_ACTION: Partial<Record<ChainAction, string>> = { submit: 'submit', saveDraft: 'saveDraft', approve: 'approve', reject: 'reject', saveCaseData: 'saveData' }
export const COMPONENT_LABELS: Record<ComponentType, string> = { heading: 'Heading', text: 'Text', view: 'Reusable view', list: 'Data list', details: 'Record details', actions: 'Action bar', extension: 'Extension' }

const ID = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/
const NAME = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/
const ROUTE = /^\/(?!\/)[A-Za-z0-9/_-]{1,199}$/
const trimmed = (value: string | undefined, max: number) => !!value && value.trim() === value && value.length <= max

export function blankPage(): PageDefinition {
  return { schemaVersion: 1, kind: 'page', title: 'New page', context: 'none', layout: 'single', breakpoint: 'md', regions: [{ id: 'main', label: 'Main', components: [{ id: 'heading1', type: 'heading', text: 'New page' }] }], chains: [] }
}

export function isPage(definition: unknown): definition is PageDefinition {
  return !!definition && typeof definition === 'object' && (definition as { kind?: unknown }).kind === 'page'
}

export function allComponents(page: PageDefinition): PageComponent[] {
  return page.regions.flatMap(region => region.components)
}

export function usedIds(page: PageDefinition): string[] {
  return [...allComponents(page).map(component => component.id), ...page.chains.map(chain => chain.id)]
}

/** Moves regions to match a layout, keeping components in order (extra regions fold into the last one). */
export function withLayout(page: PageDefinition, layout: PageLayout): PageDefinition {
  const target = LAYOUT_REGIONS[layout]
  const components = page.regions.map(region => region.components)
  const regions = target.map((region, index) => ({ id: region.id, label: page.regions[index]?.label ?? region.label, components: index === target.length - 1 ? components.slice(index).flat() : components[index] ?? [] }))
  return { ...page, layout, regions }
}

export function contextBindings(context: PageContext): string[] {
  if (context === 'task') return TASK_FIELDS.map(field => `task.${field}`)
  return []
}

/** task.<summary> and request.<field> on task pages; case.<field> on case pages. */
export function isContextBinding(binding: string, context: PageContext): boolean {
  const dot = binding.indexOf('.')
  if (dot < 1 || !NAME.test(binding.slice(dot + 1))) return false
  const scope = binding.slice(0, dot), name = binding.slice(dot + 1)
  if (context === 'task') return scope === 'task' ? (TASK_FIELDS as readonly string[]).includes(name) : scope === 'request'
  if (context === 'case') return scope === 'case'
  return false
}

export interface PageLookups {
  /** Pinned view definitions by `${id}:${version}`; missing entries are reported as not loaded. */
  views: Map<string, ViewDefinition | PageDefinition>
  extensions: Map<string, ExtensionInfo>
}
export const pinKey = (pin: Pin) => `${pin.id}:${pin.version}`

/** Local checks mirroring the server. Reference checks run for pins present in `lookups`. */
export function validatePage(page: PageDefinition, lookups?: PageLookups, publishing = false): string[] {
  const problems: string[] = []
  const ids = new Set<string>()
  const unique = (id: string, what: string) => {
    if (!ID.test(id) || ids.has(id)) problems.push(`${what} ID ${id || '(empty)'} must be a unique identifier.`)
    ids.add(id)
  }
  if (!trimmed(page.title, 120) || !page.title) problems.push('Give the page a title of at most 120 characters.')
  const expected = LAYOUT_REGIONS[page.layout]
  if (page.regions.length !== expected.length || page.regions.some((region, index) => region.id !== expected[index]?.id)) problems.push('Regions must match the layout.')
  const components = allComponents(page)
  if (components.length < 1 || components.length > 24) problems.push('Use 1 to 24 components.')
  for (const region of page.regions) {
    if (!trimmed(region.label, 120) || !region.label) problems.push(`Region ${region.id} needs a label.`)
    if (region.components.length > 12) problems.push(`Region ${region.label} can hold at most 12 components.`)
  }
  const chainIds = new Set(page.chains.map(chain => chain.id))
  for (const component of components) {
    unique(component.id, 'Component')
    const name = component.id
    if (component.visibleWhen && (!isContextBinding(component.visibleWhen.binding, page.context) || (typeof component.visibleWhen.equals === 'string' && component.visibleWhen.equals.length > 200))) problems.push(`Component ${name} shows conditionally on a binding the page context does not provide.`)
    switch (component.type) {
      case 'heading': if (!trimmed(component.text, 200) || !component.text) problems.push(`Heading ${name} needs text of at most 200 characters.`); break
      case 'text': if (!trimmed(component.text, 1000) || !component.text) problems.push(`Text ${name} needs text of at most 1000 characters.`); break
      case 'view': {
        if (!component.view?.id || !(component.view.version >= 1)) { problems.push(`Component ${name} must pin an exact published view version.`); break }
        const view = lookups?.views.get(pinKey(component.view))
        if (!view) break
        if (view.kind === 'page') { problems.push(`Component ${name} cannot embed another page.`); break }
        if (view.kind === 'request' && !component.workflowId) problems.push(`Component ${name} needs a workflow to submit the request view to.`)
        if (view.kind !== 'request' && component.workflowId) problems.push(`Component ${name} may name a workflow only for a request view.`)
        if (view.kind === 'approval' && page.context !== 'task') problems.push(`Component ${name}: an approval view needs a task page.`)
        if (view.kind === 'case' && page.context !== 'case') problems.push(`Component ${name}: a case view needs a case page.`)
        if (view.sections.some(section => section.fields.some(field => field.type === 'file' || field.type === 'row'))) problems.push(`Component ${name} uses a file or row control, which pages do not render.`)
        break
      }
      case 'list': {
        if (!trimmed(component.label, 120) || !component.label) problems.push(`List ${name} needs a label.`)
        const source = component.source as ListSource
        if (!LIST_COLUMNS[source]) { problems.push(`List ${name} needs a data source.`); break }
        fieldsProblems(component.columns, 10, field => LIST_COLUMNS[source].includes(field), `List ${name}`, problems)
        if (!component.limit || component.limit < 1 || component.limit > 50) problems.push(`List ${name} shows 1 to 50 rows.`)
        break
      }
      case 'details':
        if (!trimmed(component.label, 120) || !component.label) problems.push(`Details ${name} needs a label.`)
        if (page.context === 'none' || component.source !== page.context) problems.push(`Details ${name} must read the page's ${page.context === 'none' ? 'task or case context' : page.context}.`)
        fieldsProblems(component.fields, 20, field => isContextBinding(field, page.context), `Details ${name}`, problems)
        break
      case 'actions':
        if (!component.chains?.length || component.chains.length > 10 || component.chains.some(chain => !chainIds.has(chain))) problems.push(`Action bar ${name} must list 1 to 10 chains of this page.`)
        break
      case 'extension': {
        if (!trimmed(component.label, 120) || !component.label) problems.push(`Extension ${name} needs a label.`)
        if (!component.extension?.id) { problems.push(`Extension ${name} must pin an extension version.`); break }
        const bindings = component.bindings ?? []
        if (bindings.length > 20 || new Set(bindings).size !== bindings.length || bindings.some(binding => !isContextBinding(binding, page.context))) problems.push(`Extension ${name} grants bindings the page context does not provide.`)
        if ((component.chains ?? []).some(chain => !chainIds.has(chain))) problems.push(`Extension ${name} grants chains that are not on this page.`)
        const info = lookups?.extensions.get(pinKey(component.extension))
        if (!info) break
        if (publishing ? info.status !== 'approved' : info.status === 'rejected' || info.status === 'revoked') problems.push(`Extension ${name} uses a version that is ${info.status === 'pending' ? 'still waiting for approval' : info.status}.`)
        for (const binding of bindings) if (!info.capabilities.bindings.includes(binding)) problems.push(`Extension ${name} grants ${binding}, which the extension does not declare.`)
        for (const chainId of component.chains ?? []) for (const step of page.chains.find(chain => chain.id === chainId)?.steps ?? []) if (!info.capabilities.actions.includes(step.action)) problems.push(`Extension ${name} grants chain ${chainId} with ${step.action}, which the extension does not declare.`)
        break
      }
    }
  }
  if (page.chains.length > 10) problems.push('Use at most 10 chains.')
  const byId = new Map(components.map(component => [component.id, component]))
  for (const chain of page.chains) {
    unique(chain.id, 'Chain')
    problems.push(...chainProblems(chain, byId, lookups))
  }
  return [...new Set(problems)]
}

export function chainProblems(chain: ActionChain, components: Map<string, PageComponent>, lookups?: PageLookups): string[] {
  const problems: string[] = []
  const name = chain.label || chain.id
  if (!trimmed(chain.label, 80) || !chain.label) problems.push(`Chain ${chain.id} needs a label of at most 80 characters.`)
  if (!chain.stopOnFailure) problems.push(`Chain ${name} must stop at the first failed step.`)
  if (chain.steps.length < 1 || chain.steps.length > 5) problems.push(`Chain ${name} needs 1 to 5 steps.`)
  let decisions = 0, claimed = false
  const submitted = new Set<string>()
  chain.steps.forEach((step, index) => {
    const at = `Chain ${name}, step ${index + 1}`
    if (step.action === 'navigate') {
      if (!step.target || !ROUTE.test(step.target) || step.target.includes('..')) problems.push(`${at} needs a same-app path.`)
      if (index !== chain.steps.length - 1) problems.push(`${at}: navigation must be the last step.`)
      return
    }
    const component = step.component ? components.get(step.component) : undefined
    if (!component || component.type !== 'view') { problems.push(`${at} must target a view component on this page.`); return }
    if (step.action === 'approve' || step.action === 'reject') { if (++decisions > 1) problems.push(`${at}: a chain can contain only one approve or reject step.`) }
    if (step.action === 'claim') { if (claimed || decisions > 0) problems.push(`${at}: claim may appear once, before a decision.`); claimed = true }
    if (step.action === 'submit') { if (submitted.has(component.id)) problems.push(`${at}: a request can be submitted once per chain.`); submitted.add(component.id) }
    const view = component.view ? lookups?.views.get(pinKey(component.view)) : undefined
    if (!view || view.kind === 'page') return
    const kind = STEP_KIND[step.action]
    if (view.kind !== kind) problems.push(`${at} needs a ${kind} view; ${component.id} pins a ${view.kind} view.`)
    const declared = STEP_VIEW_ACTION[step.action]
    if (declared && view.kind === kind && !view.actions.some(action => action.type === declared)) problems.push(`${at}: the pinned view of ${component.id} has no ${declared} action.`)
  })
  return problems
}

function fieldsProblems(fields: PageField[] | undefined, max: number, allowed: (field: string) => boolean, what: string, problems: string[]) {
  if (!fields?.length || fields.length > max) { problems.push(`${what} needs 1 to ${max} fields.`); return }
  const seen = new Set<string>()
  for (const field of fields) {
    if (!allowed(field.field) || seen.has(field.field)) problems.push(`${what}: ${field.field || '(empty)'} is not an available field.`)
    if (!trimmed(field.label, 120) || !field.label) problems.push(`${what}: every field needs a label.`)
    seen.add(field.field)
  }
}

export function uniqueComponentId(page: PageDefinition, base: string): string {
  const taken = new Set(usedIds(page))
  let index = 1
  while (taken.has(`${base}${index}`)) index++
  return `${base}${index}`
}

/** A new component of the given type with valid defaults for the page context. */
export function newComponent(page: PageDefinition, type: ComponentType): PageComponent {
  const id = uniqueComponentId(page, type)
  switch (type) {
    case 'heading': return { id, type, text: 'Heading' }
    case 'text': return { id, type, text: 'Explain what this page is for.' }
    case 'view': return { id, type, view: { id: '', version: 0 } }
    case 'list': return { id, type, label: 'My assigned tasks', source: 'assignedTasks', columns: [{ field: 'requestTitle', label: 'Request' }, { field: 'status', label: 'Status' }], limit: 10 }
    case 'details': return { id, type, label: 'Details', source: page.context === 'case' ? 'case' : 'task', fields: [{ field: page.context === 'case' ? 'case.field' : 'task.requestTitle', label: page.context === 'case' ? 'Field' : 'Request' }] }
    case 'actions': return { id, type, label: 'Actions', chains: page.chains.slice(0, 1).map(chain => chain.id) }
    case 'extension': return { id, type, label: 'Extension', extension: { id: '', version: 0 }, bindings: [], chains: [], height: 240 }
  }
}

/** Components on the page whose pinned view suits a step (by kind) for the chain editor's target list. */
export function stepTargets(page: PageDefinition, action: ChainAction, lookups?: PageLookups): PageComponent[] {
  if (action === 'navigate') return []
  return allComponents(page).filter(component => {
    if (component.type !== 'view' || !component.view) return false
    const view = lookups?.views.get(pinKey(component.view))
    return !view || view.kind === STEP_KIND[action]
  })
}

/** Evaluates presentation-only visibility. Hiding a component never grants or denies an operation. */
export function visible(component: PageComponent, data: Record<string, unknown>): boolean {
  if (!component.visibleWhen) return true
  return data[component.visibleWhen.binding] === component.visibleWhen.equals
}
