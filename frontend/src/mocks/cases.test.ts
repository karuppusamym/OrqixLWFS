import { describe, expect, it } from 'vitest'
import { setCurrentUser } from './handlers'
import { seedDb } from './seed'
import { WS_PURCHASING } from './state'
import { completeCaseTask, getCaseInstance, listCaseDefinitions, listCaseInstances, startCaseInstance } from '@/features/cases/api'

describe('case walkthrough mock', () => {
  it('lets a requester start, then eligible manager and finance workers complete in order', async () => {
    seedDb()
    setCurrentUser('rhea.requester')
    const definitions = await listCaseDefinitions(WS_PURCHASING)
    const started = await startCaseInstance(WS_PURCHASING, definitions[0]!.id)
    expect(started.tasks[0]?.eligible).toBe(false)
    expect((await listCaseInstances(WS_PURCHASING)).some(instance => instance.id === started.id)).toBe(true)

    setCurrentUser('mani.manager')
    const managerView = await getCaseInstance(WS_PURCHASING, started.id)
    expect(managerView.tasks[0]).toMatchObject({ name: 'Collect evidence', eligible: true })
    const afterManager = await completeCaseTask(WS_PURCHASING, started.id, managerView.tasks[0]!.id, managerView.version)
    expect(afterManager.tasks[0]).toMatchObject({ name: 'Review evidence', eligible: false })

    setCurrentUser('fin.finance')
    const financeView = await getCaseInstance(WS_PURCHASING, started.id)
    expect(financeView.tasks[0]?.eligible).toBe(true)
    const completed = await completeCaseTask(WS_PURCHASING, started.id, financeView.tasks[0]!.id, financeView.version)
    expect(completed.status).toBe('completed')
    expect(completed.tasks).toEqual([])
  })
})
