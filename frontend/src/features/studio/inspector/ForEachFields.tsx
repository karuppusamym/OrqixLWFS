import { FormField } from '@/components/common/FormField'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitInput, CommitNumber, InspectorSection } from './controls'

/** `config.forEach` of a call, external worker or AI task step (CG-E06, docs/build/S138). */
export interface StepForEach {
  itemsRef: string
  itemInput?: string
  itemMapping?: Record<string, string>
  maxItems: number
  mode?: 'sequential' | 'parallel'
  batchSize?: number
  completion?: { when: 'all' | 'firstSuccess' | 'atLeast'; count?: number }
  collect?: Record<string, string>
}

export const FOR_EACH_MAX_ITEMS = 500

/**
 * "Run once for each item" for a call, an external worker or an AI task: the request list, the input that receives
 * each item (or the columns of a row), the limit, one-at-a-time or parallel batches (calls only), when the step is
 * done, and which per-item results are collected into lists in item order.
 */
export function ForEachFields({ node, results, parallel }: { node: WorkflowNode; results: string[]; parallel: boolean }) {
  const def = useStudio((s) => s.definition)
  const { store, readOnly } = useStudioContext()
  const each = cfg<StepForEach>(node, 'forEach')
  const lists = Object.entries(def.inputSchema.properties).filter(([, field]) => field.type === 'array')
  const set = (next: StepForEach | undefined) => {
    store.getState().apply((d) => ({
      ...d,
      nodes: d.nodes.map((n) => {
        if (n.id !== node.id) return n
        const config = { ...n.config }
        if (next) config.forEach = next
        else delete config.forEach
        return { ...n, config }
      }),
    }))
  }
  const listKey = each?.itemsRef.replace(/^\/input\//, '') ?? ''
  const list = def.inputSchema.properties[listKey]
  const rows = list?.items?.type === 'object'
  const columns = Object.entries(list?.items?.properties ?? {})
  const collect = each?.collect ?? {}
  const when = each?.completion?.when ?? 'all'

  return (
    <InspectorSection title="For each item">
      <div className="flex items-center gap-2">
        <Checkbox
          id={`step-foreach-${node.id}`}
          checked={!!each}
          disabled={readOnly || lists.length === 0}
          onCheckedChange={(checked) => {
            if (checked === true) {
              const [key, field] = lists[0] ?? []
              if (!key) return
              const max = Math.min(FOR_EACH_MAX_ITEMS, field?.maxItems ?? 25)
              set({ itemsRef: `/input/${key}`, maxItems: max, ...(field?.items?.type === 'object' ? { itemMapping: {} } : { itemInput: '' }) })
            } else set(undefined)
          }}
        />
        <Label htmlFor={`step-foreach-${node.id}`}>Run once for each item of a list</Label>
      </div>
      {lists.length === 0 ? <p className="text-xs text-muted-foreground">Add a list to the request form first.</p> : null}
      {each ? (
        <>
          <FormField label="List" required id={`step-foreach-list-${node.id}`}>
            {(ids) => (
              <Select
                id={ids.input}
                value={each.itemsRef}
                disabled={readOnly}
                onChange={(e) => {
                  const field = def.inputSchema.properties[e.target.value.replace(/^\/input\//, '')]
                  const { itemInput: _i, itemMapping: _m, ...rest } = each
                  set({ ...rest, itemsRef: e.target.value, maxItems: Math.min(FOR_EACH_MAX_ITEMS, field?.maxItems ?? each.maxItems), ...(field?.items?.type === 'object' ? { itemMapping: {} } : { itemInput: '' }) })
                }}
              >
                {lists.map(([key, field]) => (
                  <option key={key} value={`/input/${key}`}>
                    {field.title || key}
                  </option>
                ))}
              </Select>
            )}
          </FormField>
          {rows ? (
            <fieldset className="space-y-3">
              <legend className="text-sm font-medium">Inputs filled from each row</legend>
              {columns.map(([column, c]) => (
                <FormField key={column} label={`${c.title || column} goes to input`} optionalText id={`step-foreach-col-${node.id}-${column}`}>
                  {(ids) => (
                    <CommitInput
                      id={ids.input}
                      value={Object.entries(each.itemMapping ?? {}).find(([, col]) => col === column)?.[0] ?? ''}
                      disabled={readOnly}
                      onCommit={(input) => {
                        const mapping = Object.fromEntries(Object.entries(each.itemMapping ?? {}).filter(([, col]) => col !== column))
                        if (input.trim()) mapping[input.trim()] = column
                        set({ ...each, itemMapping: mapping })
                      }}
                    />
                  )}
                </FormField>
              ))}
            </fieldset>
          ) : (
            <FormField label="Input that receives each item" required id={`step-foreach-input-${node.id}`}>
              {(ids) => <CommitInput id={ids.input} value={each.itemInput ?? ''} disabled={readOnly} onCommit={(v) => set({ ...each, itemInput: v.trim() })} />}
            </FormField>
          )}
          <FormField label="At most (items)" required id={`step-foreach-max-${node.id}`} description={`1 to ${FOR_EACH_MAX_ITEMS}; must cover the list's maximum. Every item has its own call, job or AI decision, so a retry never repeats another item.`}>
            {(ids) => (
              <CommitNumber id={ids.input} integer min={1} max={FOR_EACH_MAX_ITEMS} value={each.maxItems} disabled={readOnly} onCommit={(v) => v !== undefined && set({ ...each, maxItems: v })} />
            )}
          </FormField>
          {parallel ? (
            <FormField label="Order" id={`step-foreach-mode-${node.id}`} description="Parallel sends the items of each batch at the same time, then the next batch.">
              {(ids) => (
                <Select
                  id={ids.input}
                  value={each.mode ?? 'sequential'}
                  disabled={readOnly}
                  onChange={(e) => {
                    const { batchSize: _b, mode: _m, ...rest } = each
                    set(e.target.value === 'parallel' ? { ...rest, mode: 'parallel' } : rest)
                  }}
                >
                  <option value="sequential">One item at a time</option>
                  <option value="parallel">In parallel batches</option>
                </Select>
              )}
            </FormField>
          ) : (
            <p className="text-xs text-muted-foreground">Items run one at a time.</p>
          )}
          {each.mode === 'parallel' ? (
            <FormField label="Batch size" optionalText id={`step-foreach-batch-${node.id}`} description="1 to 25 items at the same time (default 25).">
              {(ids) => (
                <CommitNumber
                  id={ids.input}
                  integer
                  min={1}
                  max={25}
                  value={each.batchSize}
                  disabled={readOnly}
                  onCommit={(v) => {
                    const { batchSize: _b, ...rest } = each
                    set(v === undefined ? rest : { ...rest, batchSize: v })
                  }}
                />
              )}
            </FormField>
          ) : null}
          <FormField label="The step is done" id={`step-foreach-when-${node.id}`}>
            {(ids) => (
              <Select
                id={ids.input}
                value={when}
                disabled={readOnly}
                onChange={(e) => {
                  const { completion: _c, ...rest } = each
                  const value = e.target.value as 'all' | 'firstSuccess' | 'atLeast'
                  set(value === 'all' ? rest : { ...rest, completion: value === 'atLeast' ? { when: value, count: 1 } : { when: value } })
                }}
              >
                <option value="all">After every item</option>
                <option value="firstSuccess">After the first item that succeeded</option>
                <option value="atLeast">Once enough items succeeded</option>
              </Select>
            )}
          </FormField>
          {when === 'atLeast' ? (
            <FormField label="Items that must succeed" required id={`step-foreach-count-${node.id}`}>
              {(ids) => (
                <CommitNumber id={ids.input} integer min={1} max={each.maxItems} value={each.completion?.count} disabled={readOnly} onCommit={(v) => v !== undefined && set({ ...each, completion: { when: 'atLeast', count: v } })} />
              )}
            </FormField>
          ) : null}
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Collect results</legend>
            <p className="text-xs text-muted-foreground">
              After the step: itemCount, completedCount and stoppedEarly, plus one list per collected result, in item order.
            </p>
            {results.map((result) => {
              const name = Object.keys(collect).find((k) => collect[k] === result)
              return (
                <div key={result} className="flex items-center gap-2">
                  <Checkbox
                    id={`step-collect-${node.id}-${result}`}
                    checked={!!name}
                    disabled={readOnly}
                    onCheckedChange={(checked) => {
                      const next = { ...collect }
                      if (checked === true) next[name ?? `${result}List`] = result
                      else if (name) delete next[name]
                      const { collect: _c, ...rest } = each
                      set(Object.keys(next).length > 0 ? { ...rest, collect: next } : rest)
                    }}
                  />
                  <Label htmlFor={`step-collect-${node.id}-${result}`}>
                    {result}
                    {name ? <span className="text-muted-foreground"> as {name}</span> : null}
                  </Label>
                </div>
              )
            })}
          </fieldset>
        </>
      ) : null}
    </InspectorSection>
  )
}
