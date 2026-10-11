import type { components } from '@/api/schema'

/**
 * Mock-mode Camunda 8 and Camunda Forms inventory. A small, honest imitation of the real adapter (backend/.../migration/camunda)
 * so that mock mode can demonstrate the Camunda 8 import: source version, connector provenance, FEEL conditions, forms and
 * timers. It reads the same reason codes but understands far less; the real backend decides what converts.
 */
type Report = components['schemas']['CompatibilityReport']
type Issue = components['schemas']['CompatibilityIssue']
type Hints = NonNullable<Report['mappingHints']>
type Preview = NonNullable<Hints['formPreviews']>[string]
type PreviewField = components['schemas']['FormPreviewField']

export const MOCK_C8_ADAPTER = 'camunda-bpmn-mock-0.5'
const ZB = 'http://camunda.org/schema/zeebe/1.0'
const MODELER = 'http://camunda.org/schema/modeler/1.0'
const BPMN = 'http://www.omg.org/spec/BPMN/20100524/MODEL'

export class MockFormError extends Error {}

function issue(el: string, name: string | null, kind: string, severity: Issue['severity'], code: string, explanation: string, suggestedAction?: string): Issue {
  return { sourceElementId: el, sourceName: name, sourceKind: kind, severity, reasonCode: code, explanation, ...(suggestedAction ? { suggestedAction } : {}),
    resolutionStatus: severity === 'supported' ? 'notNeeded' : 'open' } as Issue
}

const fieldKey = (key: string) => /^[A-Za-z][A-Za-z0-9]*$/.test(key) ? key.charAt(0).toLowerCase() + key.slice(1) : null

// Forms ---------------------------------------------------------------------------------------------------------------------

const BLOCKED_FORM: Record<string, string> = { table: 'A table holds rows.', dynamiclist: 'A dynamic list repeats fields.', filepicker: 'A file picker uploads files.',
  expression: 'A computed field is evaluated from an expression.', iframe: 'An embedded page cannot be part of a request form.', checklist: 'A checklist holds several values.', taglist: 'A tag list holds several values.' }
const DISPLAY = new Set(['text', 'html', 'image', 'separator', 'spacer', 'button'])

export interface FormModel { formId: string | null; preview: Preview; issues: Issue[]; fields: PreviewField[] }

