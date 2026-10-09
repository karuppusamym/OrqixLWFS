import { cva, type VariantProps } from 'class-variance-authority'
import { AlertTriangle, CheckCircle2, Info, XCircle } from 'lucide-react'
import type { HTMLAttributes, ReactNode } from 'react'
import { cn } from '@/lib/cn'

const alertVariants = cva('flex gap-3 rounded-card border p-4 text-sm', {
  variants: {
    tone: {
      info: 'border-info-border bg-info-soft text-info-foreground',
      success: 'border-success-border bg-success-soft text-success-foreground',
      warning: 'border-warning-border bg-warning-soft text-warning-foreground',
      danger: 'border-danger-border bg-danger-soft text-danger-foreground',
    },
  },
  defaultVariants: { tone: 'info' },
})

const icons = { info: Info, success: CheckCircle2, warning: AlertTriangle, danger: XCircle } as const

export interface AlertProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'>, VariantProps<typeof alertVariants> {
  title?: ReactNode
  /** Announce politely (status) or assertively (alert). Defaults by tone. */
  live?: 'polite' | 'assertive' | 'off'
}

export function Alert({ className, tone = 'info', title, children, live, ...props }: AlertProps) {
  const Icon = icons[tone ?? 'info']
  const mode = live ?? (tone === 'danger' ? 'assertive' : 'polite')
  return (
    <div
      role={mode === 'off' ? undefined : mode === 'assertive' ? 'alert' : 'status'}
      className={cn(alertVariants({ tone }), className)}
      {...props}
    >
      <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        {title ? <p className="font-semibold">{title}</p> : null}
        {children ? <div className={cn(title && 'mt-0.5')}>{children}</div> : null}
      </div>
    </div>
  )
}
