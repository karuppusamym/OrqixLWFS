export interface CaseModelItem {
  id:string
  label:string
  kind:'humanTask'|'milestone'|'stage'|'userEventListener'
  group?:string
  required?:boolean
  manual?:boolean
  repetitions?:number
  after?:string
  children?:CaseModelItem[]
}
export function caseItems(items:CaseModelItem[]):CaseModelItem[] { return items.flatMap(item=>[item,...caseItems(item.children??[])]) }
function escape(value:string):string { return value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&apos;') }

/** Generate only the admitted native CMMN constructs; publication still goes through server/engine validation. */
export function caseModelXml(key:string,name:string,items:CaseModelItem[]):string {
  if(!/^[a-z][a-z0-9-]{1,62}$/.test(key)||!name.trim()||name.length>200)throw new Error('Enter a valid case key and name before generating its model.')
  const all=caseItems(items),ids=new Set<string>(),generated=new Set<string>([key,'casePlan'])
  if(all.length<1||all.length>30)throw new Error('Use 1 to 30 plan items.')
  for(const item of all) {
    if(!/^[A-Za-z][A-Za-z0-9_-]{0,39}$/.test(item.id)||ids.has(item.id))throw new Error('Each plan item needs a unique stable ID of at most 40 characters.')
    ids.add(item.id)
    for (const suffix of ['','_item','_entry','_sentry']) { const id=`node_${item.id}${suffix}`;if(generated.has(id))throw new Error('Plan item IDs conflict with generated CMMN references.');generated.add(id) }
    if(!item.label.trim()||item.label.length>120)throw new Error('Every plan item needs a label of at most 120 characters.')
    if(item.kind==='humanTask'&&!/^[a-z][a-z0-9-]{1,62}$/.test(item.group??''))throw new Error('Choose an eligible workspace group for every human task.')
    if(item.kind==='userEventListener'&&!/^[a-z][a-z0-9-]{1,62}$/.test(item.group??''))throw new Error('Choose an authorized workspace group for every user event.')
    if(item.kind==='userEventListener'&&(item.after||item.required||item.manual||item.repetitions))throw new Error('User events cannot have entry criteria, required/manual controls or repetition.')
    if(item.repetitions!==undefined&&(item.kind!=='humanTask'||!Number.isInteger(item.repetitions)||item.repetitions<2||item.repetitions>30||item.after))throw new Error('Repeat only human tasks without entry criteria, from 2 to 30 times.')
    if(item.kind==='milestone'&&item.manual)throw new Error('Milestones cannot use manual activation.')
    if(item.kind!=='stage'&&item.children?.length)throw new Error('Only stages can contain other plan items.')
  }
  const initial=(nodes:CaseModelItem[]):boolean=>nodes.some(item=>!item.after&&(item.kind==='humanTask'||item.kind==='userEventListener'||item.kind==='stage'&&initial(item.children??[])))
  if(!initial(items))throw new Error('Keep a human task, user event or a stage containing one available when the case starts.')
  function plan(nodes:CaseModelItem[],depth:number):string {
    if(depth>4||!nodes.length)throw new Error('Stages need work and support at most four nested levels.')
    const seen=new Map<string,CaseModelItem>()
    let planItems='',definitions='',sentries=''
    for(const item of nodes) {
      const id=`node_${item.id}`,source=item.after?seen.get(item.after):undefined
      if(item.after&&!source)throw new Error('An entry condition must reference an earlier item in the same stage.')
      const controls=(item.repetitions?`<repetitionRule><condition>\${repetitionCounter &lt; ${item.repetitions}}</condition></repetitionRule>`:'')+(item.required?'<requiredRule><condition>${true}</condition></requiredRule>':'')+(item.manual?'<manualActivationRule><condition>${true}</condition></manualActivationRule>':'')
      planItems+=`<planItem id="${id}_item" name="${escape(item.label)}" definitionRef="${id}">${controls?`<itemControl>${controls}</itemControl>`:''}${source?`<entryCriterion id="${id}_entry" sentryRef="${id}_sentry"/>`:''}</planItem>`
      if(source)sentries+=`<sentry id="${id}_sentry"><planItemOnPart sourceRef="node_${source.id}_item"><standardEvent>${source.kind==='milestone'||source.kind==='userEventListener'?'occur':'complete'}</standardEvent></planItemOnPart></sentry>`
      definitions+=item.kind==='stage'?`<stage id="${id}" name="${escape(item.label)}" autoComplete="false">${plan(item.children??[],depth+1)}</stage>`:item.kind==='humanTask'?`<humanTask id="${id}" name="${escape(item.label)}" flowable:candidateGroups="${escape(item.group!)}"/>`:item.kind==='userEventListener'?`<userEventListener id="${id}" name="${escape(item.label)}" authorizedRoleRefs="case_role_${item.group}"/>`:`<milestone id="${id}" name="${escape(item.label)}"/>`
      seen.set(item.id,item)
    }
    return planItems+sentries+definitions
  }
  const eventGroups=[...new Set(all.filter(item=>item.kind==='userEventListener').map(item=>item.group!))]
  const roles=eventGroups.length?`<caseRoles>${eventGroups.map(group=>`<role id="case_role_${group}" name="${escape(group)}"/>`).join('')}</caseRoles>`:''
  const xml=`<?xml version="1.0" encoding="UTF-8"?>\n<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn" targetNamespace="urn:orqix:case-designer"><case id="${key}" name="${escape(name)}"><casePlanModel id="casePlan" name="${escape(name)}" autoComplete="false">${plan(items,0)}</casePlanModel>${roles}</case></definitions>`
  if(new TextEncoder().encode(xml).length>65536)throw new Error('The generated CMMN exceeds 64 KiB.')
  return xml
}
