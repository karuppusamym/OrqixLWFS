import type { WorkspaceFeature } from '@/api/types'

/**
 * Workspace feature switches (docs/build/S127): what each module covers and what switching it off does, in the
 * words the Settings → Features tab shows. Hiding UI is convenience; the server refuses the module's operations with
 * 409 FEATURE_DISABLED.
 */
export interface FeatureInfo {
  feature: WorkspaceFeature
  name: string
  /** What the module is, in one sentence. */
  covers: string
  /** What stops while it is off. */
  whenOff: string
  /** What happens to work that already exists or is running. */
  running: string
  /** Where Settings → Features lists it: a modelling standard, a way to bring models in, or an add-on. */
  group: FeatureGroup
  /** Standards and file formats the module covers, shown as chips. */
  formats?: readonly string[]
}

export type FeatureGroup = 'modelling' | 'import' | 'addon'

export const FEATURE_GROUPS: readonly { group: FeatureGroup; title: string; description: string }[] = [
  { group: 'modelling', title: 'Modelling', description: 'The kinds of model people design and run in this workspace.' },
  { group: 'import', title: 'Import', description: 'Bringing existing models in from other products.' },
  { group: 'addon', title: 'Add-ons', description: 'Optional capabilities on top of workflows and cases.' },
]

/** Core modelling that cannot be switched off: shown beside the switches so every standard has one place. */
export const ALWAYS_ON: readonly { name: string; covers: string; formats: readonly string[] }[] = [
  { name: 'Workflows', covers: 'Requests that follow a designed sequence of steps, approvals and integrations.', formats: ['BPMN 2.0'] },
  { name: 'Decisions', covers: 'Decision tables that workflows evaluate, edited in a workflow or shared from the Decisions library.', formats: ['DMN decision tables'] },
]

export const FEATURES: readonly FeatureInfo[] = [
  {
    feature: 'ai',
    name: 'AI assistance',
    covers: 'Drafting workflows from a description, explaining a workflow, suggesting form and step changes, and proposing a Pega Blueprint transcription.',
    whenOff: 'All AI help disappears from the studio, the new-workflow dialog and Settings. Nobody can ask the AI for anything in this workspace.',
    running: 'Drafts the AI already wrote stay as they are. Agent steps in published workflows are controlled by Agents and tools, not by this switch.',
    group: 'addon',
  },
  {
    feature: 'cases',
    name: 'Cases',
    covers: 'Case types (CMMN) and the cases started from them.',
    whenOff: 'Cases leaves the menu. Nobody can create a case type or start a new case.',
    running: 'Cases that are already open keep running: their tasks, stages and steps can still be worked on, and they can be closed as usual.',
    group: 'modelling',
    formats: ['CMMN 1.1'],
  },
  {
    feature: 'migration',
    name: 'Migration and import',
    covers: 'Bringing in Camunda and Pega files (BPMN, DMN, Pega rule archives and Blueprint PDFs) and converting them.',
    whenOff: 'Import leaves Home and the Workflows page. Nobody can upload, transcribe or convert a source file.',
    running: 'Workflows already imported stay and work like any other workflow, including their review and publishing.',
    group: 'import',
    formats: ['Camunda BPMN', 'DMN', 'Camunda forms', 'CMMN (inventory only)', 'Pega rules', 'Pega XPDL', 'Pega Blueprint PDF'],
  },
  {
    feature: 'agents',
    name: 'Agents and tools',
    covers: 'Agent connections (AI tasks that call MCP or A2A tools) and approving agent write tools.',
    whenOff: 'Agent writes leaves the menu. Nobody can add, bind or test an agent connection, or approve a write tool.',
    running: 'Requests already running keep running, including their agent steps. Approvals can still be revoked, and agent writes with an unknown outcome can still be reconciled.',
    group: 'addon',
    formats: ['MCP', 'A2A'],
  },
  {
    feature: 'apps',
    name: 'Apps and extensions',
    covers: 'Application pages and sandboxed UI extensions.',
    whenOff: 'Apps leaves the menu. Nobody can assign a page, run its actions, or add, submit or approve an extension.',
    running: 'Pages and extensions are kept and can still be read; they come back as they were when you switch this on.',
    group: 'addon',
  },
  {
    feature: 'kafka',
    name: 'Kafka triggers and adapters',
    covers: 'Kafka triggers that start or continue requests from a topic, Kafka adapters, and Kafka-sourced inbound triggers.',
    whenOff: 'Kafka triggers pause within seconds: messages wait on the topic and are read from where they stopped when you switch this on. Nobody can create, change or test a Kafka trigger or adapter.',
    running: 'Requests already started keep running, and steps that send to a Kafka adapter still send. Kafka clusters themselves stay in server configuration.',
    group: 'addon',
  },
]

export function featureInfo(feature: WorkspaceFeature): FeatureInfo {
  return FEATURES.find((f) => f.feature === feature)!
}
