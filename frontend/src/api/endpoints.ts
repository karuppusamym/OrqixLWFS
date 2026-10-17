import { api, call, callVoid, callWithResponse, etagOf, revisionEtag } from './client'
import type { components } from './schema'
import type { Lifecycle, OutcomeKind, TaskView } from './types'

type S = components['schemas']

/**
 * One thin function per operationId used by the UI (contracts/openapi.yaml).
 * Every function resolves with the response body or throws a ProblemError.
 */

// Identity
export const getMe = () => call(api.GET('/me'))

// Templates and workflows
export const listTemplates = (ws: string) => call(api.GET('/workspaces/{workspaceId}/templates', { params: { path: { workspaceId: ws } } }))

export const listWorkflows = (ws: string, query: { q?: string; startable?: boolean; folder?: string; owner?: string; cursor?: string; limit?: number } = {}) =>
  call(api.GET('/workspaces/{workspaceId}/workflows', { params: { path: { workspaceId: ws }, query } }))

// Folders and owners (PL01)
export const getWorkflowOrganization = (ws: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflow-organization', { params: { path: { workspaceId: ws } } }))

export const listWorkflowOwnerCandidates = (ws: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflow-owner-candidates', { params: { path: { workspaceId: ws } } }))

export const createWorkflowFolder = (ws: string, name: string) =>
  call(api.POST('/workspaces/{workspaceId}/workflow-folders', { params: { path: { workspaceId: ws } }, body: { name } }))

export const renameWorkflowFolder = (ws: string, folderId: string, body: S['RenameWorkflowFolder']) =>
  call(api.PUT('/workspaces/{workspaceId}/workflow-folders/{folderId}', { params: { path: { workspaceId: ws, folderId } }, body }))

export const deleteWorkflowFolder = (ws: string, folderId: string) =>
  callVoid(api.DELETE('/workspaces/{workspaceId}/workflow-folders/{folderId}', { params: { path: { workspaceId: ws, folderId } } }))

export const setWorkflowFolder = (ws: string, workflowId: string, body: S['SetWorkflowFolder']) =>
  call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/folder', { params: { path: { workspaceId: ws, workflowId } }, body }))

export const setWorkflowOwner = (ws: string, workflowId: string, body: S['SetWorkflowOwner']) =>
  call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/owner', { params: { path: { workspaceId: ws, workflowId } }, body }))

export const getWorkflowDiscussion = (ws: string, workflowId: string, elementId?: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/discussion', { params: { path: { workspaceId: ws, workflowId }, query: elementId ? { elementId } : {} } }))

export const postWorkflowComment = (ws: string, workflowId: string, body: S['PostWorkflowComment']) =>
  call(api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/discussion', { params: { path: { workspaceId: ws, workflowId } }, body }))

export const resolveWorkflowThread = (ws: string, workflowId: string, commentId: string, resolved: boolean) =>
  call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/discussion/{commentId}/resolution', { params: { path: { workspaceId: ws, workflowId, commentId } }, body: { resolved } }))

export const createWorkflow = (ws: string, body: S['CreateWorkflowRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/workflows', { params: { path: { workspaceId: ws } }, body }))

export const getWorkflow = (ws: string, workflowId: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}', { params: { path: { workspaceId: ws, workflowId } } }))

export async function getDraft(ws: string, workflowId: string) {
  const { data, response } = await callWithResponse(
    api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/draft', { params: { path: { workspaceId: ws, workflowId } } }),
  )
  return { draft: data, etag: etagOf(response) ?? revisionEtag(data.revision) }
}

export async function saveDraft(ws: string, workflowId: string, definition: object, revision: number) {
  const { data, response } = await callWithResponse(
    api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/draft', {
      params: { path: { workspaceId: ws, workflowId }, header: { 'If-Match': revisionEtag(revision) } },
      body: { definition: definition as Record<string, never> },
    }),
  )
  return { draft: data, etag: etagOf(response) ?? revisionEtag(data.revision) }
}

export const validateDraft = (ws: string, workflowId: string, revision: number) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/validate', {
      params: { path: { workspaceId: ws, workflowId } },
      body: { revision },
    }),
  )

export const evaluateDecision = (ws: string, workflowId: string, decisionKey: string, body: S['DecisionEvaluateRequest']) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/decisions/{decisionKey}/evaluate', {
      params: { path: { workspaceId: ws, workflowId, decisionKey } },
      body,
    }),
  )

