import { useId,useMemo,useState } from 'react'
import { readStorage,writeStorage } from '@/lib/storage'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/input'
import type { ViewDefinition } from './model'
import { localizedView,viewLocales } from './presentation'

export function usePresentedView(definition?:ViewDefinition) {
  const [selected,setSelected]=useState<string|undefined>(()=>readStorage('orqix.viewLanguage')??undefined)
  const setLocale=(value:string)=>{setSelected(value);writeStorage('orqix.viewLanguage',value)}
  const locales=viewLocales(definition),base=definition?.presentation?.baseLocale
  const preferred=typeof navigator==='undefined'?undefined:navigator.languages.find(language=>locales.includes(language)||locales.includes(language.split('-')[0]!))
  const locale=selected&&locales.includes(selected)?selected:preferred&&locales.includes(preferred)?preferred:preferred&&locales.includes(preferred.split('-')[0]!)?preferred.split('-')[0]!:base
  const view=useMemo(()=>definition?localizedView(definition,locale??''):undefined,[definition,locale])
  return {view,locale,setLocale}
}
export function ViewLanguage({definition,locale,onChange}:{definition?:ViewDefinition;locale?:string;onChange:(locale:string)=>void}) {
  const id=useId(),locales=viewLocales(definition)
  if(locales.length<2)return null
  return <div lang="en" className="flex flex-wrap items-center gap-2"><Label htmlFor={id}>View language</Label><Select id={id} className="w-auto" value={locale} onChange={event=>onChange(event.target.value)}>{locales.map(language=><option key={language} value={language}>{language}</option>)}</Select></div>
}
