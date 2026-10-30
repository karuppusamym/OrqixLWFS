export type CaseItemKind = 'humanTask' | 'processTask' | 'caseTask' | 'decisionTask' | 'milestone' | 'stage' | 'userEventListener' | 'timerEventListener'

/** A case file item event: the case data field gets its first value (create) or a changed value (update). */
export interface CaseFileEvent { field: string; event: 'create' | 'update' }

export type ConditionOperator = '==' | '!=' | '<' | '<=' | '>' | '>='
/** One comparison of a case data field with a literal; an entry rule can require all or any of them (CMMN if-part). */
export interface CaseConditionClause { field: string; op: ConditionOperator; value: string | number | boolean }
export interface CaseCondition { join: 'all' | 'any'; clauses: CaseConditionClause[] }
export const CONDITION_OPERATORS: ConditionOperator[] = ['==', '!=', '<', '<=', '>', '>=']

/** Workflow a process task runs: the workspace workflow key and explicit field mappings (pinned at publication). */
export interface CaseWorkflowRef {
  key: string
  /** Workflow request field -> case data field. */
  input: Record<string, string>
  /** Case data field -> workflow result field, or outcome / outcomeKind / reference. */
  result: Record<string, string>
}

/** Model element IDs kept from an imported CMMN file, so a round trip does not rename them. */
export interface CaseItemIds {
  definition?: string
  planItem?: string
  entry?: string
  sentry?: string
  exit?: string
  exitSentry?: string
}

export interface CaseItemLayout { x: number; y: number; w: number; h: number }

export interface CaseModelItem {
  id: string
  label: string
  kind: CaseItemKind
  /** Human task: eligible group. User event: authorized group. */
  group?: string
  required?: boolean
  manual?: boolean
  repetitions?: number
  /** Entry criterion: starts after this item of the same stage completes (or occurs). */
  after?: string
  /** Further items of the same stage that must also have completed (one sentry, all on-parts). */
  afterAll?: string[]
  /** Exit criterion: ends when this item of the same stage completes (or occurs). */
  exitAfter?: string
  /** In the stage's planning table: people of the planner groups decide at run time whether it is needed. */
  discretionary?: boolean
  planners?: string[]
  workflow?: CaseWorkflowRef
  /** Case task: the case definition key it starts; input is child field -> case field, result is case field -> child field or outcome. */
  childCase?: CaseWorkflowRef
  /** Decision task: the shared decision table key; input is table fieldRef -> case field, result is case field -> table output. */
  decision?: CaseWorkflowRef
  /** Timer: ISO-8601 duration (for example PT4H, P2D) counted from when the timer becomes available, or a date and time with an offset. */
  timer?: string
  /** Entry rule events on case data (CMMN case file item on-parts). */
  onData?: CaseFileEvent[]
  /** The entry rule's sources are alternatives (any of them starts the item, one entry criterion each) instead of all together. */
  anyOf?: boolean
  /** Entry rule condition on case data (CMMN if-part): with "after" it must hold when the event happens; alone, the item starts when the data makes it true. */
  when?: CaseCondition
  ids?: CaseItemIds
  /** Absolute diagram position (CMMNDI bounds). */
  layout?: CaseItemLayout
  children?: CaseModelItem[]
}

export interface CaseModelOptions {
  /** Emit CMMN 1.1 diagram interchange (positions of every shape and connector). */
  diagram?: boolean
  /** Imported IDs of the case plan model and case roles (group -> role ID). */
  planId?: string
  roleIds?: Record<string, string>
  planLayout?: CaseItemLayout
}