/** "Test this workflow": a dry run of a draft revision with a sample request and a scenario. No side effects. */
export const simulateWorkflow = (ws: string, workflowId: string, body: S['SimulateRequest']) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/simulate', {
      params: { path: { workspaceId: ws, workflowId } },
      body,
    }),
  )

// Releases
export const publishDraft = (ws: string, workflowId: string, body: S['PublishRequest'], idempotencyKey: string) =>
  callWithResponse(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/publish', {
      params: { path: { workspaceId: ws, workflowId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const listReleases = (ws: string, workflowId: string, query: { cursor?: string; limit?: number } = {}) =>
  call(
    api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/releases', {
      params: { path: { workspaceId: ws, workflowId }, query },
    }),
  )

export const getRelease = (ws: string, workflowId: string, releaseId: string) =>
  call(
    api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/releases/{releaseId}', {
      params: { path: { workspaceId: ws, workflowId, releaseId } },
    }),
  )

export async function exportRelease(ws: string, workflowId: string, releaseId: string) {
  const { data, response } = await callWithResponse(
    api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/releases/{releaseId}/export', {
      params: { path: { workspaceId: ws, workflowId, releaseId } },
      parseAs: 'blob',
    }),
  )
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  return { blob: data as unknown as Blob, filename: match?.[1] ? decodeURIComponent(match[1]) : 'workflow-release.zip' }
}

export const setActiveRelease = (ws: string, workflowId: string, releaseId: string, expectedVersion: number) =>
  call(
    api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/active-release', {
      params: { path: { workspaceId: ws, workflowId } },
      body: { releaseId, expectedVersion },
    }),
  )

