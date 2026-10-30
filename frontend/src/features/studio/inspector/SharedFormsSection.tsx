import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link2Off, Share2, SquarePlus } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { formContentOfVersion, type SharedAsset } from '@/api/types'
import { FormField } from '@/components/common/FormField'
import { toProblem } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { applyFields, fieldsFromDefinition } from '../model/ops'
import {
  assetKeyOf, choiceProblems, clashingKeys, copiedQuestions, copyAsContent, copyMatches, defaultChoices, formContentOf, formDiff, formLinks,
  insertSharedForm, isValidAssetKey, linkSharedForm, mappingFor, questionCount, questionLabel, unlinkSharedForm, updateClashes, updateSharedForm,
  type ClashChoice, type FormContent, type FormLink,
} from '../model/sharedForms'
import type { Definition } from '../model/types'
import { InspectorSection } from './controls'

function messageOf(error: unknown): string {
  const problem = toProblem(error)
  return problem.fieldErrors[0]?.message ?? problem.detail ?? problem.title
}

const assetQuery = (wsId: string, key: string) => ['ws', wsId, 'shared-asset', 'form', key] as const

function contentOf(detail: SharedAsset | undefined, version: number): FormContent | undefined {
  return formContentOfVersion(detail?.versions.find((v) => v.version === version)?.content)
}