export const KIND_LABEL: Record<CaseItemKind, string> = {
  humanTask: 'Human task', processTask: 'Workflow task', caseTask: 'Case task', decisionTask: 'Decision task', milestone: 'Milestone', stage: 'Stage', userEventListener: 'User event', timerEventListener: 'Timer',
}
export const WORKFLOW_RESULT_SOURCES = ['outcome', 'outcomeKind', 'reference'] as const
export const SHAPE: Record<CaseItemKind, { w: number; h: number }> = {
  humanTask: { w: 180, h: 84 }, processTask: { w: 180, h: 84 }, caseTask: { w: 180, h: 84 }, decisionTask: { w: 180, h: 84 }, milestone: { w: 170, h: 52 }, userEventListener: { w: 150, h: 96 }, timerEventListener: { w: 150, h: 96 }, stage: { w: 420, h: 240 },
}

export function caseItems(items: CaseModelItem[]): CaseModelItem[] { return items.flatMap(item => [item, ...caseItems(item.children ?? [])]) }
function escape(value: string): string { return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;') }
const GROUP = /^[a-z][a-z0-9-]{1,62}$/
const ID = /^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/
export const occurs = (item: CaseModelItem | undefined) => item?.kind === 'milestone' || item?.kind === 'userEventListener' || item?.kind === 'timerEventListener'
export const isListener = (item: CaseModelItem | undefined) => item?.kind === 'userEventListener' || item?.kind === 'timerEventListener'
const FIELD = /^[A-Za-z][A-Za-z0-9_]{0,63}$/
const DURATION = /^P(?:\d{1,3}D)?(?:T(?:\d{1,4}H)?(?:\d{1,6}M)?(?:\d{1,8}S)?)?$/

/** Minutes of an ISO-8601 duration (days, hours, minutes, seconds), or null when it is not one. */
export function durationMinutes(value: string): number | null {
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value)
  if (!match || !DURATION.test(value) || value === 'P' || value.endsWith('T')) return null
  return Number(match[1] ?? 0) * 1440 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0) + Number(match[4] ?? 0) / 60
}

/** The if-part expression, for example ${cd_amount > 5000 && cd_region == 'EU'}; only this grammar is admitted. */
export function conditionExpression(condition: CaseCondition): string {
  const literal = (value: CaseConditionClause['value']) => typeof value === 'string' ? `'${value}'` : String(value)
  return `\${${condition.clauses.map(clause => `cd_${clause.field} ${clause.op} ${literal(clause.value)}`).join(condition.join === 'all' ? ' && ' : ' || ')}}`
}
export const hasEntry = (item: CaseModelItem) => !!item.after || !!item.when?.clauses.length || !!item.onData?.length
const TIMER_DATE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})$/
/** A timer date: an ISO date and time with an offset, from a minute to five years ahead. */
export function isTimerDate(value: string): boolean {
  if (!TIMER_DATE.test(value)) return false
  const at = Date.parse(value), now = Date.now()
  return Number.isFinite(at) && at >= now + 60_000 && at <= now + 5 * 366 * 86_400_000
}
/** The entry alternatives: with anyOf each source and each data event gets its own entry criterion. */
function alternatives(item: CaseModelItem): number { return item.anyOf ? entrySources(item).length + (item.onData?.length ?? 0) : hasEntry(item) ? 1 : 0 }

/** Every source this item's entry criterion waits for. */
export function entrySources(item: CaseModelItem): string[] { return item.after ? [item.after, ...(item.afterAll ?? []).filter(id => id !== item.after)] : [] }

export interface ItemRefs { definition: string; planItem: string; entry: string; sentry: string; exit: string; exitSentry: string }
export function refs(item: CaseModelItem): ItemRefs {
  const base = `node_${item.id}`
  return {
    definition: item.ids?.definition ?? base, planItem: item.ids?.planItem ?? `${base}_item`, entry: item.ids?.entry ?? `${base}_entry`,
    sentry: item.ids?.sentry ?? `${base}_sentry`, exit: item.ids?.exit ?? `${base}_exit`, exitSentry: item.ids?.exitSentry ?? `${base}_exit_sentry`,
  }
}

