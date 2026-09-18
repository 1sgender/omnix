import { useCallback, useRef, useState } from 'react'
import type { ToastItem } from '../components/ui'

export function useToasts() {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const counter = useRef(0)

  const push = useCallback((kind: 'ok' | 'err', text: string) => {
    const id = ++counter.current
    setToasts((prev) => [...prev, { id, kind, text }])
    window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 4200)
  }, [])

  return { toasts, ok: (t: string) => push('ok', t), err: (t: string) => push('err', t) }
}
