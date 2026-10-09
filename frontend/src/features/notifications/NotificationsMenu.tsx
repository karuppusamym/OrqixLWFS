import { Bell, BellRing } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useMarkNotificationRead, useNotifications } from '@/api/queries'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Button } from '@/components/ui/button'
import { useWsId } from '@/app/workspace'
import { cn } from '@/lib/cn'

/**
 * Notifications bell (listMyNotifications, polled every 60 s and on window focus).
 * In R0 only in-app notifications exist; the UI never claims email or chat delivery.
 */
export function NotificationsMenu({ buttonClassName }: { buttonClassName?: string } = {}) {
  const ws = useWsId()
  const [open, setOpen] = useState(false)
  const query = useNotifications(ws)
  const markRead = useMarkNotificationRead(ws)
  const unread = query.data?.unreadCount ?? 0
  const items = query.data?.items ?? []

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'} className={cn('relative', buttonClassName)}>
          {unread > 0 ? <BellRing aria-hidden="true" /> : <Bell aria-hidden="true" />}
          {unread > 0 ? (
            <span className="absolute -top-0.5 -right-0.5 flex min-w-5 items-center justify-center rounded-full bg-accent px-1 text-[11px] leading-5 font-bold text-accent-foreground" aria-hidden="true">
              {unread > 99 ? '99+' : unread}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent aria-label="Notifications">
        <div className="border-b border-border px-4 py-3">
          <h2 className="text-base">Notifications</h2>
        </div>
        {query.isPending ? (
          <p className="px-4 py-6 text-sm text-muted-foreground">Loading…</p>
        ) : query.isError ? (
          <p className="px-4 py-6 text-sm text-danger">Notifications could not be loaded.</p>
        ) : items.length === 0 ? (
          <p className="px-4 py-6 text-sm text-muted-foreground">You have no notifications.</p>
        ) : (
          <ul className="max-h-96 divide-y divide-border overflow-y-auto">
            {items.map((n) => {
              const isUnread = !n.readAt
              const body = (
                <>
                  <span className={cn('block text-sm', isUnread ? 'font-semibold' : 'font-normal')}>
                    {isUnread ? <span className="sr-only">Unread: </span> : null}
                    {n.message}
                  </span>
                  <span className="mt-0.5 block text-xs text-muted-foreground">
                    {n.runReference ? <span className="font-semibold tabular-nums">{n.runReference}</span> : null}
                    {n.runReference ? ' · ' : ''}
                    {n.kind === 'runAttention' ? 'Needs attention · ' : ''}
                    <RelativeTime value={n.createdAt} />
                  </span>
                </>
              )
              return (
                <li key={n.id} className={cn(isUnread && 'bg-accent-soft')}>
                  {n.runId ? (
                    <Link
                      to={`/w/${ws}/runs/${n.runId}`}
                      className="block px-4 py-3 text-foreground no-underline hover:bg-black/5"
                      onClick={() => {
                        if (isUnread) markRead.mutate(n.id)
                        setOpen(false)
                      }}
                    >
                      {body}
                    </Link>
                  ) : (
                    <button type="button" className="block w-full px-4 py-3 text-left hover:bg-black/5" onClick={() => isUnread && markRead.mutate(n.id)}>
                      {body}
                    </button>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  )
}
