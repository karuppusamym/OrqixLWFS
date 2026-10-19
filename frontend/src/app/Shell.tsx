import { Activity, BarChart3, Check, ClipboardList, ChevronsUpDown, FlaskConical, House, Inbox, Layers3, LogOut, Menu, MessageSquare, PanelsTopLeft, Rocket, Settings, Workflow, X } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation, useMatches, useNavigate } from 'react-router'
import { signOut } from '@/api/auth'
import { useTaskCounters } from '@/api/queries'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { NotificationsMenu } from '@/features/notifications/NotificationsMenu'
import { AiJobToasts } from '@/features/ai/AiJobToasts'
import { cn } from '@/lib/cn'
import { initials } from '@/lib/text'
import { useIsDesktop } from '@/lib/useMediaQuery'
import { useWorkspace } from './workspace'

interface RouteHandle {
  fullBleed?: boolean
}

interface NavItem {
  to: string
  label: string
  icon: typeof House
  end?: boolean
  visible: boolean
  badge?: number
}

/**
 * Application shell (docs/build/S7-frontend.md section 1): 224 px navigation (collapsible below 1024 px),
 * top bar with workspace switcher, notifications and user menu, and the test-workspace banner.
 * Navigation items are shown by permission; hiding is convenience and the server enforces.
 */
