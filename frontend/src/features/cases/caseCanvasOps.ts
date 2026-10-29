import { SHAPE, autoLayout, caseItems, entrySources, type CaseItemKind, type CaseItemLayout, type CaseModelItem } from './caseModel'

/** The stage that directly contains an item, or null for the case plan. */
export function parentOf(items: CaseModelItem[], id: string, parent: CaseModelItem | null = null): CaseModelItem | null | undefined {
  for (const item of items) {
    if (item.id === id) return parent
    const found = parentOf(item.children ?? [], id, item)
    if (found !== undefined) return found
  }
  return undefined
}

export function findItem(items: CaseModelItem[], id: string): CaseModelItem | undefined { return caseItems(items).find(item => item.id === id) }

function mapItems(items: CaseModelItem[], change: (item: CaseModelItem) => CaseModelItem): CaseModelItem[] {
  return items.map(item => { const next = change(item); return next.children ? { ...next, children: mapItems(next.children, change) } : next })
}

function shiftTree(item: CaseModelItem, dx: number, dy: number): CaseModelItem {
  return { ...item, layout: item.layout && { ...item.layout, x: item.layout.x + dx, y: item.layout.y + dy }, children: item.children?.map(child => shiftTree(child, dx, dy)) }
}

/** Moves an item (and, for a stage, everything inside it) to an absolute position. */
export function moveItem(items: CaseModelItem[], id: string, x: number, y: number): CaseModelItem[] {
  return mapItems(items, item => {
    if (item.id !== id || !item.layout) return item
    return shiftTree(item, Math.round(x - item.layout.x), Math.round(y - item.layout.y))
  })
}

export function resizeStage(items: CaseModelItem[], id: string, w: number, h: number): CaseModelItem[] {
  return mapItems(items, item => item.id === id && item.layout ? { ...item, layout: { ...item.layout, w: Math.max(SHAPE.stage.w / 2, Math.round(w)), h: Math.max(120, Math.round(h)) } } : item)
}

/** Rules only connect items of one stage; moving an item to another stage drops the rules that would cross it. */
function dropCrossingRules(items: CaseModelItem[]): CaseModelItem[] {
  const scope = (nodes: CaseModelItem[]): CaseModelItem[] => {
    const local = new Set(nodes.map(item => item.id))
    return nodes.map(item => {
      const sources = entrySources(item).filter(id => local.has(id))
      const next: CaseModelItem = { ...item, after: sources[0], afterAll: sources.length > 1 ? sources.slice(1) : undefined, exitAfter: item.exitAfter && local.has(item.exitAfter) ? item.exitAfter : undefined }
      if (!next.after) delete next.after
      if (!next.afterAll) delete next.afterAll
      if (!next.exitAfter) delete next.exitAfter
      return next.children ? { ...next, children: scope(next.children) } : next
    })
  }
  return scope(items)
}

/** Puts an item into a stage (or the case plan when stageId is null), keeping its absolute position. */
export function reparent(items: CaseModelItem[], id: string, stageId: string | null): CaseModelItem[] {
  const moving = findItem(items, id)
  if (!moving) return items
  if (stageId && (stageId === id || caseItems([moving]).some(item => item.id === stageId))) throw new Error('A stage cannot be placed inside itself.')
  if (stageId && findItem(items, stageId)?.kind !== 'stage') throw new Error('Items can only be placed inside a stage.')
  const remove = (nodes: CaseModelItem[]): CaseModelItem[] => nodes.filter(item => item.id !== id).map(item => item.children ? { ...item, children: remove(item.children) } : item)
  let next = remove(items)
  if (!stageId) next = [...next, moving]
  else next = mapItems(next, item => item.id === stageId ? { ...item, children: [...(item.children ?? []), moving] } : item)
  return dropCrossingRules(next)
}

/** The innermost stage whose frame contains the point, ignoring the item itself and anything inside it. */
export function stageAt(items: CaseModelItem[], x: number, y: number, ignore: string): string | null {
  const excluded = new Set(caseItems([findItem(items, ignore)!].filter(Boolean)).map(item => item.id))
  let found: { id: string; area: number } | null = null
  for (const item of caseItems(items)) {
    if (item.kind !== 'stage' || !item.layout || excluded.has(item.id)) continue
    const b = item.layout
    if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h && (!found || b.w * b.h < found.area)) found = { id: item.id, area: b.w * b.h }
  }
  return found?.id ?? null
}

