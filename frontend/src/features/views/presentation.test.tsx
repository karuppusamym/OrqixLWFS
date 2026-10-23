import { render,screen,within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe,it,expect } from 'vitest'
import { blankView,pruneTranslations,validateView,type ViewDefinition } from './model'
import { localizedView,viewGrid } from './presentation'
import { ViewPreview } from './ViewPreview'
import { parseFormUi,presentFields,buildFields,parseFormSchema } from '@/lib/form/schema'
import { bindRequestView } from './requestBinding'
import { createView,getView } from '@/api/views'
import { renderAppReady,signInAs,WS } from '@/test/utils'

const configured=():ViewDefinition=>({...blankView('case'),presentation:{baseLocale:'en',breakpoint:'lg',density:'compact',tone:'neutral',translations:{fr:{section1:'Détails',field1:'Résumé'}}}})

describe('reusable view presentation',()=>{
  it('localizes stable labels with fallback while preserving values, bindings and base text',()=>{
    const source=configured();source.sections[0]!.fields[0]={id:'field1',label:'Priority',type:'choice',binding:'case.priority',options:[{value:'urgent',label:'urgent'}]}
    source.presentation!.translations.fr!['field1.option.0']='Prioritaire'
    const translated=localizedView(source,'fr')
    expect(translated.sections[0]!.fields[0]).toMatchObject({label:'Résumé',binding:'case.priority',options:[{value:'urgent',label:'Prioritaire'}]})
    expect(source.sections[0]!.fields[0]!.options![0]!.label).toBe('urgent')
    expect(localizedView(source,'de').sections[0]!.label).toBe('Details')
    expect(viewGrid(source,2)).toContain('lg:grid-cols-2')
    expect(viewGrid(source,2)).toContain('gap-2')
    source.sections[0]!.fields[0]!.id='constructor'
    expect(localizedView(source,'fr').sections[0]!.fields[0]!.label).toBe('Priority')
  })
  it('rejects stale translation targets and prunes only labels removed from the model',()=>{
    const source=configured();source.presentation!.translations.fr!.retired='Old'
    expect(validateView(source).join(' ')).toContain('retired')
    const pruned=pruneTranslations(source)
    expect(pruned.presentation!.translations.fr).toEqual({section1:'Détails',field1:'Résumé'})
    source.presentation!.tone='url(evil)' as 'neutral'
    expect(validateView(source)).not.toHaveLength(0)
    expect(parseFormUi({viewDefinition:{kind:'request',sections:null}}).viewDefinition).toBeUndefined()
  })
  it('switches preview language and preserves narrow single-column rendering',async()=>{
    const user=userEvent.setup(),view=configured();view.sections[0]!.columns=2
    const mounted=render(<ViewPreview definition={view} narrow/>)
    await user.selectOptions(screen.getByLabelText('View language'),'fr')
    expect(screen.getByRole('heading',{name:'Détails'})).toBeInTheDocument()
    const field=screen.getByLabelText('Résumé')
    expect(field.closest('section')!.querySelector('.grid')!.className).not.toContain('grid-cols-2')
    expect(screen.getByLabelText('View preview')).toHaveAttribute('lang','fr')
    mounted.unmount();render(<ViewPreview definition={view}/>)
    expect(screen.getByRole('heading',{name:'Détails'})).toBeInTheDocument()
  })
  it('copies request presentation and translates rendered fields without changing input rules',()=>{
    const schema=parseFormSchema({type:'object',additionalProperties:false,properties:{priority:{type:'string',title:'Priority',enum:['urgent']}},required:['priority']})
    const view=configured();view.kind='request';view.sections[0]!.fields[0]={id:'field1',label:'Priority',type:'choice',binding:'request.priority',required:true,options:[{value:'urgent',label:'urgent'}]};view.actions=[]
    const workflow={inputSchema:schema,nodes:[]} as unknown as Parameters<typeof bindRequestView>[0]
    const bound=bindRequestView(workflow,'11111111-1111-4111-8111-111111111111',1,view)
    const ui=parseFormUi(bound.inputUi),rendered=presentFields(buildFields(schema,ui),localizedView(view,'fr'))
    expect(ui.viewDefinition).toEqual(view);expect(rendered[0]).toMatchObject({label:'Résumé',required:true,schema:{enum:['urgent']}})
    expect(bound.inputSchema.required).toEqual(['priority'])
  })
  it('authors and saves translations through the real editor with current draft revision',async()=>{
    signInAs('dana.designer');const view=await createView(WS,{name:'Localized view',definition:blankView()})
    const user=userEvent.setup();await renderAppReady(`/w/${WS}/views/${view.id}`)
    await user.click(await screen.findByRole('button',{name:'Configure presentation'}))
    await user.type(screen.getByLabelText('New language code'),'fr')
    await user.click(screen.getByRole('button',{name:'Add translated language'}))
    await user.type(screen.getByLabelText('Translation for field1'),'Résumé')
    const preview=screen.getByLabelText('View preview')
    await user.selectOptions(within(preview).getByLabelText('View language'),'fr')
    expect(within(preview).getByLabelText('Résumé')).toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'Save draft'}))
    expect((await getView(WS,view.id)).definition).toMatchObject({presentation:{translations:{fr:{field1:'Résumé'}}}})
  })
})
