import { expect,test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet,workspaceId } from '../support/app'
import { apiSend,createWorkflow,publish,uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

test('published worklist columns and navigation preserve authorized task actions',async({sessions})=>{
  test.skip(MOCK,'Uses the real workspace presentation pin and task engine.')
  test.setTimeout(180_000)
  let page=await sessions.as('dana.designer');const ws=await workspaceId(page)
  const workflow=await createWorkflow(page,ws,uniq('Worklist'))
  const definition={schemaVersion:1,kind:'worklist',sections:[{id:'tasks',label:'Review queue',columns:1,fields:[{id:'title',label:'Work request',binding:'task.requestTitle',type:'text'},{id:'priority',label:'Urgency',binding:'task.priority',type:'number'}]}],actions:[{id:'open',label:'Review work',type:'openTask'}]}
  const created=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/views`,{name:uniq('Worklist'),definition});expect(created.status).toBe(201)
  page=await sessions.as('paul.publisher');await publish(page,ws,workflow.id)
  expect((await apiSend(page,'POST',`/workspaces/${ws}/views/${created.body.id}/publish`,{expectedRevision:1})).status).toBe(200)
  const prior=await apiGet<{revision:number;binding:{id:string;version:number}|null}>(page,`/workspaces/${ws}/worklist-view`)
  try {
    await page.goto(`/w/${ws}/views/${created.body.id}`)
    await page.getByLabel('Preview version').selectOption('1')
    await page.getByRole('button',{name:'Use selected published version'}).click()
    await expect(page.getByText('Published version applied to My tasks.',{exact:true})).toBeVisible()
    page=await sessions.as('rhea.requester');const title=uniq('Worklist request')
    const started=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/runs`,{workflowId:workflow.id,input:{title,purpose:'Review worklist rendering',costCenter:'OPS',amount:100}});expect(started.status).toBe(201)
    const run=await apiGet<{tasks:Array<{id:string;version:number}>}>(page,`/workspaces/${ws}/runs/${started.body.id}`)
    page=await sessions.as('olga.operator')
    expect((await apiSend(page,'PUT',`/workspaces/${ws}/tasks/${run.tasks[0]!.id}/priority`,{expectedVersion:run.tasks[0]!.version,priority:100,reason:'Synthetic worklist verification'})).status).toBe(200)
    page=await sessions.as('max.manager');await page.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
    await expect(page.getByRole('columnheader',{name:'Work request'})).toBeVisible()
    const open=page.getByRole('button',{name:`Review work: ${title}`,exact:true})
    for(let i=0;i<30 && await open.count()===0;i++) {
      const more=page.getByRole('button',{name:'Load more',exact:true});if(!await more.count())break
      const count=await page.getByRole('row').count();await more.click();await expect.poll(()=>page.getByRole('row').count()).toBeGreaterThan(count)
    }
    await expect(open).toBeVisible();await expectNoSeriousViolations(page,'Configured worklist')
    await page.setViewportSize({width:390,height:844});await expectNoSeriousViolations(page,'Configured worklist mobile')
    await open.click();await expect(page.getByRole('button',{name:'Claim task',exact:true})).toBeVisible()
    await expect(page.getByRole('button',{name:'Approve',exact:true})).toHaveCount(0)
  } finally {
    page=await sessions.as('paul.publisher')
    const current=await apiGet<{revision:number}>(page,`/workspaces/${ws}/worklist-view`)
    expect((await apiSend(page,'PUT',`/workspaces/${ws}/worklist-view`,{expectedRevision:current.revision,viewRef:prior.binding?{id:prior.binding.id,version:prior.binding.version}:null})).status).toBe(200)
  }
})
