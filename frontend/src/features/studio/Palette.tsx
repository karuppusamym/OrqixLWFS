import { PanelLeftClose, PanelLeftOpen, Search } from 'lucide-react'
import { useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/cn'
import { usePersistedFlag } from '@/lib/usePersistedFlag'
import { STEPS_COLLAPSED_KEY } from './viewKeys'
import { useAnnounce } from '@/components/common/Announcer'
import { PALETTE_MIME } from './CanvasView'
import { KINDS, PALETTE_KINDS } from './model/kinds'
import type { NodeKind } from './model/types'
import { KIND_ACCENT, KIND_ICON } from './nodeMeta'
import { useWorkspace } from '@/app/workspace'
import { useConnectorPalette, type ConnectorPaletteItem } from '@/features/settings/connectorPackagesApi'
import type { StepTemplate } from '@/api/types'
import { WorkspaceTemplateSteps } from './WorkspaceTemplates'

/**
 * Step palette with business labels (docs/build/S7-frontend.md section 5). Searchable; each item has a one-line
 * explanation. Items are buttons (activate with Enter or Space to add a step) and can also be dragged onto the canvas.
 */

export function Palette({ onAdd, onAddPackageStep, onAddWorkspaceTemplate, disabled, defaultCollapsed = false }: {
  onAdd: (kind: NodeKind) => void
  /** S133: a step template or operation of an installed connector package. */
  onAddPackageStep?: (item: ConnectorPaletteItem) => void
  /** S141 (CG-S03): a workspace step template. */
  onAddWorkspaceTemplate?: (template: StepTemplate) => void
  disabled: boolean
  defaultCollapsed?: boolean
}) {
  const [query, setQuery] = useState('')
  // A per-viewer convenience: collapsing the list gives the canvas the width of a long workflow.
  const [collapsed, setCollapsed] = usePersistedFlag(STEPS_COLLAPSED_KEY, defaultCollapsed)
  const announce = useAnnounce()
  const searchId = useId()
  const q = query.trim().toLowerCase()
  const items = PALETTE_KINDS.map((k) => KINDS[k]).filter((i) => !q || i.paletteLabel.toLowerCase().includes(q) || i.description.toLowerCase().includes(q))
  const toggle = setCollapsed

  if (collapsed) {
    return (
      <aside className="flex w-12 shrink-0 flex-col items-center gap-2 border-r border-border bg-surface py-3" aria-label="Palette">
        <Button variant="ghost" size="icon" onClick={() => toggle(false)} aria-label="Show the step list" aria-expanded={false} title="Show the step list">
          <PanelLeftOpen aria-hidden="true" />
        </Button>
        {PALETTE_KINDS.map((k) => {
          const Icon = KIND_ICON[k]
          return (
            <button
              key={k}
              type="button"
              disabled={disabled}
              onClick={() => {
                onAdd(k)
                announce(`${KINDS[k].paletteLabel} step added`)
              }}
              className={cn('flex size-9 items-center justify-center rounded-control text-white disabled:cursor-not-allowed disabled:opacity-60', KIND_ACCENT[k])}
              aria-label={`Add: ${KINDS[k].paletteLabel}`}
              title={`${KINDS[k].paletteLabel}: ${KINDS[k].description}`}
            >
              <Icon className="size-4" aria-hidden="true" />
            </button>
          )
        })}
      </aside>
    )
  }

  return (
    <aside className="w-64 shrink-0 overflow-y-auto border-r border-border bg-surface" aria-label="Palette">
    <section aria-label="Step palette" className="flex h-full flex-col gap-3 p-3">
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <Label htmlFor={searchId}>Add a step</Label>
          <Button variant="ghost" size="icon" className="-mr-1 size-8" onClick={() => toggle(true)} aria-label="Hide the step list" aria-expanded={true} title="Hide the step list">
            <PanelLeftClose aria-hidden="true" />
          </Button>
        </div>
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input id={searchId} type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search steps" className="pl-9" autoComplete="off" />
        </div>
      </div>
      <p className="text-xs text-muted-foreground">Click a step to add it, or drag it onto the canvas.</p>
      <ul className="space-y-2" aria-label="Available steps">
        {items.map((info) => {
          const Icon = KIND_ICON[info.kind]
          return (
            <li key={info.kind}>
              <button
                type="button"
                disabled={disabled}
                draggable={!disabled}
                onDragStart={(e) => {
                  e.dataTransfer.setData(PALETTE_MIME, info.kind)
                  e.dataTransfer.effectAllowed = 'copy'
                }}
                onClick={() => {
                  onAdd(info.kind)
                  announce(`${info.paletteLabel} step added`)
                }}
                className="flex w-full items-start gap-3 rounded-card border border-border bg-surface p-3 text-left hover:border-input-border hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60"
              >
                <span className={cn('mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-control text-white', KIND_ACCENT[info.kind])} aria-hidden="true">
                  <Icon className="size-4" />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-semibold">{info.paletteLabel}</span>
                  <span className="block text-xs text-muted-foreground">{info.description}</span>
                </span>
              </button>
            </li>
          )
        })}
        {items.length === 0 ? <li className="text-sm text-muted-foreground">No step matches &ldquo;{query}&rdquo;.</li> : null}
      </ul>
      {onAddWorkspaceTemplate ? <WorkspaceTemplateSection query={q} disabled={disabled} onAdd={onAddWorkspaceTemplate} /> : null}
      {onAddPackageStep ? <PackageSteps query={q} disabled={disabled} onAdd={onAddPackageStep} /> : null}
    </section>
    </aside>
  )
}

/**
 * Steps from installed connector packages (docs/build/S133): search by operation ("Raise an incident · ServiceNow
 * ITSM"); a step template prefills the call, an operation adds a call to it.
 */
function PackageSteps({ query, disabled, onAdd }: { query: string; disabled: boolean; onAdd: (item: ConnectorPaletteItem) => void }) {
  const { workspace } = useWorkspace()
  const palette = useConnectorPalette(workspace.id, query)
  const announce = useAnnounce()
  const items = (palette.data?.items ?? []).slice(0, 12)
  if (items.length === 0) return null
  return (
    <section aria-label="From connector packages" className="space-y-2">
      <h3 className="text-xs font-semibold text-muted-foreground uppercase">From connector packages</h3>
      <ul className="space-y-2" aria-label="Package steps">
        {items.map((item) => (
          <li key={`${item.kind}-${item.packageVersionId}-${item.connectionKey}-${item.templateKey ?? item.operation}`}>
            <button
              type="button"
              disabled={disabled}
              onClick={() => {
                onAdd(item)
                announce(`${item.label} step added`)
              }}
              className="w-full rounded-card border border-border bg-surface p-3 text-left hover:border-input-border hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60"
            >
              <span className="block text-sm font-semibold">{item.label}</span>
              <span className="block text-xs text-muted-foreground">{item.kind === 'stepTemplate' ? 'Template' : 'Operation'} · {item.packageVersion}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function WorkspaceTemplateSection({ query, disabled, onAdd }: { query: string; disabled: boolean; onAdd: (template: StepTemplate) => void }) {
  const { workspace } = useWorkspace()
  return <WorkspaceTemplateSteps ws={workspace.id} query={query} disabled={disabled} onAdd={onAdd} />
}