export function Shell() {
  const { workspace, can, me } = useWorkspace()
  const desktop = useIsDesktop()
  const [navOpenPath, setNavOpenPath] = useState<string | null>(null)
  const location = useLocation()
  // The drawer belongs to the page it was opened on, so navigating closes it.
  const navOpen = navOpenPath === location.pathname
  const pathname = location.pathname
  const setNavOpen = (open: boolean) => setNavOpenPath(open ? pathname : null)
  const matches = useMatches()
  const fullBleed = matches.some((m) => (m.handle as RouteHandle | undefined)?.fullBleed)
  const base = `/w/${workspace.id}`
  const counters = useTaskCounters(workspace.id, can('task.act'))
  const navRef = useRef<HTMLElement>(null)
  const menuButtonRef = useRef<HTMLButtonElement>(null)

  // Drawer: Escape closes it and returns focus to the menu button; focus moves into it when opened.
  useEffect(() => {
    if (!navOpen || desktop) return
    navRef.current?.querySelector<HTMLElement>('a')?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavOpenPath(null)
        menuButtonRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [navOpen, desktop])

  const assigned = counters.data?.assigned ?? 0
  const items: NavItem[] = [
    { to: base, label: 'Home', icon: House, end: true, visible: true },
    { to: `${base}/requests`, label: 'My requests', icon: ClipboardList, visible: true },
    { to: `${base}/assistant`, label: 'Assistant', icon: MessageSquare, visible: true },
    { to: `${base}/tasks`, label: 'My tasks', icon: Inbox, visible: can('task.act'), badge: assigned },
    { to: `${base}/workflows`, label: 'Workflows', icon: Workflow, visible: can('workflow.read', 'workflow.start') },
    { to: `${base}/runs`, label: 'Runs', icon: Activity, visible: can('run.read') },
    { to: `${base}/cases`, label: 'Cases', icon: Layers3, visible: can('workflow.read', 'workflow.start', 'run.read', 'task.act') },
    { to: `${base}/views`, label: 'Views', icon: PanelsTopLeft, visible: can('workflow.read') },
    { to: `${base}/insights`, label: 'Insights', icon: BarChart3, visible: can('run.read') },
    { to: `${base}/promotions`, label: 'Promotions', icon: Rocket, visible: can('workflow.approve-promotion', 'workspace.manage-access', 'workflow.edit') },
    { to: `${base}/settings`, label: 'Settings', icon: Settings, visible: can('workspace.manage-access', 'connection.read') },
  ]

  const navVisible = desktop || navOpen

  return (
    <div className={cn('flex flex-col', fullBleed ? 'h-dvh' : 'min-h-dvh')}>
      <a
        href="#main"
        className="sr-only z-[100] rounded-control bg-surface px-4 py-2 font-semibold text-foreground shadow-lg focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
        onClick={(event) => {
          event.preventDefault()
          const main = document.getElementById('main')
          main?.focus()
          main?.scrollIntoView()
        }}
      >
        Skip to main content
      </a>

      <TopBar
        homeHref={base}
        menuButton={
          !desktop ? (
            <Button
              ref={menuButtonRef}
              variant="ghost"
              size="icon"
              className="text-topbar-foreground hover:bg-topbar-hover active:bg-topbar-hover"
              aria-label="Open navigation"
              aria-expanded={navOpen}
              onClick={() => setNavOpen(true)}
            >
              <Menu aria-hidden="true" />
            </Button>
          ) : null
        }
      />

      <div className="flex min-h-0 flex-1">
        {!desktop && navOpen ? <div className="fixed inset-0 z-40 bg-overlay" aria-hidden="true" onClick={() => setNavOpen(false)} /> : null}

        <nav
          ref={navRef}
          aria-label="Main"
          className={cn(
            'focus-on-nav z-50 flex w-56 shrink-0 flex-col border-r border-nav-border bg-nav text-nav-foreground',
            desktop ? 'sticky top-[calc(3.5rem+var(--topbar-stripe-height))] h-[calc(100dvh-3.5rem-var(--topbar-stripe-height))] self-start' : 'fixed inset-y-0 left-0 shadow-xl',
            !navVisible && 'hidden',
          )}
        >
          {!desktop ? (
            <div className="flex items-center justify-between px-4 pt-3 pb-1">
              <p className="text-lg font-bold text-nav-active-foreground">Orqix</p>
              <Button variant="ghost" size="icon" className="text-nav-foreground hover:bg-nav-hover" aria-label="Close navigation" onClick={() => setNavOpen(false)}>
                <X aria-hidden="true" />
              </Button>
            </div>
          ) : null}
          <ul className="space-y-1 px-3 pt-4">
            {items
              .filter((i) => i.visible)
              .map((item) => (
                <li key={item.to}>
                  <NavLink
                    to={item.to}
                    end={item.end}
                    className={({ isActive }) =>
                      cn(
                        'relative flex min-h-11 items-center gap-3 rounded-control px-3 py-2 text-sm font-medium text-nav-foreground no-underline hover:bg-nav-hover',
                        isActive &&
                          'bg-nav-active font-semibold text-nav-active-foreground before:absolute before:inset-y-2 before:-left-3 before:w-1 before:rounded-r-full before:bg-nav-indicator',
                      )
                    }
                  >
                    <item.icon className="size-5" aria-hidden="true" />
                    <span className="flex-1">{item.label}</span>
                    {item.badge ? (
                      <span className="rounded-full bg-nav-badge px-2 py-0.5 text-xs font-bold text-nav-badge-foreground">
                        {item.badge}
                        <span className="sr-only"> assigned to you</span>
                      </span>
                    ) : null}
                  </NavLink>
                </li>
              ))}
          </ul>
          <div className="mt-auto border-t border-nav-border px-5 py-4">
            <p className="truncate text-sm font-semibold text-nav-active-foreground" title={me.subject.displayName}>{me.subject.displayName}</p>
            <p className="truncate text-xs text-nav-muted" title={workspace.organizationName}>{workspace.organizationName}</p>
          </div>
        </nav>

        <div className="flex min-w-0 flex-1 flex-col">
          {workspace.isTest ? (
            <div className="flex items-center gap-2 border-b border-accent-border bg-accent-soft px-4 py-2 text-sm font-medium text-warning-foreground lg:px-8" data-testid="test-banner">
              <FlaskConical className="size-4 shrink-0" aria-hidden="true" />
              <span>Test workspace &mdash; requests here use test connections.</span>
            </div>
          ) : null}
          <main id="main" tabIndex={-1} className={cn('min-w-0 flex-1 outline-none', fullBleed ? 'min-h-0 overflow-hidden' : 'mx-auto w-full max-w-[1200px] px-4 py-6 lg:px-8 lg:py-8')}>
            <Outlet />
          </main>
          <AiJobToasts />
        </div>
      </div>
    </div>
  )
}

/** Red top bar with the gold stripe (wf brand) or a white bar with a hairline (orqix brand): wordmark, workspace, bell, account. */
function TopBar({ menuButton, homeHref }: { menuButton: ReactNode; homeHref: string }) {
  const { me, workspace } = useWorkspace()
  const navigate = useNavigate()
  const switchTo = (id: string) => {
    if (id !== workspace.id) navigate(`/w/${id}`)
  }
  const other = me.workspaces.length > 1
  const barButton = 'text-topbar-foreground hover:bg-topbar-hover active:bg-topbar-hover'
  return (
    <header className="focus-on-bar sticky top-0 z-30 bg-topbar text-topbar-foreground">
      <div className="flex h-14 items-center gap-1 px-3 lg:gap-2 lg:px-4">
        {menuButton}
        <Link to={homeHref} className="rounded-control px-2 py-1 text-xl font-bold tracking-tight text-topbar-foreground no-underline hover:bg-topbar-hover" aria-label="Orqix home">
          Orqix
        </Link>
        <span className="mx-1 hidden h-6 w-px bg-topbar-foreground/30 sm:block" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          {other ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" className={cn('max-w-full gap-2 px-2', barButton)} aria-label={`Workspace: ${workspace.name}. Switch workspace`}>
                  <span className="truncate font-semibold" title={workspace.name}>{workspace.name}</span>
                  <ChevronsUpDown aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuLabel>Switch workspace</DropdownMenuLabel>
                {me.workspaces.map((w) => (
                  <DropdownMenuItem key={w.id} onSelect={() => switchTo(w.id)}>
                    <span className="flex-1">
                      {w.name}
                      {w.isTest ? <span className="ml-2 text-xs text-muted-foreground">(test)</span> : null}
                      <span className="block text-xs text-muted-foreground">{w.organizationName}</span>
                    </span>
                    {w.id === workspace.id ? <Check className="size-4" aria-label="Current workspace" /> : null}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          ) : (
            <p className="truncate px-2 font-semibold">{workspace.name}</p>
          )}
        </div>
        <NotificationsMenu buttonClassName={barButton} />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" className={cn('gap-2 px-2', barButton)} aria-label={`Account menu for ${me.subject.displayName}`}>
              <span className="flex size-8 items-center justify-center rounded-full bg-topbar-foreground text-xs font-bold text-topbar" aria-hidden="true">
                {initials(me.subject.displayName)}
              </span>
              <span className="hidden max-w-40 truncate sm:inline" title={me.subject.displayName}>{me.subject.displayName}</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuLabel>
              {me.subject.displayName}
              {me.subject.email ? <span className="block font-normal">{me.subject.email}</span> : null}
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem asChild>
              <Link to={`/w/${workspace.id}/requests`} className="text-foreground no-underline">
                My requests
              </Link>
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => signOut()}>
              <LogOut className="size-4" aria-hidden="true" /> Sign out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <div className="topbar-stripe" aria-hidden="true" />
    </header>
  )
}
