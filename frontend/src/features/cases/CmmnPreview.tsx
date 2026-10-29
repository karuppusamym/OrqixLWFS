import { Clock, FolderOpen, User, Workflow } from 'lucide-react'
import { useId } from 'react'
import { cn } from '@/lib/cn'
import { conditionExpression, entrySources, type CaseModelItem } from './caseModel'

/**
 * Read-only CMMN 1.1 notation preview of the case model designer (STU01). The designer itself is a form (a tree of plan
 * items), not a canvas, so the notation is shown beside it as a picture that follows every edit:
 *
 * - case plan model: folder shape with the case name on its tab
 * - stage: rectangle with angled corners and a collapse marker (minus: shown expanded) at the bottom
 * - human task: rounded rectangle with a person icon
 * - milestone: stadium (fully rounded ends)
 * - user event listener: double circle with a person icon
 * - entry criterion (sentry): small hollow diamond on the left edge
 * - markers at the bottom: manual activation (play triangle), required (!), repetition (#)
 *
 * Shapes and markers are decoration: each item's kind, name, markers and entry condition are also printed as words,
 * and the whole preview is a nested list a screen reader reads in order.
 */
export const CMMN_KIND: Record<CaseModelItem['kind'], { label: string; symbol: string; element: string }> = {
  stage: { label: 'Stage', symbol: 'Rectangle with angled corners and a collapse marker', element: 'stage' },
  humanTask: { label: 'Human task', symbol: 'Rounded rectangle with a person icon', element: 'human task' },
  processTask: { label: 'Workflow task', symbol: 'Rounded rectangle with a process icon', element: 'process task' },
  caseTask: { label: 'Case task', symbol: 'Rounded rectangle with a folder icon', element: 'case task' },
  timerEventListener: { label: 'Timer', symbol: 'Double circle with a clock', element: 'timer event listener' },
  milestone: { label: 'Milestone', symbol: 'Stadium (rounded ends)', element: 'milestone' },
  userEventListener: { label: 'User event', symbol: 'Double circle with a person icon', element: 'user event listener' },
}

type Lookup = Map<string, CaseModelItem>

function markerWords(item: CaseModelItem): string[] {
  const words: string[] = []
  if (item.required) words.push('Required')
  if (item.manual) words.push('Manual start')
  if (item.repetitions) words.push(`Repeats ${item.repetitions} times`)
  if (item.discretionary) words.push(`Discretionary: planned by ${(item.planners ?? []).join(', ') || 'nobody yet'}`)
  if (item.kind === 'processTask' && item.workflow?.key) words.push(`Runs workflow ${item.workflow.key}`)
  if (item.kind === 'caseTask' && item.childCase?.key) words.push(`Starts case ${item.childCase.key}`)
  if (item.kind === 'timerEventListener' && item.timer) words.push(`Fires after ${item.timer}`)
  return words
}

function eventWords(id: string, lookup: Lookup): string | null {
  const source = lookup.get(id)
  if (!source) return null
  return `${source.label} ${source.kind === 'milestone' || source.kind === 'userEventListener' ? 'occurs' : 'completes'}`
}

function entryText(item: CaseModelItem, lookup: Lookup): string | null {
  const words = entrySources(item).map(id => eventWords(id, lookup)).filter((value): value is string => !!value)
  const data = item.when?.clauses.length ? `case data ${conditionExpression(item.when).slice(2, -1).replaceAll('cd_', '')}` : null
  if (!words.length && !data) return null
  return words.length ? `Starts after ${words.join(' and ')}${data ? ` when ${data}` : ''}` : `Starts when ${data}`
}

function exitText(item: CaseModelItem, lookup: Lookup): string | null {
  const words = item.exitAfter ? eventWords(item.exitAfter, lookup) : null
  return words ? `Ends when ${words}` : null
}

