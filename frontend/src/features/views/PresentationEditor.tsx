import { useId,useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input,Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { translationTargets,type ViewDefinition,type ViewPresentation } from './model'

export function PresentationEditor({definition,disabled,onChange}:{definition:ViewDefinition;disabled:boolean;onChange:(value:ViewPresentation|undefined)=>void}) {
  const id=useId(),[newLocale,setNewLocale]=useState(''),[selected,setSelected]=useState('')
  const p=definition.presentation
  const language=selected&&p?.translations[selected]?selected:Object.keys(p?.translations??{})[0]
  const update=(patch:Partial<ViewPresentation>)=>onChange({...p!,...patch})
  const canAdd=/^[a-z]{2,3}(?:-[A-Z]{2})?$/.test(newLocale)&&newLocale!==p?.baseLocale&&!p?.translations[newLocale]&&Object.keys(p?.translations??{}).length<8
  return <fieldset disabled={disabled} className="space-y-3 rounded-card border p-4"><legend className="font-semibold">Responsive layout and languages</legend>
    {!p?<Button type="button" variant="secondary" onClick={()=>onChange({baseLocale:'en',breakpoint:'md',density:'comfortable',tone:'brand',translations:{}})}>Configure presentation</Button>:<>
      <p className="text-xs text-muted-foreground">Settings are pinned with each published version. Translations change labels; stored values and authorized actions stay the same. Missing translations use base labels.</p>
      <div><Label htmlFor={`${id}-base`}>Base language</Label><Input id={`${id}-base`} value={p.baseLocale} maxLength={6} onChange={event=>update({baseLocale:event.target.value})}/></div>
      <div><Label htmlFor={`${id}-breakpoint`}>Two-column breakpoint</Label><Select id={`${id}-breakpoint`} value={p.breakpoint} onChange={event=>update({breakpoint:event.target.value as ViewPresentation['breakpoint']})}><option value="sm">Small (640 px)</option><option value="md">Medium (768 px)</option><option value="lg">Large (1024 px)</option></Select></div>
      <div><Label htmlFor={`${id}-density`}>View density</Label><Select id={`${id}-density`} value={p.density} onChange={event=>update({density:event.target.value as ViewPresentation['density']})}><option value="comfortable">Comfortable</option><option value="compact">Compact</option></Select></div>
      <div><Label htmlFor={`${id}-tone`}>View theme</Label><Select id={`${id}-tone`} value={p.tone} onChange={event=>update({tone:event.target.value as ViewPresentation['tone']})}><option value="brand">Brand accent</option><option value="neutral">Neutral</option></Select></div>
      <div><Label htmlFor={`${id}-new`}>New language code</Label><Input id={`${id}-new`} value={newLocale} placeholder="fr or fr-CA" maxLength={6} onChange={event=>setNewLocale(event.target.value)}/><Button type="button" variant="secondary" disabled={!canAdd} onClick={()=>{update({translations:{...p.translations,[newLocale]:{}}});setSelected(newLocale);setNewLocale('')}}>Add translated language</Button></div>
      {language?<><Label htmlFor={`${id}-language`}>Edit translations</Label><Select id={`${id}-language`} value={language} onChange={event=>setSelected(event.target.value)}>{Object.keys(p.translations).map(locale=><option key={locale} value={locale}>{locale}</option>)}</Select>
        {translationTargets(definition).map(target=><div key={target.id}><Label htmlFor={`${id}-${target.id}`}>Translation for {target.id}</Label><p className="text-xs text-muted-foreground">{target.label}</p><Input id={`${id}-${target.id}`} maxLength={120} value={p.translations[language]?.[target.id]??''} placeholder="Use base label" onChange={event=>{const labels={...p.translations[language]};if(event.target.value==='')delete labels[target.id];else labels[target.id]=event.target.value;update({translations:{...p.translations,[language]:labels}})}}/></div>)}
        <Button type="button" variant="secondary" onClick={()=>{const translations={...p.translations};delete translations[language];update({translations});setSelected('')}}>Remove translated language</Button>
      </>:null}
      <Button type="button" variant="secondary" onClick={()=>onChange(undefined)}>Use standard presentation</Button>
    </>}
  </fieldset>
}