export type RuleKind = 'entry' | 'exit'

/** Why a connector from source to target cannot become a rule, or null when it can. */
export function connectionProblem(items: CaseModelItem[], sourceId: string, targetId: string, kind: RuleKind): string | null {
  const source = findItem(items, sourceId), target = findItem(items, targetId)
  if (!source || !target) return 'Connect two plan items.'
  if (sourceId === targetId) return 'An item cannot wait for itself.'
  if (parentOf(items, sourceId) !== parentOf(items, targetId)) return 'Rules connect items of the same stage. Move both items into one stage first.'
  if (source.discretionary) return 'Rules cannot wait for a discretionary item.'
  if (target.discretionary) return 'A discretionary item starts when someone plans it, so it cannot have rules.'
  if (target.kind === 'userEventListener' || target.kind === 'timerEventListener') return 'A user event or timer cannot have entry or exit rules.'
  if (kind === 'exit') {
    if (target.kind === 'milestone') return 'A milestone cannot have an exit rule.'
    if (target.repetitions) return 'A repeated item cannot also have an exit rule.'
    if (target.exitAfter === sourceId) return 'That exit rule exists already.'
    return null
  }
  if (entrySources(target).includes(sourceId)) return 'That entry rule exists already.'
  // An entry rule must not make the target wait for itself through other rules.
  const waitsFor = (id: string, seen = new Set<string>()): boolean => {
    if (id === targetId) return true
    if (seen.has(id)) return false
    seen.add(id)
    const item = findItem(items, id)
    return !!item && entrySources(item).some(next => waitsFor(next, seen))
  }
  if (waitsFor(sourceId)) return 'That rule would form a cycle.'
  return null
}

export function connect(items: CaseModelItem[], sourceId: string, targetId: string, kind: RuleKind): CaseModelItem[] {
  const problem = connectionProblem(items, sourceId, targetId, kind)
  if (problem) throw new Error(problem)
  return mapItems(items, item => {
    if (item.id !== targetId) return item
    if (kind === 'exit') return { ...item, exitAfter: sourceId }
    if (!item.after) return { ...item, after: sourceId }
    return { ...item, afterAll: [...(item.afterAll ?? []), sourceId] }
  })
}

export function disconnect(items: CaseModelItem[], sourceId: string, targetId: string, kind: RuleKind): CaseModelItem[] {
  return mapItems(items, item => {
    if (item.id !== targetId) return item
    if (kind === 'exit') { const next = { ...item }; delete next.exitAfter; return next }
    const remaining = entrySources(item).filter(id => id !== sourceId)
    const next: CaseModelItem = { ...item, after: remaining[0], afterAll: remaining.length > 1 ? remaining.slice(1) : undefined }
    if (!next.after) delete next.after
    if (!next.afterAll) delete next.afterAll
    if (next.kind === 'milestone' && !next.after && !next.when) delete next.repetitions
    return next
  })
}

/** A free spot for a new item inside a stage (or the case plan): below what is already there. */
export function placement(items: CaseModelItem[], stageId: string | null, kind: CaseItemKind): CaseItemLayout {
  const laid = autoLayout(items)
  const scope = stageId ? findItem(laid, stageId)?.children ?? [] : laid
  const frame = stageId ? findItem(laid, stageId)?.layout : undefined
  const size = SHAPE[kind]
  const lowest = scope.reduce((max, item) => Math.max(max, (item.layout?.y ?? 0) + (item.layout?.h ?? 0)), frame ? frame.y + 20 : 0)
  return { x: frame ? frame.x + 30 : 40, y: scope.length ? lowest + 50 : frame ? frame.y + 50 : 60, w: size.w, h: size.h }
}

/** Grows stages so they enclose their children after a move or an addition. */
export function fitStages(items: CaseModelItem[]): CaseModelItem[] {
  return items.map(item => {
    if (item.kind !== 'stage' || !item.layout) return item
    const children = fitStages(item.children ?? [])
    const { x, y } = item.layout
    let { w, h } = item.layout
    for (const child of children) {
      if (!child.layout) continue
      w = Math.max(w, child.layout.x + child.layout.w - x + 30)
      h = Math.max(h, child.layout.y + child.layout.h - y + 40)
    }
    return { ...item, children, layout: { x, y, w, h } }
  })
}