/** Runs one action with busy, status and error state, as the shared decision section does. */
function useAction() {
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const run = async (work: () => Promise<string | void>) => {
    setError(null)
    setStatus(null)
    setBusy(true)
    try {
      const done = await work()
      if (done) setStatus(done)
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }
  return { busy, status, error, run, setStatus }
}

/**
 * Shared forms on the Form tab (docs/build/S127). Insert copies a shared form's questions, rules and groups into this
 * request form and pins the version; a later change to the shared form never reaches this workflow until a designer
 * updates it here. Save as shared form makes this request form reusable by other workflows.
 */
export function SharedFormsSection() {
  const { readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const links = formLinks(def)
  if (readOnly && links.length === 0) return null
  return (
    <InspectorSection title="Shared forms">
      <p className="text-sm text-muted-foreground">
        Reuse a set of questions kept for the whole workspace, or share this form. The workflow keeps its own copy of the version you choose; publishing checks the copy like any other question.
      </p>
      {links.length > 0 ? (
        <ul className="space-y-3" aria-label="Linked shared forms">
          {links.map((link) => <LinkedForm key={link.key} link={link} />)}
        </ul>
      ) : null}
      {!readOnly ? <InsertSharedForm /> : null}
      {!readOnly ? <SaveSharedForm /> : null}
    </InspectorSection>
  )
}

/** Rename-or-skip choices for shared questions whose key this form already uses. Nothing is overwritten silently. */
function ClashChoices({ def, content, clashes, choices, onChange, problems }: {
  def: Definition; content: FormContent; clashes: string[]; choices: Record<string, ClashChoice>; onChange: (next: Record<string, ClashChoice>) => void; problems: Record<string, string>
}) {
  if (clashes.length === 0) return null
  return (
    <div className="space-y-3" data-testid="shared-form-clashes">
      <p className="text-sm">
        {clashes.length === 1 ? 'One question uses a key' : `${clashes.length} questions use keys`} this form already has. Rename the shared question, or skip it and keep this form's own question.
      </p>
      {clashes.map((k) => {
        const choice = choices[k]
        const ours = (def.inputSchema.properties[k] as { title?: string } | undefined)?.title ?? k
        const name = `clash-${k}`
        return (
          <fieldset key={k} className="space-y-2 rounded-control border border-border p-3">
            <legend className="px-1 text-sm font-semibold">{questionLabel(content, k)} <code className="text-xs font-normal">{k}</code></legend>
            <p className="text-xs text-muted-foreground">This form already asks “{ours}” with the key {k}.</p>
            <div className="flex flex-wrap items-center gap-2">
              <label className="flex items-center gap-2 text-sm">
                <input type="radio" name={name} checked={choice?.action === 'rename'} onChange={() => onChange({ ...choices, [k]: { action: 'rename', to: choice?.action === 'rename' ? choice.to : `${k}2` } })} />
                Rename to
              </label>
              <Input
                aria-label={`New key for ${questionLabel(content, k)}`}
                className="w-48"
                value={choice?.action === 'rename' ? choice.to : ''}
                disabled={choice?.action !== 'rename'}
                spellCheck={false}
                maxLength={64}
                aria-invalid={!!problems[k]}
                onChange={(e) => onChange({ ...choices, [k]: { action: 'rename', to: e.target.value } })}
              />
              <label className="flex items-center gap-2 text-sm">
                <input type="radio" name={name} checked={choice?.action === 'skip'} onChange={() => onChange({ ...choices, [k]: { action: 'skip' } })} />
                Skip (keep this form's question)
              </label>
            </div>
            {problems[k] ? <p role="alert" className="text-xs text-danger">{problems[k]}</p> : null}
          </fieldset>
        )
      })}
    </div>
  )
}

function InsertSharedForm() {
  const { store, wsId } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const linked = new Set(formLinks(def).map((l) => l.key))
  const [assetKey, setAssetKey] = useState('')
  const [version, setVersion] = useState<number | null>(null)
  const [choices, setChoices] = useState<Record<string, ClashChoice>>({})
  const { busy, status, error, run, setStatus } = useAction()
  const list = useQuery({ queryKey: ['ws', wsId, 'shared-assets', 'form-choices'], queryFn: () => endpoints.listSharedAssets(wsId, { kind: 'form' }), retry: false })
  const detail = useQuery({ queryKey: assetQuery(wsId, assetKey), queryFn: () => endpoints.getSharedAsset(wsId, 'form', assetKey), enabled: !!assetKey, retry: false })
  const items = (list.data?.items ?? []).filter((a) => !linked.has(a.key))
  const chosenVersion = version ?? detail.data?.asset.latestVersion ?? null
  const content = chosenVersion ? contentOf(detail.data, chosenVersion) : undefined
  const clashes = content ? clashingKeys(def, content) : []
  // Each clash defaults to a rename to a free key until the designer chooses otherwise.
  const effective = content ? { ...defaultChoices(def, clashes, content), ...choices } : choices
  const problems = content ? choiceProblems(def, content, clashes, effective) : {}
  const pick = (key: string) => {
    setAssetKey(key)
    setVersion(null)
    setChoices({})
    setStatus(null)
  }

  if (items.length === 0 && !assetKey) {
    return (
      <>
        {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
        {list.isSuccess ? <p className="text-sm text-muted-foreground">No other shared form to insert yet. Save a request form as a shared form to reuse it.</p> : null}
      </>
    )
  }
  return (
    <div className="space-y-3 rounded-control border border-border p-3" data-testid="insert-shared-form">
      <FormField label="Insert a shared form" id="insert-shared-form" description="Copies its questions, rules and groups into this request form and links the version.">
        {(ids) => (
          <Select id={ids.input} value={assetKey} disabled={busy} onChange={(e) => pick(e.target.value)}>
            <option value="">Choose a shared form</option>
            {items.map((a) => <option key={a.key} value={a.key}>{a.name} (version {a.latestVersion})</option>)}
          </Select>
        )}
      </FormField>
      {detail.data && detail.data.versions.length > 1 ? (
        <FormField label="Version" id="insert-shared-form-version">
          {(ids) => (
            <Select id={ids.input} value={String(chosenVersion ?? '')} onChange={(e) => { setVersion(Number(e.target.value)); setChoices({}) }}>
              {detail.data.versions.map((v) => <option key={v.version} value={v.version}>Version {v.version}{v.changeNote ? ` — ${v.changeNote}` : ''}</option>)}
            </Select>
          )}
        </FormField>
      ) : null}
      {content ? (
        <>
          <p className="text-sm" data-testid="insert-shared-form-summary">
            {questionCount(content)} {questionCount(content) === 1 ? 'question' : 'questions'}: {Object.keys(content.inputSchema.properties).map((k) => questionLabel(content, k)).join(', ')}.
          </p>
          <ClashChoices def={def} content={content} clashes={clashes} choices={effective} onChange={setChoices} problems={problems} />
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="primary" disabled={busy || Object.keys(problems).length > 0} onClick={() => run(async () => {
              const mapping = mappingFor(content, effective)
              store.getState().apply((d) => insertSharedForm(d, assetKey, chosenVersion!, content, effective))
              const renamed = Object.entries(mapping).filter(([k, v]) => k !== v).map(([k, v]) => `${k} as ${v}`)
              const skipped = clashes.filter((k) => effective[k]?.action === 'skip')
              pick('')
              return `Inserted ${Object.keys(mapping).length} ${Object.keys(mapping).length === 1 ? 'question' : 'questions'} from ${detail.data!.asset.name}, version ${chosenVersion}.`
                + (renamed.length ? ` Renamed: ${renamed.join(', ')}.` : '') + (skipped.length ? ` Skipped: ${skipped.join(', ')}.` : '')
            })}>
              <SquarePlus className="size-4" aria-hidden="true" /> Insert shared form v{chosenVersion}
            </Button>
            <Button type="button" variant="ghost" disabled={busy} onClick={() => pick('')}>Cancel</Button>
          </div>
          {Object.keys(problems).length > 0 ? <p className="text-xs text-muted-foreground">Resolve every key clash to insert.</p> : null}
        </>
      ) : null}
      {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
      {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
    </div>
  )
}

function SaveSharedForm() {
  const { store, wsId } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyEdited, setKeyEdited] = useState(false)
  const [description, setDescription] = useState('')
  const { busy, status, error, run } = useAction()
  const questions = Object.keys(def.inputSchema.properties).length
  const effectiveKey = keyEdited ? key : assetKeyOf(name)
  const save = () => run(async () => {
    // Save the questions in the shape the editor writes, so the copy and the shared version compare equal.
    const normalized = applyFields(def, fieldsFromDefinition(def))
    const content = formContentOf(normalized)
    const created = await endpoints.createFormAsset(wsId, { key: effectiveKey.trim(), name: name.trim(), description: description.trim() || null, content })
    const claimed = copiedQuestions(normalized)
    const fields = Object.fromEntries(Object.keys(normalized.inputSchema.properties).filter((k) => !claimed.has(k)).map((k) => [k, k]))
    store.getState().apply((d) => {
      const base = d === def ? normalized : applyFields(d, fieldsFromDefinition(d))
      return Object.keys(fields).length > 0 ? linkSharedForm(base, created.asset.key, 1, fields) : base
    })
    setOpen(false)
    void queryClient.invalidateQueries({ queryKey: ['ws', wsId, 'shared-assets'] })
    return `Saved as shared form ${created.asset.name}, version 1. This request form is linked to it; other workflows can insert it.`
  })
  if (!open) {
    return (
      <div className="space-y-2">
        <Button type="button" variant="secondary" disabled={questions === 0} onClick={() => { setOpen(true); setName(def.name ? `${def.name} request` : ''); setKeyEdited(false) }}>
          <Share2 className="size-4" aria-hidden="true" /> Save as shared form
        </Button>
        {questions === 0 ? <p className="text-xs text-muted-foreground">Add a question first.</p> : null}
        {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
      </div>
    )
  }
  return (
    <form aria-label="Save as shared form" className="space-y-3 rounded-control border border-border p-3" onSubmit={(e) => { e.preventDefault(); void save() }}>
      <p className="text-sm">Saves all {questions} {questions === 1 ? 'question' : 'questions'} with their rules and groups as version 1. The submit label and other form settings stay with this workflow.</p>
      <FormField label="Shared name" required id="share-form-name">
        {(ids) => <Input id={ids.input} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <FormField label="Shared key" required id="share-form-key" description="Lowercase letters, digits and dashes. Workflows refer to the shared form by this key." error={effectiveKey && !isValidAssetKey(effectiveKey) ? 'Use 2 to 64 lowercase letters, digits and dashes, starting with a letter.' : undefined}>
        {(ids) => <Input id={ids.input} value={effectiveKey} maxLength={64} spellCheck={false} onChange={(e) => { setKey(e.target.value); setKeyEdited(true) }} />}
      </FormField>
      <FormField label="Description" id="share-form-description" optionalText>
        {(ids) => <Input id={ids.input} value={description} maxLength={300} onChange={(e) => setDescription(e.target.value)} />}
      </FormField>
      <div className="flex gap-2">
        <Button type="submit" variant="primary" disabled={busy || !name.trim() || !isValidAssetKey(effectiveKey)}>Save shared form</Button>
        <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
      </div>
      {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
    </form>
  )
}

function LinkedForm({ link }: { link: FormLink }) {
  const { store, wsId, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const queryClient = useQueryClient()
  const asset = useQuery({ queryKey: assetQuery(wsId, link.key), queryFn: () => endpoints.getSharedAsset(wsId, 'form', link.key), retry: false })
  const [updating, setUpdating] = useState(false)
  const [choices, setChoices] = useState<Record<string, ClashChoice>>({})
  const { busy, status, error, run } = useAction()
  const detail = asset.data
  const pinned = contentOf(detail, link.version)
  const latest = detail?.versions[0]
  const latestContent = latest ? contentOf(detail, latest.version) : undefined
  const differs = !!pinned && !copyMatches(def, link, pinned)
  const newer = !!latest && latest.version > link.version
  const renamed = Object.entries(link.fields).filter(([k, v]) => k !== v)
  const diff = pinned && latestContent && newer ? formDiff(pinned, latestContent) : null
  const clashes = pinned && latestContent && newer ? updateClashes(def, link, pinned, latestContent) : []
  const effective = latestContent ? { ...defaultChoices(def, clashes, latestContent), ...choices } : choices
  const problems = latestContent ? choiceProblems(def, latestContent, clashes, effective) : {}
  const label = (keys: string[], content: FormContent) => keys.map((k) => questionLabel(content, k)).join(', ')

  return (
    <li className="space-y-2 rounded-control border border-border p-3" data-testid="shared-form-link">
      <div className="flex flex-wrap items-center gap-2">
        <strong>{detail?.asset.name ?? link.key}</strong>
        <code className="text-xs">{link.key}</code>
        <Badge tone="neutral">Version {link.version}</Badge>
        {differs ? <Badge tone="danger">Edited here</Badge> : newer ? <Badge tone="warning">Version {latest!.version} available</Badge> : detail ? <Badge tone="success">Up to date</Badge> : null}
      </div>
      <p className="text-xs text-muted-foreground">
        {Object.keys(link.fields).length} {Object.keys(link.fields).length === 1 ? 'question' : 'questions'} copied{renamed.length ? `; renamed here: ${renamed.map(([k, v]) => `${k} as ${v}`).join(', ')}` : ''}.
      </p>
      {asset.isError ? <p role="alert" className="text-sm text-danger">The shared form could not be loaded. It may have been removed; validation reports it before publishing.</p> : null}
      {detail?.asset.archived ? <p role="alert" className="text-sm text-danger">This shared form is archived. Publishing is blocked until it is restored or you unlink it.</p> : null}
      {differs ? <p role="alert" className="text-sm text-danger">The copied questions no longer match version {link.version}. Publishing is blocked until you restore them, save them as a new shared version, or unlink.</p> : null}
      {newer && !updating ? <p className="text-sm">Version {latest!.version} is available. This workflow keeps version {link.version} until you update it.</p> : null}
      {updating && diff && latestContent && pinned ? (
        <div className="space-y-3 rounded-control bg-surface-muted p-3" data-testid="shared-form-diff">
          <p className="text-sm font-semibold">Changes in version {latest!.version}</p>
          <ul className="space-y-1 text-sm">
            <li>Added: {diff.added.length ? label(diff.added, latestContent) : 'none'}</li>
            <li>Removed: {diff.removed.length ? label(diff.removed, pinned) : 'none'}</li>
            <li>Changed: {diff.changed.length ? label(diff.changed, latestContent) : 'none'}</li>
          </ul>
          {diff.removed.some((k) => k in link.fields) ? <p className="text-xs text-muted-foreground">Removed questions are deleted from this form. Check rules and steps that use them before publishing.</p> : null}
          {differs ? <p className="text-xs text-danger">Your edits to the copied questions are replaced by version {latest!.version}.</p> : null}
          <ClashChoices def={def} content={latestContent} clashes={clashes} choices={effective} onChange={setChoices} problems={problems} />
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="primary" disabled={busy || Object.keys(problems).length > 0} onClick={() => run(async () => {
              store.getState().apply((d) => updateSharedForm(d, link, pinned, latestContent, latest!.version, effective))
              setUpdating(false)
              setChoices({})
              return `Updated to version ${latest!.version}.`
            })}>
              Apply version {latest!.version}
            </Button>
            <Button type="button" variant="ghost" onClick={() => { setUpdating(false); setChoices({}) }}>Cancel</Button>
          </div>
        </div>
      ) : null}
      {!readOnly && !updating ? (
        <div className="flex flex-wrap gap-2">
          {newer && latestContent ? (
            <Button type="button" variant="secondary" disabled={busy} onClick={() => setUpdating(true)}>Update to v{latest!.version}</Button>
          ) : null}
          {differs && pinned ? (
            <Button type="button" variant="secondary" disabled={busy} onClick={() => run(async () => {
              store.getState().apply((d) => updateSharedForm(d, link, pinned, pinned, link.version, {}))
              return `Restored the copied questions to version ${link.version}.`
            })}>
              Restore version {link.version}
            </Button>
          ) : null}
          {differs && pinned && detail && !detail.asset.archived && !newer ? (
            <Button type="button" variant="secondary" disabled={busy} onClick={() => run(async () => {
              const saved = await endpoints.addFormAssetVersion(wsId, link.key, { expectedVersion: detail.asset.latestVersion, content: copyAsContent(def, link, pinned) })
              store.getState().apply((d) => linkSharedForm(d, link.key, saved.asset.latestVersion, link.fields))
              void queryClient.invalidateQueries({ queryKey: ['ws', wsId, 'shared-asset'] })
              return `Saved as version ${saved.asset.latestVersion} of ${saved.asset.name}. Other workflows keep their versions.`
            })}>
              Save as shared version {detail.asset.latestVersion + 1}
            </Button>
          ) : null}
          <Button type="button" variant="ghost" disabled={busy} onClick={() => run(async () => {
            store.getState().apply((d) => unlinkSharedForm(d, link.key))
            return 'Unlinked. The questions stay as this workflow\'s own and are no longer checked against the shared form.'
          })}>
            <Link2Off className="size-4" aria-hidden="true" /> Unlink
          </Button>
        </div>
      ) : null}
      {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
      {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
    </li>
  )
}