/** Positions for items that have none: rows of shapes inside their stage, stages sized around their children. */
export function autoLayout(items: CaseModelItem[], origin = { x: 40, y: 60 }): CaseModelItem[] {
  let x = origin.x, y = origin.y, rowHeight = 0
  const maxWidth = 1100
  const stageSize = (item: CaseModelItem, at: { x: number; y: number }) => {
    const inner = autoLayout(item.children ?? [], { x: at.x + 30, y: at.y + 50 })
    const box = bounds(inner)
    const size = box ? { w: Math.max(SHAPE.stage.w, box.x + box.w - at.x + 30), h: Math.max(SHAPE.stage.h, box.y + box.h - at.y + 40) } : SHAPE.stage
    return { inner, size }
  }
  return items.map(item => {
    if (item.layout) {
      if (item.kind !== 'stage') return item
      const { inner, size } = stageSize(item, item.layout)
      return { ...item, children: inner, layout: { ...item.layout, w: Math.max(item.layout.w, size.w), h: Math.max(item.layout.h, size.h) } }
    }
    let size = item.kind === 'stage' ? stageSize(item, { x, y }).size : SHAPE[item.kind]
    if (x + size.w > origin.x + maxWidth && x > origin.x) { x = origin.x; y += rowHeight + 60; rowHeight = 0 }
    let children = item.children
    if (item.kind === 'stage') { const placed = stageSize(item, { x, y }); children = placed.inner; size = placed.size }
    const placed = { ...item, children, layout: { x, y, w: size.w, h: size.h } }
    x += size.w + 70; rowHeight = Math.max(rowHeight, size.h)
    return placed
  })
}
export function bounds(items: CaseModelItem[]): CaseItemLayout | null {
  const boxes = items.map(item => item.layout).filter((box): box is CaseItemLayout => !!box)
  if (!boxes.length) return null
  const x = Math.min(...boxes.map(b => b.x)), y = Math.min(...boxes.map(b => b.y))
  return { x, y, w: Math.max(...boxes.map(b => b.x + b.w)) - x, h: Math.max(...boxes.map(b => b.y + b.h)) - y }
}

/** Groups that appear as case roles (user events and planners of discretionary items). */
function roleGroups(all: CaseModelItem[]): string[] {
  return [...new Set(all.flatMap(item => item.kind === 'userEventListener' ? [item.group!] : item.discretionary ? item.planners ?? [] : []))]
}

