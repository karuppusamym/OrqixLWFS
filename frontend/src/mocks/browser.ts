import { FetchInterceptor } from '@mswjs/interceptors/fetch'
import { getResponse } from 'msw'
import { claim, decide, engineConfig, resumeTimers } from './engine'
import { createAiHandlers } from '@/features/ai/mocks'
import { createMigrationHandlers } from '@/features/migration/mocks'
import { createHandlers, getCurrentUser, setCurrentUser } from './handlers'
import { createViewHandlers, resetMockViews, restoreMockViews, snapshotMockViews } from './views'
import { createCaseHandlers, resetMockCases, restoreMockCases, snapshotMockCases } from './cases'
import { createPromotionHandlers } from './promotion'
import { createInsightsHandlers } from './insights'
import { createAdapterHandlers } from './adapters'
import { createCallbackHandlers } from './callbacks'
import { createAgentWriteHandlers } from './agentWrites'
import { createCaseFileHandlers } from './caseFiles'
import { createDocumentLibraryHandlers } from './documentLibrary'
import { createSharedAssetHandlers, resetSharedAssets, restoreSharedAssets, snapshotSharedAssets } from './sharedAssets'
import { detectOverdueStages, expireStage } from './stages'
import { createConversationHandlers, resetConversations } from './conversation'
import { seedDb } from './seed'
import { db, restoreDb, snapshotDb, type ProcurementFault, userByName } from './state'
import { setSignOutHandler } from '@/api/auth'
import { readStorage, removeStorage, writeStorage } from '@/lib/storage'

export const PERSONA_KEY = 'orqix.mock.user'
const DB_KEY = 'orqix.mock.db.v1'
const VIEW_DB_KEY = 'orqix.mock.views.v1'
const CASE_DB_KEY = 'orqix.mock.cases.v1'
const ASSET_DB_KEY = 'orqix.mock.assets.v1'

/** Hooks for demos and Playwright (mock mode only): simulate "another person did something" and flip faults. */
export interface MockControl {
  /** Without options: the walkthrough data. With options: the small fixture set the tests use unless `demo` is true. */
  reset(options?: { published?: boolean; sampleRuns?: boolean; demo?: boolean }): void
  setUser(username: string): void
  setProcurementFault(fault: ProcurementFault): void
  /** Another approver completes a task, so the signed-in person sees a conflict (T-UI-03). */
  decideTaskAs(username: string, taskId: string, action: 'approve' | 'reject', reason?: string): void
  claimTaskAs(username: string, taskId: string): void
  latency(ms: number): void
  /** Makes a stage of a run late now and records it (case stages, docs/build/S17). */
  expireStage(runId: string, stageId: string): void
  state(): { runs: number; workflows: number; fault: ProcurementFault }
}

declare global {
  interface Window {
    __orqixMock?: MockControl
  }
}

function persist() {
  writeStorage(DB_KEY, snapshotDb())
  writeStorage(VIEW_DB_KEY, snapshotMockViews())
  writeStorage(CASE_DB_KEY, snapshotMockCases())
  writeStorage(ASSET_DB_KEY, snapshotSharedAssets())
}

/** Start the in-browser API. Called once before the app renders when mock mode is on. */
export async function startMock(): Promise<void> {
  const saved = readStorage(DB_KEY)
  if (!saved || !restoreDb(saved)) seedDb({ demo: true })
  if (!restoreMockViews(readStorage(VIEW_DB_KEY))) resetMockViews()
  if (!restoreMockCases(readStorage(CASE_DB_KEY))) resetMockCases()
  if (!restoreSharedAssets(readStorage(ASSET_DB_KEY))) resetSharedAssets()
  db.latencyMs = 150
  setCurrentUser(readStorage(PERSONA_KEY) ?? 'rhea.requester')
  engineConfig.retryDelayMs = 4000
  engineConfig.schedule = (fn, ms) => {
    window.setTimeout(() => {
      fn()
      persist()
    }, ms)
  }
  resumeTimers()
  window.setInterval(() => {
    if (detectOverdueStages() > 0) persist()
  }, 15_000)

  // In-page fetch interception (no service worker): works in every browser context, including embedded previews.
  const handlers = [...createAiHandlers(), ...createMigrationHandlers(), ...createConversationHandlers(), ...createInsightsHandlers(), ...createAdapterHandlers(), ...createCallbackHandlers(), ...createAgentWriteHandlers(), ...createCaseFileHandlers(), ...createDocumentLibraryHandlers(), ...createSharedAssetHandlers(), ...createViewHandlers(getCurrentUser), ...createCaseHandlers(getCurrentUser), ...createPromotionHandlers(getCurrentUser), ...createHandlers()]
  const interceptor = new FetchInterceptor()
  interceptor.on('request', async ({ request, controller }) => {
    const response = await getResponse(handlers, request)
    if (!response) return // not an API call: fall through to the network (assets, HMR)
    controller.respondWith(response)
    if (request.method !== 'GET') persist()
  })
  interceptor.apply()
  // There is no server session to end: signing out returns to the default persona.
  setSignOutHandler(() => {
    removeStorage(PERSONA_KEY)
    window.location.assign('/')
  })

  const findTask = (taskId: string) => {
    for (const run of db.runs.values()) {
      const task = run.tasks.find((t) => t.id === taskId)
      if (task) return { run, task }
    }
    return undefined
  }

  window.__orqixMock = {
    reset(options) {
      seedDb(options ?? { demo: true })
      resetConversations()
      resetSharedAssets()
      db.latencyMs = 150
      persist()
    },
    setUser(username) {
      writeStorage(PERSONA_KEY, username)
      setCurrentUser(username)
    },
    setProcurementFault(fault) {
      db.procurementFault = fault
      persist()
    },
    decideTaskAs(username, taskId, action, reason) {
      const found = findTask(taskId)
      if (!found) throw new Error(`No task ${taskId}`)
      const user = userByName(username)
      if (!found.task.assigneeId) claim(found.run, found.task, user)
      decide(found.run, found.task, user, action, action === 'reject' ? (reason ?? 'Rejected') : null, null)
      persist()
    },
    claimTaskAs(username, taskId) {
      const found = findTask(taskId)
      if (!found) throw new Error(`No task ${taskId}`)
      claim(found.run, found.task, userByName(username))
      persist()
    },
    latency(ms) {
      db.latencyMs = ms
    },
    expireStage(runId, stageId) {
      expireStage(runId, stageId)
      persist()
    },
    state() {
      return { runs: db.runs.size, workflows: db.workflows.size, fault: db.procurementFault }
    },
  }
}

/** Forget persisted mock data and the persona, then reload with the seed. */
export function resetMockData(): void {
  removeStorage(DB_KEY)
  removeStorage(VIEW_DB_KEY)
  removeStorage(CASE_DB_KEY)
  removeStorage(PERSONA_KEY)
  resetConversations()
  window.location.reload()
}
