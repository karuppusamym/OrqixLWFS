import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * Polite live region for save state, claim results and validation completion
 * (docs/build/S7-frontend.md section 7). Messages are alternated with a zero-width suffix so repeating
 * the same text is still announced.
 */
type Announce = (message: string) => void

const AnnounceContext = createContext<Announce>(() => undefined)

export function AnnouncerProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('')
  const [flip, setFlip] = useState(false)
  const announce = useCallback((text: string) => {
    setMessage(text)
    setFlip((f) => !f)
  }, [])
  const value = useMemo(() => announce, [announce])
  return (
    <AnnounceContext.Provider value={value}>
      {children}
      <div aria-live="polite" aria-atomic="true" role="status" className="sr-only" data-testid="announcer">
        {message}
        {flip ? '​' : ''}
      </div>
    </AnnounceContext.Provider>
  )
}

export function useAnnounce(): Announce {
  return useContext(AnnounceContext)
}
