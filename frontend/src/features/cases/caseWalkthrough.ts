import { entrySources, type CaseModelItem } from './caseModel'

/**
 * A walkthrough of a case plan's rules in the browser (the case designer's Test tab). It follows the plan the way a
 * running case would — entry and exit rules, manual start, required work, stages, milestones, listeners and
 * discretionary items — but the designer plays the people, the data and the clock: nothing is executed, workflows,
 * decisions and child cases are not run, and data conditions are whatever the designer says they are.
 */
export type WalkStatus = 'waiting' | 'offered' | 'enabled' | 'active' | 'available' | 'completed' | 'ended'
export type WalkAction = 'plan' | 'start' | 'complete' | 'occur' | 'data'

export interface WalkState {
  status: Record<string, WalkStatus>
  /** Items whose data condition the designer declared met. */
  dataMet: string[]
  log: string[]
}

const needsData = (item: CaseModelItem) => !!item.when || !!item.onData?.length
const done = (status: WalkStatus | undefined) => status === 'completed'
const open = (status: WalkStatus | undefined) => status === 'enabled' || status === 'active' || status === 'available'

export function startWalk(items: CaseModelItem[]): WalkState {
  const status: Record<string, WalkStatus> = {}
  const mark = (list: CaseModelItem[]) => list.forEach(item => { status[item.id] = item.discretionary ? 'offered' : 'waiting'; mark(item.children ?? []) })
  mark(items)
  return settle(items, { status, dataMet: [], log: ['Case started.'] })
}

/** What a person can do with an item now; empty when nothing. */
export function actionsFor(item: CaseModelItem, state: WalkState): WalkAction[] {
  const status = state.status[item.id]
  const actions: WalkAction[] = []
  if (status === 'offered') actions.push('plan')
  if (status === 'enabled') actions.push('start')
  if (status === 'active' && item.kind !== 'stage') actions.push('complete')
  if (status === 'available') actions.push('occur')
  if (status === 'waiting' && needsData(item) && !state.dataMet.includes(item.id)) actions.push('data')
  return actions
}

export function act(items: CaseModelItem[], state: WalkState, id: string, action: WalkAction): WalkState {
  const item = find(items, id)
  if (!item || !actionsFor(item, state).includes(action)) return state
  const status = { ...state.status }
  const dataMet = [...state.dataMet]
  const verb = { plan: 'planned into the case', start: 'started', complete: 'completed', occur: item.kind === 'timerEventListener' ? 'fired' : 'occurred', data: 'has its data condition met' }[action]
  if (action === 'plan') status[id] = 'waiting'
  if (action === 'start') status[id] = 'active'
  if (action === 'complete' || action === 'occur') status[id] = 'completed'
  if (action === 'data') dataMet.push(id)
  return settle(items, { status, dataMet, log: [...state.log, `${item.label} ${verb}.`] })
}

/** True when nothing required is left and no work is open: the case could be closed. */
export function caseCanClose(items: CaseModelItem[], state: WalkState): boolean {
  const all = flat(items)
  return all.every(item => !item.required || done(state.status[item.id]) || state.status[item.id] === 'ended')
    && !all.some(item => item.kind !== 'userEventListener' && open(state.status[item.id]))
}

/** Applies entry, exit and stage completion rules until nothing changes. */
function settle(items: CaseModelItem[], state: WalkState): WalkState {
  const status = { ...state.status }
  const log = [...state.log]
  let changed = true
  for (let round = 0; changed && round < 200; round++) {
    changed = false
    const visit = (siblings: CaseModelItem[], containerActive: boolean) => {
      for (const item of siblings) {
        const current = status[item.id]
        // Exit rule: the item ends when its exit source completes, whatever it was doing.
        if (item.exitAfter && done(status[item.exitAfter]) && current !== 'completed' && current !== 'ended') {
          status[item.id] = 'ended'; log.push(`${item.label} ended because ${find(items, item.exitAfter)?.label ?? item.exitAfter} completed.`); changed = true; continue
        }
        if (containerActive && current === 'waiting' && entryMet(item, status, state.dataMet)) {
          const next: WalkStatus = item.kind === 'stage' ? 'active'
            : item.kind === 'milestone' ? 'completed'
            : item.kind === 'userEventListener' || item.kind === 'timerEventListener' ? 'available'
            : item.kind === 'humanTask' && item.manual ? 'enabled'
            : 'active'
          status[item.id] = next; changed = true
          log.push(item.kind === 'milestone' ? `Milestone ${item.label} reached.` : next === 'enabled' ? `${item.label} can be started.` : `${item.label} ${next === 'available' ? 'is waiting for its event' : 'started'}.`)
        }
        if (item.kind === 'stage') {
          const children = item.children ?? []
          visit(children, status[item.id] === 'active')
          if (status[item.id] === 'active' && children.length && stageDone(children, status)) {
            status[item.id] = 'completed'; log.push(`Stage ${item.label} completed.`); changed = true
          }
        }
      }
    }
    visit(items, true)
  }
  return { status, dataMet: state.dataMet, log }
}

function entryMet(item: CaseModelItem, status: Record<string, WalkStatus>, dataMet: string[]): boolean {
  const sources = entrySources(item)
  const sourcesMet = !sources.length || (item.anyOf ? sources.some(id => done(status[id])) : sources.every(id => done(status[id])))
  return sourcesMet && (!needsData(item) || dataMet.includes(item.id))
}

/** A stage completes when its required work is done and nothing in it is still open. */
function stageDone(children: CaseModelItem[], status: Record<string, WalkStatus>): boolean {
  const required = children.filter(child => child.required)
  const settled = (child: CaseModelItem) => done(status[child.id]) || status[child.id] === 'ended'
  if (children.some(child => child.kind !== 'userEventListener' && child.kind !== 'timerEventListener' && open(status[child.id]))) return false
  return required.length ? required.every(settled) : children.filter(child => !child.discretionary && !child.kind.endsWith('EventListener')).every(settled)
}

function flat(items: CaseModelItem[]): CaseModelItem[] { return items.flatMap(item => [item, ...flat(item.children ?? [])]) }
function find(items: CaseModelItem[], id: string): CaseModelItem | undefined { return flat(items).find(item => item.id === id) }
