import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel } from '../model/fieldRefs'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitInput, CommitTextarea, InspectorSection } from './controls'

export function documentPlaceholders(template: string): string[] {
  return [...new Set([...template.matchAll(/\{\{([A-Za-z][A-Za-z0-9_]{0,62})}}/g)].map((match) => match[1]!))]
}

/**
 * Copies the latest version of a registry template (DOC08) into the step. The step then holds its own text: later
 * registry changes never alter this workflow, and the published release keeps exactly what is here.
 */
function TemplatePicker({ node, mapping, disabled }: { node: WorkflowNode; mapping: Record<string, string>; disabled: boolean }) {
  const { workspace, can } = useWorkspace()
  const { store } = useStudioContext()
  const ws = workspace.id
  const allowed = can('workflow.read')
  const list = useQuery({ queryKey: ['ws', ws, 'document-templates', false], queryFn: () => endpoints.listDocumentTemplates(ws), enabled: allowed })
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState<string | null>(null)
  if (!allowed || !list.data || list.data.items.length === 0) return null
  const use = async (id: string) => {
    setError(null)
    setCopied(null)
    if (!id) return
    try {
      const template = await endpoints.getDocumentTemplate(ws, id)
      const latest = template.versions[0]!
      store.getState().updateNodeConfig(node.id, {
        format: latest.format,
        template: latest.body,
        inputMapping: Object.fromEntries(documentPlaceholders(latest.body).map((name) => [name, mapping[name] ?? ''])),
      })
      setCopied(`Copied version ${latest.version} of ${template.name}. Choose a value for each field below.`)
    } catch {
      setError('The template could not be loaded. Try again.')
    }
  }
  return (
    <FormField label="Start from a saved template" id={`document-saved-${node.id}`} description="Copies the latest version into this step. Later changes to the saved template do not change this workflow.">
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
