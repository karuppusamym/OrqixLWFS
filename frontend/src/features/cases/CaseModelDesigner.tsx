import { useState } from 'react'
import { downloadBlob } from '@/lib/download'
import { useGroups } from '@/api/queries'
import { Button } from '@/components/ui/button'
import { Input,Select,Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Alert } from '@/components/ui/alert'
import { caseItems,caseModelXml,type CaseModelItem } from './caseModel'
import { CmmnPreview } from './CmmnPreview'

export function CaseModelDesigner({ws,caseKey,name,onApply,disabled}:{ws:string;caseKey:string;name:string;onApply:(xml:string|null)=>void;disabled:boolean}) {
  const groups=useGroups(ws)
  const [items,setItems]=useState<CaseModelItem[]>([{id:'task1',kind:'humanTask',label:'Review evidence',required:true,manual:true,group:''}])
  const [history,setHistory]=useState<CaseModelItem[][]>([]),[error,setError]=useState<string>(),[xml,setXml]=useState('')
  const edit=(change:(items:CaseModelItem[])=>CaseModelItem[])=>{setHistory(previous=>[...previous.slice(-49),items]);setItems(change(structuredClone(items)));setXml('');setError(undefined);onApply(null)}
  const nextId=()=>{let index=1;const used=new Set(caseItems(items).map(item=>item.id));while(used.has(`item${index}`))index++;return `item${index}`}
  const replace=(id:string,patch:Partial<CaseModelItem>)=>edit(nodes=>{
    const update=(list:CaseModelItem[]):CaseModelItem[]=>list.map(item=>item.id===id?{...item,...patch}:{...item,children:item.children?update(item.children):undefined})
    return update(nodes)
  })
  const add=(kind:CaseModelItem['kind'],parent?:string)=>edit(nodes=>{
    const item:CaseModelItem={id:nextId(),kind,label:kind==='humanTask'?'New human task':kind==='stage'?'New stage':kind==='userEventListener'?'New user event':'New milestone',...(kind==='humanTask'?{group:'',manual:true}:kind==='userEventListener'?{group:''}:{}),...(kind==='stage'?{children:[]}: {})}
    if(!parent)return [...nodes,item]
    const stage=caseItems(nodes).find(item=>item.id===parent)!;stage.children=[...(stage.children??[]),item];return nodes
  })
  const remove=(id:string)=>edit(nodes=>{
    const removed=new Set(caseItems(nodes).filter(item=>item.id===id).flatMap(item=>caseItems([item])).map(item=>item.id))
    const filter=(list:CaseModelItem[]):CaseModelItem[]=>list.filter(item=>!removed.has(item.id)).map(item=>({...item,after:item.after&&removed.has(item.after)?undefined:item.after,children:item.children?filter(item.children):undefined}))
    return filter(nodes)
  })
  const move=(id:string,delta:-1|1)=>edit(nodes=>{
    const reorder=(list:CaseModelItem[]):CaseModelItem[]=>{
      const index=list.findIndex(item=>item.id===id),target=index+delta
      if(index>=0&&target>=0&&target<list.length){[list[index],list[target]]=[list[target]!,list[index]!];return list}
      return list.map(item=>({...item,children:item.children?reorder(item.children):undefined}))
    }
    return reorder(nodes)
  })
  const render=(nodes:CaseModelItem[],depth=0):React.ReactNode=><ol className="space-y-3">{nodes.map((item,index)=><li key={item.id} className="space-y-3 rounded border border-border bg-surface p-3">
    <p className="text-xs font-semibold uppercase text-muted-foreground">{item.kind==='humanTask'?'Human task':item.kind==='stage'?'Stage':item.kind==='userEventListener'?'User event':'Milestone'} · {item.id}</p>
    <div><Label htmlFor={`case-model-${item.id}-label`}>Name for {item.id}</Label><Input id={`case-model-${item.id}-label`} value={item.label} maxLength={120} onChange={event=>replace(item.id,{label:event.target.value})}/></div>
    {item.kind==='humanTask'||item.kind==='userEventListener'?<div><Label htmlFor={`case-model-${item.id}-group`}>Eligible group for {item.id}</Label><Select id={`case-model-${item.id}-group`} value={item.group??''} onChange={event=>replace(item.id,{group:event.target.value})}><option value="">Choose a group</option>{groups.data?.items.map(group=><option key={group.key} value={group.key}>{group.name}</option>)}</Select></div>:null}
    {item.kind!=='userEventListener'?<div><Label htmlFor={`case-model-${item.id}-after`}>Start after for {item.id}</Label><Select id={`case-model-${item.id}-after`} value={item.after??''} onChange={event=>replace(item.id,{after:event.target.value||undefined,...(event.target.value?{repetitions:undefined}:{})})}><option value="">Available when its stage starts</option>{nodes.slice(0,index).map(source=><option key={source.id} value={source.id}>{source.label} {source.kind==='milestone'||source.kind==='userEventListener'?'occurs':'completes'}</option>)}</Select></div>:null}
    {item.kind!=='userEventListener'?<label className="flex items-center gap-2"><input type="checkbox" checked={!!item.required} onChange={event=>replace(item.id,{required:event.target.checked})}/>Required work {item.id}</label>:null}
    {item.kind!=='milestone'&&item.kind!=='userEventListener'?<label className="flex items-center gap-2"><input type="checkbox" checked={!!item.manual} onChange={event=>replace(item.id,{manual:event.target.checked})}/>Manual activation {item.id}</label>:null}
    {item.kind==='humanTask'?<div><Label htmlFor={`case-model-${item.id}-repeat`}>Executions for {item.id}</Label><Select id={`case-model-${item.id}-repeat`} value={item.repetitions??1} disabled={!!item.after} onChange={event=>replace(item.id,{repetitions:Number(event.target.value)===1?undefined:Number(event.target.value)})}>{Array.from({length:30},(_,i)=><option key={i+1} value={i+1}>{i+1}</option>)}</Select><p className="text-xs text-muted-foreground">Repeated tasks run sequentially. Each occurrence fires its own completion event. Entry conditions require a single execution.</p></div>:null}
    <div className="flex flex-wrap gap-2"><Button type="button" variant="secondary" disabled={index===0} onClick={()=>move(item.id,-1)}>Move {item.id} up</Button><Button type="button" variant="secondary" disabled={index===nodes.length-1} onClick={()=>move(item.id,1)}>Move {item.id} down</Button><Button type="button" variant="secondary" onClick={()=>remove(item.id)}>Remove {item.id}</Button></div>
    {item.kind==='stage'?<div className="space-y-3 border-l-2 pl-3">{render(item.children??[],depth+1)}<div className="flex flex-wrap gap-2"><Button type="button" variant="secondary" disabled={caseItems(items).length>=30} onClick={()=>add('humanTask',item.id)}>Add task to {item.id}</Button><Button type="button" variant="secondary" disabled={caseItems(items).length>=30} onClick={()=>add('milestone',item.id)}>Add milestone to {item.id}</Button><Button type="button" variant="secondary" disabled={caseItems(items).length>=30} onClick={()=>add('userEventListener',item.id)}>Add user event to {item.id}</Button>{depth<3?<Button type="button" variant="secondary" disabled={caseItems(items).length>=30} onClick={()=>add('stage',item.id)}>Add stage to {item.id}</Button>:null}</div></div>:null}
  </li>)}</ol>
  return <fieldset disabled={disabled} className="space-y-4 rounded border p-4"><legend className="font-semibold">Case model designer</legend>
    <p className="text-sm text-muted-foreground">Design human tasks, nested stages, milestones and authorized user events. Human tasks can repeat up to 30 times; events trigger entry conditions. Required work and manual activation use the existing case controls. The server validates the generated CMMN before creating a definition.</p>
    {groups.isError?<Alert tone="danger">Workspace groups could not be loaded. <Button type="button" variant="ghost" onClick={()=>void groups.refetch()}>Retry groups</Button></Alert>:null}
    {render(items)}
    <div className="flex flex-wrap gap-2">{(['humanTask','stage','milestone','userEventListener'] as const).map(kind=><Button type="button" key={kind} variant="secondary" disabled={caseItems(items).length>=30} onClick={()=>add(kind)}>Add {kind==='humanTask'?'human task':kind==='userEventListener'?'user event':kind}</Button>)}<Button type="button" variant="secondary" disabled={!history.length} onClick={()=>{setItems(history.at(-1)!);setHistory(history.slice(0,-1));setXml('');onApply(null)}}>Undo model change</Button><Button type="button" onClick={()=>{try { const generated=caseModelXml(caseKey,name,items);setXml(generated);onApply(generated);setError(undefined) } catch(cause) { setError(cause instanceof Error?cause.message:'The case model is invalid.') }}}>Use generated CMMN</Button></div>
    {error?<Alert tone="danger">{error}</Alert>:null}{xml?<div><Label htmlFor="case-generated-xml">Generated CMMN preview</Label><Textarea id="case-generated-xml" rows={8} readOnly value={xml}/><Button type="button" variant="secondary" onClick={()=>downloadBlob(new Blob([xml],{type:'application/xml'}),`${caseKey}.cmmn`)}>Download generated CMMN</Button></div>:null}
    <CmmnPreview name={name} items={items}/>
  </fieldset>
}
