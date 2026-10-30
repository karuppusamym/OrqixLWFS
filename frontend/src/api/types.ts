import type { components } from './schema'

type S = components['schemas']

export type Me = S['Me']
export type WorkspaceMembership = S['WorkspaceMembership']
export type Permission = S['Permission']
export type WorkspaceFeature = S['WorkspaceFeature']
export type WorkspaceFeatureState = S['WorkspaceFeatureState']
export type WorkspaceFeatures = S['WorkspaceFeatures']
export type SubjectRef = S['SubjectRef']
export type Principal = S['Principal']

export type Template = S['Template']
export type WorkflowSummary = S['WorkflowSummary']
export type Workflow = S['Workflow']
export type WorkflowDraft = S['WorkflowDraft']
export type ValidationIssue = S['ValidationIssue']
export type ValidationReport = S['ValidationReport']
export type DecisionEvaluateResult = S['DecisionEvaluateResult']
export type SimulateRequest = S['SimulateRequest']
export type SimulationScenario = S['SimulationScenario']
export type SimulationResult = S['SimulationResult']
export type SimulationStep = S['SimulationStep']
export type SimulationAssertions = S['SimulationAssertions']
export type SimulationAssertionResult = S['SimulationAssertionResult']
export type SimulateCasesResult = S['SimulateCasesResult']
export type WorkflowTestSummary = S['WorkflowTestSummary']
export type PublishRequest = S['PublishRequest']
export type ReleaseSummary = S['ReleaseSummary']
export type Release = S['Release']

export type StartForm = S['StartForm']
export type StartRunRequest = S['StartRunRequest']
export type Lifecycle = S['Lifecycle']
export type RunDiagnosis = S['RunDiagnosis']
export type ConsistencyFinding = S['ConsistencyFinding']
export type ConsistencyFindings = S['ConsistencyFindings']
export type MigrationOptions = S['MigrationOptions']
export type MigrationOption = S['MigrationOption']
export type OutcomeKind = S['OutcomeKind']
export type CurrentStep = S['CurrentStep']
export type Attention = S['Attention']
export type Run = S['Run']
export type RunDetail = S['RunDetail']
export type RunPage = S['RunPage']
export type RunEvent = S['RunEvent']
export type EventPage = S['EventPage']
export type Effect = S['Effect']
export type ActionAvailability = S['ActionAvailability']

export type StageView = S['StageView']
export type Attachment = S['Attachment']
export type AttachmentList = S['AttachmentList']
export type StagedUpload = S['StagedUpload']
export type AttachmentOcr = S['AttachmentOcr']
export type AttachmentRetention = S['AttachmentRetention']
export type DocumentTemplate = S['DocumentTemplate']
export type DocumentTemplateSummary = S['DocumentTemplateSummary']
export type DocumentTemplateVersion = S['DocumentTemplateVersion']
export type SharedAssetKind = S['SharedAssetKind']
export type SharedAssetSummary = S['SharedAssetSummary']
export type SharedAsset = S['SharedAsset']
export type SharedAssetUsage = S['SharedAssetUsage']
export type DecisionAssetContent = S['DecisionAssetContent']

export type TaskSummary = S['TaskSummary']
export type TaskDetail = S['TaskDetail']
export type TaskDecision = S['TaskDecision']

export type Notification = S['Notification']

export type ConnectionSummary = S['ConnectionSummary']
export type ConnectionDetail = S['ConnectionDetail']
export type ConnectionDefinitionRecord = S['ConnectionDefinitionRecord']
export type ConnectionBinding = S['ConnectionBinding']
export type ConnectionTestResult = S['ConnectionTestResult']
export type PutConnectionBindingRequest = S['PutConnectionBindingRequest']

export type Group = S['Group']
export type AccessOverview = S['AccessOverview']
export type RoleBinding = S['RoleBinding']
export type PermissionGrant = S['PermissionGrant']

export type ProblemCode = S['Problem']['code']
export type FieldError = S['FieldError']

/** Task list views accepted by listTasks. */
export type TaskView = 'assigned' | 'claimable' | 'completed'

/** Effect class of a connection operation (BD-09). */
export type EffectClass = Effect['effectClass']

/**
 * Free-form `type: object` schemas in contracts/openapi.yaml are generated as `Record<string, never>`.
 * This is the single place that widens them to what the server actually sends.
 */
export function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

export type WorkspaceEnvironment = S['WorkspaceEnvironment']
export type PromotionReport = S['PromotionReport']
export type ReleasePromotion = S['ReleasePromotion']
export type PromotionRecord = S['PromotionRecord']
export type PromotionRequest = S['PromotionRequest']
export type AgentWriteToolDeclared = S['AgentWriteToolDeclared']
export type AgentWriteToolApproval = S['AgentWriteToolApproval']
export type AgentWriteCall = S['AgentWriteCall']
export type AgentWriteRetention = S['AgentWriteRetention']
