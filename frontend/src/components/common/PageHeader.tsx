import { useEffect, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/cn'

/**
 * Page title. Receives focus on mount so keyboard and screen reader users land at the new content after a
 * route change (the title is not a tab stop; tabIndex -1 only allows programmatic focus).
 */
export function PageHeader({
  title,
  description,
  actions,
  className,
  focusOnMount = true,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  className?: string
  focusOnMount?: boolean
}) {
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    if (focusOnMount) ref.current?.focus({ preventScroll: true })
  }, [focusOnMount])
  return (
    <div className={cn('mb-6 flex flex-wrap items-start justify-between gap-4', className)}>
      <div className="min-w-0">
        <h1 ref={ref} tabIndex={-1} className="outline-none">
          {title}
        </h1>
        {description ? <p className="mt-1 text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}
