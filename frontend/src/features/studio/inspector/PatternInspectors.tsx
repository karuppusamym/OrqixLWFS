import { useRelease, useWorkflows } from '@/api/queries'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, normalizeDefinition, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, InspectorSection } from './controls'
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
    {childDef ? <>
      <InspectorSection title="Request values">
        {Object.entries(childDef.inputSchema.properties).map(([key, field]) => <FormField key={key} label={field.title || key} required={childDef.inputSchema.required?.includes(key)} id={`child-input-${node.id}-${key}`}>
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
