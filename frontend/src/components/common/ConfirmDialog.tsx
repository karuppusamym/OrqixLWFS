import { Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'

export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  cancelLabel = 'Cancel',
  destructive,
  pending,
  onConfirm,
  onCancel,
  error,
}: {
  open: boolean
  title: string
  children?: ReactNode
  confirmLabel: string
  cancelLabel?: string
  destructive?: boolean
  pending?: boolean
  onConfirm: () => void
  onCancel: () => void
  error?: ReactNode
}) {
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={title}
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={pending}>
            {cancelLabel}
          </Button>
          <Button variant={destructive ? 'danger-solid' : 'primary'} onClick={onConfirm} loading={pending}>
            {destructive && !pending ? <Trash2 aria-hidden="true" /> : null}
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="space-y-3 text-sm">{children}</div>
      {error ? <div className="mt-3">{error}</div> : null}
    </Dialog>
  )
}
