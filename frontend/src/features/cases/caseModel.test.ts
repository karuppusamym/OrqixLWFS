import { describe,it,expect } from 'vitest'
import engineFixture from '../../../../backend/app/src/test/resources/cases/visual-designer.cmmn?raw'
import { caseModelXml,type CaseModelItem } from './caseModel'

const items:CaseModelItem[]=[{id:'review',label:'Review & check',kind:'humanTask',group:'managers',required:true,manual:true},{id:'ready',label:'Ready',kind:'milestone',after:'review'}]
describe('case model native CMMN generation',()=>{
  it('generates native case roles and user occurrence conditions with controls before criteria',()=>{
    const xml=caseModelXml('event-case','Event case',[
      {id:'release',kind:'userEventListener',label:'Release review',group:'finance-approvers'},
      {id:'review',kind:'humanTask',label:'Review',group:'managers',after:'release',required:true,manual:true},
    ])
    expect(xml).toContain('authorizedRoleRefs="case_role_finance-approvers"')
    expect(xml).toContain('<caseRoles><role id="case_role_finance-approvers" name="finance-approvers"/></caseRoles>')
    const document=new DOMParser().parseFromString(xml,'application/xml')
    expect(document.querySelector('parsererror')).toBeNull()
    const review=document.querySelector('[id="node_review_item"]')!
    expect([...review.children].map(child=>child.localName)).toEqual(['itemControl','entryCriterion'])
    expect(xml).toContain('sourceRef="node_release_item"><standardEvent>occur</standardEvent>')
  })
  it('generates finite sequential repetition without allowing listener controls or unbounded expressions',()=>{
    const repeated={...items[0]!,repetitions:3}
    expect(caseModelXml('repeat-case','Repeat case',[repeated])).toContain('<repetitionRule><condition>${repetitionCounter &lt; 3}</condition></repetitionRule>')
    for(const invalid of [
      {...repeated,repetitions:31}, {...repeated,repetitions:1.5}, {...repeated,after:'other'},
      {id:'event',kind:'userEventListener' as const,label:'Event',group:'managers',required:true},
      {id:'event',kind:'userEventListener' as const,label:'Event',group:'managers',repetitions:3},
    ])expect(()=>caseModelXml('repeat-case','Repeat case',[invalid])).toThrow()
  })
  it('preserves stable ids, escaped labels, typed controls and source-linked entry sentries',()=>{
    const xml=caseModelXml('review-case','Review case',items)
    expect(xml).toContain('name="Review &amp; check"')
    expect(xml).toContain('flowable:candidateGroups="managers"')
    expect(xml).toContain('<requiredRule><condition>${true}</condition></requiredRule>')
    expect(xml).toContain('id="node_ready_entry" sentryRef="node_ready_sentry"')
    expect(xml).toContain('sourceRef="node_review_item"><standardEvent>complete</standardEvent>')
    expect(new DOMParser().parseFromString(xml,'application/xml').querySelector('parsererror')).toBeNull()
    expect(caseModelXml('review-case','Review case',structuredClone(items))).toBe(xml)
  })
  it('matches the exact model exercised by the backend engine test',()=>{
    expect(caseModelXml('visual-case','Visual case',[{id:'review',kind:'humanTask',label:'Review evidence',group:'managers',required:true,manual:true},{id:'ready',kind:'milestone',label:'Ready',after:'review'}])).toBe(engineFixture)
  })
  it('generates scoped nested stages and milestone occurrence conditions',()=>{
    const nested:CaseModelItem[]=[{id:'phase',label:'Evidence',kind:'stage',children:[...items,{id:'finish',label:'Finish',kind:'humanTask',group:'managers',after:'ready'}]}]
    const xml=caseModelXml('review-case','Review case',nested)
    expect(xml).toContain('<stage id="node_phase"')
    expect(xml).toContain('sourceRef="node_ready_item"><standardEvent>occur</standardEvent>')
  })
  it('refuses duplicate/colliding ids, missing groups, cycles and cross-stage references',()=>{
    for(const invalid of [
      [...items,{...items[0]!}],
      [...items,{...items[0]!,id:'review_item'}],
      [{...items[0]!,group:''}],
      [{...items[0]!,after:'ready'},items[1]!],
      [items[0]!,{id:'phase',label:'Phase',kind:'stage' as const,children:[{...items[0]!,id:'inner',after:'review'}]}],
    ])expect(()=>caseModelXml('review-case','Review case',invalid)).toThrow()
  })
  it('refuses excessive plans and invalid case keys',()=>{
    expect(()=>caseModelXml('bad/key','Case',items)).toThrow('valid case key')
    expect(()=>caseModelXml('review-case','Case',Array.from({length:31},(_,index)=>({...items[0]!,id:`task${index}`})))).toThrow('1 to 30')
  })
})
