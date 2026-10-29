import { durationMinutes, isTimerDate, type CaseCondition, type CaseFileEvent, type CaseConditionClause, type CaseItemLayout, type CaseModelItem, type CaseModelOptions, type ConditionOperator } from './caseModel'

const CMMN = 'http://www.omg.org/spec/CMMN/20151109/MODEL'
const CMMNDI = 'http://www.omg.org/spec/CMMN/20151109/CMMNDI'
const DC = 'http://www.omg.org/spec/CMMN/20151109/DC'
const FLOWABLE = 'http://flowable.org/cmmn'
const WORKFLOW_NS = 'urn:orqix:workflow'
const CASE_NS = 'urn:orqix:case'
const DECISION_NS = 'urn:orqix:decision'
const CLAUSE = /^cd_([A-Za-z][A-Za-z0-9_]{0,63})\s*(==|!=|<=|>=|<|>)\s*(-?\d{1,15}(?:\.\d{1,6})?|'[^'\\$#{}]{0,120}'|true|false)$/

/** Reads an if-part condition in the admitted grammar (case data fields compared with literals, joined by && or ||). */
export function parseCondition(text: string): CaseCondition | null {
  const body = /^\$\{(.*)\}$/s.exec(text.trim())?.[1]?.trim()
  if (!body) return null
  const join = body.includes('||') ? 'any' : 'all'
  if (body.includes('&&') && body.includes('||')) return null
  const clauses: CaseConditionClause[] = []
  for (const part of body.split(join === 'all' ? '&&' : '||')) {
    const match = CLAUSE.exec(part.trim())
    if (!match) return null
    const raw = match[3]!
    clauses.push({ field: match[1]!, op: match[2] as ConditionOperator, value: raw.startsWith("'") ? raw.slice(1, -1) : raw === 'true' ? true : raw === 'false' ? false : Number(raw) })
  }
  return clauses.length && clauses.length <= 10 ? { join, clauses } : null
}

function qnameKey(element: Element, attribute: string, namespace: string): string {
  const ref = element.getAttribute(attribute)?.trim() ?? ''
  const colon = ref.indexOf(':')
  const prefix = colon < 0 ? null : ref.slice(0, colon), local = colon < 0 ? ref : ref.slice(colon + 1)
  return element.lookupNamespaceURI(prefix) === namespace && /^[a-z][a-z0-9-]{1,62}$/.test(local) ? local : ''
}

/** One finding about the imported file, linked to the element it concerns. */
export interface ImportDiagnostic {
  severity: 'error' | 'warning'
  /** Element path such as casePlanModel[plan]/stage[review]/planItem[collect]. */
  path: string
  elementId?: string
  message: string
}

export interface ImportedCaseModel {
  key: string
  name: string
  items: CaseModelItem[]
  options: CaseModelOptions
  diagnostics: ImportDiagnostic[]
}

const elements = (parent: Element, name?: string) => [...parent.children].filter(child => child.namespaceURI === CMMN && (!name || child.localName === name))
function pathOf(element: Element): string {
  const parts: string[] = []
  for (let node: Element | null = element; node && node.localName !== 'definitions'; node = node.parentElement)
    parts.unshift(node.getAttribute('id') ? `${node.localName}[${node.getAttribute('id')}]` : node.localName)
  return parts.join('/')
}

/**
 * Reads a CMMN 1.1 file into the case model designer. Model and diagram IDs, labels, positions, controls, criteria,
 * planning tables and workflow references are kept. Anything the executable subset cannot run is listed with the path
 * of the element it concerns; errors are left out of the imported model, warnings are approximated.
 */
export function importCaseModel(xml: string): ImportedCaseModel {
  const diagnostics: ImportDiagnostic[] = []
  const report = (severity: ImportDiagnostic['severity'], element: Element, message: string) =>
    diagnostics.push({ severity, path: pathOf(element), elementId: element.getAttribute('id') ?? undefined, message })
  const document = new DOMParser().parseFromString(xml, 'application/xml')
  const root = document.documentElement
  if (document.querySelector('parsererror') || root.namespaceURI !== CMMN || root.localName !== 'definitions')
    throw new Error('This is not a CMMN 1.1 file (expected <definitions> in the CMMN 1.1 namespace).')
  const cases = elements(root, 'case')
  if (!cases.length) throw new Error('The file has no case.')
  if (cases.length > 1) for (const other of cases.slice(1)) report('error', other, 'Only the first case of a file is imported; this case was left out.')
  const caseElement = cases[0]!
  const plan = elements(caseElement, 'casePlanModel')[0]
  if (!plan) throw new Error('The case has no case plan model.')

  const roles = new Map<string, string>(), roleIds: Record<string, string> = {}
  for (const role of elements(caseElement, 'caseRoles').flatMap(group => elements(group, 'role'))) {
    const name = role.getAttribute('name') ?? ''
    if (!/^[a-z][a-z0-9-]{1,62}$/.test(name)) report('warning', role, `Case role "${name}" does not name a workspace group key; choose a group for the items that use it.`)
    roles.set(role.getAttribute('id') ?? '', name)
    if (role.getAttribute('id')) roleIds[name] = role.getAttribute('id')!
  }
  const processes = new Map(elements(root, 'process').map(process => [process.getAttribute('id') ?? '', process]))
  const decisions = new Map(elements(root, 'decision').map(decision => [decision.getAttribute('id') ?? '', decision]))
  const fileItems = new Map<string, string>()
  for (const model of elements(caseElement, 'caseFileModel')) for (const item of elements(model)) {
    if (item.localName !== 'caseFileItem') { report('error', item, `<${item.localName}> in the case file is not supported.`); continue }
    const name = item.getAttribute('name') ?? ''
    if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(name)) report('error', item, `Case file item "${name}" must be named after a case data field; its events were left out.`)
    else fileItems.set(item.getAttribute('id') ?? '', name)
    if (item.getAttribute('multiplicity') && !['ExactlyOne', 'ZeroOrOne'].includes(item.getAttribute('multiplicity')!)) report('warning', item, 'The item holds a list; Orqix case data fields hold one value.')
    if (elements(item).length) report('warning', item, 'Nested case file items were left out.')
  }
  if (fileItems.size) diagnostics.push({ severity: 'warning', path: pathOf(caseElement), elementId: caseElement.getAttribute('id') ?? undefined, message: `Declare case data fields named ${[...new Set(fileItems.values())].join(', ')} for the case file items.` })
  for (const child of [...root.children]) {
    if (child.namespaceURI === CMMNDI && child.localName === 'CMMNDI') continue
    if (child.namespaceURI !== CMMN || !['case', 'process', 'decision', 'caseFileItemDefinition'].includes(child.localName))
      report(child.localName === 'documentation' ? 'warning' : 'error', child, `<${child.localName}> is not used by the case designer and was left out.`)
  }
  for (const child of elements(caseElement)) {
    if (!['casePlanModel', 'caseRoles', 'caseFileModel'].includes(child.localName))
      report('error', child, `<${child.localName}> of the case was left out.`)
  }

  const layouts = new Map<string, CaseItemLayout>()
  for (const shape of [...document.getElementsByTagNameNS(CMMNDI, 'CMMNShape')]) {
    const bounds = [...shape.children].find(child => child.namespaceURI === DC && child.localName === 'Bounds')
    const ref = shape.getAttribute('cmmnElementRef')
    if (!bounds || !ref) continue
    const value = (name: string) => Number(bounds.getAttribute(name))
    const box = { x: value('x'), y: value('y'), w: value('width'), h: value('height') }
    if (Object.values(box).every(Number.isFinite)) layouts.set(ref, box)
  }

  const flag = (rule: Element | undefined, what: string): boolean | undefined => {
    if (!rule) return undefined
    const condition = elements(rule, 'condition')[0]?.textContent?.trim()
    if (condition === undefined || condition === '${true}') return true
    if (condition === '${false}') return false
    report('error', rule, `The ${what} condition "${condition}" is an expression; only constant true or false is supported. It was treated as false.`)
    return false
  }
  const rule = (controls: Element[], name: string) => controls.flatMap(control => elements(control, name))[0]
  const claimedSentries = new Set<string>()

  function readPlan(container: Element, depth: number): CaseModelItem[] {
    const definitions = new Map<string, Element>(), sentries = new Map<string, Element>()
    for (const child of elements(container)) {
      if (child.localName === 'sentry') sentries.set(child.getAttribute('id') ?? '', child)
      else if (!['planItem', 'planningTable', 'defaultControl'].includes(child.localName)) definitions.set(child.getAttribute('id') ?? '', child)
    }
    for (const child of [...container.children]) if (child.namespaceURI !== CMMN) report('warning', child, `Extension element <${child.localName}> was left out.`)
    const items: CaseModelItem[] = []
    const used = new Set<string>()
    const readItem = (reference: Element, discretionary: boolean): CaseModelItem | null => {
      const definition = definitions.get(reference.getAttribute('definitionRef') ?? '')
      if (!definition) { report('error', reference, 'Its definition is missing from this stage, so it was left out.'); return null }
      used.add(definition.getAttribute('id') ?? '')
      const kind = definition.localName
      if (!['humanTask', 'processTask', 'caseTask', 'decisionTask', 'milestone', 'stage', 'userEventListener', 'timerEventListener'].includes(kind)) {
        report('error', definition, `<${kind}> is not supported by the executable case subset and was left out.`)
        return null
      }
      if (discretionary && !['humanTask', 'processTask', 'caseTask', 'decisionTask'].includes(kind)) { report('error', reference, 'Only human, workflow, case and decision tasks can be discretionary; this item was left out.'); return null }
      const controls = [...elements(reference, 'itemControl'), ...elements(definition, 'defaultControl')]
      const id = reference.getAttribute('id') ?? ''
      const item: CaseModelItem = {
        id, kind: kind as CaseModelItem['kind'],
        label: (reference.getAttribute('name') || definition.getAttribute('name') || id).slice(0, 120),
        ids: { definition: definition.getAttribute('id') ?? undefined, planItem: id },
      }
      const required = flag(rule(controls, 'requiredRule'), 'required')
      if (required) item.required = true
      const manual = flag(rule(controls, 'manualActivationRule'), 'manual activation')
      if (manual) item.manual = true
      const repetition = rule(controls, 'repetitionRule')
      if (repetition) {
        const condition = elements(repetition, 'condition')[0]?.textContent?.trim() ?? ''
        const bound = /^\$\{repetitionCounter\s*<\s*(\d+)\}$/.exec(condition)
        if (bound && Number(bound[1]) >= 2 && Number(bound[1]) <= 30) item.repetitions = Number(bound[1])
        else report('error', repetition, `Repetition "${condition || 'without a condition'}" is not a finite counter from 2 to 30; the item was imported without repetition.`)
      }
      if (kind === 'humanTask') {
        const groups = (definition.getAttributeNS(FLOWABLE, 'candidateGroups') ?? '').split(',').map(value => value.trim()).filter(Boolean)
        item.group = groups[0] ?? ''
        if (!groups.length) report('warning', definition, 'No eligible group was given; choose one.')
        if (groups.length > 1) report('warning', definition, `Several candidate groups (${groups.join(', ')}); the designer keeps the first.`)
        const assignee = definition.getAttributeNS(FLOWABLE, 'assignee')
        if (assignee) report('error', definition, 'A fixed assignee is not supported; eligibility comes from the group.')
      }
      if (kind === 'userEventListener') {
        const refs = (definition.getAttribute('authorizedRoleRefs') ?? '').trim().split(/\s+/).filter(Boolean)
        item.group = roles.get(refs[0] ?? '') ?? ''
        if (!refs.length) report('warning', definition, 'No authorized role; choose the group who may trigger the event.')
        if (refs.length > 1) report('warning', definition, 'Several authorized roles; the designer keeps the first.')
      }
      if (kind === 'processTask') {
        const process = processes.get(definition.getAttribute('processRef') ?? '')
        const external = process?.getAttribute('externalRef')?.trim() ?? ''
        const [prefix, local] = external.includes(':') ? [external.slice(0, external.indexOf(':')), external.slice(external.indexOf(':') + 1)] : [null, external]
        const workflowKey = process && process.lookupNamespaceURI(prefix) === WORKFLOW_NS && /^[a-z][a-z0-9-]{1,62}$/.test(local) ? local : ''
        item.workflow = { key: workflowKey, input: {}, result: {} }
        if (!workflowKey) report('warning', definition, `The process "${external || definition.getAttribute('processRef') || ''}" is not a workspace workflow; choose the workflow it should run.`)
        if (elements(definition, 'parameterMapping').length) report('warning', definition, 'Parameter mappings were left out; map case data to the workflow request explicitly.')
        report('warning', definition, 'Map case data to the workflow request and its result before publishing.')
      }
      if (kind === 'decisionTask') {
        const decision = decisions.get(definition.getAttribute('decisionRef') ?? '')
        const decisionKey = decision ? qnameKey(decision, 'externalRef', DECISION_NS) : ''
        item.decision = { key: decisionKey, input: {}, result: {} }
        if (!decisionKey) report('warning', definition, 'The decision is not a shared decision table of the workspace; choose the decision it should evaluate.')
        report('warning', definition, 'Map case data to the decision table and its result before publishing.')
      }
      if (kind === 'caseTask') {
        const childKey = qnameKey(definition, 'caseRef', CASE_NS)
        item.childCase = { key: childKey, input: {}, result: {} }
        if (!childKey) report('warning', definition, `The case "${definition.getAttribute('caseRef') ?? ''}" is not a workspace case definition; choose the case it should start.`)
        if (elements(definition, 'parameterMapping').length) report('warning', definition, 'Parameter mappings were left out; map case data to the child case explicitly.')
        report('warning', definition, 'Map case data to the child case and its result before publishing.')
      }
      if (kind === 'timerEventListener') {
        const expression = elements(definition, 'timerExpression')[0]?.textContent?.trim() ?? ''
        const minutes = durationMinutes(expression)
        if (!isTimerDate(expression) && (minutes === null || minutes < 1 || minutes > 366 * 1440)) { report('error', definition, `Timer "${expression}" is not a duration from 1 minute to 366 days (dates, cycles and expressions are not supported); the timer was left out.`); return null }
        item.timer = expression
        if (elements(definition, 'planItemStartTrigger').length || elements(definition, 'caseFileItemStartTrigger').length) report('warning', definition, 'The timer start trigger was left out; the timer counts from when its stage starts.')
      }
      if (kind === 'stage') {
        if (definition.getAttribute('autoComplete') === 'true') report('warning', definition, 'The stage completed automatically; the designer uses explicit completion (autoComplete=false).')
        item.children = depth >= 4 ? [] : readPlan(definition, depth + 1)
        if (depth >= 4) report('error', definition, 'More than four nested stages are not supported; its content was left out.')
      }
      if (discretionary) {
        item.discretionary = true
        item.planners = (reference.getAttribute('authorizedRoleRefs') ?? '').trim().split(/\s+/).filter(Boolean).map(ref => roles.get(ref) ?? '').filter(Boolean)
        if (!item.planners.length) report('warning', reference, 'No planner role; choose who may plan this item.')
        if (reference.getAttribute('applicabilityRuleRefs')) report('error', reference, 'Applicability rules are not supported; everyone in the planner groups may plan it.')
        for (const criterion of [...elements(reference, 'entryCriterion'), ...elements(reference, 'exitCriterion')]) report('error', criterion, 'Criteria on discretionary items are not supported and were left out.')
      } else {
        const entries = elements(reference, 'entryCriterion')
        // Several entry criteria mean "any of": kept when each waits for one event and all share the same condition.
        const alternatives = entries.map(criterion => { const sentry = sentries.get(criterion.getAttribute('sentryRef') ?? ''); return sentry ? readSentry(sentry, true) : null })
        const sameCondition = alternatives.every(a => JSON.stringify(a?.when) === JSON.stringify(alternatives[0]?.when))
        if (entries.length > 1 && alternatives.every(a => a && !a.problems && a.sources.length + a.onData.length === 1) && sameCondition) {
          for (const a of alternatives) { item.onData = [...(item.onData ?? []), ...a!.onData]; if (a!.sources[0]) { if (!item.after) item.after = a!.sources[0]; else item.afterAll = [...(item.afterAll ?? []), a!.sources[0]] } }
          if (alternatives[0]?.when) item.when = alternatives[0].when
          if (!item.onData?.length) delete item.onData
          item.anyOf = true
        }
        else if (entries.length > 1) for (const extra of entries.slice(1)) report('error', extra, 'Several entry criteria are kept as "any of" only when each waits for one event with the same condition; this one was left out.')
        const entry = item.anyOf ? undefined : entries[0]
        if (entry) {
          const sentry = sentries.get(entry.getAttribute('sentryRef') ?? '')
          if (!sentry) report('error', entry, 'Its sentry is missing from this stage.')
          else {
            const { sources, when, onData } = readSentry(sentry)
            if (sources.length) { item.after = sources[0]; if (sources.length > 1) item.afterAll = sources.slice(1) }
            if (when) item.when = when
            if (onData.length) item.onData = onData
            if (sources.length || when || onData.length) item.ids = { ...item.ids, entry: entry.getAttribute('id') ?? undefined, sentry: keepSentry(sentry, entry) }
          }
        }
        const exits = elements(reference, 'exitCriterion')
        if (exits.length > 1) for (const extra of exits.slice(1)) report('error', extra, 'Only one exit criterion per item is supported; this one was left out.')
        const exit = exits[0]
        if (exit) {
          const sentry = sentries.get(exit.getAttribute('sentryRef') ?? '')
          const read = sentry ? readSentry(sentry) : { sources: [], when: undefined, onData: [], problems: 0 }
          if (read.onData.length) report('error', exit, 'Case data events on exit criteria are not supported; the exit waits only for plan item events.')
          const sources = read.sources
          if (read.when) report('error', exit, 'Conditions on exit criteria are not supported; the exit waits only for its event.')
          if (sources.length > 1) report('error', exit, 'An exit criterion waiting for several items is not supported; the first was kept.')
          if (sources[0]) { item.exitAfter = sources[0]; item.ids = { ...item.ids, exit: exit.getAttribute('id') ?? undefined, exitSentry: keepSentry(sentry!, exit) } }
        }
      }
      const layout = layouts.get(id)
      if (layout) item.layout = layout
      return item
    }
    /** A sentry shared by several criteria keeps its ID for the first; the others get their own copy with a new ID. */
    function keepSentry(sentry: Element, criterion: Element): string | undefined {
      const id = sentry.getAttribute('id') ?? undefined
      if (!id) return undefined
      if (!claimedSentries.has(id)) { claimedSentries.add(id); return id }
      report('warning', criterion, `Sentry ${id} is shared by several criteria; this criterion gets its own copy with a new ID.`)
      return undefined
    }
    function readSentry(sentry: Element, quiet = false): { sources: string[]; when?: CaseCondition; onData: CaseFileEvent[]; problems: number } {
      const sources: string[] = []
      const onData: CaseFileEvent[] = []
      let when: CaseCondition | undefined
      let problems = 0
      const note = (severity: ImportDiagnostic['severity'], element: Element, message: string) => { problems++; if (!quiet) report(severity, element, message) }
      for (const child of elements(sentry)) {
        if (child.localName === 'planItemOnPart') {
          const source = child.getAttribute('sourceRef') ?? ''
          const event = elements(child, 'standardEvent')[0]?.textContent?.trim()
          if (!['complete', 'occur'].includes(event ?? '')) note('error', child, `The "${event ?? 'missing'}" event is not supported; rules wait for completion or occurrence.`)
          if (source) sources.push(source)
        } else if (child.localName === 'ifPart') {
          const text = elements(child, 'condition')[0]?.textContent ?? ''
          const parsed = parseCondition(text)
          if (parsed && !child.getAttribute('contextRef')) when = parsed
          else note('error', child, `The condition "${text.trim()}" is not supported; conditions compare case data fields (cd_<field>) with literal values. It was left out.`)
        } else if (child.localName === 'caseFileItemOnPart') {
          const field = fileItems.get(child.getAttribute('sourceRef') ?? '')
          const event = elements(child, 'standardEvent')[0]?.textContent?.trim()
          if (field && (event === 'create' || event === 'update')) onData.push({ field, event })
          else note('error', child, `Case file event "${event ?? 'missing'}" on "${child.getAttribute('sourceRef') ?? ''}" is not supported; only create and update of a case data field are. It was left out.`)
        } else note('error', child, `<${child.localName}> in a sentry is not supported.`)
      }
      return { sources, when, onData, problems }
    }
    for (const child of elements(container)) {
      if (child.localName === 'planItem') { const item = readItem(child, false); if (item) items.push(item) }
      else if (child.localName === 'planningTable') {
        for (const nested of elements(child)) {
          if (nested.localName === 'discretionaryItem') { const item = readItem(nested, true); if (item) items.push(item) }
          else report('error', nested, 'Nested planning tables are not supported and were left out.')
        }
      }
    }
    for (const [id, definition] of definitions) if (!used.has(id) && definition.localName !== 'sentry')
      report('warning', definition, `<${definition.localName}> is defined but never used in this stage; it was left out.`)
    // Rules that wait for items which were not imported are dropped, with the reason.
    const ids = new Set(items.map(item => item.id))
    for (const item of items) {
      const sources = [item.after, ...(item.afterAll ?? [])].filter((value): value is string => !!value)
      const kept = sources.filter(source => ids.has(source))
      if (kept.length !== sources.length) diagnostics.push({ severity: 'error', path: `${pathOf(container)}/planItem[${item.id}]`, elementId: item.id, message: 'Its entry rule waited for an item that was not imported; that part was left out.' })
      item.after = kept[0]; item.afterAll = kept.length > 1 ? kept.slice(1) : undefined
      if (!kept.length) { delete item.after; delete item.afterAll }
      if (item.exitAfter && !ids.has(item.exitAfter)) delete item.exitAfter
    }
    return items
  }

  const items = readPlan(plan, 0)
  const options: CaseModelOptions = { planId: plan.getAttribute('id') ?? undefined, roleIds, diagram: layouts.size > 0, planLayout: layouts.get(plan.getAttribute('id') ?? '') }
  return { key: caseElement.getAttribute('id') ?? '', name: caseElement.getAttribute('name') ?? plan.getAttribute('name') ?? '', items, options, diagnostics }
}
