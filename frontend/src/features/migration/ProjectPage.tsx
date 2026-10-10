import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { JobReview } from './JobReview'

type Project = components['schemas']['MigrationProject']
type Conversion = components['schemas']['MigrationProjectConversion']

/**
 * The server derives a status and the created workflow for each file. The published contract does not list these two
 * properties yet, so they are read through this extension and treated as optional.
 */
export type ProjectFile = Project['files'][number] & { status?: 'analyzed' | 'converted' | 'failed' | 'blocked'; convertedWorkflowId?: string | null }

const STATUS_TONE = { analyzed: 'neutral', converting: 'info', converted: 'success', blocked: 'danger', failed: 'danger' } as const
const FILE_STATUS = { analyzed: 'Not converted yet', converted: 'Converted', failed: 'Failed', blocked: 'Blocked' } as const

export const projectKey = (ws: string, id: string) => ['ws', ws, 'migration-project', id] as const

/** The file convert-next converts now: the first one in the conversion order that is not converted yet. */
export function nextFile(project: Project): ProjectFile | null {
  const files = project.files as ProjectFile[]
  for (const id of project.conversionOrder) {
    const file = files.find((f) => f.migrationId === id)
    if (file && file.status !== 'converted') return file
  }
  return null
}

export function ProjectPage() {
  const { projectId = '' } = useParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const [last, setLast] = useState<{ fileName: string; workflowId: string; workflowKey: string } | null>(null)
  const query = useQuery({ queryKey: projectKey(ws, projectId), queryFn: () => endpoints.getMigrationProject(ws, projectId), enabled: can('workflow.read') })
  const back = <Button asChild><Link to={`/w/${ws}/migrations`}>Import another file</Link></Button>
  if (!can('workflow.read')) return <Alert tone="warning">You need workflow access to view migrations.</Alert>
  if (query.isPending) return <LoadingBlock rows={4} label="Loading the project" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="this migration project" backTo={{ to: `/w/${ws}/migrations`, label: 'Back to Import workflow' }} />
  const project = query.data
  const files = project.files as ProjectFile[]
  const byMigration = new Map(files.map((f) => [f.migrationId, f]))
  const converted = files.filter((f) => f.status === 'converted').length
  const next = nextFile(project)
  const position = next ? project.conversionOrder.indexOf(next.migrationId!) + 1 : 0
  const onConverted = (result: Conversion) => {
    client.setQueryData(projectKey(ws, projectId), result.project)
    setLast({ fileName: next?.fileName ?? 'the file', workflowId: result.converted.workflow.id, workflowKey: result.converted.workflow.key })
    announce(`Converted ${next?.fileName ?? 'the file'} to a draft. ${converted + 1} of ${files.length} files converted.`)
  }
  return <>
    <PageHeader title={project.name} description={`Multi-file migration project: ${files.length} ${files.length === 1 ? 'file' : 'files'}, ${converted} converted.`} actions={back} />
    <div className="mb-4 flex flex-wrap items-center gap-2" data-testid="project-status">
      <Badge tone={STATUS_TONE[project.status]}>{project.status === 'analyzed' ? 'Analyzed' : project.status === 'converting' ? 'Converting' : project.status === 'converted' ? 'All files converted' : 'Blocked'}</Badge>
      <span className="text-sm text-muted-foreground">The source files are stored unchanged. Converting creates drafts only; nothing is published or started.</span>
    </div>

    {project.issues.length ? <Alert tone="danger" title="Project findings block conversion" className="mb-6">
      <p>No file can be converted until every finding is resolved. A project cannot be edited: fix the source files and import the project again.</p>
      <ul aria-label="Project findings" className="mt-2 list-disc space-y-1 pl-5">{project.issues.map((issue, index) => <li key={index}><span className="font-semibold">{issue.fileName}</span>: {issue.detail} <span className="font-mono text-xs">{issue.code}</span></li>)}</ul>
    </Alert> : null}

    <section aria-label="Analysis" className="mb-6 space-y-3">
      <h2>Files and dependencies</h2>
      <Table aria-label="Project files">
        <THead><TR><TH>File</TH><TH>Process</TH><TH>Calls</TH><TH>Status</TH></TR></THead>
        <TBody>{files.map((file) => <TR key={file.fileName}>
          <TD className="break-all font-semibold">{file.migrationId ? <Link to={`/w/${ws}/migrations/${file.migrationId}`}>{file.fileName}</Link> : file.fileName}</TD>
          <TD className="break-all font-mono text-xs">{file.processId ?? 'Not analyzed'}</TD>
          <TD className="break-all font-mono text-xs">{file.dependencies.length ? file.dependencies.join(', ') : 'None'}</TD>
          <TD><Badge tone={file.status === 'converted' ? 'success' : file.status === 'blocked' || file.status === 'failed' ? 'danger' : 'neutral'}>{FILE_STATUS[file.status ?? 'analyzed']}</Badge>
            {file.convertedWorkflowId ? <> <Link className="text-sm" to={`/w/${ws}/workflows/${file.convertedWorkflowId}/edit?migration=${file.migrationId}`}>Open draft</Link></> : null}</TD>
        </TR>)}</TBody>
      </Table>
      {project.conversionOrder.length ? <div>
        <h3>Conversion order</h3>
        <p className="text-sm text-muted-foreground">Called processes first, so each parent can point at a converted and published child.</p>
        <ol aria-label="Conversion order" className="mt-2 list-decimal space-y-1 pl-6">{project.conversionOrder.map((id) => {
          const file = byMigration.get(id)
          return <li key={id} className="break-all">{file?.fileName ?? id}{file?.status === 'converted' ? ' (converted)' : file === next ? ' (next)' : ''}</li>
        })}</ol>
      </div> : null}
    </section>

    {last ? <Alert tone="success" className="mb-6" title={`Converted ${last.fileName}`}>
      <p>The draft <span className="font-mono">{last.workflowKey}</span> was created. Review it and publish it before you convert a file that calls it, so the parent can pin the child&apos;s release.</p>
      <p className="mt-2 flex flex-wrap gap-3"><Link to={`/w/${ws}/workflows/${last.workflowId}/edit`}>Open the draft</Link><Link to={`/w/${ws}/workflows/${last.workflowId}/publish`}>Review and publish</Link></p>
    </Alert> : null}

    {project.status === 'blocked' ? null : next ? <NextFile key={next.migrationId} projectId={projectId} file={next} position={position} total={project.conversionOrder.length} editable={can('workflow.edit')} onConverted={onConverted} />
      : project.status === 'converted' ? <Alert tone="success" title="Every file is converted">Review each draft and publish children before the parents that call them.</Alert> : null}
  </>
}

function NextFile({ projectId, file, position, total, editable, onConverted }: { projectId: string; file: ProjectFile; position: number; total: number; editable: boolean; onConverted: (result: Conversion) => void }) {
  const { workspace } = useWorkspace()
  const job = useQuery({ queryKey: ['ws', workspace.id, 'migration', file.migrationId], queryFn: () => endpoints.getMigration(workspace.id, file.migrationId!) })
  return <section aria-label="Next file to convert" className="space-y-3">
    <h2>Next file: {file.fileName}</h2>
    <p className="text-sm text-muted-foreground">Step {position} of {total}. {editable ? 'Resolve this file’s mappings with the same form as a single import, then convert it. The next file follows.' : 'You can read the report; converting needs permission to edit workflows.'}</p>
    {job.isPending ? <LoadingBlock label="Loading the compatibility report" /> : null}
    {job.isError ? <QueryError error={job.error} onRetry={() => void job.refetch()} what="the migration report" /> : null}
    {job.data ? <JobReview key={job.data.id} job={job.data} editable={editable} project={{ projectId, onConverted }} /> : null}
  </section>
}
