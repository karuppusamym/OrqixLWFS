import { readFileSync } from 'node:fs'
import { expect,test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet,workspaceId,selectStep } from '../support/app'
import { apiSend,createWorkflow,publish,uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

test('authored languages and responsive tokens reach pinned request and approval views',async({sessions})=>{
  test.skip(MOCK,'Requires publication compatibility and release snapshots.');test.setTimeout(240_000)
  let page=await sessions.as('dana.designer');const ws=await workspaceId(page),workflow=await createWorkflow(page,ws,uniq('Localized'))
  const draft=await apiGet<{definition:{inputSchema:{required:string[];properties:Record<string,{type:string;enum?:string[]}>}}}>(page,`/workspaces/${ws}/workflows/${workflow.id}/draft`)
  const request={schemaVersion:1,kind:'request',sections:[{id:'details',label:'Shared details',columns:2,fields:Object.entries(draft.definition.inputSchema.properties).map(([key,property])=>({id:key,label:`Shared ${key}`,binding:`request.${key}`,type:property.enum?'choice':property.type==='number'?'number':'text',required:draft.definition.inputSchema.required.includes(key),...(property.enum?{options:property.enum.map(value=>({value,label:value}))}:{})}))}],actions:[{id:'submit',type:'submit',label:'Send for review'}]}
  const created=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/views`,{name:uniq('Localized request'),definition:request});expect(created.status).toBe(201)
  await page.goto(`/w/${ws}/views/${created.body.id}`)
  await page.getByRole('button',{name:'Configure presentation',exact:true}).click()
  await page.getByLabel('Two-column breakpoint').selectOption('lg')
  await page.getByLabel('View density').selectOption('compact');await page.getByLabel('View theme').selectOption('neutral')
  await page.getByLabel('New language code').fill('fr');await page.getByRole('button',{name:'Add translated language'}).click()
  for(const [id,label] of Object.entries({details:'Détails partagés',title:'Titre',purpose:'Objet',costCenter:'Centre de coût',amount:'Montant',submit:'Envoyer','costCenter.option.0':'Opérations'}))await page.getByLabel(`Translation for ${id}`,{exact:true}).fill(label)
  await page.getByLabel('View preview').getByLabel('View language').selectOption('fr')
  await expect(page.getByLabel('View preview').getByRole('heading',{name:'Détails partagés'})).toBeVisible()
  await expectNoSeriousViolations(page,'Localized view authoring')
  await page.getByRole('button',{name:'Save draft',exact:true}).click()
  await expect(page.getByText(/Draft revision 2/)).toBeVisible()
  const saved=await apiGet<{definition:typeof request&{presentation:{baseLocale:string;breakpoint:string;density:string;tone:string;translations:Record<string,Record<string,string>>}}}>(page,`/workspaces/${ws}/views/${created.body.id}`)
  const approval={...saved.definition,kind:'approval',actions:[{id:'yes',type:'approve',label:'Authorize'},{id:'no',type:'reject',label:'Decline'}],presentation:{...saved.definition.presentation,translations:{fr:{...saved.definition.presentation.translations.fr,submit:undefined,yes:'Autoriser',no:'Refuser'}}}}
  const review=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/views`,{name:uniq('Localized approval'),definition:approval});expect(review.status).toBe(201)
  page=await sessions.as('paul.publisher')
  expect((await apiSend(page,'POST',`/workspaces/${ws}/views/${created.body.id}/publish`,{expectedRevision:2})).status).toBe(200)
  expect((await apiSend(page,'POST',`/workspaces/${ws}/views/${review.body.id}/publish`,{expectedRevision:1})).status).toBe(200)
  page=await sessions.as('dana.designer');await page.goto(`/w/${ws}/workflows/${workflow.id}/edit`)
  await page.getByRole('tab',{name:'Steps',exact:true}).click();await selectStep(page,'start')
  await page.getByRole('button',{name:'Choose reusable view'}).click();await page.getByLabel('Published request view').selectOption(created.body.id)
  await page.getByRole('button',{name:'Apply selected version'}).click();await expect(page.getByTestId('save-state')).toContainText('Saved')
  await selectStep(page,'manager');await page.getByRole('button',{name:'Choose approval view'}).click();await page.getByLabel('Published approval view').selectOption(review.body.id)
  await page.getByRole('button',{name:'Apply approval view',exact:true}).click();await expect(page.getByTestId('save-state')).toContainText('Saved')
  // "Saved" may still be the previous save's text: wait until the server draft holds both pinned views before publishing.
  await expect.poll(async()=>{const d=await apiGet<{definition:{inputUi?:{viewRef?:{id:string}};nodes:Array<{id:string;config:{viewBinding?:{id:string}}}>}}>(page,`/workspaces/${ws}/workflows/${workflow.id}/draft`)
    return `${d.definition.inputUi?.viewRef?.id ?? ''}|${d.definition.nodes.find(n=>n.id==='manager')?.config.viewBinding?.id ?? ''}`},{timeout:30_000}).toBe(`${created.body.id}|${review.body.id}`)
  page=await sessions.as('paul.publisher');await publish(page,ws,workflow.id)
  page=await sessions.as('rhea.requester');await page.goto(`/w/${ws}/start/${workflow.id}`)
  await page.getByLabel('View language').selectOption('fr')
  const title=uniq('Localized request');await page.getByLabel('Titre',{exact:false}).fill(title)
  await page.getByLabel('Objet',{exact:false}).fill('Verify localized presentation')
  const code=draft.definition.inputSchema.properties.costCenter!.enum![0]!
  await page.getByLabel('Centre de coût',{exact:false}).selectOption(code);await page.getByLabel('Montant',{exact:false}).fill('100')
  await page.getByLabel('View language').selectOption('en');await expect(page.getByLabel('Shared title',{exact:false})).toHaveValue(title)
  await page.getByLabel('View language').selectOption('fr')
  await page.setViewportSize({width:900,height:900})
  const grid=page.getByRole('group',{name:'Détails partagés'}).locator('div.grid').first()
  await expect.poll(()=>grid.evaluate(element=>getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  await page.setViewportSize({width:1200,height:900})
  await expect.poll(()=>grid.evaluate(element=>getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(2)
  await page.getByRole('button',{name:'Review request',exact:true}).click();await expect(page.getByRole('definition').filter({hasText:'Opérations'})).toBeVisible()
  await page.setViewportSize({width:390,height:844});await expectNoSeriousViolations(page,'Localized request mobile review')
  const response=page.waitForResponse(response=>response.url().endsWith(`/workspaces/${ws}/runs`)&&response.request().method()==='POST')
  await page.getByRole('button',{name:'Envoyer',exact:true}).click();const started=await (await response).json() as {id:string}
  await expect(page.getByRole('heading',{name:'Request submitted'})).toBeVisible()
  const run=await apiGet<{input:{costCenter:string};tasks:Array<{id:string;nodeId:string}>}>(page,`/workspaces/${ws}/runs/${started.id}`)
  expect(run.input.costCenter).toBe(code)
  page=await sessions.as('max.manager');await page.goto(`/w/${ws}/tasks/${run.tasks.find(task=>task.nodeId==='manager')!.id}?view=claimable`)
  await page.getByLabel('View language').selectOption('fr');await expect(page.getByRole('region',{name:'Détails partagés'})).toContainText(title)
  await expect(page.getByRole('button',{name:'Autoriser',exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'Claim task',exact:true}).click();await expect(page.getByRole('button',{name:'Autoriser',exact:true})).toBeVisible()
  await page.setViewportSize({width:390,height:844});await expectNoSeriousViolations(page,'Localized approval mobile')
  await page.getByLabel('Comment',{exact:false}).fill('Synthetic localized refusal');await page.getByRole('button',{name:'Refuser',exact:true}).click()
  await expect(page.getByTestId('task-banner')).toContainText('You rejected')
})

test('localized case choices preserve stored values and current correction permissions',async({sessions})=>{
  test.skip(MOCK,'Requires immutable case snapshots and current data grants.');test.setTimeout(180_000)
  let page=await sessions.as('dana.designer');const ws=await workspaceId(page),key=uniq('localized-case')
  const view={schemaVersion:1,kind:'case',sections:[{id:'details',label:'Case details',columns:2,fields:[{id:'priority',label:'Priority',binding:'case.priority',type:'choice',required:true,options:[{value:'normal',label:'normal'},{value:'urgent',label:'urgent'}]}]}],actions:[{id:'save',type:'saveData',label:'Save case data'}],presentation:{baseLocale:'en',breakpoint:'lg',density:'compact',tone:'brand',translations:{fr:{details:'Preuves',priority:'Priorité','priority.option.0':'Normale','priority.option.1':'Urgente',save:'Enregistrer'}}}}
  const created=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/views`,{name:uniq('Localized case'),definition:view});expect(created.status).toBe(201)
  page=await sessions.as('paul.publisher');expect((await apiSend(page,'POST',`/workspaces/${ws}/views/${created.body.id}/publish`,{expectedRevision:1})).status).toBe(200)
  page=await sessions.as('dana.designer')
  const sourceXml=readFileSync(new URL('../../../backend/app/src/test/resources/cases/manual-required.cmmn',import.meta.url),'utf8').replaceAll('lifecycleCase',key)
  const definition=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/cases/definitions`,{key,name:key,sourceXml,viewRef:{id:created.body.id,version:1},dataFields:[{key:'priority',label:'Priority',type:'choice',required:true,options:['normal','urgent']}]});expect(definition.status).toBe(201)
  page=await sessions.as('rhea.requester');await page.goto(`/w/${ws}/cases`)
  await page.getByRole('row').filter({has:page.getByRole('cell',{name:key,exact:true})}).getByRole('button',{name:'Start case',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'Start case',exact:true});await dialog.getByLabel('View language').selectOption('fr')
  await dialog.getByLabel('Priorité (required)').selectOption('urgent');await dialog.getByRole('button',{name:'Start case with data'}).click()
  await expect(page).toHaveURL(new RegExp(`/w/${ws}/cases/[^/]+$`));const url=page.url(),id=url.split('/').at(-1)!
  await page.getByRole('button',{name:'Show case data'}).click();await page.getByLabel('View language').selectOption('fr')
  await expect(page.getByLabel('Priorité (required)')).toHaveValue('Urgente');await expect(page.getByRole('button',{name:'Enregistrer',exact:true})).toHaveCount(0)
  page=await sessions.as('mani.manager');await page.goto(url);await page.getByRole('button',{name:'Show case data'}).click();await page.getByLabel('View language').selectOption('fr')
  await page.getByLabel('Priorité (required)').selectOption('normal');await page.getByLabel('Reason for data change').fill('Synthetic localized correction')
  await page.getByRole('button',{name:'Enregistrer',exact:true}).click();await expect(page.getByText(/Case version 2/).first()).toBeVisible()
  const data=await apiGet<{values:{priority:string}}>(page,`/workspaces/${ws}/cases/instances/${id}/data`);expect(data.values.priority).toBe('normal')
  await page.setViewportSize({width:390,height:844});await expectNoSeriousViolations(page,'Localized case data mobile')
})

test('worklist language and authored table breakpoint preserve authorized navigation',async({sessions})=>{
  test.skip(MOCK,'Requires the workspace presentation snapshot.');test.setTimeout(120_000)
  let page=await sessions.as('dana.designer');const ws=await workspaceId(page)
  const view={schemaVersion:1,kind:'worklist',sections:[{id:'queue',label:'Review queue',columns:1,fields:[{id:'title',label:'Request',type:'text',binding:'task.requestTitle'}]}],actions:[{id:'open',label:'Open task',type:'openTask'}],presentation:{baseLocale:'en',breakpoint:'md',density:'compact',tone:'neutral',translations:{fr:{queue:'Travail',title:'Demande',open:'Ouvrir'}}}}
  const created=await apiSend<{id:string}>(page,'POST',`/workspaces/${ws}/views`,{name:uniq('Localized queue'),definition:view});expect(created.status).toBe(201)
  page=await sessions.as('paul.publisher');expect((await apiSend(page,'POST',`/workspaces/${ws}/views/${created.body.id}/publish`,{expectedRevision:1})).status).toBe(200)
  const prior=await apiGet<{revision:number;binding:{id:string;version:number}|null}>(page,`/workspaces/${ws}/worklist-view`)
  try {
    expect((await apiSend(page,'PUT',`/workspaces/${ws}/worklist-view`,{expectedRevision:prior.revision,viewRef:{id:created.body.id,version:1}})).status).toBe(200)
    page=await sessions.as('max.manager');await page.setViewportSize({width:900,height:900});await page.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
    await page.getByLabel('View language').selectOption('fr');await expect(page.getByRole('columnheader',{name:'Demande',exact:true})).toBeVisible()
    await page.setViewportSize({width:390,height:844});await expect(page.getByRole('list',{name:'Travail',exact:true})).toBeVisible();await expect(page.getByRole('columnheader',{name:'Demande',exact:true})).toHaveCount(0)
    await expectNoSeriousViolations(page,'Localized worklist mobile')
  } finally {
    page=await sessions.as('paul.publisher');const current=await apiGet<{revision:number}>(page,`/workspaces/${ws}/worklist-view`)
    expect((await apiSend(page,'PUT',`/workspaces/${ws}/worklist-view`,{expectedRevision:current.revision,viewRef:prior.binding?{id:prior.binding.id,version:prior.binding.version}:null})).status).toBe(200)
  }
})
