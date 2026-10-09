import { claim, createRun, decide } from './engine'
import {
  COMPILER_VERSION,
  PURCHASE_WORKFLOW_ID,
  WS_PURCHASING,
  clock,
  db,
  emptyDb,
  newId,
  purchaseTemplate,
  replaceDb,
  userByName,
  type MockRelease,
  type MockWorkflow,
} from './state'

function hash(text: string): string {
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0
  return Math.abs(h).toString(16).padStart(8, '0').repeat(8).slice(0, 64)
}

export function makeRelease(workflowId: string, releaseNumber: number, draftRevision: number, definition: MockRelease['definition'], publishedById: string, note: string | null = null): MockRelease {
  const text = JSON.stringify(definition)
  const { uiLayout: _layout, ...semantic } = definition
  return {
    id: newId(),
    workflowId,
    releaseNumber,
    draftRevision,
    publishedAt: clock.iso(),
    publishedById,
    compilerVersion: COMPILER_VERSION,
    releaseNote: note,
    sourceHash: hash(text),
    semanticHash: hash(JSON.stringify(semantic)),
    warnings: [],
    definition: structuredClone(definition),
  }
}

export interface SeedOptions {
  /** Publish version 1 so requesters can start immediately. Default true. */
  published?: boolean
  /** Add example runs in different states. Default true. */
  sampleRuns?: boolean
}

/** Reset the mock backend to its seed (see docs/build/S1-engineering-baseline.md section 6.1 for the personas). */
export function seedDb(options: SeedOptions = {}): void {
  const { published = true, sampleRuns = true } = options
  replaceDb(emptyDb())
  const designer = userByName('dana.designer')
  const publisher = userByName('paul.publisher')
  const definition = purchaseTemplate()

  const wf: MockWorkflow = {
    id: PURCHASE_WORKFLOW_ID,
    wsId: WS_PURCHASING,
    key: 'purchase-approval',
    name: 'Purchase approval',
    description: 'Manager approval, finance review at USD 5,000 or more, purchase order creation and requester notification.',
    ownerId: designer.id,
    drafts: [{ revision: 1, savedAt: clock.iso(), savedById: designer.id, definition: structuredClone(definition) }],
    releases: [],
    activeReleaseId: null,
    version: 1,
    createdAt: clock.iso(),
    updatedAt: clock.iso(),
  }
  db.workflows.set(wf.id, wf)
  db.bindings.set(`${WS_PURCHASING}:procurement`, {
    connectionKey: 'procurement',
    baseUrl: 'https://procurement.example.internal',
    auth: { type: 'apiKeyHeader', headerName: 'X-Api-Key', secretRef: 'procurement-key' },
    enabled: true,
    revision: 1,
    updatedAt: clock.iso(),
    updatedById: userByName('wade.admin').id,
  })

  if (published) {
    const release = makeRelease(wf.id, 1, 1, definition, publisher.id, 'First version')
    wf.releases.push(release)
    wf.activeReleaseId = release.id
    wf.version = 2
  }

  if (published && sampleRuns) seedRuns(wf)
}

function seedRuns(wf: MockWorkflow): void {
  const release = wf.releases[0] as MockRelease
  const rhea = userByName('rhea.requester')
  const mani = userByName('mani.manager')
  const max = userByName('max.manager')
  const dana = userByName('dana.designer')
  const mk = (hoursAgo: number, who: typeof rhea, input: Record<string, unknown>, key: string | null = null) =>
    clock.asIfAgo(hoursAgo, () => createRun({ wsId: WS_PURCHASING, workflow: wf, release, initiator: who, input, businessKey: key }))
  const act = <T,>(hoursAgo: number, fn: () => T): T => clock.asIfAgo(hoursAgo, fn)
  const firstTask = (run: ReturnType<typeof mk>) => run.tasks.find((t) => t.status === 'open')!

  // Completed and fulfilled.
  const done = mk(50, rhea, { title: 'Monitor', purpose: 'Second screen for the design team.', costCenter: 'IT', amount: 240 }, 'ORQ-1001')
  act(49, () => {
    const t = firstTask(done)
    claim(done, t, mani)
    decide(done, t, mani, 'approve', null, 'Approved.')
  })

  // Rejected by a manager.
  const rejected = mk(30, rhea, { title: 'Conference tickets', purpose: 'Two tickets for the supply chain summit.', costCenter: 'OPS', amount: 900 }, 'ORQ-1002')
  act(29, () => {
    const t = firstTask(rejected)
    claim(rejected, t, max)
    decide(rejected, t, max, 'reject', 'The travel budget for this quarter is spent. Please request it again next quarter.', null)
  })

  // Waiting for finance after manager approval.
  const finance = mk(26, rhea, { title: 'Standing desks', purpose: 'Replace six desks in the operations room.', costCenter: 'OPS', amount: 5200 }, 'ORQ-1003')
  act(25, () => {
    const t = firstTask(finance)
    claim(finance, t, mani)
    decide(finance, t, mani, 'approve', null, null)
  })

  // Needs attention: the procurement service replied in a way that makes the outcome unknown.
  const saved = db.procurementFault
  db.procurementFault = 'unknown'
  const stuck = mk(5, dana, { title: 'Team lunch', purpose: 'Quarterly planning lunch for twelve people.', costCenter: 'HR', amount: 400 }, 'ORQ-1004')
  act(4, () => {
    const t = firstTask(stuck)
    claim(stuck, t, max)
    decide(stuck, t, max, 'approve', null, null)
  })
  db.procurementFault = saved

  // Waiting for a manager (two of them: one by a requester, one by a manager who cannot approve their own).
  mk(3, rhea, { title: 'Replacement laptop', purpose: 'The current device failed and cannot be repaired.', costCenter: 'OPS', amount: 1800 }, 'ORQ-1005')
  mk(2, mani, { title: 'Training budget', purpose: 'Leadership course for two team leads.', costCenter: 'HR', amount: 700 }, 'ORQ-1006')
}
