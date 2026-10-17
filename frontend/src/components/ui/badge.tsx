import { cva, type VariantProps } from 'class-variance-authority'
import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

const badgeVariants = cva('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold [&_svg]:size-3.5', {
  variants: {
    tone: {
      neutral: 'bg-neutral-soft text-muted-foreground',
      primary: 'bg-primary-soft text-primary-text',
      accent: 'bg-accent text-accent-foreground',
      success: 'bg-success-soft text-success-foreground',
      danger: 'bg-danger-soft text-danger-foreground',
      warning: 'bg-warning-soft text-warning-foreground',
      info: 'bg-info-soft text-info-foreground',
    },
  },
  defaultVariants: { tone: 'neutral' },
})

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />
}
