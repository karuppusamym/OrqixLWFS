import type { ComponentProps } from 'react'
import { cn } from '@/lib/cn'

const control =
  'block w-full rounded-control border border-input-border bg-surface px-3 text-base text-foreground shadow-none file:mr-3 file:cursor-pointer file:rounded-control file:border-0 file:bg-neutral-soft file:px-3 file:py-1 file:text-sm file:font-semibold file:text-foreground hover:file:bg-neutral-hover disabled:bg-surface-muted disabled:text-muted-foreground aria-[invalid=true]:border-2 aria-[invalid=true]:border-danger'

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return <input className={cn(control, 'h-10 [&[type=file]]:h-auto [&[type=file]]:py-2', className)} {...props} />
}

export function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return <textarea className={cn(control, 'min-h-24 py-2', className)} {...props} />
}

/** Native select: best keyboard, screen reader and mobile behavior, and no style injection under a strict CSP. */
export function Select({ className, children, ...props }: ComponentProps<'select'>) {
  return (
    <select className={cn(control, 'h-10 pr-8', className)} {...props}>
      {children}
    </select>
  )
}