export function analyzeFormText(text: string): FormModel {
  let root: { id?: string; type?: string; components?: unknown }
  try { root = JSON.parse(text) as typeof root } catch { throw new MockFormError('The file is not valid JSON, so it cannot be a Camunda form.') }
  if (!root || typeof root !== 'object' || !Array.isArray(root.components)) throw new MockFormError("This JSON file is not a Camunda form: it has no 'components' list.")
  const formId = root.id ?? null
  const fields: PreviewField[] = []
  const skipped: PreviewField[] = []
  const issues: Issue[] = []
  const walk = (components: unknown[]) => {
    for (const raw of components) {
      const c = raw as Record<string, unknown>
      const id = String(c.id ?? c.key ?? c.type)
      const label = String(c.label ?? c.key ?? c.type)
      const type = String(c.type)
      const validate = (c.validate ?? {}) as Record<string, unknown>
      const base = { id, key: (c.key as string | undefined) ?? null, label, component: type }
      if (type === 'group') { issues.push(issue(id, label, 'formComponent', 'info', 'FORM_GROUP_FLATTENED', `The group '${label}' is only a frame; its fields are imported without it.`, 'Acknowledge.')); walk((c.components as unknown[]) ?? []); continue }
      if (DISPLAY.has(type)) { skipped.push({ ...base, required: false, disposition: 'info', reasonCode: 'FORM_DISPLAY_NOT_IMPORTED' }); issues.push(issue(id, label, 'formComponent', 'info', 'FORM_DISPLAY_NOT_IMPORTED', `The ${type} component only displays content and is not imported.`, 'Acknowledge.')); continue }
      const block = (code: string, why: string) => { skipped.push({ ...base, required: false, disposition: 'blocker', reasonCode: code }); issues.push(issue(id, label, 'formComponent', 'blocker', code, why, 'Rebuild this part in the studio, or remove it from the source form.')) }
      if (type in BLOCKED_FORM) { block('FORM_COMPONENT_NOT_SUPPORTED', `The ${type} component '${label}' cannot be imported. ${BLOCKED_FORM[type]}`); continue }
      if ((c.conditional as { hide?: string } | undefined)?.hide) { block('FORM_EXPRESSION_NOT_SUPPORTED', `'${label}' is shown or hidden by an expression.`); continue }
      if (c.valuesKey || c.valuesExpression) { block(c.valuesExpression ? 'FORM_EXPRESSION_NOT_SUPPORTED' : 'FORM_OPTIONS_NOT_STATIC', `The options of '${label}' are not a fixed list.`); continue }
      const key = typeof c.key === 'string' ? fieldKey(c.key) : null
      const known = ['textfield', 'textarea', 'number', 'checkbox', 'select', 'radio', 'datetime'].includes(type)
      if (!known) { block('FORM_COMPONENT_NOT_SUPPORTED', `The component type '${type}' is not known to the importer.`); continue }
      if (!key) { block('FORM_KEY_NOT_SUPPORTED', `The field '${label}' has no usable key.`); continue }
      if (type === 'datetime' && c.subtype && c.subtype !== 'date') { block('FORM_COMPONENT_NOT_SUPPORTED', `'${label}' collects a ${String(c.subtype)}; only dates are supported.`); continue }
      const options = Array.isArray(c.values) ? (c.values as { value?: string }[]).map(v => String(v.value)) : []
      const widget = type === 'datetime' ? 'text' : type
      fields.push({ ...base, key, type: type === 'number' ? 'number' : type === 'checkbox' ? 'boolean' : 'string', widget, required: validate.required === true, options, description: (c.description as string | undefined) ?? null, disposition: 'supported' })
      issues.push(issue(id, label, 'formComponent', 'supported', 'FORM_FIELD_MAPPED', `'${label}' becomes a request field.`))
      if (c.defaultValue) issues.push(issue(id, label, 'formComponent', 'info', 'FORM_DEFAULT_NOT_IMPORTED', `The default value of '${label}' is not imported.`, 'Acknowledge.'))
    }
  }
  walk(root.components as unknown[])
  if (fields.length) issues.push(issue(formId ?? 'form', null, 'form', 'review', 'FORM_MAPPED', `${fields.length} input field(s) become fields of the request form.`, 'Check labels, required fields and choices in the studio.'))
  return { formId, preview: { formId, fields, skipped }, issues, fields }
}

export function analyzeFormSource(text: string, sourceHash: string): Report {
  const model = analyzeFormText(text)
  const count = (s: Issue['severity']) => model.issues.filter(i => i.severity === s).length
  return {
    sourceSystem: 'camundaForm', adapterVersion: 'camunda-form-mock-0.1', sourceHash, processId: model.formId, processName: model.formId,
    elementCounts: { total: model.issues.length, supported: count('supported'), info: count('info'), review: count('review'), action: 0, blocker: count('blocker') },
    issues: model.issues,
    assumptions: ['Mock form inventory: a form has no target of its own; attach it to a Camunda 8 BPMN import. A start form becomes the request form; a task form becomes a note on the approval.'],
    requiredMappings: {}, publishable: count('blocker') === 0 && count('info') === 0,
    mappingHints: { formPreviews: { [model.formId ?? 'form']: model.preview } },
  } as Report
}

// BPMN ----------------------------------------------------------------------------------------------------------------------

const zeebe = (el: Element, name: string) => Array.from(el.getElementsByTagNameNS(ZB, name))
const text = (el: Element | undefined | null) => (el?.textContent ?? '').trim()
const literal = (value: string | null | undefined) => value == null ? null : /^=/.test(value.trim()) ? (/^="([^"]*)"$/.exec(value.trim())?.[1] ?? null) : value.trim()
const isDuration = (v: string) => /^P(?:\d{1,3}D)?(?:T(?:\d{1,4}H)?(?:\d{1,5}M)?)?$/.test(v) && v !== 'P' && v !== 'PT'

