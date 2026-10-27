import { Search, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { Dialog } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export interface Destination { to: string; label: string }

/**
 * Ctrl+K (Cmd+K on Mac) "Ask Orqix": jump to a page you may open, or send a question to the Assistant. The question
 * travels in router state, not in the address, and only fills the Assistant's message box: nothing is sent until the
 * person presses Send there.
 */
export function CommandPalette({ destinations, assistantPath }: { destinations: Destination[]; assistantPath: string }) {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const navigate = useNavigate()

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setOpen((v) => !v)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const q = text.trim().toLowerCase()
  const matches = useMemo(() => (q ? destinations.filter((d) => d.label.toLowerCase().includes(q)) : destinations), [destinations, q])
  const close = () => { setOpen(false); setText('') }
  const go = (to: string, state?: unknown) => { close(); void navigate(to, state ? { state } : undefined) }
  const ask = (event: FormEvent) => {
    event.preventDefault()
    if (text.trim()) go(assistantPath, { ask: text.trim() })
  }

  return (
    <Dialog open={open} onClose={close} title="Ask Orqix" description="Type a question for the Assistant, or the name of a page to open.">
      <form onSubmit={ask} className="space-y-3">
        <div className="space-y-1.5">
          <Label htmlFor="command-input">Question or page</Label>
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input id="command-input" className="pl-9" value={text} maxLength={500} autoComplete="off" onChange={(e) => setText(e.target.value)} />
          </div>
        </div>
        {text.trim() ? <Button type="submit" variant="primary"><Sparkles aria-hidden="true" /> Ask the Assistant</Button> : null}
        <ul className="space-y-1" aria-label="Pages">
          {matches.map((d) => (
            <li key={d.to}><Button type="button" variant="ghost" className="w-full justify-start" onClick={() => go(d.to)}>{d.label}</Button></li>
          ))}
          {matches.length === 0 ? <li className="text-sm text-muted-foreground">No page matches. Press Enter to ask the Assistant.</li> : null}
        </ul>
      </form>
    </Dialog>
  )
}
