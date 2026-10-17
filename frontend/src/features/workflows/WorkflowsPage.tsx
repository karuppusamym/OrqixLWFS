import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, FileClock, FilePlus2, PencilLine, Workflow as WorkflowIcon } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { useTemplates, useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { fieldErrorMap } from '@/lib/problemMessages'
import { humanize, KEY_PATTERN, toKey } from '@/lib/text'
import { useListFilters } from '@/lib/useListFilters'
import { DescribeItChoice, DescribeItDialog } from '@/features/ai/DescribeItDialog'
import { PinnedWorkflows, PinWorkflowButton } from './PinnedWorkflows'

/** Workflow list with "New workflow" (docs/build/S7-frontend.md section 2). */
export function WorkflowsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const { params, update } = useListFilters()
  const applied = params.get('q') ?? ''
  const clearSearch = () => update({ q: '' })
  const [creating, setCreating] = useState(false)
  const canRead = can('workflow.read')
  const query = useWorkflows(ws, applied ? { q: applied } : {})
  const items = query.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <PageHeader
        title="Workflows"
        description={canRead ? 'Build, publish and roll back the workflows your team uses.' : 'Workflows you can start.'}
        actions={
          can('workflow.edit') ? (
            <div className="flex flex-wrap gap-2">
            <Button asChild size="lg"><Link to={`/w/${ws}/migrations`}>Import workflow</Link></Button>
            <Button variant="primary" size="lg" onClick={() => setCreating(true)}>
              <FilePlus2 aria-hidden="true" /> New workflow
            </Button>
            </div>
          ) : null
        }
      />
      <PinnedWorkflows />
      <form
        className="mb-4 flex flex-wrap items-end gap-2 rounded-card border border-border bg-surface p-4"
        role="search"
        onSubmit={(event) => {
          event.preventDefault()
          const value = new FormData(event.currentTarget).get('q')
          update({ q: typeof value === 'string' ? value.trim() : '' })
        }}
      >
        <div className="space-y-1.5">
          <Label htmlFor="wf-search">Search workflows</Label>
          <Input id="wf-search" key={`${ws}:${applied}`} name="q" defaultValue={applied} className="w-64 max-w-full" placeholder="Search by name" maxLength={100} />
        </div>
        <Button type="submit" variant="secondary">
          Search
        </Button>
        {applied ? <Button variant="ghost" onClick={clearSearch}>Clear search</Button> : null}
      </form>

      {query.isPending ? (
        <LoadingBlock rows={3} label="Loading workflows" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="workflows" />
      ) : items.length === 0 ? (
        <EmptyState icon={WorkflowIcon} title={applied ? 'No workflows match your search' : 'No workflows yet'} action={applied ? <Button onClick={clearSearch}>Show all workflows</Button> : undefined}>
          {applied ? 'Try another name or clear your search.' : can('workflow.edit') ? 'Create one from a template to get started.' : 'Ask a designer to publish a workflow.'}
        </EmptyState>
      ) : (
        <>
          <div className="overflow-hidden rounded-card border border-border bg-surface">
            <Table>
              <caption className="sr-only">Workflows</caption>
              <THead>
                <TR>
                  <TH>Name</TH>
                  <TH>Status</TH>
                  <TH>Draft</TH>
                  <TH>Last change</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {items.map((w) => (
                  <TR key={w.id}>
                    <TD>
                      {canRead ? (
                        <Link to={`/w/${ws}/workflows/${w.id}`} className="font-semibold">
                          {w.name}
                        </Link>
                      ) : (
                        <span className="font-semibold">{w.name}</span>
                      )}
                      {w.description ? <span className="block max-w-md text-xs text-muted-foreground">{w.description}</span> : null}
                    </TD>
                    <TD>
                      {w.activeRelease ? (
                        <div className="flex flex-wrap gap-1.5">
                          <Badge tone="success" title="New requests use this version">
                            <CheckCircle2 aria-hidden="true" /> Active v{w.activeRelease.releaseNumber}
                          </Badge>
                          {w.draftRevision > w.activeRelease.draftRevision ? (
                            <Badge tone="warning" title="The draft has changes that new requests do not use until you publish">
                              <PencilLine aria-hidden="true" /> Changes not published
                            </Badge>
                          ) : null}
                        </div>
                      ) : (
                        <Badge title="Nobody can start this workflow until it is published">
                          <FileClock aria-hidden="true" /> Draft only
                        </Badge>
                      )}
                    </TD>
                    <TD>Revision {w.draftRevision}</TD>
                    <TD className="whitespace-nowrap text-muted-foreground">
                      <RelativeTime value={w.updatedAt} />
                    </TD>
                    <TD>
                      <div className="flex flex-wrap justify-end gap-2">
                        {canRead ? <PinWorkflowButton id={w.id} name={w.name} /> : null}
                        {w.canStart ? (
                          <Button asChild size="sm" variant="primary">
                            <Link to={`/w/${ws}/start/${w.id}`} aria-label={`Start ${w.name}`}>
                              Start
                            </Link>
                          </Button>
                        ) : null}
                        {canRead ? (
                          <Button asChild size="sm" variant="secondary">
                            <Link to={`/w/${ws}/workflows/${w.id}/edit`} aria-label={`${can('workflow.edit') ? 'Edit draft of' : 'View draft of'} ${w.name}`}>
                              {can('workflow.edit') ? 'Edit draft' : 'View draft'}
                            </Link>
                          </Button>
                        ) : null}
                      </div>
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
          {query.hasNextPage ? (
            <div className="mt-4 flex justify-center">
              <Button variant="secondary" onClick={() => void query.fetchNextPage()} loading={query.isFetchingNextPage}>
                Load more
              </Button>
            </div>
          ) : null}
        </>
      )}
      {creating ? <NewWorkflowDialog onClose={() => setCreating(false)} /> : null}
    </>
  )
}

function NewWorkflowDialog({ onClose }: { onClose: () => void }) {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const navigate = useNavigate()
  const client = useQueryClient()
  const templates = useTemplates(ws)
  const [templateId, setTemplateId] = useState('')
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyTouched, setKeyTouched] = useState(false)
  const [description, setDescription] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [describing, setDescribing] = useState(false)

  const effectiveKey = keyTouched ? key : toKey(name)
  const chosenTemplate = templateId === '' ? (templates.data?.items[0]?.id ?? 'blank') : templateId
  const radioValue = chosenTemplate

  const submit = async () => {
    const next: Record<string, string> = {}
    if (!name.trim()) next.name = 'Give the workflow a name.'
    if (!KEY_PATTERN.test(effectiveKey)) next.key = 'Use 2 or more lowercase letters, digits and dashes, starting with a letter.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setPending(true)
    setError(null)
    try {
      const created = await endpoints.createWorkflow(ws, {
        key: effectiveKey,
        name: name.trim(),
        ...(description.trim() ? { description: description.trim() } : {}),
        ...(chosenTemplate !== 'blank' ? { templateId: chosenTemplate } : {}),
      })
      await client.invalidateQueries({ queryKey: ['ws', ws, 'workflows'] })
      navigate(`/w/${ws}/workflows/${created.id}/edit`)
    } catch (cause) {
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (Object.keys(mapped).length > 0) setErrors(mapped)
      else setError(problem)
      if (problem.code === 'DUPLICATE_KEY') setErrors({ key: 'A workflow with this key already exists. Choose a different key.' })
    } finally {
      setPending(false)
    }
  }

  if (describing) return <DescribeItDialog onClose={onClose} onBack={() => setDescribing(false)} />

  return (
    <Dialog
      open
      onClose={onClose}
      title="New workflow"
      description="Start from a template or from a blank workflow. You can change everything afterwards."
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>
            Create workflow
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <fieldset className="space-y-2">
          <legend className="text-sm font-semibold">Start from</legend>
          {templates.isPending ? (
            <p className="text-sm text-muted-foreground">Loading templates…</p>
          ) : (
            <RadioGroup value={radioValue} onValueChange={setTemplateId}>
              {(templates.data?.items ?? []).map((t) => (
                <div key={t.id} className="flex items-start gap-3 rounded-control border border-border p-3">
                  <RadioGroupItem value={t.id} id={`tpl-${t.id}`} />
                  <Label htmlFor={`tpl-${t.id}`} className="font-normal">
                    <span className="font-semibold">{t.name}</span>
                    <span className="block text-muted-foreground">{t.description}</span>
                    <span className="mt-1 block text-xs text-muted-foreground">
                      Needs approver groups: {(t.requiredGroups ?? []).map(humanize).join(', ') || 'none'} &middot; connections: {(t.requiredConnections ?? []).map(humanize).join(', ') || 'none'}{(t.requiredWorkflows ?? []).length > 0 ? <> &middot; starts workflows: {(t.requiredWorkflows ?? []).map(humanize).join(', ')}</> : null}
                    </span>
                  </Label>
                </div>
              ))}
              <div className="flex items-start gap-3 rounded-control border border-border p-3">
                <RadioGroupItem value="blank" id="tpl-blank" />
                <Label htmlFor="tpl-blank" className="font-normal">
                  <span className="font-semibold">Blank workflow</span>
                  <span className="block text-muted-foreground">A request form and a finish step to build on.</span>
                </Label>
              </div>
            </RadioGroup>
          )}
          <DescribeItChoice onSelect={() => setDescribing(true)} />
        </fieldset>
        <FormField label="Name" required id="wf-name" error={errors.name}>
          {(ids) => <Input id={ids.input} value={name} onChange={(e) => setName(e.target.value)} maxLength={200} aria-invalid={errors.name ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" />}
        </FormField>
        <FormField label="Key" required id="wf-key" error={errors.key} description="Used in links and exports. Letters, digits and dashes.">
          {(ids) => (
            <Input
              id={ids.input}
              value={effectiveKey}
              onChange={(e) => {
                setKey(e.target.value)
                setKeyTouched(true)
              }}
              maxLength={64}
              aria-invalid={errors.key ? true : undefined}
              aria-describedby={ids.describedBy}
              autoComplete="off"
              spellCheck={false}
            />
          )}
        </FormField>
        <FormField label="Description" optionalText id="wf-desc">
          {(ids) => <Textarea id={ids.input} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} rows={2} />}
        </FormField>
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}
