import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { isProblemError } from '@/api/problem'
import { AnnouncerProvider } from '@/components/common/Announcer'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        refetchOnWindowFocus: true,
        // Client errors (4xx) are answers, not outages: do not retry them.
        retry: (count, error) => !(isProblemError(error) && error.status >= 400 && error.status < 500) && count < 2,
      },
    },
  })
}

export function Providers({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [queryClient] = useState(() => client ?? createQueryClient())
  return (
    <QueryClientProvider client={queryClient}>
      <AnnouncerProvider>{children}</AnnouncerProvider>
    </QueryClientProvider>
  )
}