// Canary and release analytics (PL02)
export const getWorkflowRollout = (ws: string, workflowId: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/rollout', { params: { path: { workspaceId: ws, workflowId } } }))

export const setWorkflowCanary = (ws: string, workflowId: string, body: S['SetWorkflowCanary']) =>
  call(api.PUT('/workspaces/{workspaceId}/workflows/{workflowId}/canary', { params: { path: { workspaceId: ws, workflowId } }, body }))

export const clearWorkflowCanary = (ws: string, workflowId: string, expectedVersion: number) =>
  call(api.DELETE('/workspaces/{workspaceId}/workflows/{workflowId}/canary', { params: { path: { workspaceId: ws, workflowId }, query: { expectedVersion } } }))

export const getReleaseAnalytics = (ws: string, workflowId: string, days = 30) =>
  call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/release-analytics', { params: { path: { workspaceId: ws, workflowId }, query: { days } } }))

// Runs
export const getStartForm = (ws: string, workflowId: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/start-form', { params: { path: { workspaceId: ws, workflowId } } }))

export const startRun = (ws: string, body: S['StartRunRequest'], idempotencyKey: string) =>
  callWithResponse(
    api.POST('/workspaces/{workspaceId}/runs', {
      params: { path: { workspaceId: ws }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export interface RunListQuery {
  workflowId?: string
  lifecycle?: Lifecycle
  outcomeKind?: OutcomeKind
  businessKey?: string
  cursor?: string
  limit?: number
}

export const listRuns = (ws: string, query: RunListQuery = {}) =>
  call(api.GET('/workspaces/{workspaceId}/runs', { params: { path: { workspaceId: ws }, query } }))

export const listMyRequests = (ws: string, query: { lifecycle?: Lifecycle; cursor?: string; limit?: number } = {}) =>
  call(api.GET('/workspaces/{workspaceId}/me/requests', { params: { path: { workspaceId: ws }, query } }))

export const getRun = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/runs/{runId}', { params: { path: { workspaceId: ws, runId } } }))

export const listRunEvents = (ws: string, runId: string, after: number, limit = 100) =>
  call(
    api.GET('/workspaces/{workspaceId}/runs/{runId}/events', {
      params: { path: { workspaceId: ws, runId }, query: { after, limit } },
    }),
  )

export const cancelRun = (ws: string, runId: string, body: S['CancelRunRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/cancel', {
      params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const suspendRun = (ws: string, runId: string, body: S['SuspendRunRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/suspend', {
      params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const getRunDiagnosis = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/runs/{runId}/diagnosis', { params: { path: { workspaceId: ws, runId } } }))

export const repairRun = (ws: string, runId: string, body: S['RepairRunRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/repair', {
      params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const resumeRun = (ws: string, runId: string, body: S['ResumeRunRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/resume', {
      params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const getRunMigrationOptions = (ws: string, runId: string) =>
  call(api.GET('/workspaces/{workspaceId}/runs/{runId}/migration-options', { params: { path: { workspaceId: ws, runId } } }))

export const migrateRun = (ws: string, runId: string, body: S['MigrateRunRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/migrate', {
      params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const retryRun = (ws: string, runId: string, body: S['RetryRunRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/retry', {
      params: { path: { workspaceId: ws, runId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const reconcileEffect = (ws: string, runId: string, effectId: string, body: S['ReconcileEffectRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/runs/{runId}/effects/{effectId}/reconcile', {
      params: { path: { workspaceId: ws, runId, effectId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

// Notifications
export const listMyNotifications = (ws: string, query: { unread?: boolean; cursor?: string; limit?: number } = {}) =>
  call(api.GET('/workspaces/{workspaceId}/me/notifications', { params: { path: { workspaceId: ws }, query } }))

export const markNotificationRead = (ws: string, notificationId: string) =>
  callVoid(
    api.POST('/workspaces/{workspaceId}/notifications/{notificationId}/read', {
      params: { path: { workspaceId: ws, notificationId } },
    }),
  )

// Tasks
export const listTasks = (ws: string, view: TaskView, query: { cursor?: string; limit?: number; sort?: "priority" | "oldest" | "newest"; minPriority?: number } = {}) =>
  call(api.GET('/workspaces/{workspaceId}/tasks', { params: { path: { workspaceId: ws }, query: { view, ...query } } }))

export const getTask = (ws: string, taskId: string) =>
  call(api.GET('/workspaces/{workspaceId}/tasks/{taskId}', { params: { path: { workspaceId: ws, taskId } } }))

export const claimTask = (ws: string, taskId: string, expectedVersion: number) =>
  call(
    api.POST('/workspaces/{workspaceId}/tasks/{taskId}/claim', {
      params: { path: { workspaceId: ws, taskId } },
      body: { expectedVersion },
    }),
  )

export const releaseTask = (ws: string, taskId: string, expectedVersion: number) =>
  call(
    api.POST('/workspaces/{workspaceId}/tasks/{taskId}/release', {
      params: { path: { workspaceId: ws, taskId } },
      body: { expectedVersion },
    }),
  )

export const decideTask = (ws: string, taskId: string, body: S['DecisionRequest'], idempotencyKey: string) =>
  callWithResponse(
    api.POST('/workspaces/{workspaceId}/tasks/{taskId}/decision', {
      params: { path: { workspaceId: ws, taskId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const correctTaskInput = (ws: string, taskId: string, body: S['CorrectTaskInputRequest'], idempotencyKey: string) =>
  call(
    api.POST('/workspaces/{workspaceId}/tasks/{taskId}/correct-input', {
      params: { path: { workspaceId: ws, taskId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )

export const reassignTask = (ws: string, taskId: string, body: S['ReassignRequest']) =>
  call(
    api.POST('/workspaces/{workspaceId}/tasks/{taskId}/reassign', {
      params: { path: { workspaceId: ws, taskId } },
      body,
    }),
  )

// Connections
export const listConnections = (ws: string) => call(api.GET('/workspaces/{workspaceId}/connections', { params: { path: { workspaceId: ws } } }))

export const getConnection = (ws: string, connectionKey: string) =>
  call(api.GET('/workspaces/{workspaceId}/connections/{connectionKey}', { params: { path: { workspaceId: ws, connectionKey } } }))

export async function putConnectionBinding(
  ws: string,
  connectionKey: string,
  body: S['PutConnectionBindingRequest'],
  currentRevision: number | null,
) {
  const { data } = await callWithResponse(
    api.PUT('/workspaces/{workspaceId}/connections/{connectionKey}/binding', {
      params: {
        path: { workspaceId: ws, connectionKey },
        header: currentRevision === null ? {} : { 'If-Match': revisionEtag(currentRevision) },
      },
      body,
    }),
  )
  return data
}

export const testConnection = (ws: string, connectionKey: string, version: number, operation: string, input?: Record<string, string>) =>
  call(
    api.POST('/workspaces/{workspaceId}/connections/{connectionKey}/test', {
      params: { path: { workspaceId: ws, connectionKey } },
      body: { version, operation, ...(input ? { input: input as Record<string, never> } : {}) },
    }),
  )

// Access
export const getAccessOverview = (ws: string) => call(api.GET('/workspaces/{workspaceId}/access', { params: { path: { workspaceId: ws } } }))

export const listGroups = (ws: string) => call(api.GET('/workspaces/{workspaceId}/groups', { params: { path: { workspaceId: ws } } }))

// Migration sources are immutable; conversion creates a validated draft, never a release.
export const uploadMigration = (ws: string, body: { fileName: string; content: string; sourceFormat?: 'bpmn' | 'dmn' | 'camundaForm' | 'pegaArchive' | 'pegaMapping' | 'pegaXpdl' }) =>
  call(api.POST('/workspaces/{workspaceId}/migrations', { params: { path: { workspaceId: ws } }, body }))
export const getMigration = (ws: string, migrationId: string) =>
  call(api.GET('/workspaces/{workspaceId}/migrations/{migrationId}', { params: { path: { workspaceId: ws, migrationId } } }))
export const convertMigration = (ws: string, migrationId: string, body: S['ConvertMigrationRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/migrations/{migrationId}/convert', { params: { path: { workspaceId: ws, migrationId } }, body }))

// Multi-file projects: child processes are converted before the parents that call them.
export const uploadMigrationProject = (ws: string, body: S['UploadMigrationProjectRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/migrations/projects', { params: { path: { workspaceId: ws } }, body }))
export const getMigrationProject = (ws: string, projectId: string) =>
  call(api.GET('/workspaces/{workspaceId}/migrations/projects/{projectId}', { params: { path: { workspaceId: ws, projectId } } }))
export const convertMigrationProjectNext = (ws: string, projectId: string, body: S['ConvertMigrationProjectRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/migrations/projects/{projectId}/convert-next', { params: { path: { workspaceId: ws, projectId } }, body }))

// Maker-checker governance of migrated workflows (independent review before publishing).
export const getMigrationPolicy = (ws: string) =>
  call(api.GET('/workspaces/{workspaceId}/migration-policy', { params: { path: { workspaceId: ws } } }))
export const setMigrationPolicy = (ws: string, body: S['MigrationPolicyRequest']) =>
  call(api.PUT('/workspaces/{workspaceId}/migration-policy', { params: { path: { workspaceId: ws } }, body }))
export const getMigrationReview = (ws: string, workflowId: string) =>
  call(api.GET('/workspaces/{workspaceId}/workflows/{workflowId}/migration-review', { params: { path: { workspaceId: ws, workflowId } } }))
export const approveMigrationReview = (ws: string, workflowId: string, body: S['MigrationReviewRequest']) =>
  call(api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/migration-review', { params: { path: { workspaceId: ws, workflowId } }, body }))

// Inbound messages (docs/build/S16): tell requests that wait for a named message that it arrived.
export const sendMessage = (ws: string, body: S['SendMessageRequest'], idempotencyKey: string) =>
  call(api.POST('/workspaces/{workspaceId}/messages', { params: { path: { workspaceId: ws }, header: { 'Idempotency-Key': idempotencyKey } }, body }))

// Document template registry (DOC08)
export const listDocumentTemplates = (ws: string, includeArchived = false) =>
  call(api.GET('/workspaces/{workspaceId}/document-templates', { params: { path: { workspaceId: ws }, query: { includeArchived } } }))

export const getDocumentTemplate = (ws: string, templateId: string) =>
  call(api.GET('/workspaces/{workspaceId}/document-templates/{templateId}', { params: { path: { workspaceId: ws, templateId } } }))

export const createDocumentTemplate = (ws: string, body: S['CreateDocumentTemplate']) =>
  call(api.POST('/workspaces/{workspaceId}/document-templates', { params: { path: { workspaceId: ws } }, body }))

export const addDocumentTemplateVersion = (ws: string, templateId: string, body: S['AddDocumentTemplateVersion']) =>
  call(api.POST('/workspaces/{workspaceId}/document-templates/{templateId}/versions', { params: { path: { workspaceId: ws, templateId } }, body }))

export const setDocumentTemplateArchived = (ws: string, templateId: string, archived: boolean) =>
  call(api.PUT('/workspaces/{workspaceId}/document-templates/{templateId}/archived', { params: { path: { workspaceId: ws, templateId } }, body: { archived } }))
