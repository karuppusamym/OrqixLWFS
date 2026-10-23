import type { ViewDefinition } from './model'

export function viewLocales(view?:ViewDefinition):string[] {
  return view?.presentation?[view.presentation.baseLocale,...Object.keys(view.presentation.translations)]:[]
}
export function localizedView(view:ViewDefinition,locale:string):ViewDefinition {
  const labels=view.presentation?.translations[locale]??{}
  const text=(id:string,fallback:string)=>Object.hasOwn(labels,id)?labels[id]!:fallback
  return {...view,sections:view.sections.map(section=>({...section,label:text(section.id,section.label),fields:section.fields.map(field=>({...field,label:text(field.id,field.label),...(field.options?{options:field.options.map((option,index)=>({...option,label:text(`${field.id}.option.${index}`,option.label)}))}:{})}))})),actions:view.actions.map(action=>({...action,label:text(action.id,action.label)}))}
}
export function viewGrid(view:ViewDefinition|undefined,columns:number,fallback:'sm'|'md'|'lg'='sm'):string {
  const breakpoint=view?.presentation?.breakpoint??fallback
  const grid={sm:'sm:grid-cols-2',md:'md:grid-cols-2',lg:'lg:grid-cols-2'}[breakpoint]
  return `grid ${view?.presentation?.density==='compact'?'gap-2':'gap-4'} ${columns===2?grid:''}`
}
export function viewSurface(view?:ViewDefinition):string {
  if(!view?.presentation)return ''
  return `rounded-card border p-4 ${view.presentation.tone==='brand'?'border-l-4 border-l-primary':'border-border'} ${view.presentation.density==='compact'?'space-y-2':'space-y-5'}`
}
