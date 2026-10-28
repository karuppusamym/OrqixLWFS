import { FormField } from '@/components/common/FormField'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitNumber } from './controls'

/** config.perItem of a list-driven approval (docs/build/S88, S104). */
export interface PerItem {
  itemsRef: string
  itemLabel?: string
  allowSamePerson?: true
  sequential?: true
  quorum?: number
  assigneeFrom?: string
}

/** Most items a list-driven approval opens tasks for (the compiler's WF_PER_ITEM_BOUND). */
export const MAX_LIST_ITEMS = 20

/**
 * "One approval per list item": choose the request list, the column that names each item (lists of rows) and whether
 * one person may decide several items. Choosing a list removes a number of approvers, which cannot be combined.
 */
export function ListApprovalFields({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const properties = useStudio((s) => s.definition.inputSchema.properties)
  const perItem = cfg<PerItem>(node, 'perItem')
  const lists = Object.entries(properties).filter(([, field]) => field?.type === 'array')
  const key = perItem?.itemsRef.replace(/^\/input\//, '')
  const list = key ? properties[key] : undefined
  const columns = list?.items?.type === 'object'
    ? Object.entries(list.items.properties ?? {}).filter(([, c]) => c.type !== 'boolean' && c.format !== 'attachment')
    : []
  // S104: plain text columns can hold the email of the person proposed for each item.
  const emailColumns = columns.filter(([, c]) => c.type === 'string' && !c.format)
  const minItems = typeof list?.minItems === 'number' ? list.minItems : 1
  const set = (next: PerItem | undefined) => store.getState().updateNodeConfig(node.id, next
    ? { perItem: next, multiInstance: undefined }
    : { perItem: undefined })
  return (
    <>
      <FormField label="One approval per list item" optionalText id={`approval-list-${node.id}`} description={`Opens one task for each item of a required request list (at most ${MAX_LIST_ITEMS} items). By default every item must be approved and the first rejection rejects the step and cancels the open items. Not combined with a number of approvers, a time limit, reminders or corrections.`}>
        {(ids) => (
          <Select
            id={ids.input}
            data-field="config.perItem"
            value={key ?? ''}
            disabled={readOnly}
            aria-describedby={ids.describedBy}
            onChange={(e) => {
              const chosen = e.target.value
              if (!chosen) return set(undefined)
              const field = properties[chosen]
              const firstColumn = field?.items?.type === 'object'
                ? Object.entries(field.items.properties ?? {}).find(([, c]) => c.type === 'string' && c.format !== 'attachment')?.[0]
                : undefined
              // The quorum and the proposed-person column belong to the old list, so they are not carried over.
              set({ itemsRef: `/input/${chosen}`, ...(firstColumn ? { itemLabel: firstColumn } : {}), ...(perItem?.allowSamePerson ? { allowSamePerson: true } : {}), ...(perItem?.sequential ? { sequential: true } : {}) })
            }}
          >
            <option value="">No, one task</option>
            {lists.map(([k, field]) => <option key={k} value={k}>{field?.title || k}</option>)}
          </Select>
        )}
      </FormField>
      {perItem && columns.length > 0 ? (
        <FormField label="Item label" required id={`approval-item-label-${node.id}`} description="The column each task shows to name its item.">
          {(ids) => (
            <Select
              id={ids.input}
              data-field="config.perItem.itemLabel"
              value={perItem.itemLabel ?? ''}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onChange={(e) => set({ ...perItem, itemLabel: e.target.value || undefined })}
            >
              <option value="">Choose a column</option>
              {columns.map(([k, c]) => <option key={k} value={k}>{c.title || k}</option>)}
            </Select>
          )}
        </FormField>
      ) : null}
      {perItem ? (
        <div className="flex items-start gap-3">
          <Checkbox
            id={`approval-same-person-${node.id}`}
            checked={perItem.allowSamePerson === true}
            disabled={readOnly}
            aria-describedby={`approval-same-person-${node.id}-help`}
            onCheckedChange={(checked) => {
              const { allowSamePerson: _omit, ...rest } = perItem
              set(checked === true ? { ...rest, allowSamePerson: true } : rest)
            }}
          />
          <div>
            <Label htmlFor={`approval-same-person-${node.id}`}>One person may decide several items</Label>
            <p id={`approval-same-person-${node.id}-help`} className="text-xs text-muted-foreground">
              Off: each item needs a different person from the group, so the group needs at least as many members as the list has items.
            </p>
          </div>
        </div>
      ) : null}
      {perItem ? (
        <div className="flex items-start gap-3">
          <Checkbox
            id={`approval-sequential-${node.id}`}
            checked={perItem.sequential === true}
            disabled={readOnly}
            aria-describedby={`approval-sequential-${node.id}-help`}
            onCheckedChange={(checked) => {
              const { sequential: _omit, ...rest } = perItem
              set(checked === true ? { ...rest, sequential: true } : rest)
            }}
          />
          <div>
            <Label htmlFor={`approval-sequential-${node.id}`}>Decide items one at a time, in list order</Label>
            <p id={`approval-sequential-${node.id}-help`} className="text-xs text-muted-foreground">
              Off: every item gets its task at once. On: the next item opens only after the previous one is decided, and items not reached when the step ends count as cancelled.
            </p>
          </div>
        </div>
      ) : null}
      {perItem ? (
        <FormField label="Items that must be approved" optionalText id={`approval-quorum-${node.id}`} description={`Leave empty for every item. With a number, the step is approved as soon as that many items are approved, and rejected as soon as it can no longer be reached. The list must always have at least this many items (its minimum is ${minItems}).`}>
          {(ids) => (
            <CommitNumber
              id={ids.input}
              data-field="config.perItem.quorum"
              integer
              placeholder="All"
              value={perItem.quorum}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onCommit={(value) => {
                const { quorum: _omit, ...rest } = perItem
                set(value === undefined ? rest : { ...rest, quorum: value })
              }}
            />
          )}
        </FormField>
      ) : null}
      {perItem && emailColumns.length > 0 ? (
        <FormField label="Proposed approver" optionalText id={`approval-assignee-${node.id}`} description="A text column holding the email of the person proposed for each item. That person gets the task when they may decide it; otherwise the item waits in the group queue.">
          {(ids) => (
            <Select
              id={ids.input}
              data-field="config.perItem.assigneeFrom"
              value={perItem.assigneeFrom ?? ''}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onChange={(e) => {
                const { assigneeFrom: _omit, ...rest } = perItem
                set(e.target.value ? { ...rest, assigneeFrom: e.target.value } : rest)
              }}
            >
              <option value="">Nobody, use the group queue</option>
              {emailColumns.map(([k, c]) => <option key={k} value={k}>{c.title || k}</option>)}
            </Select>
          )}
        </FormField>
      ) : null}
    </>
  )
}