/** Checks every rule the server admits; throws the first problem in words. */
export function validateCaseModel(items: CaseModelItem[]): void {
  const all = caseItems(items), ids = new Set<string>()
  if (all.length < 1 || all.length > 30) throw new Error('Use 1 to 30 plan items.')
  for (const item of all) {
    const preserved = !!item.ids
    if (!(preserved ? ID : /^[A-Za-z][A-Za-z0-9_-]{0,39}$/).test(item.id) || ids.has(item.id)) throw new Error('Each plan item needs a unique stable ID of at most 40 characters.')
    ids.add(item.id)
    if (!item.label.trim() || item.label.length > 120) throw new Error('Every plan item needs a label of at most 120 characters.')
    if (item.kind === 'humanTask' && !GROUP.test(item.group ?? '')) throw new Error('Choose an eligible workspace group for every human task.')
    if (item.kind === 'userEventListener' && !GROUP.test(item.group ?? '')) throw new Error('Choose an authorized workspace group for every user event.')
    if (isListener(item) && (hasEntry(item) || item.exitAfter || item.required || item.manual || item.repetitions || item.discretionary)) throw new Error('User events and timers cannot have entry or exit rules, required/manual controls, repetition or planning.')
    if (item.kind === 'processTask') {
      if (!item.workflow || !GROUP.test(item.workflow.key)) throw new Error(`Choose the workflow that "${item.label}" runs.`)
      if (item.manual) throw new Error('Workflow tasks start automatically; make them discretionary to let people decide.')
    }
    if (item.kind === 'caseTask') {
      if (!item.childCase || !GROUP.test(item.childCase.key)) throw new Error(`Choose the case that "${item.label}" starts.`)
      if (item.manual) throw new Error('Case tasks start automatically; make them discretionary to let people decide.')
    }
    if (item.kind === 'timerEventListener') {
      const minutes = durationMinutes(item.timer ?? '')
      if (!isTimerDate(item.timer ?? '') && (minutes === null || minutes < 1 || minutes > 366 * 1440)) throw new Error(`Give timer "${item.label}" a duration from 1 minute to 366 days, or a date from a minute to five years ahead.`)
    }
    if (item.kind === 'decisionTask') {
      if (!item.decision || !/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(item.decision.key)) throw new Error(`Choose the decision that "${item.label}" evaluates.`)
      if (item.manual) throw new Error('Decision tasks run automatically; make them discretionary to let people decide.')
    }
    for (const event of item.onData ?? []) if (!FIELD.test(event.field) || !['create', 'update'].includes(event.event)) throw new Error(`Complete the case data events of "${item.label}".`)
    if (item.anyOf && item.when?.clauses.length && !entrySources(item).length && !item.onData?.length) throw new Error(`"${item.label}" needs events to choose from when it starts after any of them.`)
    if (item.when) {
      if (!item.when.clauses.length || item.when.clauses.length > 10) throw new Error(`The data condition of "${item.label}" needs 1 to 10 comparisons.`)
      for (const clause of item.when.clauses) {
        if (!FIELD.test(clause.field) || !CONDITION_OPERATORS.includes(clause.op)) throw new Error(`Complete the data condition of "${item.label}".`)
        if (typeof clause.value === 'string' && (/['\\$#{}]/.test(clause.value) || clause.value.length > 120)) throw new Error('Condition text cannot contain quotes, backslashes, $, #, { or } and is at most 120 characters.')
        if (typeof clause.value === 'number' && (!Number.isFinite(clause.value) || Math.abs(clause.value) >= 1e15)) throw new Error('Condition numbers must be finite.')
      }
    }
    if (item.repetitions !== undefined) {
      if (!Number.isInteger(item.repetitions) || item.repetitions < 2 || item.repetitions > 30) throw new Error('Repeat an item from 2 to 30 times.')
      if (isListener(item) || item.discretionary) throw new Error('User events, timers and discretionary items cannot repeat.')
      if (item.exitAfter) throw new Error('A repeated item cannot also have an exit rule.')
      if (item.kind === 'milestone' && !hasEntry(item)) throw new Error('A repeated milestone needs a "start after" rule; it repeats each time that rule is met again.')
    }
    if (item.kind === 'milestone' && item.manual) throw new Error('Milestones cannot use manual activation.')
    if (item.kind === 'milestone' && item.exitAfter) throw new Error('A milestone cannot have an exit rule.')
    if (item.discretionary) {
      if (!['humanTask', 'processTask', 'caseTask', 'decisionTask'].includes(item.kind)) throw new Error('Only human, workflow, case and decision tasks can be discretionary.')
      if (hasEntry(item) || item.exitAfter) throw new Error('Discretionary items start when someone plans them, so they cannot have entry or exit rules.')
      if (!item.planners?.length || item.planners.length > 10 || item.planners.some(group => !GROUP.test(group))) throw new Error(`Choose 1 to 10 groups who may plan "${item.label}".`)
    }
    if (item.kind !== 'stage' && item.children?.length) throw new Error('Only stages can contain other plan items.')
  }
  const generated = new Set<string>()
  const claim = (id: string) => { if (!ID.test(id)) throw new Error(`Generated CMMN ID ${id} is not valid.`); if (generated.has(id)) throw new Error('Plan item IDs conflict with generated CMMN references.'); generated.add(id) }
  for (const item of all) {
    const r = refs(item)
    claim(r.definition); claim(r.planItem)
    if (hasEntry(item) && !item.anyOf) { claim(r.entry); claim(r.sentry) }
    if (item.anyOf) for (let index = 1; index <= alternatives(item); index++) { claim(`${r.entry}_${index}`); claim(`${r.sentry}_${index}`) }
    if (item.exitAfter) { claim(r.exit); claim(r.exitSentry) }
    if (item.kind === 'processTask') claim(`${r.definition}_process`)
    if (item.kind === 'decisionTask') claim(`${r.definition}_decision`)
    if (item.discretionary) for (const suffix of ['__planner', '__planner_item', '__plan_entry', '__plan_sentry']) claim(`${r.planItem}${suffix}`)
  }
  const initial = (nodes: CaseModelItem[]): boolean => nodes.some(item => !hasEntry(item) && !item.discretionary && (item.kind === 'humanTask' || item.kind === 'processTask' || item.kind === 'caseTask' || item.kind === 'decisionTask' || isListener(item) || item.kind === 'stage' && initial(item.children ?? [])))
  if (!initial(items)) throw new Error('Keep a human task, workflow task, case task, user event, timer or a stage containing one available when the case starts.')
  const scope = (nodes: CaseModelItem[], depth: number) => {
    if (depth > 4 || !nodes.some(item => !item.discretionary)) throw new Error('Stages need work and support at most four nested levels.')
    const local = new Map(nodes.map(item => [item.id, item]))
    for (const item of nodes) {
      for (const source of [...entrySources(item), ...(item.exitAfter ? [item.exitAfter] : [])]) {
        const found = local.get(source)
        if (!found) throw new Error('An entry or exit rule must reference another item in the same stage.')
        if (found.discretionary) throw new Error('Rules cannot wait for a discretionary item; CMMN sentries refer to plan items only.')
        if (source === item.id) throw new Error('An item cannot wait for itself.')
      }
    }
    const visiting = new Set<string>(), done = new Set<string>()
    const visit = (id: string) => {
      if (done.has(id)) return
      if (visiting.has(id)) throw new Error('Entry rules cannot form a cycle.')
      visiting.add(id)
      for (const source of entrySources(local.get(id)!)) visit(source)
      visiting.delete(id); done.add(id)
    }
    for (const item of nodes) visit(item.id)
    for (const item of nodes) if (item.kind === 'stage') scope(item.children ?? [], depth + 1)
  }
  scope(items, 0)
}

/** Generate only the admitted native CMMN constructs; publication still goes through server/engine validation. */
export function caseModelXml(key: string, name: string, items: CaseModelItem[], options: CaseModelOptions = {}): string {
  if (!/^[a-z][a-z0-9-]{1,62}$/.test(key) || !name.trim() || name.length > 200) throw new Error('Enter a valid case key and name before generating its model.')
  validateCaseModel(items)
  const all = caseItems(items)
  const laid = options.diagram ? autoLayout(items) : items
  const lookup = new Map(caseItems(laid).map(item => [item.id, item]))
  const groups = roleGroups(all)
  const roleId = (group: string) => options.roleIds?.[group] ?? `case_role_${group}`
  const withIds = !!options.diagram || all.some(item => item.ids)
  const processes: string[] = []
  let caseTasks = 0
  const decisions: string[] = []
  const fileFields = new Set<string>()
  function plan(nodes: CaseModelItem[], owner: string): string {
    let planItems = '', definitions = '', sentries = '', table = ''
    for (const item of nodes) {
      const r = refs(item)
      const sources = entrySources(item).map(id => lookup.get(id)!)
      const controls = (item.repetitions ? `<repetitionRule><condition>\${repetitionCounter &lt; ${item.repetitions}}</condition></repetitionRule>` : '') + (item.required ? '<requiredRule><condition>${true}</condition></requiredRule>' : '') + (item.manual ? '<manualActivationRule><condition>${true}</condition></manualActivationRule>' : '')
      const onPart = (source: CaseModelItem, sentry: string, index: number) => `<planItemOnPart ${withIds ? `id="${sentry}_on${index + 1}" ` : ''}sourceRef="${refs(source).planItem}"><standardEvent>${occurs(source) ? 'occur' : 'complete'}</standardEvent></planItemOnPart>`
      if (item.discretionary) {
        table += `<discretionaryItem id="${r.planItem}" name="${escape(item.label)}" definitionRef="${r.definition}" authorizedRoleRefs="${(item.planners ?? []).map(roleId).join(' ')}">${controls ? `<itemControl>${controls}</itemControl>` : ''}</discretionaryItem>`
      } else {
        const exit = item.exitAfter ? lookup.get(item.exitAfter)! : undefined
        const condition = (sentry: string) => item.when?.clauses.length ? `<ifPart${withIds ? ` id="${sentry}_if"` : ''}><condition>${escape(conditionExpression(item.when))}</condition></ifPart>` : ''
        const fileParts = (events: CaseFileEvent[], sentry: string) => events.map((event, index) => { fileFields.add(event.field); return `<caseFileItemOnPart${withIds ? ` id="${sentry}_file${index + 1}"` : ''} sourceRef="file_${event.field}"><standardEvent>${event.event}</standardEvent></caseFileItemOnPart>` }).join('')
        let entries = ''
        if (item.anyOf) {
          const parts = [...sources.map((source, index) => (sentry: string) => onPart(source, sentry, index)), ...(item.onData ?? []).map(event => (sentry: string) => fileParts([event], sentry))]
          parts.forEach((part, index) => {
            const entry = `${r.entry}_${index + 1}`, sentry = `${r.sentry}_${index + 1}`
            entries += `<entryCriterion id="${entry}" sentryRef="${sentry}"/>`
            sentries += `<sentry id="${sentry}">${part(sentry)}${condition(sentry)}</sentry>`
          })
        } else if (hasEntry(item)) {
          entries = `<entryCriterion id="${r.entry}" sentryRef="${r.sentry}"/>`
          sentries += `<sentry id="${r.sentry}">${fileParts(item.onData ?? [], r.sentry)}${sources.map((source, index) => onPart(source, r.sentry, index)).join('')}${condition(r.sentry)}</sentry>`
        }
        planItems += `<planItem id="${r.planItem}" name="${escape(item.label)}" definitionRef="${r.definition}">${controls ? `<itemControl>${controls}</itemControl>` : ''}${entries}${exit ? `<exitCriterion id="${r.exit}" sentryRef="${r.exitSentry}"/>` : ''}</planItem>`
        if (exit) sentries += `<sentry id="${r.exitSentry}">${onPart(exit, r.exitSentry, 0)}</sentry>`
      }
      if (item.kind === 'processTask') processes.push(`<process id="${r.definition}_process" name="${escape(item.label)}" externalRef="orqixwf:${item.workflow!.key}"/>`)
      definitions += item.kind === 'stage' ? `<stage id="${r.definition}" name="${escape(item.label)}" autoComplete="false">${plan(item.children ?? [], r.definition)}</stage>`
        : item.kind === 'humanTask' ? `<humanTask id="${r.definition}" name="${escape(item.label)}" flowable:candidateGroups="${escape(item.group!)}"/>`
          : item.kind === 'processTask' ? `<processTask id="${r.definition}" name="${escape(item.label)}" processRef="${r.definition}_process" isBlocking="true"/>`
          : item.kind === 'decisionTask' ? (decisions.push(`<decision id="${r.definition}_decision" name="${escape(item.label)}" externalRef="orqixdec:${item.decision!.key}"/>`), `<decisionTask id="${r.definition}" name="${escape(item.label)}" decisionRef="${r.definition}_decision" isBlocking="true"/>`)
          : item.kind === 'caseTask' ? (caseTasks++, `<caseTask id="${r.definition}" name="${escape(item.label)}" caseRef="orqixcase:${item.childCase!.key}" isBlocking="true"/>`)
          : item.kind === 'timerEventListener' ? `<timerEventListener id="${r.definition}" name="${escape(item.label)}"><timerExpression>${item.timer}</timerExpression></timerEventListener>`
            : item.kind === 'userEventListener' ? `<userEventListener id="${r.definition}" name="${escape(item.label)}" authorizedRoleRefs="${roleId(item.group!)}"/>`
              : `<milestone id="${r.definition}" name="${escape(item.label)}"/>`
    }
    return planItems + sentries + (table ? `<planningTable id="${owner}_planning">${table}</planningTable>` : '') + definitions
  }
  const planId = options.planId ?? 'casePlan'
  const body = plan(laid, planId)
  const roles = groups.length ? `<caseRoles>${groups.map(group => `<role id="${roleId(group)}" name="${escape(group)}"/>`).join('')}</caseRoles>` : ''
  const diagram = options.diagram ? diagramXml(laid, planId, options.planLayout) : ''
  const fileModel = fileFields.size ? `<caseFileModel id="${planId}_file">${[...fileFields].sort().map(field => `<caseFileItem id="file_${field}" name="${field}" multiplicity="ZeroOrOne" definitionRef="file_value"/>`).join('')}</caseFileModel>` : ''
  const fileDefinition = fileFields.size ? '<caseFileItemDefinition id="file_value" name="Case data value" definitionType="http://www.omg.org/spec/CMMN/DefinitionType/Unspecified"/>' : ''
  const namespaces = (processes.length ? ' xmlns:orqixwf="urn:orqix:workflow"' : '') + (caseTasks ? ' xmlns:orqixcase="urn:orqix:case"' : '') + (decisions.length ? ' xmlns:orqixdec="urn:orqix:decision"' : '') + (options.diagram ? ' xmlns:cmmndi="http://www.omg.org/spec/CMMN/20151109/CMMNDI" xmlns:dc="http://www.omg.org/spec/CMMN/20151109/DC" xmlns:di="http://www.omg.org/spec/CMMN/20151109/DI"' : '')
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn"${namespaces} targetNamespace="urn:orqix:case-designer">${fileDefinition}<case id="${key}" name="${escape(name)}">${fileModel}<casePlanModel id="${planId}" name="${escape(name)}" autoComplete="false">${body}</casePlanModel>${roles}</case>${processes.join('')}${decisions.join('')}${diagram}</definitions>`
  if (new TextEncoder().encode(xml).length > 65536) throw new Error('The generated CMMN exceeds 64 KiB.')
  return xml
}

const n = (value: number) => String(Math.round(value))
function diagramXml(items: CaseModelItem[], planId: string, planLayout?: CaseItemLayout): string {
  const all = caseItems(items)
  const lookup = new Map(all.map(item => [item.id, item]))
  const box = bounds(items)!
  const frame = planLayout ?? { x: box.x - 30, y: box.y - 40, w: box.w + 60, h: box.h + 70 }
  let shapes = `<cmmndi:CMMNShape id="${planId}_di" cmmnElementRef="${planId}"><dc:Bounds x="${n(frame.x)}" y="${n(frame.y)}" width="${n(frame.w)}" height="${n(frame.h)}"/><cmmndi:CMMNLabel/></cmmndi:CMMNShape>`
  let edges = ''
  for (const item of all) {
    const r = refs(item), b = item.layout!
    shapes += `<cmmndi:CMMNShape id="${r.planItem}_di" cmmnElementRef="${r.planItem}"><dc:Bounds x="${n(b.x)}" y="${n(b.y)}" width="${n(b.w)}" height="${n(b.h)}"/><cmmndi:CMMNLabel/></cmmndi:CMMNShape>`
    const sources = entrySources(item)
    if (hasEntry(item) && !item.anyOf) {
      shapes += `<cmmndi:CMMNShape id="${r.entry}_di" cmmnElementRef="${r.entry}"><dc:Bounds x="${n(b.x - 6)}" y="${n(b.y + b.h / 2 - 10)}" width="12" height="20"/><cmmndi:CMMNLabel/></cmmndi:CMMNShape>`
      sources.forEach((id, index) => {
        const s = lookup.get(id)!.layout!
        edges += `<cmmndi:CMMNEdge id="${r.sentry}_on${index + 1}_di" cmmnElementRef="${r.sentry}_on${index + 1}" sourceCMMNElementRef="${refs(lookup.get(id)!).planItem}" targetCMMNElementRef="${r.entry}"><di:waypoint x="${n(s.x + s.w)}" y="${n(s.y + s.h / 2)}"/><di:waypoint x="${n(b.x - 6)}" y="${n(b.y + b.h / 2)}"/><cmmndi:CMMNLabel/></cmmndi:CMMNEdge>`
      })
    }
    if (item.exitAfter) {
      const s = lookup.get(item.exitAfter)!.layout!
      shapes += `<cmmndi:CMMNShape id="${r.exit}_di" cmmnElementRef="${r.exit}"><dc:Bounds x="${n(b.x + b.w - 6)}" y="${n(b.y + b.h / 2 - 10)}" width="12" height="20"/><cmmndi:CMMNLabel/></cmmndi:CMMNShape>`
      edges += `<cmmndi:CMMNEdge id="${r.exitSentry}_on1_di" cmmnElementRef="${r.exitSentry}_on1" sourceCMMNElementRef="${refs(lookup.get(item.exitAfter)!).planItem}" targetCMMNElementRef="${r.exit}"><di:waypoint x="${n(s.x + s.w / 2)}" y="${n(s.y + s.h)}"/><di:waypoint x="${n(b.x + b.w + 6)}" y="${n(b.y + b.h / 2)}"/><cmmndi:CMMNLabel/></cmmndi:CMMNEdge>`
    }
  }
  return `<cmmndi:CMMNDI><cmmndi:CMMNDiagram id="${planId}_diagram">${shapes}${edges}</cmmndi:CMMNDiagram></cmmndi:CMMNDI>`
}

/** The decision mappings the server needs alongside the CMMN source, one per decision task (keyed by its definition ID). */
export function caseModelDecisionTasks(items: CaseModelItem[]): Array<{ taskId: string; inputMapping: Record<string, string>; resultMapping: Record<string, string> }> {
  return caseItems(items).filter(item => item.kind === 'decisionTask' && item.decision).map(item => ({ taskId: refs(item).definition, inputMapping: { ...item.decision!.input }, resultMapping: { ...item.decision!.result } }))
}

/** The child case mappings the server needs alongside the CMMN source, one per case task (keyed by its definition ID). */
export function caseModelCaseTasks(items: CaseModelItem[]): Array<{ taskId: string; inputMapping: Record<string, string>; resultMapping: Record<string, string> }> {
  return caseItems(items).filter(item => item.kind === 'caseTask' && item.childCase).map(item => ({ taskId: refs(item).definition, inputMapping: { ...item.childCase!.input }, resultMapping: { ...item.childCase!.result } }))
}

/** The workflow mappings the server needs alongside the CMMN source, one per workflow task (keyed by its definition ID). */
export function caseModelWorkflowTasks(items: CaseModelItem[]): Array<{ taskId: string; inputMapping: Record<string, string>; resultMapping: Record<string, string> }> {
  return caseItems(items).filter(item => item.kind === 'processTask' && item.workflow).map(item => ({ taskId: refs(item).definition, inputMapping: { ...item.workflow!.input }, resultMapping: { ...item.workflow!.result } }))
}
