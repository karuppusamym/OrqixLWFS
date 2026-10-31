import { usePresentedView,ViewLanguage } from '@/features/views/ViewLanguage'
import { viewSurface } from '@/features/views/presentation'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Briefcase, FolderOpen } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { CaseDataFields, typedValues, type DataField, type DataInputs } from './CaseDataFields'
import type { components } from '@/api/schema'
import type { ViewDefinition } from '@/features/views/model'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { listCaseDefinitions, listCaseInstances, startCaseInstance } from './api'
import { CASE_STATUS, caseTypeName, shortCaseId } from './caseText'

/**
 * Working cases: start a case of a published case type, follow and work the cases. Case types are authored on the
 * case designer page (S126, docs/26 C3), linked from here for people who may author.
 */
export function CasesPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const definitions = useQuery({ queryKey: ['ws', ws, 'case-definitions'], queryFn: () => listCaseDefinitions(ws), enabled: can('workflow.read', 'workflow.start') })
  const cases = useQuery({ queryKey: ['ws', ws, 'cases'], queryFn: () => listCaseInstances(ws), enabled: can('run.read', 'workflow.start', 'task.act') })
  const [starting, setStarting] = useState<components['schemas']['CaseDefinition'] | null>(null)
  const appearance=usePresentedView(starting?.viewBinding?.definition as ViewDefinition|undefined)
  const [initialData, setInitialData] = useState<DataInputs>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const start = async (definitionId: string, fields: DataField[] = []) => {
    setPending(true); setError(null)
    try {
      const instance = await startCaseInstance(ws, definitionId, typedValues(fields, initialData))
      await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'cases'] })
      navigate(`/w/${ws}/cases/${instance.id}`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The case could not be started.') }
    finally { setPending(false) }
  }

  if (!can('workflow.read', 'workflow.start', 'run.read', 'task.act')) return <Alert tone="warning">You need case access to view this page.</Alert>
  return <div className="space-y-8">
    <PageHeader title="Cases" description="Work that moves through stages and needs people to decide what happens next. Start a case, then follow its tasks and progress."
      actions={can('workflow.edit') ? <Button asChild size="lg"><Link to={`/w/${ws}/case-designs`}>Design case types</Link></Button> : null} />
    {error ? <Alert tone="danger">{error}</Alert> : null}
    {can('workflow.read', 'workflow.start') ? <section className="space-y-3" aria-labelledby="case-types-heading">
      <h2 id="case-types-heading" className="text-lg">Case types</h2>
      {definitions.isPending ? <LoadingBlock label="Loading case types" /> : definitions.isError ? <QueryError error={definitions.error} onRetry={() => void definitions.refetch()} what="case types" /> : definitions.data.length ?
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{definitions.data.map(definition => <li key={definition.id} className="flex flex-col justify-between gap-3 rounded-card border border-border bg-surface p-4">
          <div className="min-w-0">
            <p className="flex items-start gap-2 font-semibold"><Briefcase className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" /><span className="wrap-anywhere">{definition.name}</span></p>
            <p className="mt-1 text-sm text-muted-foreground">Version {definition.version}{definition.dataFields?.length ? ` · asks for ${definition.dataFields.length} ${definition.dataFields.length === 1 ? 'detail' : 'details'} to start` : ''}</p>
          </div>
          {can('workflow.start') ? <div><Button variant="primary" disabled={pending} onClick={() => { if (definition.dataFields?.length) { setInitialData({}); setStarting(definition) } else void start(definition.id) }}>Start case</Button></div> : null}
        </li>)}</ul>
        : <EmptyState icon={Briefcase} title="No case types published yet" action={can('workflow.edit') ? <Button asChild><Link to={`/w/${ws}/case-designs`}>Design a case type</Link></Button> : undefined}>
            {can('workflow.edit') ? 'Design a case type and publish it to let people start cases here.' : 'Ask a designer to publish a case type.'}
          </EmptyState>}
    </section> : null}
    <Dialog open={starting !== null} onClose={() => setStarting(null)} dismissible={!pending} title="Start case" description={starting?.name}>
      <form lang={appearance.locale} className={`space-y-4 ${viewSurface(appearance.view)}`} onSubmit={event => { event.preventDefault(); if (starting) void start(starting.id, starting.dataFields ?? []) }}>
        <ViewLanguage definition={starting?.viewBinding?.definition as ViewDefinition|undefined} locale={appearance.locale} onChange={appearance.setLocale}/>
        {starting ? <CaseDataFields view={appearance.view} fields={starting.dataFields ?? []} values={initialData} onChange={setInitialData} disabled={pending} /> : null}
        {error ? <Alert tone="danger">{error}</Alert> : null}
        <Button type="submit" variant="primary" loading={pending} disabled={pending}>Start case with data</Button>
      </form>
    </Dialog>
    {can('run.read', 'workflow.start', 'task.act') ? <section className="space-y-3" aria-labelledby="recent-cases-heading">
      <h2 id="recent-cases-heading" className="text-lg">Recent cases</h2>
      {cases.isPending ? <LoadingBlock label="Loading cases" /> : cases.isError ? <QueryError error={cases.error} onRetry={() => void cases.refetch()} what="cases" /> : cases.data.length ?
        <div className="overflow-x-auto rounded-card border border-border bg-surface"><Table><caption className="sr-only">Recent cases</caption><THead><TR><TH>Case</TH><TH>Status</TH><TH>Open tasks</TH><TH>Started</TH></TR></THead><TBody>{cases.data.map(instance => <TR key={instance.id}>
          <TD><Link className="font-semibold" to={`/w/${ws}/cases/${instance.id}`}>{caseTypeName(definitions.data, instance.definitionId) ?? 'Case'}</Link><span className="block font-mono text-xs text-muted-foreground" title={instance.id}>#{shortCaseId(instance.id)}</span></TD>
          <TD><Badge tone={CASE_STATUS[instance.status].tone}>{CASE_STATUS[instance.status].label}</Badge></TD>
          <TD>{instance.tasks.length}</TD>
          <TD className="whitespace-nowrap text-muted-foreground"><RelativeTime value={instance.createdAt} /></TD>
        </TR>)}</TBody></Table></div>
        : <EmptyState icon={FolderOpen} title="No cases yet">Cases you start or work on appear here.</EmptyState>}
    </section> : null}
  </div>
}
