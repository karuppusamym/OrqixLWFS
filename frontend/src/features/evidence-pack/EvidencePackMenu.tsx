import { FileCheck2 } from 'lucide-react'
import { useState } from 'react'
import { exportReleaseEvidencePack, exportRunEvidencePack, type EvidencePackFormat } from '@/api/endpoints'
import type { Permission } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { downloadBlob } from '@/lib/download'

export type EvidencePackTarget = { kind: 'release'; workflowId: string; releaseId: string } | { kind: 'run'; runId: string }

const FORMATS: { format: EvidencePackFormat; label: string }[] = [
  { format: 'pdf', label: 'PDF document' },
  { format: 'docx', label: 'Word document (.docx)' },
  { format: 'json', label: 'JSON (machine-readable)' },
]

/** Who may download: release packs need workflow.read, run packs run.read; audit.read covers both (server-checked). */
function allowed(can: (...permissions: Permission[]) => boolean, kind: EvidencePackTarget['kind']) {
  return kind === 'release' ? can('workflow.read', 'audit.read') : can('run.read', 'audit.read')
}

/**
 * "Evidence pack" download menu: the control evidence an auditor reads without platform access
 * (docs/27 section 3, Create). The server renders the file; this only picks the format and saves it.
 */
export function EvidencePackMenu({ target, label, size = 'sm' }: { target: EvidencePackTarget; label: string; size?: 'sm' | 'default' | 'lg' }) {
  const { workspace, can } = useWorkspace()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>()
  if (!allowed(can, target.kind)) return null

  const download = async (format: EvidencePackFormat) => {
    setPending(true)
    setError(undefined)
    try {
      const { blob, filename } = target.kind === 'release'
        ? await exportReleaseEvidencePack(workspace.id, target.workflowId, target.releaseId, format)
        : await exportRunEvidencePack(workspace.id, target.runId, format)
      downloadBlob(blob, filename)
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }

  return (
    <span className="inline-flex flex-col items-end gap-2">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button size={size} variant="secondary" loading={pending} aria-label={label}>
            <FileCheck2 aria-hidden="true" /> Evidence pack
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuLabel>Download for auditors</DropdownMenuLabel>
          {FORMATS.map(({ format, label: text }) => (
            <DropdownMenuItem key={format} onSelect={() => void download(format)}>
              {text}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      {error ? <ProblemAlert error={error} context={{ action: 'download the evidence pack' }} /> : null}
    </span>
  )
}
