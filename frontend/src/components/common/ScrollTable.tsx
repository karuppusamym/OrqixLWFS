import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/**
 * A table that may scroll (sideways on a phone, or up and down when long). A scrollable region must be reachable by
 * keyboard, so the region is focusable and named; the table inside keeps its own caption.
 */
export function ScrollTable({ label, className, children, ...props }: { label: string } & HTMLAttributes<HTMLDivElement>) {
  return (
    // A scrollable region must be focusable so keyboard users can scroll it (WCAG 2.1.1).
    // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
    <div role="region" aria-label={label} tabIndex={0} className={cn('relative w-full overflow-auto rounded-control', className)} {...props}>
      <table className="w-full border-collapse text-left text-sm">{children}</table>
    </div>
  )
}
