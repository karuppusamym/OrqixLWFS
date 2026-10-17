import { useState } from 'react'
import { useRelease, useWorkflows } from '@/api/queries'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select } from '@/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { DEFAULT_WAIT_TIME, WAIT_HORIZON_DAYS, dateWaitPreview } from '../model/summary'
import { setSplitMode } from '../model/ops'
import { cfg, normalizeDefinition, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, DurationPreview, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'


export function ParallelInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const inclusive = node.type === 'parallelSplit' && cfg<string>(node, 'mode') === 'inclusive'
  return <>
    {node.type === 'parallelSplit' ? <fieldset className="space-y-2">
      <legend className="text-sm font-semibold">Which paths run?</legend>
      <RadioGroup value={inclusive ? 'inclusive' : 'all'} disabled={readOnly} aria-label="Which paths run?" onValueChange={(mode) => {
        const fields = availableFields(def, node.id, connections)
        const first = fields.find((f) => f.type === 'number') ?? fields[0]
        store.getState().apply((d) => setSplitMode(d, node.id, mode === 'inclusive' ? 'inclusive' : 'all', first?.ref ?? ''))
      }}>
        <div className="flex items-center gap-3">
          <RadioGroupItem value="all" id={`split-mode-all-${node.id}`} />
          <Label htmlFor={`split-mode-all-${node.id}`} className="font-medium">Every path</Label>
        </div>
        <div className="flex items-center gap-3">
          <RadioGroupItem value="inclusive" id={`split-mode-inclusive-${node.id}`} />
          <Label htmlFor={`split-mode-inclusive-${node.id}`} className="font-medium">Every path whose condition applies</Label>
        </div>
      </RadioGroup>
      {inclusive ? <p className="text-sm text-muted-foreground">Several conditions can apply at once. When none does, the Otherwise path runs. The Wait for all step waits only for the paths that started.</p> : null}
    </fieldset> : null}
    {node.type === 'parallelSplit' ? <FormField label="Wait for all at" required id={`parallel-join-${node.id}`} description="Every branch must reach this step before the workflow continues.">
      {(ids) => <Select id={ids.input} value={cfg<string>(node, 'joinRef') ?? ''} disabled={readOnly} onChange={(e) => store.getState().updateNodeConfig(node.id, { joinRef: e.target.value })}>
        <option value="">Choose a Wait for all step</option>
        {def.nodes.filter((n) => n.type === 'parallelJoin').map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
      </Select>}
    </FormField> : <p className="text-sm text-muted-foreground">Continues after all branches of the matching Run in parallel step arrive. A rejection ends the whole request.</p>}
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}

/** Wait: for a length of time, or until a date taken from a Date question or an earlier step (S12 section 10). */
export function WaitInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const [sample, setSample] = useState('')
  const untilField = cfg<string>(node, 'untilField')
  const mode = untilField !== undefined ? 'date' : 'duration'
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  const dateFields = availableFields(def, node.id, connections).filter((f) => f.type === 'date')
  const chosen = dateFields.find((f) => f.ref === untilField)
  const time = cfg<string>(node, 'atTime') ?? DEFAULT_WAIT_TIME
  const choose = (next: string) => {
    if (next === mode) return
    if (next === 'date') patch({ duration: undefined, untilField: dateFields[0]?.ref ?? '', atTime: undefined })
    else patch({ duration: 'PT1H', untilField: undefined, atTime: undefined })
  }
  return <>
    <fieldset className="space-y-2">
      <legend className="text-sm font-semibold">What does it wait for?</legend>
      <RadioGroup value={mode} onValueChange={choose} disabled={readOnly} aria-label="What does it wait for?">
        <div className="flex items-center gap-3">
          <RadioGroupItem value="duration" id={`wait-mode-duration-${node.id}`} />
          <Label htmlFor={`wait-mode-duration-${node.id}`} className="font-medium">For a duration</Label>
        </div>
        <div className="flex items-center gap-3">
          <RadioGroupItem value="date" id={`wait-mode-date-${node.id}`} />
          <Label htmlFor={`wait-mode-date-${node.id}`} className="font-medium">Until a date</Label>
        </div>
      </RadioGroup>
    </fieldset>
    {mode === 'duration' ? <>
      <FormField label="Wait duration" required id={`wait-duration-${node.id}`} description="Use a duration such as PT30M (30 minutes), PT2H (2 hours), or P1D (1 day).">
        {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'duration') ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(duration) => patch({ duration: duration.trim() })} />}
      </FormField>
      <DurationPreview text={cfg<string>(node, 'duration') ?? ''} verb="waits" />
    </> : <>
      <FormField label="Date to wait until" required id={`wait-field-${node.id}`} description="A Date question of the request form, or a date from an earlier step. If the date has already passed, the request continues at once.">
        {(ids) => <Select id={ids.input} value={untilField ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => patch({ untilField: e.target.value })}>
          <option value="">Choose a date</option>
          {untilField && !chosen ? <option value={untilField}>{untilField} (not a date question)</option> : null}
          {dateFields.map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
        </Select>}
      </FormField>
      {dateFields.length === 0 ? <p className="text-sm text-muted-foreground">There is no date to choose yet. Add a question of type Date to the request form first.</p> : null}
      <FormField label="Time of day (UTC)" id={`wait-time-${node.id}`} description="The wait ends at this time on that date. Times are in UTC.">
        {(ids) => <CommitInput id={ids.input} type="time" value={time} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch({ atTime: v && v !== DEFAULT_WAIT_TIME ? v : undefined })} />}
      </FormField>
      <FormField label="Try a date" optionalText id={`wait-sample-${node.id}`} description={`Only for this preview. Dates more than ${WAIT_HORIZON_DAYS} days ahead are not accepted when a request starts.`}>
        {(ids) => <Input id={ids.input} type="date" value={sample} onChange={(e) => setSample(e.target.value)} aria-describedby={ids.describedBy} />}
      </FormField>
      <p role="status" data-testid="date-wait-preview" className="text-sm text-muted-foreground">{dateWaitPreview(chosen?.label, time, sample)}</p>
    </>}
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}

