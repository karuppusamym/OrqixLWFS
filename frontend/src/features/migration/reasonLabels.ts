import type { components } from '@/api/schema'

type Report = components['schemas']['CompatibilityReport']

/**
 * Plain-language names for importer reason codes. The technical code stays visible under each finding; this is what a
 * business analyst reads first. Codes without an entry fall back to the code alone.
 */
const LABELS: Record<string, string> = {
  CONNECTOR_MAPPING_REQUIRED: 'Choose the system call that replaces this connector',
  CONNECTOR_MAPPED: 'Connector replaced by a system call you chose',
  CONNECTOR_NOT_SUPPORTED: 'This connector cannot be imported',
  WORKER_NOT_PORTABLE: 'Custom job worker: choose a system call',
  EXPRESSION_NOT_SUPPORTED: 'Condition is too complex to convert',
  CONDITION_CONVERTED: 'Condition converted',
  DUE_DATE_MAPPED: 'Due time carried over',
  DUE_DATE_NOT_IMPORTED: 'Due date not carried over',
  FOLLOW_UP_NOT_IMPORTED: 'Follow-up date not carried over',
  BOUNDARY_TIMER_MAPPED: 'Time limit on an approval',
  DURATION_TIMER_MAPPED: 'Wait for a fixed time',
  DATE_TIMER_MAPPED: 'Wait until a date the requester enters',
  TIMER_CYCLE_NOT_SUPPORTED: 'Repeating timers cannot be imported',
  TIMER_DATE_NOT_SUPPORTED: 'This date timer cannot be imported',
  TIMER_DURATION_NOT_SUPPORTED: 'This wait time cannot be imported',
  MESSAGE_EVENT_NOT_SUPPORTED: 'This message event cannot be imported',
  MESSAGE_CATCH_MAPPED: 'Wait for a message',
  SIGNAL_EVENT_NOT_SUPPORTED: 'Signal events are not supported',
  EVENT_NOT_SUPPORTED_IN_V0: 'This event cannot be imported',
  PROPAGATE_ALL_NOT_SUPPORTED: 'All variables are passed on: list them explicitly',
  CHILD_WORKFLOW_MAPPING_REQUIRED: 'Choose the workflow this step starts',
  CHILD_WORKFLOW_MAPPED: 'Starts another workflow',
  DECISION_MAPPING_REQUIRED: 'Link the decision table',
  DECISION_MAPPED: 'Decision table linked',
  IO_MAPPING_NOT_IMPORTED: 'Variable mappings not carried over',
  JOB_WORKER_USER_TASK: 'Task handled by a worker, imported as a normal task',
  FORM_NOT_IMPORTED: 'Form not imported',
  FORM_MAPPED: 'Form becomes the request form',
  TASK_FORM_NOTED: 'Task form kept as a note on the approval',
  FORM_FIELD_MAPPED: 'Form field imported',
  FORM_FIELD_MAPPED_WITH_CHANGES: 'Form field imported with differences',
  FORM_DISPLAY_NOT_IMPORTED: 'Display-only form content not imported',
  FORM_DEFAULT_NOT_IMPORTED: 'Default value not imported',
  FORM_GROUP_FLATTENED: 'Form group frame removed',
  FORM_COMPONENT_NOT_SUPPORTED: 'This form component cannot be imported',
  FORM_EXPRESSION_NOT_SUPPORTED: 'Form logic is not supported',
  FORM_OPTIONS_NOT_STATIC: 'Choices come from data, not a fixed list',
  FORM_OPTION_NOT_SUPPORTED: 'Choices cannot be imported',
  FORM_KEY_NOT_SUPPORTED: 'Form field name cannot be used',
  FORM_TOO_LARGE: 'Form is too large',
  FORM_ID_MISSING: 'Form has no id',
  MULTI_INSTANCE_NOT_SUPPORTED: 'Repetition cannot be imported',
  MULTI_INSTANCE_MAPPED: 'Runs once per list item',
  GROUP_MAPPING_REQUIRED: 'Choose who decides',
  APPROVAL_ROUTE_REQUIRED: 'Choose which path means approved',
  APPROVAL_ROUTE_INFERRED: 'Approved and rejected paths read from the conditions',
  ASSIGNMENT_NOT_SUPPORTED: 'Assignment is calculated, so it cannot be imported',
  GATEWAY_MAPPED_TO_BRANCH: 'Choice between paths',
  GATEWAY_NOT_SUPPORTED_IN_V0: 'This gateway cannot be imported',
  TASK_TYPE_NOT_SUPPORTED: 'This kind of task cannot be imported',
  SUBPROCESS_NOT_SUPPORTED: 'Sub-processes cannot be imported',
  LISTENER_DROPPED: 'Listener code not imported',
  CMMN_RUNTIME_NOT_EXPOSED: 'Case element inventoried; execution is not available yet',
  CMMN_ELEMENT_UNRECOGNIZED: 'Case element needs explicit review',
  CMMN_EXTENSION_REVIEW_REQUIRED: 'Extension element needs explicit review',
  CMMN_EXTENSION_ATTRIBUTE_REVIEW_REQUIRED: 'Extension settings need explicit review',
  CMMN_REFERENCE_MISSING: 'Case model refers to a missing element',
  CMMN_DUPLICATE_ID: 'Case model reuses an identifier',
  CMMN_CASE_ID_MISSING: 'Case has no identifier',
}

export function reasonLabel(code: string): string | null {
  return LABELS[code] ?? null
}

/** "Camunda 8, version 8.5.0" for the import page header; plain wording for other sources. */
export function sourceLabel(report: Pick<Report, 'sourceSystem' | 'sourceVersion'>): string {
  const name: Record<string, string> = { camunda7: 'Camunda 7', camunda8: 'Camunda 8', bpmn: 'Plain BPMN 2.0', dmn: 'Camunda DMN', camundaForm: 'Camunda form', pega: 'Pega', cmmn: 'CMMN 1.1 case model' }
  const base = name[report.sourceSystem] ?? report.sourceSystem
  return report.sourceVersion ? `${base}, version ${report.sourceVersion}` : base
}
