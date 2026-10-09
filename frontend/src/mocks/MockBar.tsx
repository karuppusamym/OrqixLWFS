import { FlaskConical } from 'lucide-react'
import { useState } from 'react'
import { PERSONA_KEY, resetMockData } from './browser'
import { USERS } from './handlers'
import { db, type ProcurementFault } from './state'
import { readStorage, writeStorage } from '@/lib/storage'

const PERSONA_NOTES: Record<string, string> = {
  'pat.platform': 'Platform admin (no workspace)',
  'wade.admin': 'Workspace admin + connections',
  'dana.designer': 'Designer',
  'paul.publisher': 'Publisher',
  'olga.operator': 'Operator',
  'vic.viewer': 'Viewer',
  'rhea.requester': 'Requester',
  'mani.manager': 'Manager',
  'max.manager': 'Manager',
  'fin.finance': 'Finance approver',
  'fred.facilities': 'Facilities only',
  'otto.outsider': 'No workspace',
}

/**
 * Mock-mode control panel (never part of a normal build): switch the signed-in person, choose how the
 * procurement service behaves, and reset the demo data.
 */
export function MockBar() {
  const [open, setOpen] = useState(false)
  const [fault, setFault] = useState<ProcurementFault>(db.procurementFault)
  const current = readStorage(PERSONA_KEY) ?? 'rhea.requester'

  return (
    <div className="fixed bottom-3 left-3 z-[60] text-sm" data-testid="mock-bar">
      {open ? (
        <div className="w-72 space-y-3 rounded-card border border-border bg-surface p-4 shadow-xl">
          <div className="flex items-center justify-between">
            <p className="font-semibold">Mock mode</p>
            <button type="button" className="min-h-6 rounded px-2 text-primary-text underline" onClick={() => setOpen(false)}>
              Hide
            </button>
          </div>
          <p className="text-xs text-muted-foreground">No backend is running. Data lives in this browser only.</p>
          <div className="space-y-1">
            <label htmlFor="mock-user" className="block font-semibold">
              Signed in as
            </label>
            <select
              id="mock-user"
              className="h-10 w-full rounded-control border border-input-border bg-surface px-2"
              value={current}
              onChange={(e) => {
                writeStorage(PERSONA_KEY, e.target.value)
                window.location.assign('/')
              }}
            >
              {USERS.map((u) => (
                <option key={u.username} value={u.username}>
                  {u.displayName} ({PERSONA_NOTES[u.username] ?? 'member'})
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-1">
            <label htmlFor="mock-fault" className="block font-semibold">
              Procurement service
            </label>
            <select
              id="mock-fault"
              className="h-10 w-full rounded-control border border-input-border bg-surface px-2"
              value={fault}
              onChange={(e) => {
                const next = e.target.value as ProcurementFault
                setFault(next)
                window.__orqixMock?.setProcurementFault(next)
              }}
            >
              <option value="none">Working</option>
              <option value="failOnce">Fails once, then works</option>
              <option value="unknown">Outcome unknown</option>
              <option value="down">Down (retries run out)</option>
            </select>
          </div>
          <button type="button" className="min-h-9 w-full rounded-control border border-input-border bg-surface px-3 font-semibold hover:bg-surface-muted" onClick={() => resetMockData()}>
            Reset demo data
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="flex min-h-9 items-center gap-2 rounded-full border border-warning-border bg-warning-soft px-3 font-semibold text-warning-foreground shadow"
          onClick={() => setOpen(true)}
        >
          <FlaskConical className="size-4" aria-hidden="true" /> Mock mode
        </button>
      )}
    </div>
  )
}
