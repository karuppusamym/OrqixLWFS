import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookmarkPlus } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { StepTemplate } from '@/api/types'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { suggestKey, templateKind, templateOf } from './model/workspaceTemplates'
import type { WorkflowNode } from './model/types'

const templatesKey = (ws: string, q: string) => ['ws', ws, 'step-templates', q] as const

/**
 * Palette section "From workspace templates" (CG-S03, docs/build/S141): this workspace's step templates and those the
 * organization's other workspaces share, searched with the palette text (name, description, step type, operation).
 */
export function WorkspaceTemplateSteps({ ws, query, disabled, onAdd }: { ws: string; query: string; disabled: boolean; onAdd: (template: StepTemplate) => void }) {
  const templates = useQuery({ queryKey: templatesKey(ws, query), queryFn: () => endpoints.listStepTemplates(ws, query) })
  const announce = useAnnounce()
  const items = (templates.data?.items ?? []).filter((t) => templateKind(t.stepType)).slice(0, 12)
  if (items.length === 0) return null
  return (
    <section aria-label="From workspace templates" className="space-y-2">
      <h3 className="text-xs font-semibold text-muted-foreground uppercase">From workspace templates</h3>
      <ul className="space-y-2" aria-label="Workspace templates">
        {items.map((t) => (
          <li key={t.id}>
            <button type="button" disabled={disabled}
              onClick={() => {
                onAdd(t)
                announce(`${t.name} step added`)
              }}
              className="w-full rounded-card border border-border bg-surface p-3 text-left hover:border-input-border hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60">
              <span className="block text-sm font-semibold">{t.name}</span>
              <span className="block text-xs text-muted-foreground">
                {t.operation ?? t.stepType} · version {t.latestVersion}{t.own ? '' : ` · shared by ${t.workspaceKey}`}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Inspector action: save the configured step as a new workspace template (or a new version of one). */
export function SaveAsTemplate({ ws, node }: { ws: string; node: WorkflowNode }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState(node.label)
  const [key, setKey] = useState(suggestKey(node.label))
  const [shared, setShared] = useState(false)
  const save = useMutation({
    mutationFn: () => endpoints.createStepTemplate(ws, { key: key.trim(), name: name.trim(), shared, step: { ...templateOf(node) } }),
    onSuccess: (created) => {
      void client.invalidateQueries({ queryKey: ['ws', ws, 'step-templates'] })
      announce(`Saved as the template ${created.name}`)
      setOpen(false)
    },
  })
  if (!templateKind(node.type)) return null
  if (!open) {
    return <Button variant="secondary" size="sm" onClick={() => setOpen(true)}><BookmarkPlus aria-hidden="true" /> Save as template</Button>
  }
  return (
    <form className="space-y-3 rounded-card border border-border p-3" aria-label="Save as template" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
      <p className="text-xs text-muted-foreground">Keeps this step&apos;s type, name and settings, not its routes. Designers find it in the step list; references are checked where it is used.</p>
      <FormField label="Template name" id={`template-name-${node.id}`}>{(ids) => <Input id={ids.input} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}</FormField>
      <FormField label="Template key" id={`template-key-${node.id}`} description="Lowercase letters, digits and dashes; cannot be changed later.">
        {(ids) => <Input id={ids.input} value={key} maxLength={64} onChange={(e) => setKey(e.target.value)} />}
      </FormField>
      <div className="flex items-center gap-2">
        <Checkbox id={`template-shared-${node.id}`} checked={shared} onCheckedChange={(c) => setShared(c === true)} />
        <Label htmlFor={`template-shared-${node.id}`}>Share with the organization&apos;s other workspaces</Label>
      </div>
      {save.isError ? <ProblemAlert error={save.error} /> : null}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={save.isPending || !name.trim() || !key.trim()}>Save template</Button>
        <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>Cancel</Button>
      </div>
    </form>
  )
}
