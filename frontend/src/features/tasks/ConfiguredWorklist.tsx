import { usePresentedView,ViewLanguage } from '@/features/views/ViewLanguage'
import { viewSurface } from '@/features/views/presentation'
import { useMediaQuery } from '@/lib/useMediaQuery'
import { Link } from 'react-router'
import type { TaskSummary } from '@/api/types'
import type { ViewDefinition } from '@/features/views/model'
import { Button } from '@/components/ui/button'
import { Table,THead,TBody,TR,TH,TD } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { taskTitle,requestReference } from '@/lib/runText'

function value(task:TaskSummary,binding:string):string {
  switch(binding) {
    case 'task.requestTitle': return taskTitle(task)
    case 'task.reference': return requestReference(task)
    case 'task.label': return task.label
    case 'task.workflowName': return task.workflowName
    case 'task.priority': return String(task.priority??0)
    case 'task.requester': return task.requester.displayName
    case 'task.candidateGroup': return task.candidateGroup.name
    case 'task.createdAt': return formatDateTime(task.createdAt)
    case 'task.dueAt': return task.dueAt?formatDateTime(task.dueAt):'No due date'
    case 'task.status': return task.status
    default:return ''
  }
}
export function ConfiguredWorklist({tasks,definition,ws,query,desktop,onOpen}:{tasks:TaskSummary[];definition:ViewDefinition;ws:string;query:string;desktop:boolean;onOpen:(id:string)=>void}) {
  const appearance=usePresentedView(definition),view=appearance.view!
  const breakpoint={sm:640,md:768,lg:1024}[view.presentation?.breakpoint??'lg']
  const authoredDesktop=useMediaQuery(`(min-width: ${breakpoint}px)`)
  const showTable=view.presentation?authoredDesktop:desktop
  const section=view.sections[0]!, label=view.actions[0]!.label
  const link=(task:TaskSummary)=><Link className="font-semibold wrap-anywhere" to={`/w/${ws}/tasks/${task.id}?${query}`}>{taskTitle(task)}</Link>
  return <section lang={appearance.locale} className={viewSurface(view)}><ViewLanguage definition={definition} locale={appearance.locale} onChange={appearance.setLocale}/>{showTable?<Table><caption className="sr-only">{section.label}</caption><THead><TR>{section.fields.map(field=><TH key={field.id}>{field.label}</TH>)}<TH>Action</TH></TR></THead><TBody>{tasks.map(task=><TR key={task.id}>{section.fields.map(field=><TD key={field.id}>{field.binding==='task.requestTitle'?link(task):value(task,field.binding)}</TD>)}<TD><Button variant="secondary" onClick={()=>onOpen(task.id)} aria-label={`${label}: ${taskTitle(task)}`}>{label}</Button></TD></TR>)}</TBody></Table>
    :<ul aria-label={section.label} className="space-y-3">{tasks.map(task=><li key={task.id} className="space-y-3 rounded border p-4">{link(task)}<dl className="space-y-2">{section.fields.filter(field=>field.binding!=='task.requestTitle').map(field=><div key={field.id}><dt className="text-sm text-muted-foreground">{field.label}</dt><dd className="wrap-anywhere">{value(task,field.binding)}</dd></div>)}</dl><Button variant="secondary" onClick={()=>onOpen(task.id)} aria-label={`${label}: ${taskTitle(task)}`}>{label}</Button></li>)}</ul>}</section>
}
