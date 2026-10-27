import { Handle, Position } from '@xyflow/react'
import { AlertTriangle, CheckCircle2, XCircle } from 'lucide-react'
import { cn } from '@/lib/cn'
import { KINDS } from '../model/kinds'
import { BPMN_NOTATION } from '../model/notation'
import { SHAPE_SIZES } from '../model/shapeSizes'
import { KIND_VAR } from '../nodeMeta'
import type { StepNodeData } from '../StepNode'
import { BpmnGlyph, type GlyphColors } from './BpmnGlyph'

/** Outcome text of a Finish step, the same words as on the card. */
export function outcomeText(data: Pick<StepNodeData, 'outcomeKind' | 'outcome'>): string {
  const rejectedEnd = data.outcomeKind === 'rejected' || data.outcomeKind === 'failed'
  if (data.outcomeKind === 'failed') return data.outcome && !/fail/i.test(data.outcome) ? `${data.outcome} · Failed` : data.outcome || 'Failed'
  return data.outcome || (rejectedEnd ? 'Rejected' : 'Completed')
}

export function glyphColors(data: Pick<StepNodeData, 'kind' | 'outcomeKind'>, selected: boolean): GlyphColors {
  if (data.kind === 'end') {
    const rejectedEnd = data.outcomeKind === 'rejected' || data.outcomeKind === 'failed'
    return {
      stroke: selected ? 'var(--primary)' : rejectedEnd ? 'var(--status-rejected-fg)' : 'var(--status-success-fg)',
      fill: rejectedEnd ? 'var(--status-rejected-bg)' : 'var(--status-success-bg)',
      marker: rejectedEnd ? 'var(--status-rejected-fg)' : 'var(--status-success-fg)',
    }
  }
  const kind = KIND_VAR[data.kind]
  return { stroke: selected ? 'var(--primary)' : kind, fill: 'var(--surface)', marker: kind }
}

/**
 * A step drawn in BPMN 2.0 notation (STU01). The element's box is exactly the shape's bounding box (SHAPE_SIZES), so the
 * saved position (top-left) and the handles on the left, right and bottom edges line up with the circle's and the
 * diamond's extreme points. Tasks print the kind and the name inside; events and gateways print them beneath the shape
 * (above it when a Timed out route leaves from the bottom). Approved, Rejected and Failed routes are printed on their
 * lines as text, with an icon beside each handle.
 */
