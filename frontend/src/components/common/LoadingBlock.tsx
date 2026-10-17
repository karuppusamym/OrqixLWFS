import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'

/** Skeleton rows inside a status region, so assistive technology hears "Loading" once. */
export function LoadingBlock({ rows = 3, label = 'Loading', className }: { rows?: number; label?: string; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={cn('space-y-3', className)}>
      <span className="sr-only">{label}…</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-14 w-full" />
      ))}
    </div>
  )
}
