import type { components } from '@/api/schema'
import type { Definition } from '@/features/studio/model/types'

type BpmnCheck = components['schemas']['BpmnCheck']

/**
 * Mock double of the BPMN source endpoints (UI03, docs/build/S60). It is deliberately much simpler than the server:
 * it writes a minimal BPMN shape (no extensions, no diagram) and only applies edits of step and connection names.
 * The real exactness checks (regenerating BPMN with the compiler, the round trip, blockers) live on the server.
 */
const TAG: Record<string, string> = {
  start: 'startEvent',
  end: 'endEvent',
  approval: 'userTask',
  branch: 'exclusiveGateway',
  parallelSplit: 'parallelGateway',
  parallelJoin: 'parallelGateway',
  wait: 'intermediateCatchEvent',
  waitForMessage: 'intermediateCatchEvent',
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')

export function mockDraftBpmn(def: Definition): string {
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="https://orqix.example/workflows">',
    `  <process id="${esc(def.workflowKey)}" name="${esc(def.name)}" isExecutable="true">`,
    ...def.nodes.map((n) => `    <${TAG[n.type] ?? 'serviceTask'} id="${esc(n.id)}" name="${esc(n.label)}"/>`),
    ...def.transitions.map((t) => `    <sequenceFlow id="${esc(t.id)}"${t.label ? ` name="${esc(t.label)}"` : ''} sourceRef="${esc(t.from)}" targetRef="${esc(t.to)}"/>`),
    '  </process>',
    '</definitions>',
    '',
  ]
  return lines.join('\n')
}

/** Checks edited mock BPMN: only names of existing steps and connections may change. */
export function mockCheckBpmn(def: Definition, text: string): BpmnCheck & { definition?: Definition } {
  const refuse = (kind: BpmnCheck['problems'][number]['kind'], message: string, elementId: string | null = null): BpmnCheck => ({
    revision: 1, applicable: false, problems: [{ kind, elementId, message }], notes: [], changes: [],
  })
  if (/<!doctype|<!entity/i.test(text)) return refuse('xml', 'The BPMN text contains a DOCTYPE or ENTITY declaration.')
  const doc = new DOMParser().parseFromString(text, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) return refuse('xml', 'The BPMN text is not well-formed XML.')
  const process = doc.getElementsByTagNameNS('*', 'process')[0]
  if (!process) return refuse('unsupported', 'There is no bpmn:process.')
  if (process.getAttribute('id') !== def.workflowKey) return refuse('identity', `The process id is '${process.getAttribute('id')}', but this workflow's key is '${def.workflowKey}'.`, process.getAttribute('id'))
  const lane = doc.getElementsByTagNameNS('*', 'laneSet')[0]
  if (lane) return refuse('unsupported', 'Lanes (bpmn:laneSet) are not stored in the workflow document.', lane.getAttribute('id'))
  const names = new Map<string, string | null>()
  for (const el of Array.from(process.children)) names.set(el.getAttribute('id') ?? '', el.getAttribute('name'))
  const ids = [...def.nodes.map((n) => n.id), ...def.transitions.map((t) => t.id)]
  if (names.size !== ids.length || ids.some((id) => !names.has(id))) {
    return refuse('notRepresentable', 'The mock server only applies changes to step and connection names; use the real server for structural edits.')
  }
  const changes: BpmnCheck['changes'] = []
  const next: Definition = {
    ...def,
    name: process.getAttribute('name') ?? def.name,
    nodes: def.nodes.map((n) => {
      const label = names.get(n.id) ?? n.label
      if (label !== n.label) changes.push({ kind: 'changed', what: `Step '${n.id}' (${label})` })
      return label === n.label ? n : { ...n, label }
    }),
    transitions: def.transitions.map((t) => {
      const label = names.get(t.id) ?? undefined
      if (label !== t.label) changes.push({ kind: 'changed', what: `Connection '${t.id}' (${t.from} to ${t.to}, ${t.on})` })
      if (label === t.label) return t
      const copy = { ...t }
      if (label === undefined) delete copy.label
      else copy.label = label
      return copy
    }),
  }
  if (next.name !== def.name) changes.unshift({ kind: 'changed', what: `Workflow name: "${def.name}" to "${next.name}"` })
  return { revision: 1, applicable: true, problems: [], notes: changes.length ? ['The release hash and compiler version are recomputed for the new document (1 value).'] : [], changes, definition: next }
}

/** Mock .bpmn download (STU04): the mock BPMN plus a header comment and a simple diagram (the real server computes the layout). */
export function mockBpmnFile(def: Definition, source: string): string {
  const base = mockDraftBpmn(def)
  const shapes = def.nodes.map((n, i) => {
    const size = n.type === 'start' || n.type === 'end' || n.type === 'wait' || n.type === 'waitForMessage' ? 48 : n.type === 'branch' || n.type.startsWith('parallel') ? 64 : null
    const w = size ?? 200
    const h = size ?? 80
    return `    <bpmndi:BPMNShape id="${esc(n.id)}__di" bpmnElement="${esc(n.id)}"><omgdc:Bounds x="${40 + i * 280}" y="40" width="${w}" height="${h}"/></bpmndi:BPMNShape>`
  })
  const diagram = [
    `  <bpmndi:BPMNDiagram id="${esc(def.workflowKey)}__diagram"><bpmndi:BPMNPlane id="${esc(def.workflowKey)}__plane" bpmnElement="${esc(def.workflowKey)}">`,
    ...shapes,
    '  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>',
  ].join('\n')
  const withNamespaces = base.replace('<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"', '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"')
  return withNamespaces
    .replace('<?xml version="1.0" encoding="UTF-8"?>', `<?xml version="1.0" encoding="UTF-8"?>\n<!-- Mock export. Source: ${source} -->`)
    .replace('</definitions>', `${diagram}\n</definitions>`)
}