export function BpmnStepNode({ data, selected }: { data: StepNodeData; selected: boolean }) {
  const notation = BPMN_NOTATION[data.kind]
  const size = SHAPE_SIZES[notation.sizeClass]
  const info = KINDS[data.kind]
  const issues = data.blockers + data.warnings
  const isEnd = data.kind === 'end'
  const rejectedEnd = isEnd && (data.outcomeKind === 'rejected' || data.outcomeKind === 'failed')
  const task = notation.sizeClass === 'task'
  const twoRoutes = data.kind === 'approval' || data.kind === 'http'
  const labelAbove = !task && !!data.timeout
  const OutcomeIcon = rejectedEnd ? XCircle : CheckCircle2
  return (
    <div
      className="relative"
      style={{ width: size.width, height: size.height }}
      data-kind={data.kind}
      data-notation="bpmn"
      data-shape={notation.shape}
      data-shape-class={notation.sizeClass}
      data-testid={`node-shape-${data.kind}`}
      title={`${info.caption}: ${data.label}${data.subtitle ? `. ${data.subtitle}` : ''}`}
    >
      <BpmnGlyph shape={notation.shape} colors={glyphColors(data, selected)} emphasis={selected} className={cn('absolute inset-0', selected && 'bpmn-selected')} />

      {task ? (
        <div className={cn('absolute inset-0 flex flex-col gap-0.5 py-1.5 pl-2.5', twoRoutes ? 'pr-8' : 'pr-2.5', data.kind === 'startWorkflow' && 'pb-5')}>
          <span className={cn('truncate text-sm leading-5 font-semibold', data.kind === 'externalTask' ? 'pl-10' : 'pl-6')} style={{ color: KIND_VAR[data.kind] }}>
            {info.caption}
          </span>
          <span className="line-clamp-2 text-[15px] leading-[1.2] font-semibold text-foreground">{data.label}</span>
        </div>
      ) : (
        <div
          className={cn(
            'pointer-events-none absolute left-1/2 w-[168px] -translate-x-1/2 rounded bg-canvas px-1 text-center',
            labelAbove ? 'bottom-full mb-1.5' : 'top-full mt-1.5',
          )}
          data-testid="shape-label"
        >
          <span className="line-clamp-2 text-[15px] leading-[1.2] font-semibold text-foreground">{data.label}</span>
          {isEnd ? (
            <span className={cn('inline-flex max-w-full items-center gap-1 text-sm leading-5 font-semibold', rejectedEnd ? 'text-status-rejected-fg' : 'text-status-success-fg')}>
              <OutcomeIcon className="size-4 shrink-0" aria-hidden="true" />
              <span className="truncate">{outcomeText(data)}</span>
            </span>
          ) : (
            <span className="block truncate text-sm leading-5 text-muted-foreground">{info.caption}</span>
          )}
          {data.stage ? <span className="block truncate text-sm leading-5 font-semibold text-info-foreground" data-testid="stage-chip">Stage: {data.stage}</span> : null}
        </div>
      )}

      {task && data.stage ? (
        <span
          className="absolute -bottom-3.5 left-3 z-10 max-w-[11rem] truncate rounded-full border border-info-border bg-info-soft px-2 text-sm leading-6 font-semibold text-info-foreground"
          data-testid="stage-chip"
          title={`Stage: ${data.stage}`}
        >
          Stage: {data.stage}
        </span>
      ) : null}

      {issues > 0 ? (
        <span
          className={cn(
            'absolute -top-3 -right-3 z-10 inline-flex items-center gap-0.5 rounded-full px-1.5 text-sm leading-6 font-bold',
            data.blockers > 0 ? 'bg-danger-soft text-danger-foreground' : 'bg-warning-soft text-warning-foreground',
          )}
          data-testid="node-issues"
        >
          <AlertTriangle className="size-4" aria-hidden="true" />
          {issues}
          <span className="sr-only"> {issues === 1 ? 'issue' : 'issues'}</span>
        </span>
      ) : null}

      {data.kind !== 'start' ? <Handle type="target" position={Position.Left} id="in" aria-hidden="true" /> : null}
      {data.kind === 'approval' ? (
        <>
          <Handle type="source" position={Position.Right} id="approved" style={{ top: '30%' }} title="Approved route" aria-hidden="true" />
          <Handle type="source" position={Position.Right} id="rejected" style={{ top: '70%' }} title="Rejected route" aria-hidden="true" />
          <CheckCircle2 className="pointer-events-none absolute top-[30%] right-3 size-4 -translate-y-1/2 text-success-foreground" aria-hidden="true" />
          <XCircle className="pointer-events-none absolute top-[70%] right-3 size-4 -translate-y-1/2 text-danger-foreground" aria-hidden="true" />
          {data.timeout ? <Handle type="source" position={Position.Bottom} id="timedOut" title="Timed out route" aria-hidden="true" /> : null}
        </>
      ) : data.kind === 'waitForMessage' && data.timeout ? (
        <>
          <Handle type="source" position={Position.Right} id="next" aria-hidden="true" />
          <Handle type="source" position={Position.Bottom} id="timedOut" title="Timed out route" aria-hidden="true" />
        </>
      ) : data.kind === 'http' ? (
        <>
          <Handle type="source" position={Position.Right} id="next" style={{ top: '30%' }} title="Next route" aria-hidden="true" />
          <Handle type="source" position={Position.Right} id="failed" style={{ top: '70%' }} title="Failed route" aria-hidden="true" />
          <XCircle className="pointer-events-none absolute top-[70%] right-3 size-4 -translate-y-1/2 text-danger-foreground" aria-hidden="true" />
        </>
      ) : data.kind === 'branch' ? (
        <Handle type="source" position={Position.Right} id="route" aria-hidden="true" />
      ) : data.kind !== 'end' && data.kind !== 'raiseError' ? (
        <Handle type="source" position={Position.Right} id="next" aria-hidden="true" />
      ) : null}
    </div>
  )
}