/** CMMN decorators, drawn left to right at the bottom centre of a task or stage. */
function Markers({ item, extra }: { item: CaseModelItem; extra?: React.ReactNode }) {
  const glyphs: { key: string; title: string; node: React.ReactNode }[] = []
  if (item.manual)
    glyphs.push({ key: 'manual', title: 'Manual activation', node: <polygon points="4,3 4,13 12,8" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinejoin="round" /> })
  if (item.required) glyphs.push({ key: 'required', title: 'Required', node: <text x="8" y="13" textAnchor="middle" fontSize="13" fontWeight="700" fill="currentColor">!</text> })
  if (item.repetitions) glyphs.push({ key: 'repeat', title: 'Repetition', node: <text x="8" y="13" textAnchor="middle" fontSize="13" fontWeight="700" fill="currentColor">#</text> })
  if (!glyphs.length && !extra) return null
  return (
    <span className="absolute -bottom-px left-1/2 flex -translate-x-1/2 items-end gap-0.5" aria-hidden="true" data-testid="cmmn-markers">
      {glyphs.map((g) => (
        <svg key={g.key} width={16} height={16} viewBox="0 0 16 16" className="rounded-sm border border-current bg-surface text-foreground" data-marker={g.key}>
          <title>{g.title}</title>
          {g.node}
        </svg>
      ))}
      {extra}
    </span>
  )
}

/** Collapse marker of a stage: a small square with a minus (the stage is shown expanded). */
function CollapseMarker() {
  return (
    <svg width={16} height={16} viewBox="0 0 16 16" className="rounded-sm border border-current bg-surface text-foreground" data-marker="collapse">
      <title>Expanded stage</title>
      <path d="M 4 8 L 12 8" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" />
    </svg>
  )
}

/** Exit criterion: a filled diamond on the right edge of the item. */
function ExitSentry() {
  return (
    <svg width={14} height={18} viewBox="0 0 14 18" className="absolute top-1/2 -right-[7px] -translate-y-1/2 text-foreground" aria-hidden="true" data-testid="cmmn-exit-sentry">
      <polygon points="7,1 13,9 7,17 1,9" fill="currentColor" stroke="currentColor" strokeWidth={1.6} strokeLinejoin="round" />
    </svg>
  )
}

/** Entry criterion: a hollow diamond on the left edge of the item. */
function Sentry() {
  return (
    <svg width={14} height={18} viewBox="0 0 14 18" className="absolute top-1/2 -left-[7px] -translate-y-1/2 text-foreground" aria-hidden="true" data-testid="cmmn-sentry">
      <polygon points="7,1 13,9 7,17 1,9" fill="var(--surface)" stroke="currentColor" strokeWidth={1.6} strokeLinejoin="round" />
    </svg>
  )
}

function ItemText({ item, lookup }: { item: CaseModelItem; lookup: Lookup }) {
  const words = markerWords(item)
  const entry = entryText(item, lookup)
  const exit = exitText(item, lookup)
  const all = [...words, ...(entry ? [entry] : []), ...(exit ? [exit] : [])]
  return (
    <>
      <span className="block text-sm leading-5 font-semibold text-muted-foreground">{CMMN_KIND[item.kind].label}</span>
      <span className="block text-[15px] leading-5 font-semibold break-words text-foreground">{item.label || item.id}</span>
      {all.length ? <span className="block text-sm leading-5 text-muted-foreground">{all.join(' · ')}</span> : null}
    </>
  )
}

/** Angled corners (CMMN 1.1 stage): a rectangle with the four corners cut at 45 degrees. */
const ANGLED = '[clip-path:polygon(14px_0,calc(100%-14px)_0,100%_14px,100%_calc(100%-14px),calc(100%-14px)_100%,14px_100%,0_calc(100%-14px),0_14px)]'

function PlanItem({ item, lookup }: { item: CaseModelItem; lookup: Lookup }) {
  const sentry = <>{item.after && lookup.has(item.after) || item.when?.clauses.length ? <Sentry /> : null}{item.exitAfter && lookup.has(item.exitAfter) ? <ExitSentry /> : null}</>
  const outline = item.discretionary ? 'border-dashed' : ''
  if (item.kind === 'stage')
    return (
      <li className="relative px-5 pt-3 pb-7" data-cmmn="stage" data-testid={`cmmn-${item.id}`}>
        {/* CMMN 1.1: a stage is a rectangle with angled corners. The outline and fill are two clipped layers behind the
            content, so the sentry diamonds on the edge are not clipped. */}
        <span aria-hidden="true" className={`absolute inset-0 bg-foreground ${ANGLED}`} />
        <span aria-hidden="true" className={`absolute inset-[2px] bg-surface ${ANGLED}`} />
        <div className="relative">
          {sentry}
          <ItemText item={item} lookup={lookup} />
          {item.children?.length ? (
            <ol className="mt-2 flex flex-wrap items-start gap-4">
              {item.children.map((child) => (
                <PlanItem key={child.id} item={child} lookup={lookup} />
              ))}
            </ol>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">No plan items in this stage yet.</p>
          )}
          <Markers item={item} extra={<CollapseMarker />} />
        </div>
      </li>
    )
  if (item.kind === 'userEventListener' || item.kind === 'timerEventListener')
    return (
      <li className="flex w-44 flex-col items-center text-center" data-cmmn={item.kind} data-testid={`cmmn-${item.id}`}>
        <svg width={44} height={44} viewBox="0 0 44 44" className="text-foreground" aria-hidden="true">
          <circle cx={22} cy={22} r={20.5} fill="var(--surface)" stroke="currentColor" strokeWidth={1.6} />
          <circle cx={22} cy={22} r={17} fill="none" stroke="currentColor" strokeWidth={1.6} />
          {item.kind === 'timerEventListener' ? <Clock x={13} y={13} width={18} height={18} strokeWidth={2.2} /> : <User x={13} y={13} width={18} height={18} strokeWidth={2.2} />}
        </svg>
        <ItemText item={item} lookup={lookup} />
      </li>
    )
  if (item.kind === 'milestone')
    return (
      <li className="relative w-48 rounded-full border-2 border-foreground bg-surface px-5 pt-1.5 pb-5 text-center" data-cmmn="milestone" data-testid={`cmmn-${item.id}`}>
        {sentry}
        <ItemText item={item} lookup={lookup} />
        <Markers item={item} />
      </li>
    )
  const Icon = item.kind === 'processTask' ? Workflow : item.kind === 'caseTask' ? FolderOpen : User
  return (
    <li className={cn('relative w-52 rounded-lg border-2 border-foreground bg-surface px-3 pt-2 pb-5', outline)} data-cmmn={item.kind} data-testid={`cmmn-${item.id}`}>
      {sentry}
      <Icon className="absolute top-2 right-2 size-4 text-foreground" aria-hidden="true" />
      <ItemText item={item} lookup={lookup} />
      <Markers item={item} />
    </li>
  )
}

function index(items: CaseModelItem[], into: Lookup = new Map()): Lookup {
  for (const item of items) {
    into.set(item.id, item)
    if (item.children) index(item.children, into)
  }
  return into
}

export function CmmnPreview({ name, items, className }: { name: string; items: CaseModelItem[]; className?: string }) {
  const headingId = useId()
  const lookup = index(items)
  return (
    <section className={cn('space-y-2', className)} aria-labelledby={headingId} data-testid="cmmn-preview">
      <h3 id={headingId} className="text-base font-semibold">
        CMMN diagram (read only)
      </h3>
      <p className="text-sm text-muted-foreground">
        The plan drawn in CMMN 1.1 notation. It follows your changes. Every symbol is also written out in words.
      </p>
      <div className="overflow-x-auto rounded-card border border-border bg-canvas p-4">
        <div className="relative inline-block min-w-full">
          <span className="relative z-10 inline-block max-w-[20rem] truncate rounded-t-md border-2 border-b-0 border-foreground bg-surface px-3 py-0.5 text-sm font-semibold" title={name}>
            Case plan: {name || 'Untitled case'}
          </span>
          <ol className="-mt-0.5 flex flex-wrap items-start gap-4 rounded-b-md rounded-tr-md border-2 border-foreground bg-surface/60 p-4" aria-label={`Case plan ${name || 'Untitled case'}`}>
            {items.map((item) => (
              <PlanItem key={item.id} item={item} lookup={lookup} />
            ))}
          </ol>
        </div>
      </div>
      <details className="rounded-card border border-border bg-surface text-sm" data-testid="cmmn-legend">
        <summary className="cursor-pointer px-3 py-2 font-semibold">CMMN symbols</summary>
        <ul className="space-y-1 px-3 pb-3">
          {Object.values(CMMN_KIND).map((k) => (
            <li key={k.element}>
              <span className="font-semibold">{k.label}</span>: {k.symbol} (CMMN {k.element})
            </li>
          ))}
          <li>
            <span className="font-semibold">Start after</span>: small hollow diamond on the left edge (CMMN entry criterion sentry)
          </li>
          <li>
            <span className="font-semibold">Ends when</span>: small filled diamond on the right edge (CMMN exit criterion sentry)
          </li>
          <li>
            <span className="font-semibold">Discretionary item</span>: dashed outline; people of its planner groups add it to the case when needed (CMMN planning table)
          </li>
          <li>
            <span className="font-semibold">Markers</span> at the bottom: play triangle for manual activation, ! for required, # for repetition, minus for an expanded stage
          </li>
          <li>
            <span className="font-semibold">Case plan</span>: folder shape with the case name on its tab (CMMN case plan model)
          </li>
        </ul>
      </details>
    </section>
  )
}
