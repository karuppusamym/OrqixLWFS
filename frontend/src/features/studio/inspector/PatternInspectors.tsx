import { useRelease, useWorkflows } from '@/api/queries'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, normalizeDefinition, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, DurationPreview, InspectorSection } from './controls'
import { StepIdField } from './SimpleInspectors'


export function ParallelInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  return <>
    {node.type === 'parallelSplit' ? <FormField label="Wait for all at" required id={`parallel-join-${node.id}`} description="Every branch must reach this step before the workflow continues.">
      {(ids) => <Select id={ids.input} value={cfg<string>(node, 'joinRef') ?? ''} disabled={readOnly} onChange={(e) => store.getState().updateNodeConfig(node.id, { joinRef: e.target.value })}>
        <option value="">Choose a Wait for all step</option>
        {def.nodes.filter((n) => n.type === 'parallelJoin').map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
      </Select>}
    </FormField> : <p className="text-sm text-muted-foreground">Continues after all branches of the matching Run in parallel step arrive. A rejection ends the whole request.</p>}
    <Advanced><StepIdField node={node} /></Advanced>
  </>
}

export function WaitInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly } = useStudioContext()
  return <>
    <FormField label="Wait duration" required id={`wait-duration-${node.id}`} description="Use a duration such as PT30M (30 minutes), PT2H (2 hours), or P1D (1 day).">
      {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'duration') ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(duration) => store.getState().updateNodeConfig(node.id, { duration: duration.trim() })} />}
    </FormField>
    <DurationPreview text={cfg<string>(node, 'duration') ?? ''} verb="waits" />
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
  const each = cfg<{ itemsRef: string; itemInput: string; maxItems: number }>(node, 'forEach')
  const fields = availableFields(def, node.id, connections)
  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)
  return <>
    <InspectorSection title="Workflow">
      <FormField label="Published workflow" required id={`child-workflow-${node.id}`} description="Publishing pins its current release. A later child release does not change this published workflow.">
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
        <FormField label="Maximum items" required id={`foreach-bound-${node.id}`} description="1 to 25. Items run sequentially with separate task history. Results contain the final child result and iteration count.">
          {(ids) => <CommitInput id={ids.input} type="number" min={1} max={25} value={String(each.maxItems)} disabled={readOnly} onCommit={(value) => patch({ forEach: { ...each, maxItems: Number(value) } })} />}
        </FormField>
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
            {fields.filter((f) => f.type === field.type || (field.type === 'number' && f.type === 'integer')).map((f) => <option key={f.ref} value={f.ref}>{chipLabel(f)}</option>)}
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
