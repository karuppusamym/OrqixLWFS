import { viewGrid } from '@/features/views/presentation'
import type { TaskDetail } from '@/api/types'
import { asRecord } from '@/api/types'
import type { ViewDefinition } from '@/features/views/model'
import { displayJsonValue } from '@/lib/form/display'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'

/** Read-only context. Presentation cannot supply values or grant task actions. */
export function ApprovalRequest({ task,definition }: { task: TaskDetail;definition?:ViewDefinition }) {
  const view = definition??task.approvalView as unknown as ViewDefinition
  const fields = buildFields(parseFormSchema(task.request.inputSchema), parseFormUi(task.request.inputUi))
  const input = asRecord(task.request.input)
  return <div className="space-y-4" data-testid="approval-view">
    {view.sections.map(section => <section key={section.id} aria-label={section.label}>
      <h4 className="mb-2 text-sm font-semibold">{section.label}</h4>
      <dl className={`${viewGrid(view,section.columns)} rounded-card border border-border p-4`}>
        {section.fields.map(field => {
          const key = field.binding.slice(8), source = fields.find(item => item.key === key)
          return source ? <div key={field.id} className="min-w-0 space-y-1"><dt className="text-sm font-semibold text-muted-foreground">{field.label}</dt><dd className="wrap-anywhere whitespace-pre-wrap">{field.options?.find(option=>option.value===input[key])?.label??displayJsonValue(source, input[key])}</dd></div> : null
        })}
      </dl>
    </section>)}
  </div>
}
