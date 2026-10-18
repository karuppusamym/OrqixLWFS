import { claim, createRun, decide } from './engine'
import { resetMockViews } from './views'
import { resetMockCases } from './cases'
import { resetMockPromotion } from './promotion'
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
  const { stepNotes: _notes, ...metadata } = semantic.metadata
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
    semanticHash: hash(JSON.stringify({ ...semantic, metadata })),
    warnings: [],
    definition: structuredClone(definition),
  }
}

export interface SeedOptions {
  /** Publish version 1 so requesters can start immediately. Default true. */
  published?: boolean
  /** Add example runs in different states. Default true. */
  sampleRuns?: boolean
  /**
   * Seed the walkthrough data that matches tools/demo/seed_demo.py (nine requests, long titles, every state) instead of
   * the small fixture set the tests rely on. Default false; the in-browser mock starts with it.
   */
  demo?: boolean
}

/** Reset the mock backend to its seed (see docs/build/S1-engineering-baseline.md section 6.1 for the personas). */
export function seedDb(options: SeedOptions = {}): void {
  const { published = true, sampleRuns = true, demo = false } = options
  replaceDb(emptyDb())
  resetMockViews()
  resetMockCases()
  resetMockPromotion()
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
  db.bindings.set(`${WS_PURCHASING}:vendor-risk-assistant`, {
    connectionKey: 'vendor-risk-assistant',
    baseUrl: 'https://procurement.example.internal/mcp',
    auth: { type: 'none' },
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

  if (published && sampleRuns) {
    if (demo) seedDemoRuns(wf)
    else seedRuns(wf)
  }
}

/**
 * The walkthrough data of tools/demo/seed_demo.py, in the same order, so ORQ-1001 to ORQ-1009 mean the same thing in
 * the mock and on the real stack: two waiting at a manager, two waiting at finance, two fulfilled, one rejected by a
 * manager with a reason, one rejected by finance, and one that needs attention (the procurement system answered 400).
 */
function seedDemoRuns(wf: MockWorkflow): void {
  const release = wf.releases[0] as MockRelease
  const rhea = userByName('rhea.requester')
  const dana = userByName('dana.designer') // stands in for the facilities requester of the real seed, who is in another workspace here
  const mani = userByName('mani.manager')
  const fin = userByName('fin.finance')
  const mk = (hoursAgo: number, who: typeof rhea, input: Record<string, unknown>) =>
    clock.asIfAgo(hoursAgo, () => createRun({ wsId: WS_PURCHASING, workflow: wf, release, initiator: who, input, businessKey: null }))
  const openTask = (run: ReturnType<typeof mk>) => run.tasks.find((t) => t.status === 'open')!
  const act = <T,>(hoursAgo: number, fn: () => T): T => clock.asIfAgo(hoursAgo, fn)
  const approveByManager = (run: ReturnType<typeof mk>, hoursAgo: number, comment: string) =>
    act(hoursAgo, () => {
      const t = openTask(run)
      claim(run, t, mani)
      decide(run, t, mani, 'approve', null, comment)
    })

  // 1-2: waiting for a manager.
  mk(9, rhea, { title: 'Replacement laptop for onboarding', purpose: 'New starter in Operations needs a laptop before Monday.', costCenter: 'OPS', amount: 1800 })
  mk(8, rhea, { title: 'Ergonomic chairs (12)', purpose: 'Replace chairs that failed the annual safety check.', costCenter: 'HR', amount: 3600 })

  // 3-4: manager approved, waiting for finance (USD 5,000 and above).
  approveByManager(mk(7, rhea, { title: 'Standing desks for the analytics team', purpose: 'Team of 8 moving to the third floor; budget approved in Q3 plan.', costCenter: 'IT', amount: 5000 }), 6.5, 'Approved by manager; finance to review.')
  approveByManager(mk(6, dana, { title: 'Server rack for the new office', purpose: 'Required for the branch network rollout.', costCenter: 'IT', amount: 12500 }), 5.5, 'Approved by manager; finance to review.')

  // 5-6: completed and fulfilled.
  approveByManager(mk(5, rhea, { title: 'Conference room camera', purpose: 'Hybrid meetings in the main boardroom.', costCenter: 'OPS', amount: 950 }), 4.5, 'Looks fine.')
  approveByManager(mk(4, dana, { title: 'Monitor arms (20)', purpose: 'Desk refresh for the finance floor.', costCenter: 'HR', amount: 1200 }), 3.5, 'Looks fine.')

  // 7: rejected by a manager, with a reason.
  const coffee = mk(3, rhea, { title: 'Premium coffee machine', purpose: 'Nice to have for the kitchen.', costCenter: 'HR', amount: 2400 })
  act(2.5, () => {
    const t = openTask(coffee)
    claim(coffee, t, mani)
    decide(coffee, t, mani, 'reject', "Not in this quarter's budget; please resubmit in January.", null)
  })

  // 8: approved by a manager, rejected by finance.
  const licences = mk(2, rhea, { title: 'Annual design software licences', purpose: 'Renewal for 15 seats.', costCenter: 'IT', amount: 7800 })
  approveByManager(licences, 1.7, 'Looks fine.')
  act(1.4, () => {
    const t = openTask(licences)
    claim(licences, t, fin)
    decide(licences, t, fin, 'reject', 'Over the quarterly budget for software.', null)
  })

  // 9: needs attention. The procurement system answers 400 once; an operator fixes the cause and retries.
  const saved = db.procurementFault
  db.procurementFault = 'rejected'
  approveByManager(mk(1, dana, { title: 'Label printer', purpose: 'Warehouse labelling; the old printer is broken.', costCenter: 'OPS', amount: 640 }), 0.8, 'Looks fine.')
  db.procurementFault = saved
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
