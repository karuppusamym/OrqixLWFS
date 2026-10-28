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

function describeError(code: string): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'The microphone is blocked for this site. Click the lock icon in the address bar, allow the microphone, and try again.'
    case 'audio-capture':
      return 'No microphone was found. Connect one and try again.'
    case 'network':
      return "The browser's speech service could not be reached. Dictation needs an internet connection and a browser that allows it (Chrome and Edge do; some networks block it). You can type instead."
    case 'no-speech':
      return 'No speech was heard. Press Talk and speak after it says Listening.'
    case 'aborted':
      return 'Dictation was interrupted.'
    case 'language-not-supported':
      return "The browser does not support dictation in this page's language."
    case 'ended-at-once':
      return 'Dictation stopped straight away. Check that this site may use the microphone (the lock icon in the address bar) and that no other app is using it.'
    default:
      return `Dictation stopped (${code}). You can type instead.`
  }
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
        if (result?.isFinal) {
          heard = true
          onTextRef.current(result[0].transcript.trim())
        }
      }
    }
    let startedAt = 0
    let heard = false
    let failed = false
    rec.onerror = (event) => {
      failed = true
      setProblem(describeError(event.error))
    }
    rec.onend = () => {
      setListening(false)
      recognition.current = null
      // Ended at once with no error and no words: the browser refused quietly (often a blocked microphone).
      if (!failed && !heard && Date.now() - startedAt < 1500) setProblem(describeError('ended-at-once'))
    }
    recognition.current = rec
    try {
      startedAt = Date.now()
      rec.start()
      setListening(true)
    } catch {
      setProblem('Dictation could not start.')
    }
  }, [])

  return { supported, listening, problem, start, stop }
}
