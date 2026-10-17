import { X } from 'lucide-react'
import { useEffect, useId, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { Button } from './button'

/**
 * Modal dialog on the native <dialog> element.
 *
 * Why not Radix Dialog? Its scroll lock (react-remove-scroll) injects a <style> element at runtime, which the
 * platform CSP (`style-src 'self'`, docs/build/S6-security.md section 6) blocks and reports. The native element gives
 * the same behavior without script-injected styles: top layer rendering, inert page behind it, a focus trap, Escape
 * to close, and focus restoration to the opener.
 */
export interface DialogProps {
  open: boolean
  onClose: () => void
  title: string
  description?: ReactNode
  children?: ReactNode
  /** Footer content, usually buttons. */
  footer?: ReactNode
  /** Close on Escape and backdrop click. Set false while a command is pending. */
  dismissible?: boolean
  className?: string
  /** Wider dialogs for editors. */
  size?: 'md' | 'lg' | 'xl'
}

export function Dialog({ open, onClose, title, description, children, footer, dismissible = true, className, size = 'md' }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descId = useId()

  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (open && !el.open) el.showModal()
    if (!open && el.open) el.close()
  }, [open])

  // Clicking the backdrop reports the dialog element itself as the target.
  useEffect(() => {
    const el = ref.current
    if (!el || !dismissible) return
    const onClick = (event: MouseEvent) => {
      if (event.target === el) onClose()
    }
    el.addEventListener('click', onClick)
    return () => el.removeEventListener('click', onClick)
  }, [dismissible, onClose])

  return (
    <dialog
      ref={ref}
      className={cn('orqix-dialog', size === 'lg' && 'max-w-[min(92vw,52rem)]', size === 'xl' && 'max-w-[min(94vw,68rem)]', className)}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onCancel={(event) => {
        event.preventDefault()
        if (dismissible) onClose()
      }}
    >
      {open ? (
        <div className="flex max-h-[90dvh] flex-col">
          <div className="flex items-start justify-between gap-4 border-b border-border px-6 py-4">
            <div className="min-w-0">
              <h2 id={titleId}>{title}</h2>
              {description ? (
                <div id={descId} className="mt-1 text-sm text-muted-foreground">
                  {description}
                </div>
              ) : null}
            </div>
            {dismissible ? (
              <Button variant="ghost" size="icon" onClick={onClose} aria-label="Close dialog" className="-mr-2 shrink-0">
                <X aria-hidden="true" />
              </Button>
            ) : null}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">{children}</div>
          {footer ? <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border bg-surface-muted px-6 py-4">{footer}</div> : null}
        </div>
      ) : null}
    </dialog>
  )
}