export function isCamunda8(doc: Document): boolean {
  if (/cloud/i.test(doc.documentElement.getAttributeNS(MODELER, 'executionPlatform') ?? '')) return true
  return Array.from(doc.getElementsByTagName('*')).some(e => e.namespaceURI === ZB || Array.from(e.attributes).some(a => a.namespaceURI === ZB))
}

/** One of the FEEL shapes the real importer converts; anything with a call, `in`, `null` or `if` is a blocker. */
function feelBlocker(expr: string): string | null {
  const body = expr.trim().replace(/^=/, '')
  if (/\bnull\b/.test(body)) return 'It tests for null, which workflow conditions cannot do.'
  if (/\b(?!not\b)[A-Za-z_]\w*\s*\(/.test(body)) return 'It calls a function.'
  if (/\b(in|between|if|then|else|some|every)\b/.test(body)) return 'It uses a FEEL construct that is not supported.'
  if (/\band\b/.test(body) && /\bor\b/.test(body)) return "It mixes 'and' with 'or'."
  return null
}

export function analyzeCamunda8(doc: Document, sourceHash: string): Report & { dependencies: string[]; dynamicCalls: number; processId: string; decisionDependencies: Record<string, string> } {
  const root = doc.documentElement
  const process = doc.getElementsByTagNameNS(BPMN, 'process')[0]!
  const processId = process.getAttribute('id')!
  const version = root.getAttributeNS(MODELER, 'executionPlatformVersion') || null
  const issues: Issue[] = []
  const services: Record<string, NonNullable<Hints['services']>[string]> = {}
  const forms: NonNullable<Hints['forms']> = {}
  const calls: NonNullable<Hints['calls']> = {}
  const previews: NonNullable<Hints['formPreviews']> = {}
  const embedded = new Map<string, string>()
  const groups = new Set<string>(), serviceIds: string[] = [], children: string[] = [], decisions: Record<string, string> = {}
  const dependencies = new Set<string>()
  let dynamicCalls = 0
  for (const form of zeebe(process, 'userTaskForm')) embedded.set(form.getAttribute('id') ?? '', form.textContent ?? '')
  for (const el of Array.from(process.getElementsByTagName('*'))) {
    if (el.namespaceURI !== BPMN) continue
    const kind = el.localName
    const id = el.getAttribute('id') ?? kind
    const name = el.getAttribute('name')
    const ext = Array.from(el.children).find(c => c.localName === 'extensionElements')
    const z = (n: string) => ext ? Array.from(ext.children).find(c => c.namespaceURI === ZB && c.localName === n) : undefined
    const form = z('formDefinition')
    if (form && (kind === 'startEvent' || kind === 'userTask')) {
      const target = kind === 'startEvent' ? 'start' : 'userTask'
      const formKey = form.getAttribute('formKey'), formId = form.getAttribute('formId')
      if (formKey?.startsWith('camunda-forms:bpmn:')) {
        const key = formKey.slice('camunda-forms:bpmn:'.length)
        try {
          const model = analyzeFormText(embedded.get(key) ?? '')
          forms[id] = { target, formId: key, formKey, state: 'embedded' }
          previews[id] = model.preview
          issues.push(...model.issues.map(i => target === 'userTask' && i.reasonCode === 'FORM_MAPPED'
            ? { ...i, reasonCode: 'TASK_FORM_NOTED', explanation: `${model.fields.length} input field(s) of this form are recorded as a plain-text note on the approval step. Approvals do not collect fields.` } : i))
        } catch (cause) { issues.push(issue(id, name, 'formDefinition', 'blocker', 'FORM_INVALID', cause instanceof Error ? cause.message : 'The embedded form cannot be read.')) }
      } else if (formId) {
        forms[id] = { target, formId, state: 'upload' }
        issues.push(issue(id, name, 'formDefinition', 'info', 'FORM_NOT_IMPORTED', `The ${target === 'start' ? 'start' : 'task'} form '${formId}' is a separate Camunda form file. Upload the .form file to import it; otherwise it is left out.`, `Upload the form file with the id '${formId}', or acknowledge that it is left out.`))
      }
    }
    if (kind === 'serviceTask' || kind === 'sendTask' || kind === 'scriptTask') {
      const type = z('taskDefinition')?.getAttribute('type') ?? ''
      if (type.startsWith('io.camunda:http-json:')) {
        const inputs: Record<string, string> = {}
        for (const input of zeebe(el, 'input')) inputs[input.getAttribute('target') ?? ''] = input.getAttribute('source') ?? ''
        const headers: Record<string, string> = {}
        for (const h of zeebe(el, 'header')) headers[h.getAttribute('key') ?? ''] = h.getAttribute('value') ?? ''
        const url = literal(inputs.url)
        let host: string | null = null, path: string | null = null
        if (url) { try { const u = new URL(url); host = u.hostname; path = u.pathname } catch { path = null } }
        const body = /^=\{(.*)\}$/.exec((inputs.body ?? '').trim())?.[1] ?? ''
        const inputFields: Record<string, string> = {}
        for (const part of body.split(',')) { const [k, v] = part.split(':').map(s => s.trim()); if (k && v && /^[A-Za-z]\w*$/.test(v)) inputFields[k] = v }
        const resultFields: Record<string, string> = {}
        for (const part of (/^=\{(.*)\}$/.exec((headers.resultExpression ?? '').trim())?.[1] ?? '').split(',')) {
          const [k, v] = part.split(':').map(s => s.trim()); if (k && v?.startsWith('response.body.')) resultFields[k] = '/' + v.slice('response.body.'.length).replace(/\./g, '/')
        }
        const method = literal(inputs.method)?.toUpperCase() ?? null
        const operation = (name ?? id).replace(/[^A-Za-z0-9]+(.)?/g, (_m, c: string | undefined) => (c ?? '').toUpperCase()).replace(/^./, c => c.toLowerCase())
        services[id] = { kind: 'httpJson', jobType: type, suggestedOperation: operation, method, urlHost: host, urlPath: path, urlComputed: !!inputs.url && !url, authentication: literal(inputs['authentication.type']), headerNames: [], queryNames: [], resultVariable: headers.resultVariable ?? null, inputFields, resultFields, unmapped: [] }
        serviceIds.push(id)
        issues.push(issue(id, name, 'serviceTask', 'blocker', 'CONNECTOR_MAPPING_REQUIRED', `This is the Camunda 8 REST connector. Original call (read-only provenance): ${method ?? 'request'} ${host ?? ''}${path ?? ''}. It becomes a call to a connection operation you choose; the address, credentials and headers belong to the connection, never to the workflow.`, `Choose the registered connection operation (${method ?? ''} ${path ?? ''}) that makes this call, check the mapping and test it.`))
        continue
      }
      if (type.startsWith('io.camunda:')) {
        services[id] = { kind: 'unsupportedConnector', jobType: type, replacement: 'Use a notification step, or register a connection to that system and rebuild the step as a call to it.' }
        issues.push(issue(id, name, kind, 'blocker', 'CONNECTOR_NOT_SUPPORTED', `This step uses the Camunda 8 connector '${type}'. Only the REST (http-json) connector can be converted.`, 'Use a notification step, or register a connection to that system and rebuild the step as a call to it.'))
        continue
      }
      if (kind === 'serviceTask') {
        const operation = type.replace(/[^A-Za-z0-9]+(.)?/g, (_m, c: string | undefined) => (c ?? '').toUpperCase()).replace(/^./, c => c.toLowerCase())
        services[id] = { kind: 'jobWorker', jobType: type, suggestedOperation: operation }
        serviceIds.push(id)
        issues.push(issue(id, name, kind, 'blocker', 'WORKER_NOT_PORTABLE', `This service task runs job worker for job type '${type}'. Source code is never imported or executed by Orqix. Suggested operation name: '${operation}'.`, 'Register a connection operation that does the same work, then map this step to it.'))
        continue
      }
      issues.push(issue(id, name, kind, 'blocker', 'TASK_TYPE_NOT_SUPPORTED', `A ${kind} has no equivalent in workflow v0.1.`, 'Replace it with an approval step or a connection call.'))
      continue
    }
    if (kind === 'userTask') {
      const groupText = z('assignmentDefinition')?.getAttribute('candidateGroups') ?? ''
      for (const g of groupText.split(',').map(s => s.trim()).filter(Boolean)) groups.add(g)
      issues.push(issue(id, name, kind, groupText ? 'action' : 'blocker', groupText ? 'GROUP_MAPPING_REQUIRED' : 'ASSIGNMENT_NOT_SUPPORTED', groupText ? `Becomes an approval step. Choose the workspace group that replaces '${groupText}'.` : 'The assignment is calculated or missing, so it cannot be mapped to a workspace group.', 'Choose the workspace group whose members decide this step.'))
      const due = /duration\("(P[^"]+)"\)/.exec(z('taskSchedule')?.getAttribute('dueDate') ?? '')?.[1]
      if (due && isDuration(due)) issues.push(issue(id, name, 'taskSchedule', 'review', 'DUE_DATE_MAPPED', `The due time (${due}) becomes a display-only due time on the approval.`, 'Check the due time.'))
    } else if (kind === 'exclusiveGateway') {
      const flows = Array.from(process.getElementsByTagNameNS(BPMN, 'sequenceFlow')).filter(f => f.getAttribute('sourceRef') === id)
      const conditions = flows.map(f => text(Array.from(f.children).find(c => c.localName === 'conditionExpression')))
      const bad = conditions.map(feelBlocker).find(Boolean)
      if (flows.length > 1 && bad) issues.push(issue(id, name, kind, 'blocker', 'EXPRESSION_NOT_SUPPORTED', `This choice cannot be converted. ${bad} Expression(s) in the source: ${conditions.filter(Boolean).join('; ')}.`, 'Simplify the conditions in the source model, or rebuild this choice in the studio.'))
      else if (flows.length > 1) issues.push(issue(id, name, kind, 'review', 'GATEWAY_MAPPED_TO_BRANCH', `Becomes a choice with ${conditions.filter(Boolean).length} condition(s) and a default path.`, 'Check the inferred field types.'))
      else issues.push(issue(id, name, kind, 'supported', 'MERGE_GATEWAY_REMOVED', 'A gateway with one outgoing flow only merges paths.'))
    } else if (kind === 'boundaryEvent') {
      const def = Array.from(el.children).find(c => c.localName.endsWith('EventDefinition'))?.localName
      const dur = text(el.getElementsByTagNameNS(BPMN, 'timeDuration')[0])
      if (def === 'timerEventDefinition' && el.getAttribute('cancelActivity') !== 'false' && isDuration(dur)) issues.push(issue(id, name, kind, 'review', 'BOUNDARY_TIMER_MAPPED', `The interrupting timer ${dur} becomes the time limit of the approval; the timed-out route follows the flow leaving the timer.`, 'Check the time limit and where the timed-out route leads.'))
      else issues.push(issue(id, name, kind, 'blocker', def === 'messageEventDefinition' ? 'MESSAGE_EVENT_NOT_SUPPORTED' : 'EVENT_NOT_SUPPORTED_IN_V0', 'This boundary event cannot be converted.', 'Model the escalation as a separate approval.'))
    } else if (kind === 'intermediateCatchEvent') {
      const def = Array.from(el.children).find(c => c.localName.endsWith('EventDefinition'))?.localName
      const dur = (/duration\("([^"]+)"\)/.exec(text(el.getElementsByTagNameNS(BPMN, 'timeDuration')[0])) ?? [])[1] ?? text(el.getElementsByTagNameNS(BPMN, 'timeDuration')[0])
      const messageRef = el.getElementsByTagNameNS(BPMN, 'messageEventDefinition')[0]?.getAttribute('messageRef')
      const message = Array.from(doc.getElementsByTagNameNS(BPMN, 'message')).find(m => m.getAttribute('id') === messageRef)
      const messageName = message?.getAttribute('name') ?? ''
      const correlation = message ? zeebe(message, 'subscription')[0]?.getAttribute('correlationKey') ?? '' : ''
      if (def === 'messageEventDefinition' && /^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$/.test(messageName) && /^=\s*[A-Za-z_]\w*$/.test(correlation))
        issues.push(issue(id, name, kind, 'review', 'MESSAGE_CATCH_MAPPED', `The step waits until the message '${messageName}' arrives with the same value as the request field '${correlation.replace(/^=\s*/, '')}'. Message variables are not imported.`, 'Check the message name, the correlation field and who sends the message.'))
      else if (def === 'timerEventDefinition' && isDuration(dur)) issues.push(issue(id, name, kind, 'review', 'DURATION_TIMER_MAPPED', `The constant duration ${dur} becomes a durable wait of the same length.`, 'Check that a fixed elapsed duration is what the source process meant.'))
      else issues.push(issue(id, name, kind, 'blocker', def === 'messageEventDefinition' ? 'MESSAGE_EVENT_NOT_SUPPORTED' : def === 'signalEventDefinition' ? 'SIGNAL_EVENT_NOT_SUPPORTED' : 'EVENT_NOT_SUPPORTED_IN_V0', 'This event cannot be converted. Workflows have no message or signal events yet.', 'Model the wait as a human step or a wait with a duration.'))
    } else if (kind === 'callActivity') {
      const called = z('calledElement')
      const target = called?.getAttribute('processId') ?? el.getAttribute('calledElement')
      children.push(id)
      if (target && !/^=|[$#]\{/.test(target)) dependencies.add(target); else dynamicCalls++
      const child = called?.getAttribute('propagateAllChildVariables') !== 'false', parent = called?.getAttribute('propagateAllParentVariables') !== 'false'
      calls[id] = { processId: target, binding: called?.getAttribute('bindingType') ?? 'latest', propagateAllChildVariables: child, propagateAllParentVariables: parent, inputMapping: {}, resultMapping: {} }
      issues.push(issue(id, name, kind, 'action', 'CHILD_WORKFLOW_MAPPING_REQUIRED', `Called process '${target ?? ''}' requires a reviewed workspace workflow and explicit input/result mappings.`, 'Import and publish the child first; map its key and fields.'))
      if (child || parent) issues.push(issue(id, name, 'callActivity', 'info', 'PROPAGATE_ALL_NOT_SUPPORTED', 'Camunda 8 passes every variable to and from the called process unless switched off. Orqix passes only the fields you list in the mapping.', 'Acknowledge, and list every field the called process needs.'))
    } else if (kind === 'businessRuleTask') {
      const decision = z('calledDecision')?.getAttribute('decisionId')
      if (decision) decisions[id] = decision
      issues.push(issue(id, name, kind, 'action', 'DECISION_MAPPING_REQUIRED', `This business rule task calls the DMN decision '${decision ?? ''}'. Upload the DMN file and map the task to it.`, 'Upload the DMN file (source format dmn), then map this task.'))
    } else if (['startEvent', 'endEvent', 'sequenceFlow', 'parallelGateway'].includes(kind)) {
      if (!issues.some(i => i.sourceElementId === id)) issues.push(issue(id, name, kind, 'supported', kind === 'sequenceFlow' ? 'FLOW_MAPPED' : 'START_EVENT_MAPPED', `A ${kind} is converted.`))
    } else if (['subProcess', 'inclusiveGateway', 'eventBasedGateway'].includes(kind)) {
      issues.push(issue(id, name, kind, 'blocker', 'ELEMENT_NOT_SUPPORTED', `A ${kind} has no equivalent in workflow v0.1.`, 'Redesign this part.'))
    }
  }
  const count = (s: Issue['severity']) => issues.filter(i => i.severity === s).length
  return {
    sourceSystem: 'camunda8', sourceVersion: version, adapterVersion: MOCK_C8_ADAPTER, sourceHash, processId, processName: process.getAttribute('name') ?? processId,
    elementCounts: { total: issues.length, supported: count('supported'), info: count('info'), review: count('review'), action: count('action'), blocker: count('blocker') },
    issues,
    assumptions: [`Source version: Camunda 8${version ? ` ${version}` : ''} (modeler executionPlatformVersion). The mock inventory reads only the constructs listed in the report; the real adapter decides what converts. This is not a claim of general Camunda 8 compatibility.`],
    requiredMappings: { groups: [...groups], approvalTasks: [], serviceTasks: serviceIds, childWorkflows: children, decisionTasks: Object.keys(decisions) },
    publishable: false,
    mappingHints: { services, forms, calls, formPreviews: previews },
    dependencies: [...dependencies], dynamicCalls, decisionDependencies: decisions,
  } as never
}