export function ChildInspector({ node }: { node: WorkflowNode }) {
  const { store, wsId, workflowId, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const list = useWorkflows(wsId)
  const workflows = list.data?.pages.flatMap((p) => p.items).filter((w) => w.id !== workflowId && w.activeRelease) ?? []
  const ref = cfg<string>(node, 'workflowRef') ?? ''
  const child = workflows.find((w) => w.key === ref)
  const release = useRelease(wsId, child?.id ?? '', child?.activeRelease?.id)
  const childDef = release.data?.definition ? normalizeDefinition(release.data.definition) : null
  const inputs = cfg<Record<string, string>>(node, 'inputMapping') ?? {}
  const results = cfg<Record<string, string>>(node, 'resultMapping') ?? {}
  const repeat = cfg<{ maxIterations: number; untilOutcome: string }>(node, 'repeat')
  const each = cfg<{ itemsRef: string; itemInput: string; maxItems: number; mode?: 'sequential' | 'parallel' }>(node, 'forEach')
  const collections = cfg<Record<string, string>>(node, 'collectionMapping') ?? {}
  const fields = availableFields(def, node.id, connections)
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  return <>
    <InspectorSection title="Workflow">
      <FormField label="Published workflow" required id={`child-workflow-${node.id}`} description="Publishing fixes the version used today. Later versions of the child workflow do not change this workflow.">
        {(ids) => <Select id={ids.input} value={ref} disabled={readOnly} onChange={(e) => patch({ workflowRef: e.target.value, inputMapping: {}, resultMapping: {} })}>
          <option value="">Choose a workflow</option>
          {ref && !child ? <option value={ref}>{ref} (not loaded or not published)</option> : null}
          {workflows.map((w) => <option key={w.id} value={w.key}>{w.name}</option>)}
        </Select>}
      </FormField>
      {list.hasNextPage ? <Button variant="secondary" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>Load more workflows</Button> : null}
      {list.isError || release.isError ? <p role="alert" className="text-sm text-danger">Could not load the child workflow. Reload the page to try again.</p> : null}
    </InspectorSection>
    <InspectorSection title="Repeat and rework">
      <div className="flex items-center gap-2">
        <Checkbox id={`repeat-${node.id}`} checked={!!repeat} disabled={readOnly || !!each} onCheckedChange={(checked) => {
          if (checked === true) patch({ repeat: { maxIterations: 3, untilOutcome: childDef?.nodes.find((n) => n.type === 'end' && cfg(n, 'outcomeKind') === 'success')?.config.businessOutcome ?? '' } })
          else store.getState().apply((draft) => ({ ...draft, nodes: draft.nodes.map((n) => {
            if (n.id !== node.id) return n
            const config = { ...n.config }; delete config.repeat
            return { ...n, config }
          }) }))
        }} />
        <Label htmlFor={`repeat-${node.id}`}>Repeat until an outcome is reached</Label>
      </div>
      {repeat ? <>
        <FormField label="Stop when outcome is" required id={`repeat-outcome-${node.id}`}>
          {(ids) => <Select id={ids.input} value={repeat.untilOutcome} disabled={readOnly} onChange={(e) => patch({ repeat: { ...repeat, untilOutcome: e.target.value } })}>
            <option value="">Choose an outcome</option>
            {Array.from(new Set(childDef?.nodes.filter((n) => n.type === 'end').map((n) => cfg<string>(n, 'businessOutcome')) ?? [])).filter(Boolean).map((outcome) => <option key={outcome} value={outcome}>{outcome}</option>)}
          </Select>}
        </FormField>
        <FormField label="Maximum iterations" required id={`repeat-bound-${node.id}`} description="1 to 25. Each iteration creates a separate request with its own task history. Inputs are reused. Route on Limit reached to handle exhaustion.">
          {(ids) => <CommitInput id={ids.input} type="number" min={1} max={25} value={String(repeat.maxIterations)} disabled={readOnly} onCommit={(value) => patch({ repeat: { ...repeat, maxIterations: Number(value) } })} />}
        </FormField>
      </> : null}
    </InspectorSection>
    <InspectorSection title="Collection iteration">
      <div className="flex items-center gap-2">
        <Checkbox id={`foreach-${node.id}`} checked={!!each} disabled={readOnly || !!repeat} onCheckedChange={(checked) => {
          if (checked === true) patch({ forEach: { itemsRef: '', itemInput: '', maxItems: 25 } })
          else store.getState().apply((draft) => ({ ...draft, nodes: draft.nodes.map((n) => {
            if (n.id !== node.id) return n
            const config = { ...n.config }; delete config.forEach
            delete config.collectionMapping
            return { ...n, config }
          }) }))
        }} />
        <Label htmlFor={`foreach-${node.id}`}>Run once for each collection item</Label>
      </div>
      {each ? <>
        <FormField label="Request collection" required id={`foreach-items-${node.id}`}>
          {(ids) => <Select id={ids.input} value={each.itemsRef} disabled={readOnly} onChange={(e) => patch({ forEach: { ...each, itemsRef: e.target.value } })}>
            <option value="">Choose a required collection</option>
            {Object.entries(def.inputSchema.properties).filter(([key, field]) => field.type === 'array' && def.inputSchema.required?.includes(key)).map(([key, field]) => <option key={key} value={`/input/${key}`}>{field.title || key}</option>)}
          </Select>}
        </FormField>
        <FormField label="Child field receiving each item" required id={`foreach-input-${node.id}`}>
          {(ids) => <Select id={ids.input} value={each.itemInput} disabled={readOnly} onChange={(e) => {
            const next = { ...inputs }; delete next[e.target.value]
            patch({ forEach: { ...each, itemInput: e.target.value }, inputMapping: next })
          }}>
            <option value="">Choose a request field</option>
            {Object.entries(childDef?.inputSchema.properties ?? {}).filter(([, field]) => field.type !== 'array').map(([key, field]) => <option key={key} value={key}>{field.title || key}</option>)}
          </Select>}
        </FormField>
        <FormField label="Execution order" id={`foreach-mode-${node.id}`} description="Parallel starts all bounded items together and waits for every result. Collected results always follow input order.">
          {(ids) => <Select id={ids.input} value={each.mode ?? 'sequential'} disabled={readOnly} onChange={(e) => patch({ forEach: { ...each, mode: e.target.value } })}>
            <option value="sequential">One item at a time</option><option value="parallel">All items in parallel</option>
          </Select>}
        </FormField>
        <FormField label="Maximum items" required id={`foreach-bound-${node.id}`} description="1 to 25. Every item has separate task history. Scalar results contain the final input item's child result.">
          {(ids) => <CommitInput id={ids.input} type="number" min={1} max={25} value={String(each.maxItems)} disabled={readOnly} onCommit={(value) => patch({ forEach: { ...each, maxItems: Number(value) } })} />}
        </FormField>
        <p className="text-xs text-muted-foreground">Collect arrays of child results. Add an array result field with a matching scalar item type to expose it on the request.</p>
        {[['outcome', 'Outcome'], ['outcomeKind', 'Outcome kind'], ['reference', 'Request reference'], ...Object.entries(childDef?.outputSchema.properties ?? {}).filter(([, field]) => field.type !== 'array').map(([key, field]) => [key, field.title || key])].map(([key, title]) => {
          const name = Object.keys(collections).find((k) => collections[k] === key)
          return <div key={key} className="flex items-center gap-2">
            <Checkbox id={`collect-${node.id}-${key}`} checked={!!name} disabled={readOnly} onCheckedChange={(checked) => {
              const next = { ...collections }
              if (checked === true) next[name ?? `${key}Items`] = key!
              else if (name) delete next[name]
              patch({ collectionMapping: next })
            }} />
            <Label htmlFor={`collect-${node.id}-${key}`}>{title} collection</Label>
          </div>
        })}
      </> : null}
    </InspectorSection>
    {childDef ? <>
      <InspectorSection title="Request values">
        {Object.entries(childDef.inputSchema.properties).filter(([key]) => key !== each?.itemInput).map(([key, field]) => <FormField key={key} label={field.title || key} required={childDef.inputSchema.required?.includes(key)} id={`child-input-${node.id}-${key}`}>
          {(ids) => <Select id={ids.input} value={inputs[key] ?? ''} disabled={readOnly} onChange={(e) => {
            const next = { ...inputs }
            if (e.target.value) next[key] = e.target.value
            else delete next[key]
            patch({ inputMapping: next })
          }}>
            <option value="">Choose a value</option>
            {fields.filter((f) => f.type === (field.format === 'date' ? 'date' : field.type) || (field.type === 'number' && f.type === 'integer') || (field.type === 'string' && !field.format && !field.enum && f.type === 'date')).map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
          </Select>}
        </FormField>)}
      </InspectorSection>
      <InspectorSection title="Result values">
        <p className="text-xs text-muted-foreground">Outcome, outcome kind, and request reference are always available.</p>
        {Object.entries(childDef.outputSchema.properties).filter(([key]) => !['outcome', 'outcomeKind', 'reference'].includes(key)).map(([key, field]) => {
          const mappedName = Object.keys(results).find((name) => results[name] === key)
          return <div key={key} className="flex items-center gap-2">
          <Checkbox id={`child-result-${node.id}-${key}`} checked={mappedName !== undefined} disabled={readOnly} onCheckedChange={(checked) => {
            const next = { ...results }
            if (checked === true) next[mappedName ?? key] = key
            else if (mappedName !== undefined) delete next[mappedName]
            patch({ resultMapping: next })
          }} />
          <Label htmlFor={`child-result-${node.id}-${key}`}>{field.title || key}</Label>
        </div>})}
      </InspectorSection>
    </> : null}
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}
