import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/cn'

export const buttonVariants = cva(
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-control border border-transparent text-sm font-semibold whitespace-nowrap disabled:pointer-events-none disabled:opacity-60 [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground hover:bg-primary-hover active:bg-primary-pressed disabled:border-border disabled:bg-neutral-soft disabled:text-muted-foreground disabled:opacity-100',
        secondary: 'border-input-border bg-surface text-foreground hover:bg-surface-muted active:bg-neutral-soft',
        ghost: 'text-foreground hover:bg-neutral-soft active:bg-neutral-hover',
        /* Destructive: a dark crimson outline, never the primary red. Pair it with an icon and explicit text. */
        danger: 'border-danger bg-surface text-danger hover:bg-danger-soft active:bg-danger-soft',
        'danger-solid': 'bg-danger-solid text-white hover:bg-danger-solid-hover',
        link: 'h-auto min-h-0 border-0 px-0 text-primary-text underline underline-offset-2 hover:text-primary-hover',
      },
      size: {
        default: 'min-h-9 px-4 py-1.5',
        sm: 'min-h-8 px-3 py-1 text-sm',
        lg: 'min-h-11 px-5 py-2 text-base',
        icon: 'size-9 p-0',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'default' },
  },
)

export interface ButtonProps extends ComponentProps<'button'>, VariantProps<typeof buttonVariants> {
  asChild?: boolean
  loading?: boolean
}

export function Button({ className, variant, size, asChild, loading, disabled, children, type, ...props }: ButtonProps) {
  const classes = cn(buttonVariants({ variant, size }), className)
  if (asChild) {
    return (
      <Slot className={classes} {...props}>
        {children}
      </Slot>
    )
  }
  return (
    <button
      type={type ?? 'button'}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
      {children}
    </button>
  )
}
