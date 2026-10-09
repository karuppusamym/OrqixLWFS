import { useEffect } from 'react'
import type { DraftSaver } from '@/stores/draftSaver'
import type { StudioStore } from '@/stores/studioStore'

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return !!target.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]')
}

/**
 * Whether native text editing owns Ctrl+Z here. A select has no text to undo, and an inspector field whose text was
 * already committed (no pending draft) has nothing left for the browser to undo, so the studio's own undo applies.
 */
function ownsTextUndo(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.closest('select')) return false
  const field = target.closest<HTMLElement>('input, textarea')
  if (field?.hasAttribute('data-commit')) return field.hasAttribute('data-dirty')
  return isEditable(target)
}

/**
 * Studio keyboard shortcuts (docs/build/S7-frontend.md section 5):
 * Ctrl/Cmd+Z undo, Ctrl/Cmd+Shift+Z or Ctrl+Y redo, Delete/Backspace remove the selection (with confirmation when
 * references exist), Ctrl/Cmd+S saves now. Inside text fields only Save is handled, so native text undo keeps working.
 */
export function useStudioShortcuts({ store, saver, readOnly, onDelete }: { store: StudioStore; saver: DraftSaver; readOnly: boolean; onDelete: () => void }): void {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const mod = event.ctrlKey || event.metaKey
      const key = event.key.toLowerCase()

      if (mod && key === 's') {
        event.preventDefault()
        // Blur first so an inline edit commits before saving.
        if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
        void saver.flush()
        return
      }
      if (document.querySelector('dialog[open]')) return
      const undoKey = mod && key === 'z'
      const redoKey = (mod && key === 'z' && event.shiftKey) || (event.ctrlKey && key === 'y')
      if (undoKey || redoKey) {
        if (ownsTextUndo(event.target)) return
      } else if (isEditable(event.target)) {
        return
      }

      if (mod && key === 'z' && !event.shiftKey) {
        event.preventDefault()
        if (!readOnly) store.getState().undo()
      } else if ((mod && key === 'z' && event.shiftKey) || (event.ctrlKey && key === 'y')) {
        event.preventDefault()
        if (!readOnly) store.getState().redo()
      } else if (!readOnly && !mod && (event.key === 'Delete' || event.key === 'Backspace') && store.getState().selection) {
        // Do not hijack Backspace on buttons or links the user is activating.
        if (event.target instanceof HTMLElement && event.target.closest('button, a, [role="tab"], [role="radio"], [role="checkbox"]') && event.key === 'Backspace') return
        event.preventDefault()
        onDelete()
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [store, saver, readOnly, onDelete])
}
