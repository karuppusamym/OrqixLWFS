import { conditionTerms, type Definition } from '../model/types'
import type { FieldInfo } from '../model/fieldRefs'

export function inputControllers(def: Definition, excluded: string[], section = false): FieldInfo[] {
  const conditional = new Set([...Object.keys(def.inputUi?.visibleWhen ?? {}), ...Object.keys(def.inputUi?.requiredWhen ?? {}), ...(def.inputUi?.layout?.sections ?? []).flatMap(s => s.visibleWhen ? s.fields : [])])
  const rules = [...Object.values(def.inputUi?.visibleWhen ?? {}), ...Object.values(def.inputUi?.requiredWhen ?? {}), ...(def.inputUi?.layout?.sections ?? []).flatMap(s => s.visibleWhen ? [s.visibleWhen] : [])]
  // A section cannot hide the controller of one of its questions, even when that controller is otherwise static.
  const controllerRefs = rules.flatMap(conditionTerms).map(term => term.fieldRef)
  if (section && excluded.some(key => controllerRefs.includes(`/input/${key}`))) return []
  return Object.entries(def.inputSchema.properties).flatMap(([key, schema]) => {
    if (excluded.includes(key) || conditional.has(key) || schema.type === 'array' || schema.type === 'object' || schema.format) return []
    return [{ ref: `/input/${key}`, label: schema.title, group: 'Request', type: schema.type, enumValues: schema.enum }]
  })
}
