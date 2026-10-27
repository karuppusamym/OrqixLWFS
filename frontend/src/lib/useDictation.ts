import { useCallback, useEffect, useRef, useState } from 'react'

interface RecognitionResult { readonly isFinal: boolean; readonly 0: { readonly transcript: string } }
interface RecognitionEvent { readonly resultIndex: number; readonly results: ArrayLike<RecognitionResult> }
interface Recognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: RecognitionEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  start: () => void
  stop: () => void
}
type RecognitionConstructor = new () => Recognition

function recognitionConstructor(): RecognitionConstructor | null {
  const w = window as unknown as { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

/**
 * Speech-to-text for a text box, using the browser's own recogniser (Chrome and Edge; where it is missing,
 * `supported` is false and the caller shows no microphone). The words only fill the box: the person still reads
 * and sends them, so voice never changes anything by itself. Some browsers send the audio to their vendor.
 */
export function useDictation(onText: (finalText: string) => void) {
  const [listening, setListening] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const recognition = useRef<Recognition | null>(null)
  const onTextRef = useRef(onText)
  useEffect(() => { onTextRef.current = onText })
  const supported = typeof window !== 'undefined' && recognitionConstructor() !== null

  const stop = useCallback(() => recognition.current?.stop(), [])
  useEffect(() => () => recognition.current?.stop(), [])

  const start = useCallback(() => {
    const Ctor = recognitionConstructor()
    if (!Ctor) return
    setProblem(null)
    const rec = new Ctor()
    rec.lang = document.documentElement.lang || 'en-US'
    rec.continuous = true
    rec.interimResults = false
    rec.onresult = (event) => {
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        if (result?.isFinal) onTextRef.current(result[0].transcript.trim())
      }
    }
    rec.onerror = (event) => {
      setProblem(event.error === 'not-allowed' || event.error === 'service-not-allowed' ? 'The microphone is blocked. Allow it in the browser to dictate.' : event.error === 'no-speech' ? 'No speech heard.' : 'Dictation stopped.')
    }
    rec.onend = () => { setListening(false); recognition.current = null }
    recognition.current = rec
    try {
      rec.start()
      setListening(true)
    } catch {
      setProblem('Dictation could not start.')
    }
  }, [])

  return { supported, listening, problem, start, stop }
}
