import { useMemo, useState } from 'react'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useStudio, useStudioContext } from '../StudioContext'
import { usagesOfRef } from '../model/fieldRefs'
import { questionRules } from '../model/formRules'
import { addField, applyFields, fieldsFromDefinition, renameFieldKey, type FormFieldModel } from '../model/ops'
import { FieldListEditor } from './FieldListEditor'
import { FieldConditionsEditor } from './FormBehaviorEditor'

/**
 * The request form's questions as compact rows (docs/build/S120): label, type and rules in one line each; one click opens a
 * question's details. `open` is the question to open from outside (a check issue or a focus request). The rows and details
 * are the shared field editor (docs/build/S125), bound here to the Studio store.
 */
export function QuestionList({ open }: { open?: { key: string; n: number } | null }) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const fields = useMemo(() => fieldsFromDefinition(def), [def])
  const rules = useMemo(() => questionRules(def), [def])
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)

  const update = (key: string, patch: Partial<FormFieldModel>) =>
    store.getState().apply((d) => applyFields(d, fieldsFromDefinition(d).map((f) => (f.key === key ? { ...f, ...patch } : f))))

  const move = (key: string, delta: -1 | 1) =>
    store.getState().apply((d) => {
      const list = fieldsFromDefinition(d)
      const i = list.findIndex((f) => f.key === key)
      const j = i + delta
      if (i < 0 || j < 0 || j >= list.length) return d
      const [item] = list.splice(i, 1)
      list.splice(j, 0, item as FormFieldModel)
      return applyFields(d, list)
    })

  const remove = (key: string) => store.getState().apply((d) => applyFields(d, fieldsFromDefinition(d).filter((f) => f.key !== key)))

  const usesOf = (key: string) => usagesOfRef(def, (ref) => ref === `/input/${key}` || ref.startsWith(`/input/${key}/`))
  const requestDelete = (key: string) => {
    if (usesOf(key).length === 0) remove(key)
    else setPendingDelete(key)
  }

  const add = () => {
    let added = ''
    store.getState().apply((d) => {
      const result = addField(d)
      added = result.key
      return result.def
    })
    return added || undefined
  }

  const rename = (key: string, next: string) => {
    const result = renameFieldKey(store.getState().definition, key, next)
    if (!result.ok) return result.reason
    store.getState().apply(() => result.def)
    return undefined
  }

  const deleting = pendingDelete ? fields.find((f) => f.key === pendingDelete) : undefined
  const deletingUses = pendingDelete ? usesOf(pendingDelete) : []

  return (
    <>
      <FieldListEditor
        fields={fields}
        readOnly={readOnly}
        open={open}
        listLabel="Questions on the request form"
        emptyText="The form has no questions yet."
        addLabel="Add a question"
        rules={rules}
        renderRules={(key) => <FieldConditionsEditor fieldKey={key} />}
        incidentEditable
        onUpdate={update}
        onMove={move}
        onDelete={requestDelete}
        onAdd={add}
        onRename={rename}
      />
      <ConfirmDialog
        open={!!deleting}
        title={deleting ? `Delete "${deleting.label}"?` : 'Delete question?'}
        confirmLabel="Delete question"
        destructive
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          if (pendingDelete) remove(pendingDelete)
          setPendingDelete(null)
        }}
      >
        <p>This question is used by:</p>
        <ul className="list-disc pl-5">
          {deletingUses.map((u) => (
            <li key={u}>{u}</li>
          ))}
        </ul>
        <p>Those places will report a problem until you point them at another value.</p>
      </ConfirmDialog>
    </>
  )
}
