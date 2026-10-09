import * as TabsPrimitive from '@radix-ui/react-tabs'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/cn'

export const Tabs = TabsPrimitive.Root

export function TabsList({ className, ...props }: ComponentProps<typeof TabsPrimitive.List>) {
  return <TabsPrimitive.List className={cn('flex flex-wrap items-center gap-2', className)} {...props} />
}

export function TabsTrigger({ className, ...props }: ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn(
        'inline-flex min-h-10 items-center gap-2 rounded-full bg-neutral-soft px-4 py-1 text-sm font-semibold text-muted-foreground hover:bg-neutral-hover data-[state=active]:bg-primary-soft data-[state=active]:text-primary-text data-[state=active]:ring-1 data-[state=active]:ring-primary-soft-border',
        className,
      )}
      {...props}
    />
  )
}

export function TabsContent({ className, ...props }: ComponentProps<typeof TabsPrimitive.Content>) {
  return <TabsPrimitive.Content className={cn('mt-4 outline-offset-4', className)} {...props} />
}
