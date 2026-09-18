/**
 * Переиспользуемые UI-примитивы дизайн-системы. Ничего лишнего — каждый
 * компонент используется на нескольких страницах.
 */
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react'
import { useEffect, useRef } from 'react'
import { CheckCircle2, ChevronDown, Inbox, Loader2, AlertTriangle, X, XCircle } from 'lucide-react'

/* ── Кнопка ───────────────────────────────────────────────────────────────── */

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'primary' | 'danger' | 'ghost'
  size?: 'md' | 'sm'
  loading?: boolean
}

export function Button({ variant = 'default', size = 'md', loading = false, children, disabled, className = '', ...rest }: ButtonProps) {
  return (
    <button className={`btn ${variant} ${size === 'sm' ? 'sm' : ''} ${loading ? 'loading' : ''} ${className}`} disabled={disabled || loading} {...rest}>
      {loading ? <Loader2 className="spin" aria-hidden /> : null}
      {children}
    </button>
  )
}

/* ── Поля форм ────────────────────────────────────────────────────────────── */

export function Field({ label, hint, error, children, htmlFor }: {
  label: string; hint?: string; error?: string; children: ReactNode; htmlFor?: string
}) {
  return (
    <div className="field">
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? <span className="error-text" role="alert">{error}</span> : hint ? <span className="hint">{hint}</span> : null}
    </div>
  )
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input className="input" {...props} />
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className="select" {...props} />
}

/* ── Статусы ──────────────────────────────────────────────────────────────── */

type Tone = 'ok' | 'warn' | 'err' | 'info' | 'muted'

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>
}

const STATUS_TONES: Record<string, Tone> = {
  ACTIVE: 'ok', READY: 'ok', OK: 'ok', PAID: 'ok', GRANTED: 'ok', SUCCESS: 'ok', HEALTHY: 'ok',
  ISSUED: 'info', LOADING: 'info', PENDING: 'info',
  EXPIRED: 'warn', PAST_DUE: 'warn', HALF_OPEN: 'warn', DEGRADED: 'warn', SLOW: 'warn',
  DISABLED: 'muted', REVOKED: 'muted', CANCELED: 'muted', REFUNDED: 'muted', CLOSED: 'muted', UNKNOWN: 'muted',
  OPEN: 'err', DOWN: 'err', ERROR: 'err', FAILED: 'err', PERMANENTLY_DISABLED: 'err'
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={STATUS_TONES[status] ?? 'muted'}>{status}</Badge>
}

/* ── Состояния данных ─────────────────────────────────────────────────────── */

export function SkeletonRows({ rows = 6, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="table-wrap" aria-busy="true" aria-label="Загрузка">
      <div style={{ display: 'grid', gap: 10, padding: 18 }}>
        {Array.from({ length: rows }, (_, r) => (
          <div key={r} style={{ display: 'grid', gridTemplateColumns: `repeat(${cols}, 1fr)`, gap: 14 }}>
            {Array.from({ length: cols }, (_, c) => (
              <div key={c} className="skeleton" style={{ height: 14 }} />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}

export function PageLoader() {
  return <div className="page-loader" role="status" aria-label="Загрузка страницы"><Loader2 className="spinner lg" /></div>
}

export function EmptyState({ title, desc }: { title: string; desc?: string }) {
  return (
    <div className="center-state">
      <Inbox aria-hidden />
      <div className="title">{title}</div>
      {desc ? <div className="desc">{desc}</div> : null}
    </div>
  )
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const message = typeof error === 'object' && error !== null && 'message' in error
    ? String((error as { message: unknown }).message)
    : 'Что-то пошло не так'
  return (
    <div className="center-state" role="alert">
      <AlertTriangle aria-hidden />
      <div className="title">{message}</div>
      {retry ? <Button size="sm" onClick={retry}>Повторить</Button> : null}
    </div>
  )
}

/* ── Модал ────────────────────────────────────────────────────────────────── */

export function Modal({ title, sub, onClose, children }: {
  title: string; sub?: string; onClose: () => void; children: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
          <div>
            <h2>{title}</h2>
            {sub ? <p className="modal-sub">{sub}</p> : null}
          </div>
          <button className="btn ghost sm" onClick={onClose} aria-label="Закрыть"><X size={15} /></button>
        </div>
        {children}
      </div>
    </div>
  )
}

/* ── Drawer ───────────────────────────────────────────────────────────────── */

export function Drawer({ title, sub, onClose, children }: {
  title: string; sub?: string; onClose: () => void; children: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="drawer-overlay" onMouseDown={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={title}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, marginBottom: 18 }}>
          <div>
            <h2 style={{ fontSize: 17 }}>{title}</h2>
            {sub ? <p className="modal-sub" style={{ marginBottom: 0 }}>{sub}</p> : null}
          </div>
          <button className="btn ghost sm" onClick={onClose} aria-label="Закрыть"><X size={15} /></button>
        </div>
        {children}
      </aside>
    </>
  )
}

/* ── Таблица-обёртка ──────────────────────────────────────────────────────── */

export function DataTable({ headers, children, empty }: {
  headers: string[]; children: ReactNode; empty?: ReactNode
}) {
  const rows = Array.isArray(children) ? children.filter(Boolean) : children
  return (
    <div className="table-wrap">
      <div className="table-scroll">
        <table>
          <thead><tr>{headers.map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
          <tbody>{rows}</tbody>
        </table>
        {empty}
      </div>
    </div>
  )
}

export function LoadMore({ onClick, loading, disabled }: { onClick: () => void; loading: boolean; disabled: boolean }) {
  if (disabled) return null
  return (
    <div className="load-more">
      <Button onClick={onClick} loading={loading} disabled={disabled}>
        <ChevronDown size={14} /> Загрузить ещё
      </Button>
    </div>
  )
}

/* ── Тосты ────────────────────────────────────────────────────────────────── */

export interface ToastItem { id: number; kind: 'ok' | 'err'; text: string }

export function ToastList({ items }: { items: ToastItem[] }) {
  return (
    <div className="toasts" role="status" aria-live="polite">
      {items.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`}>
          {t.kind === 'ok' ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
          <span>{t.text}</span>
        </div>
      ))}
    </div>
  )
}

/* ── Прочее ───────────────────────────────────────────────────────────────── */

/** Значение, которого бэкенд не собирает: показываем честно, серым. */
export function NotCollected({ label = 'не телеметрируется' }: { label?: string }) {
  return <span className="not-collected" title="Сервер эти данные не собирает">{label}</span>
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('ru-RU', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export function formatMoney(amountMinor: number, currency: string | null): string {
  if (!currency) return String(amountMinor)
  const value = amountMinor / 100
  try {
    return new Intl.NumberFormat('ru-RU', { style: 'currency', currency, maximumFractionDigits: 0 }).format(value)
  } catch {
    return `${value} ${currency}`
  }
}

export function formatNumber(n: number): string {
  return new Intl.NumberFormat('ru-RU').format(n)
}
