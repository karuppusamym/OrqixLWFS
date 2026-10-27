import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { useAnnounce } from '@/components/common/Announcer'
import { toProblem } from '@/components/common/ProblemAlert'
import type { ProblemError } from '@/api/problem'
import { downloadBlob } from '@/lib/download'
import { useStudioContext } from './StudioContext'

/**
 * "Download BPMN (.bpmn)" for the current draft (STU04, docs/build/S97). Unsaved edits are saved first (editors), so the
 * file is the stored revision that publishing would compile. Needs workflow.read; the server audits it as an export.
 */
export function useDownloadDraftBpmn() {
  const { store, saver, wsId, workflowId, readOnly } = useStudioContext()
  const announce = useAnnounce()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const download = async () => {
    setBusy(true)
    setError(null)
    try {
      if (!readOnly) await saver.flush()
      const { blob, filename } = await endpoints.exportDraftBpmn(wsId, workflowId, store.getState().revision)
      downloadBlob(blob, filename)
      announce(`Downloaded ${filename}`)
    } catch (cause) {
      const problem = toProblem(cause)
      setError(problem)
      announce(problem.detail ?? problem.title)
    } finally {
      setBusy(false)
    }
  }
  return { download, busy, error }
}
