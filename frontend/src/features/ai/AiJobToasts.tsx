import { useQuery } from '@tanstack/react-query'
import { Loader2, Sparkles, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router'
import { ProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { describeProblem } from '@/lib/problemMessages'
import { Button } from '@/components/ui/button'
import { aiKeys, aiPolling, getAiGeneration, isFinished, jobProblem, seedConversation } from './api'
import { untrackJob, useTrackedJobs, type TrackedJob } from './jobs'
import { saveReviewNotes } from './reviewNotes'

/**
 * Background AI drafts (S10 section 6): when the person closes "Describe it" while a draft is still being generated,
 * the draft keeps going and this stack, shown on every page of the workspace, tells them how it is doing and offers
 * "Open draft" once it is ready. Nothing opens by itself; the draft is never published.
 */
export function AiJobToasts() {
  const { workspace } = useWorkspace()
  const jobs = useTrackedJobs(workspace.id)
  if (jobs.length === 0) return null
  return (
    <div className="pointer-events-none fixed right-4 bottom-4 z-40 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-3" data-testid="ai-job-toasts">
      {jobs.map((job) => (
        <JobToast key={job.id} job={job} />
      ))}
    </div>
  )
}

function JobToast({ job }: { job: TrackedJob }) {
  const announce = useAnnounce()
  const navigate = useNavigate()
  const announced = useRef(false)
  const query = useQuery({
    queryKey: aiKeys.generation(job.ws, job.id),
    queryFn: () => getAiGeneration(job.ws, job.id),
    refetchInterval: (q) => (q.state.data && isFinished(q.state.data) ? false : aiPolling.intervalMs),
    retry: (count, error) => !(error instanceof ProblemError && error.status === 404) && count < 5,
    gcTime: 0,
  })
  const data = query.data
  const gone = query.isError && query.error instanceof ProblemError && query.error.status === 404
  const succeeded = data?.status === 'succeeded'
  const failed = data?.status === 'failed'

  useEffect(() => {
    if (announced.current || !data || !isFinished(data)) return
    announced.current = true
    announce(data.status === 'succeeded' ? `Your draft ${job.label} is ready.` : `The draft ${job.label} could not be created.`)
  }, [data, announce, job.label])

  const open = async () => {
    if (!data?.result) return
    saveReviewNotes(job.ws, data.result)
    untrackJob(job.id)
    // The description is not kept in the browser, so the conversation starts with the assistant's assumptions (S124).
    await seedConversation(job.ws, data.result.workflow.id, undefined, data.result)
    navigate(`/w/${job.ws}/workflows/${data.result.workflow.id}/edit`)
  }

  return (
    <div className="pointer-events-auto rounded-card border border-border bg-surface p-4 text-sm shadow-bar" data-testid="ai-job-toast" role="status">
      <div className="flex items-start gap-3">
        {succeeded || failed || gone ? (
          <Sparkles className="mt-0.5 size-5 shrink-0 text-primary-text" aria-hidden="true" />
        ) : (
          <Loader2 className="mt-0.5 size-5 shrink-0 animate-spin text-primary-text" aria-hidden="true" />
        )}
        <div className="min-w-0 flex-1">
          {succeeded ? (
            <>
              <p className="font-semibold">Your draft is ready</p>
              <p className="truncate text-muted-foreground">{data?.result?.workflow.name ?? job.label}</p>
              <div className="mt-2 flex gap-2">
                <Button size="sm" variant="primary" onClick={() => void open()}>
                  Open draft
                </Button>
              </div>
            </>
          ) : failed && data ? (
            <>
              <p className="font-semibold">The draft could not be created</p>
              <p className="mt-0.5 text-muted-foreground">{describeProblem(jobProblem(data)).message}</p>
            </>
          ) : gone ? (
            <p className="font-semibold">This draft is no longer available</p>
          ) : (
            <>
              <p className="font-semibold">Drafting “{job.label}”</p>
              <p className="text-muted-foreground">We&apos;ll let you know when the draft is ready. You can keep working.</p>
            </>
          )}
        </div>
        {succeeded || failed || gone ? (
          <Button size="icon" variant="ghost" className="-mr-2 -mt-1" onClick={() => untrackJob(job.id)} aria-label="Dismiss this notice">
            <X aria-hidden="true" />
          </Button>
        ) : null}
      </div>
    </div>
  )
}
