import { usePresentedView,ViewLanguage } from '@/features/views/ViewLanguage'
import { viewSurface } from '@/features/views/presentation'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { CaseDataFields, typedValues, type DataField, type DataInputs } from './CaseDataFields'
import type { components } from '@/api/schema'
import type { ViewDefinition } from '@/features/views/model'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { listCaseDefinitions, listCaseInstances, startCaseInstance } from './api'

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
  return <div className="space-y-6">
    <PageHeader title="Cases" description="Cases are pieces of work that need people to decide what happens next. Start a case of one of the case types below, work its tasks and follow its stages."
      actions={can('workflow.edit') ? <Link className="text-link underline" to={`/w/${ws}/case-designs`}>Design case types</Link> : null} />
    {error ? <Alert tone="danger">{error}</Alert> : null}
    {can('workflow.read', 'workflow.start') ? <section className="space-y-3" aria-label="Case types">
      <h2>Case types</h2>
      {definitions.isPending ? <LoadingBlock label="Loading case types" /> : definitions.isError ? <QueryError error={definitions.error} onRetry={() => void definitions.refetch()} what="case types" /> : definitions.data.length ?
        <div className="overflow-x-auto"><Table><THead><TR><TH>Name</TH><TH>Key</TH><TH>Version</TH><TH>Action</TH></TR></THead><TBody>{definitions.data.map(definition => <TR key={definition.id}><TD>{definition.name}</TD><TD>{definition.key}</TD><TD>{definition.version}</TD><TD>{can('workflow.start') ? <Button disabled={pending} onClick={() => { if (definition.dataFields?.length) { setInitialData({}); setStarting(definition) } else void start(definition.id) }}>Start case</Button> : null}</TD></TR>)}</TBody></Table></div>
        : <p className="text-sm text-muted-foreground">No case types are published in this workspace.{can('workflow.edit') ? <> <Link className="text-link underline" to={`/w/${ws}/case-designs`}>Design a case type</Link>.</> : null}</p>}
    </section> : null}
    <Dialog open={starting !== null} onClose={() => setStarting(null)} dismissible={!pending} title="Start case" description={starting?.name}>
      <form lang={appearance.locale} className={`space-y-4 ${viewSurface(appearance.view)}`} onSubmit={event => { event.preventDefault(); if (starting) void start(starting.id, starting.dataFields ?? []) }}>
        <ViewLanguage definition={starting?.viewBinding?.definition as ViewDefinition|undefined} locale={appearance.locale} onChange={appearance.setLocale}/>
        {starting ? <CaseDataFields view={appearance.view} fields={starting.dataFields ?? []} values={initialData} onChange={setInitialData} disabled={pending} /> : null}
        {error ? <Alert tone="danger">{error}</Alert> : null}
        <Button type="submit" loading={pending} disabled={pending}>Start case with data</Button>
      </form>
    </Dialog>
    {can('run.read', 'workflow.start', 'task.act') ? <section className="space-y-3" aria-label="Case instances">
      <h2>Recent cases</h2>
      {cases.isPending ? <LoadingBlock label="Loading cases" /> : cases.isError ? <QueryError error={cases.error} onRetry={() => void cases.refetch()} what="cases" /> : cases.data.length ?
        <div className="overflow-x-auto"><Table><THead><TR><TH>Case</TH><TH>Status</TH><TH>Open tasks</TH></TR></THead><TBody>{cases.data.map(instance => <TR key={instance.id}><TD><Link className="text-link underline" to={`/w/${ws}/cases/${instance.id}`}>{instance.id}</Link></TD><TD><Badge>{instance.status}</Badge></TD><TD>{instance.tasks.length}</TD></TR>)}</TBody></Table></div>
        : <p className="text-sm text-muted-foreground">No cases have started in this workspace.</p>}
    </section> : null}
  </div>
}
