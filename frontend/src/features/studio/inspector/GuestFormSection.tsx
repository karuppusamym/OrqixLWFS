import { FormField } from '@/components/common/FormField'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ALL_FILE_TYPES, FILE_TYPES } from '@/api/attachments'
import { useStudio, useStudioContext } from '../StudioContext'
import { cfg, type WorkflowNode } from '../model/types'
import { CommitInput, CommitNumber, CommitTextarea, DurationPreview, InspectorSection } from './controls'

export interface GuestResponseConfig {
  linkValidFor: string
  title: string
  instructions?: string
  recipientFrom?: string
  attachments?: { maxFiles: number; required?: boolean; acceptTypes?: string[]; maxFileBytes?: number }
}

/**
 * CG-F05 (docs/build/S140): a person outside the organization answers this wait through a one-time link and a small
 * form with the step's declared values and, optionally, files. Operators share the link from the request page, or Orqix
 * emails it to a request field's address when its domain is approved for guest links.
 */
export function GuestFormSection({ node, payloadCount }: { node: WorkflowNode; payloadCount: number }) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const guest = cfg<GuestResponseConfig>(node, 'guestResponse')
  const set = (value: GuestResponseConfig | undefined) => store.getState().apply((d) => ({
    ...d,
    nodes: d.nodes.map((n) => {
      if (n.id !== node.id) return n
      const config = { ...n.config }
      if (value) config.guestResponse = value
      else delete config.guestResponse
      return { ...n, config }
    }),
  }))
  const patch = (p: Partial<GuestResponseConfig>) => {
    if (!guest) return
    const next: GuestResponseConfig = { ...guest, ...p }
    for (const k of ['instructions', 'recipientFrom', 'attachments'] as const) if (next[k] === undefined || next[k] === '') delete next[k]
    set(next)
  }
  const textFields = Object.entries(def.inputSchema.properties).filter(([, f]) => f.type === 'string' && !f.format && !f.enum)
  const id = `message-guest-${node.id}`
  return (
    <InspectorSection title="Guest response form">
      <div className="flex items-center gap-2">
        <Checkbox id={id} checked={guest !== undefined} disabled={readOnly}
          onCheckedChange={(checked) => set(checked === true ? { linkValidFor: 'P7D', title: node.label } : undefined)} />
        <Label htmlFor={id}>Answered by a person outside the organization</Label>
      </div>
      {guest === undefined ? <p className="text-sm text-muted-foreground">Turn this on when a vendor or customer answers this wait through a one-time link, without signing in.</p> : <>
        <FormField label="Form title" required id={`${id}-title`}>
          {(ids) => <CommitInput id={ids.input} value={guest.title} maxLength={100} disabled={readOnly} onCommit={(v) => patch({ title: v.trim() || node.label })} />}
        </FormField>
        <FormField label="Instructions" optionalText id={`${id}-instructions`} description="Plain text shown above the questions.">
          {(ids) => <CommitTextarea id={ids.input} value={guest.instructions ?? ''} rows={3} maxLength={1000} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch({ instructions: v.trim() || undefined })} />}
        </FormField>
        <FormField label="Link is valid for" required id={`${id}-ttl`} description="From 1 hour (PT1H) to 30 days (P30D), counted from when the link is created.">
          {(ids) => <CommitInput id={ids.input} value={guest.linkValidFor} disabled={readOnly} aria-describedby={ids.describedBy} onCommit={(v) => patch({ linkValidFor: v.trim() })} />}
        </FormField>
        {guest.linkValidFor ? <DurationPreview text={guest.linkValidFor} verb="allows" /> : null}
        <FormField label="Email the link to" optionalText id={`${id}-recipient`} description="A request question with the guest's email address. It is sent only to domains an administrator approved for guest links; otherwise an operator shares the link.">
          {(ids) => (
            <Select id={ids.input} value={guest.recipientFrom ?? ''} disabled={readOnly} aria-describedby={ids.describedBy} onChange={(e) => patch({ recipientFrom: e.target.value || undefined })}>
              <option value="">Nobody: an operator shares the link</option>
              {textFields.map(([key, f]) => <option key={key} value={`/input/${key}`}>{f.title}</option>)}
            </Select>
          )}
        </FormField>
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-files`} checked={guest.attachments !== undefined} disabled={readOnly}
            onCheckedChange={(checked) => patch({ attachments: checked === true ? { maxFiles: 1 } : undefined })} />
          <Label htmlFor={`${id}-files`}>The guest may add files</Label>
        </div>
        {guest.attachments ? (
          <fieldset className="space-y-2">
            <legend className="sr-only">Files</legend>
            <FormField label="Most files" id={`${id}-maxfiles`}>
              {(ids) => <CommitNumber id={ids.input} integer value={guest.attachments!.maxFiles} disabled={readOnly} onCommit={(v) => patch({ attachments: { ...guest.attachments!, maxFiles: Math.max(1, Math.min(5, v ?? 1)) } })} />}
            </FormField>
            <div className="flex items-center gap-2">
              <Checkbox id={`${id}-files-required`} checked={guest.attachments.required === true} disabled={readOnly}
                onCheckedChange={(checked) => patch({ attachments: { ...guest.attachments!, required: checked === true ? true : undefined } })} />
              <Label htmlFor={`${id}-files-required`}>At least one file is required</Label>
            </div>
            <div className="grid grid-cols-2 gap-2">
              {ALL_FILE_TYPES.map((t) => (
                <div key={t} className="flex items-center gap-2">
                  <Checkbox id={`${id}-type-${t}`} checked={(guest.attachments!.acceptTypes ?? []).includes(t)} disabled={readOnly}
                    onCheckedChange={(v) => {
                      const types = ALL_FILE_TYPES.filter((x) => (x === t ? v === true : (guest.attachments!.acceptTypes ?? []).includes(x)))
                      patch({ attachments: { ...guest.attachments!, acceptTypes: types.length ? types : undefined } })
                    }} />
                  <Label htmlFor={`${id}-type-${t}`} className="font-medium">{FILE_TYPES[t]?.label}</Label>
                </div>
              ))}
            </div>
          </fieldset>
        ) : null}
        {payloadCount === 0 && !guest.attachments ? <p className="text-sm text-warning-foreground">Declare at least one value above or allow files, so the guest has something to answer.</p> : null}
      </>}
    </InspectorSection>
  )
}
