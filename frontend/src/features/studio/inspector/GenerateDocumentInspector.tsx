import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { assetRef, linkAsset, unlinkAsset, updateNodeConfig } from '../model/ops'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitInput, CommitTextarea, InspectorSection } from './controls'

export function documentPlaceholders(template: string): string[] {
  return [...new Set([...template.matchAll(/\{\{([A-Za-z][A-Za-z0-9_]{0,62})}}/g)].map((match) => match[1]!))]
}

/**
 * Copies the latest version of a registry template (DOC08) into the step and links the step to that version (PL01,
 * docs/build/S61). The step keeps its own text: a later registry change never alters this workflow. Validation and
 * publish compare the step with the linked version, report a newer version and block an archived or missing template.
 */
function TemplatePicker({ node, mapping, disabled }: { node: WorkflowNode; mapping: Record<string, string>; disabled: boolean }) {
  const { workspace, can } = useWorkspace()
  const { store } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const ws = workspace.id
  const allowed = can('workflow.read')
  const linked = assetRef(def, 'documentTemplate', node.id)
  const list = useQuery({ queryKey: ['ws', ws, 'document-templates', !!linked], queryFn: () => endpoints.listDocumentTemplates(ws, !!linked), enabled: allowed })
  const linkedSummary = linked ? list.data?.items.find((t) => t.key === linked.key) : undefined
  const detail = useQuery({ queryKey: ['ws', ws, 'document-template', linkedSummary?.id], queryFn: () => endpoints.getDocumentTemplate(ws, linkedSummary!.id), enabled: allowed && !!linkedSummary })
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState<string | null>(null)
  if (!allowed || !list.data) return null
  const copy = (template: { key: string; name: string }, version: { version: number; format: 'pdf' | 'docx'; body: string }) => {
    store.getState().apply((d) => linkAsset(updateNodeConfig(d, node.id, {
      format: version.format,
      template: version.body,
      inputMapping: Object.fromEntries(documentPlaceholders(version.body).map((name) => [name, mapping[name] ?? ''])),
    }), { kind: 'documentTemplate', key: template.key, version: version.version, target: node.id }))
    setCopied(`Copied version ${version.version} of ${template.name} and linked this step to it. Choose a value for each field below.`)
  }
  const use = async (id: string) => {
    setError(null)
    setCopied(null)
    if (!id) return
    try {
      const template = await endpoints.getDocumentTemplate(ws, id)
      copy(template, template.versions[0]!)
    } catch {
      setError('The template could not be loaded. Try again.')
    }
  }
  if (linked) {
    const template = detail.data
    const pinned = template?.versions.find((v) => v.version === linked.version)
    const latest = template?.versions[0]
    const differs = !!pinned && (pinned.body !== cfg<string>(node, 'template') || pinned.format !== (cfg<string>(node, 'format') ?? 'pdf'))
    return (
      <div className="space-y-2 rounded-card border border-border p-3" data-testid="template-link">
        <p className="text-sm">Linked to saved template <strong>{template?.name ?? linked.key}</strong> ({linked.key}), version {linked.version}.</p>
        {!linkedSummary ? <p role="alert" className="text-sm text-danger">The saved template was not found. Validation reports it before publishing.</p> : null}
        {template?.archived ? <p role="alert" className="text-sm text-danger">This saved template is archived. Publishing is blocked until it is restored or you unlink this step.</p> : null}
        {differs ? <p role="alert" className="text-sm text-danger">The text or format here no longer matches version {linked.version}. Publishing is blocked until you use a saved version or unlink.</p> : null}
        {latest && latest.version > linked.version && !differs ? <p className="text-sm text-warning-foreground">Version {latest.version} is available. This workflow keeps version {linked.version} until you choose to update.</p> : null}
        {!disabled ? (
          <div className="flex flex-wrap gap-2">
            {template && latest && (latest.version > linked.version || differs) ? (
              <Button type="button" variant="secondary" onClick={() => copy(template, latest)}>Use version {latest.version}</Button>
            ) : null}
            <Button type="button" variant="ghost" onClick={() => {
              store.getState().apply((d) => unlinkAsset(d, 'documentTemplate', node.id))
              setCopied('Unlinked. This step keeps its text and is no longer checked against the saved template.')
            }}>Unlink</Button>
          </div>
        ) : null}
        {copied ? <p role="status" className="text-sm text-muted-foreground">{copied}</p> : null}
      </div>
    )
  }
  if (list.data.items.length === 0) return null
  return (
    <FormField label="Start from a saved template" id={`document-saved-${node.id}`} description="Copies the latest version into this step and links the step to it. Later changes to the saved template do not change this workflow; validation tells you when a newer version exists.">
      {(ids) => (
        <>
          <Select id={ids.input} value="" disabled={disabled} onChange={(e) => void use(e.target.value)}>
            <option value="">Choose a template</option>
            {list.data.items.map((t) => <option key={t.id} value={t.id}>{t.name} (version {t.latestVersion})</option>)}
          </Select>
          {copied ? <p role="status" className="mt-1 text-sm text-muted-foreground">{copied}</p> : null}
          {error ? <p role="alert" className="mt-1 text-sm text-danger">{error}</p> : null}
        </>
      )}
    </FormField>
  )
}

