import type { components } from '@/api/schema'
import { caseItems, refs, type CaseModelItem, type CaseModelOptions } from './caseModel'
import { importCaseModel, type ImportDiagnostic } from './cmmnImport'

type S = components['schemas']
export type CaseDraft = S['CaseDefinitionDraft']
export type TaskMapping = S['CaseWorkflowTaskMapping']
export interface DraftModel { items: CaseModelItem[]; options: CaseModelOptions; diagram?: boolean }

/** How a saved draft opens: in the designer, or as an uploaded CMMN file the designer cannot represent fully. */
export type OpenedDraft =
  | { mode: 'designer'; model?: DraftModel }
  | { mode: 'file'; xml: string; diagnostics: ImportDiagnostic[] }

/** The workflow, child case and decision mappings of a draft written back onto imported plan items (by definition ID). */
export function applyMappings(items: CaseModelItem[], draft: Pick<CaseDraft, 'workflowTasks' | 'caseTasks' | 'decisionTasks'>): CaseModelItem[] {
  const copy = structuredClone(items)
  const find = (list: TaskMapping[], item: CaseModelItem) => list.find(mapping => mapping.taskId === refs(item).definition)
  for (const item of caseItems(copy)) {
    const kind = item.kind === 'processTask' ? 'workflow' : item.kind === 'caseTask' ? 'childCase' : item.kind === 'decisionTask' ? 'decision' : null
    const list = item.kind === 'processTask' ? draft.workflowTasks : item.kind === 'caseTask' ? draft.caseTasks : draft.decisionTasks
    const ref = kind ? item[kind] : undefined
    const mapping = kind ? find(list, item) : undefined
    if (kind && ref && mapping) item[kind] = { ...ref, input: { ...mapping.inputMapping }, result: { ...mapping.resultMapping } }
  }
  return copy
}

/** A saved designer model opens as it was; otherwise the CMMN source is imported, and kept as a file when the import leaves anything out. */
export function openDraft(draft: CaseDraft): OpenedDraft {
  const saved = draft.designerModel as Partial<DraftModel> | null
  if (saved && Array.isArray(saved.items)) return { mode: 'designer', model: { items: saved.items, options: saved.options ?? {}, diagram: saved.diagram } }
  if (!draft.sourceXml) return { mode: 'designer' }
  try {
    const imported = importCaseModel(draft.sourceXml)
    if (!imported.items.length || imported.diagnostics.some(diagnostic => diagnostic.severity === 'error'))
      return { mode: 'file', xml: draft.sourceXml, diagnostics: imported.diagnostics }
    return { mode: 'designer', model: { items: applyMappings(imported.items, draft), options: imported.options } }
  } catch {
    return { mode: 'file', xml: draft.sourceXml, diagnostics: [] }
  }
}

/** Pins of a published version become the mappings of the draft that edits it. */
export function mappingsOf(pins: Array<{ taskId: string; inputMapping: Record<string, string>; resultMapping: Record<string, string> }> | undefined): TaskMapping[] {
  return (pins ?? []).map(pin => ({ taskId: pin.taskId, inputMapping: { ...pin.inputMapping }, resultMapping: { ...pin.resultMapping } }))
}

/** The draft that edits a published version: its source, data, view and mappings. */
export function draftFromDefinition(definition: S['CaseDefinitionDetail']): S['CreateCaseDefinitionDraft'] {
  return {
    key: definition.key, name: definition.name, sourceXml: definition.sourceXml, designerModel: null,
    dataFields: definition.dataFields ?? [],
    viewRef: definition.viewBinding ? { id: definition.viewBinding.id, version: definition.viewBinding.version } : null,
    workflowTasks: mappingsOf(definition.workflowTasks), caseTasks: mappingsOf(definition.caseTasks), decisionTasks: mappingsOf(definition.decisionTasks),
  }
}
