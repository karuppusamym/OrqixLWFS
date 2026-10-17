import { render,screen,within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient,QueryClientProvider } from '@tanstack/react-query'
import { http,HttpResponse } from 'msw'
import { beforeEach,describe,expect,it,vi } from 'vitest'
import type { RunDetail,RunEvent } from '@/api/types'
import { server } from '@/test/server'
import { IncidentCorrectionPanel } from './IncidentCorrectionPanel'
import { IncidentCorrectionEvidence } from './IncidentCorrectionEvidence'
const mocked=vi.hoisted(()=>({can:vi.fn(()=>true)}))
vi.mock('@/app/workspace',()=>({useWorkspace:()=>({workspace:{id:'workspace'},can:mocked.can})}))
const schema={type:'object',additionalProperties:false,required:['title','amount'],properties:{title:{type:'string',title:'Title',incidentEditable:true},amount:{type:'number',title:'Amount'}}}
const run=():RunDetail=>({id:'run',lifecycle:'needsAttention',version:3,input:{title:'Original title',amount:10},tasks:[],effects:[]} as unknown as RunDetail)
function setup(value=run()) {
  return render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})}><IncidentCorrectionPanel run={value} inputSchema={schema} /></QueryClientProvider>)
}
describe('incident input correction',()=>{
  beforeEach(()=>mocked.can.mockReturnValue(true))
  it('sends only changed editable fields with version, reason and a stable idempotency header, without retry',async()=>{
    const seen:{body:unknown;key:string|null}[]=[]
    server.use(http.post('*/api/v1/workspaces/:ws/runs/:run/correct-input',async({request})=>{
      seen.push({body:await request.json(),key:request.headers.get('Idempotency-Key')})
      return HttpResponse.json({runId:'run',version:4,fields:1,replayed:false})
    }))
    setup();const user=userEvent.setup()
    expect(screen.queryByLabelText(/^Amount/)).not.toBeInTheDocument()
    await user.clear(screen.getByLabelText(/^Title/));await user.type(screen.getByLabelText(/^Title/),'Corrected title')
    await user.type(screen.getByLabelText(/^Correction reason/),'Remove unsupported character')
    await user.click(screen.getByRole('button',{name:'Save corrected input'}))
    expect(await screen.findByText(/Input corrected. The request still needs attention/)).toBeInTheDocument()
    expect(seen).toHaveLength(1)
    expect(seen[0]!.body).toEqual({expectedVersion:3,reason:'Remove unsupported character',changes:{title:'Corrected title'}})
    expect(seen[0]!.key).toMatch(/^[A-Za-z0-9._:-]{8,128}$/)
  })
  it('requires a reason and refuses to expose mutation controls without permission or after task history',async()=>{
    const view=setup();const user=userEvent.setup()
    await user.click(screen.getByRole('button',{name:'Save corrected input'}))
    expect(screen.getByText('Add a reason before saving.')).toBeInTheDocument()
    view.unmount();mocked.can.mockReturnValue(false);setup()
    expect(screen.queryByRole('button',{name:'Save corrected input'})).not.toBeInTheDocument()
  })
  it('shows a conservative captured-effect block instead of an editor',()=>{
    const value=run();value.effects=[{}] as RunDetail['effects'];setup(value)
    expect(screen.getByText(/external effect or task history/)).toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'Save corrected input'})).not.toBeInTheDocument()
  })
  it('shows authorized before/after evidence and handles metadata-only history without values',async()=>{
    const event={seq:1,type:'INPUT_CORRECTED',occurredAt:'2026-10-12T01:00:00Z',data:{before:{title:'Original'},after:{title:'Corrected'}}} as RunEvent
    const view=render(<IncidentCorrectionEvidence event={event} />)
    await userEvent.setup().click(screen.getByText('Corrected values'))
    const table=screen.getByRole('table',{name:'Corrected request values'})
    expect(within(table).getByText('Original')).toBeInTheDocument();expect(within(table).getByText('Corrected')).toBeInTheDocument()
    view.rerender(<IncidentCorrectionEvidence event={{...event,data:{fields:1,version:4,detailsRedacted:true}}} />)
    expect(screen.queryByText('Original')).not.toBeInTheDocument()
    expect(screen.getByText(/restricted to people allowed to see request data/)).toBeInTheDocument()
  })
})