export function GenerateDocumentInspector({ node }: { node: WorkflowNode }) {
  const { store, readOnly, connections } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const template = cfg<string>(node, 'template') ?? ''
  const mapping = cfg<Record<string, string>>(node, 'inputMapping') ?? {}
  const placeholders = documentPlaceholders(template)
  const fields = availableFields(def, node.id, connections).filter((field) => field.type !== 'array')
  const patch = (value: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, value)
  return <InspectorSection title="Document">
    <TemplatePicker node={node} mapping={mapping} disabled={readOnly} />
    <FormField label="Document format" id={`document-format-${node.id}`}>
      {(ids) => <Select id={ids.input} value={cfg<string>(node, 'format') ?? 'pdf'} disabled={readOnly} onChange={(e) => patch({ format: e.target.value })}>
        <option value="pdf">PDF</option><option value="docx">Word document</option>
      </Select>}
    </FormField>
    <FormField label="File name" required id={`document-name-${node.id}`} description="Use a simple name without a file extension or path.">
      {(ids) => <CommitInput id={ids.input} value={cfg<string>(node, 'fileName') ?? ''} maxLength={100} disabled={readOnly} onCommit={(value) => patch({ fileName: value })} />}
    </FormField>
    <FormField label="Document template" required id={`document-template-${node.id}`} description="Write plain text. Insert mapped fields with {{customer}}. The published version retains this exact template.">
      {(ids) => <CommitTextarea id={ids.input} value={template} maxLength={16000} disabled={readOnly} onCommit={(value) => patch({ template: value, inputMapping: Object.fromEntries(documentPlaceholders(value).map((name) => [name, mapping[name] ?? ''])) })} />}
    </FormField>
    {placeholders.map((name) => <FormField key={name} label={`Value for ${name}`} required id={`document-field-${node.id}-${name}`}>
      {(ids) => <Select id={ids.input} value={mapping[name] ?? ''} disabled={readOnly} onChange={(e) => patch({ inputMapping: { ...mapping, [name]: e.target.value } })}>
        <option value="">Choose a field</option>
        {mapping[name] && !fields.some((field) => field.ref === mapping[name]) ? <option value={mapping[name]}>{mapping[name]} (unavailable)</option> : null}
        {fields.map((field) => <option key={field.ref} value={field.ref}>{chipLabel(field)}</option>)}
      </Select>}
    </FormField>)}
    <p className="text-sm text-muted-foreground">Documents appear in the request's files. File limits and configured virus scanning apply. PDF supports the standard font character set; choose Word for Unicode text. Missing values stop the step with a visible incident.</p>
  </InspectorSection>
}
