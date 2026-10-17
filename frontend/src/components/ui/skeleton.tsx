import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/** Loading placeholder. Decorative: the surrounding region announces loading state. */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden="true" className={cn('animate-pulse rounded-control bg-skeleton', className)} {...props} />
}
