import { useState } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import { isApiError } from '../api/client'
import type { PaymentMethod, PaymentRequestRow } from '../api/types'
import {
  Button, DataTable, EmptyState, ErrorState, Field, Input, LoadMore, Modal, Select, SkeletonRows,
  StatusBadge, ToastList, formatDateTime, formatMoney
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { useAuth } from '../state/auth'

const FILTERS: { value: string; label: string }[] = [
  { value: '', label: 'Все' },
  { value: 'PENDING', label: 'Ждут оплаты' },
  { value: 'PAID', label: 'Оплачены' },
  { value: 'CANCELED', label: 'Отменены' },
  { value: 'EXPIRED', label: 'Истекли' }
]

const METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'cash', label: 'Наличные' },
  { value: 'bank_transfer', label: 'Банковский перевод' },
  { value: 'card_terminal', label: 'Карта (терминал)' },
  { value: 'mobile_wallet', label: 'Мобильный кошелёк' },
  { value: 'other', label: 'Другое' }
]

const PLAN_NAMES: Record<string, string> = { pro: 'OMNIX Pro', omnix: 'OMNIX Max' }

type Dialog =
  | { kind: 'confirm'; row: PaymentRequestRow; step: 'form' | 'review' }
  | { kind: 'cancel'; row: PaymentRequestRow }
  | null

/** Сумма из поля ввода (тмт, допускается запятая) в минорные единицы; null — некорректно. */
function parseAmountMinor(text: string): number | null {
  const normalized = text.trim().replace(',', '.')
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null
  const minor = Math.round(Number(normalized) * 100)
  return minor > 0 ? minor : null
}

export default function PaymentRequests() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState('PENDING')
  const [search, setSearch] = useState('')
  const [dialog, setDialog] = useState<Dialog>(null)
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState<PaymentMethod>('cash')
  const [note, setNote] = useState('')
  const [reason, setReason] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const canWrite = can('LICENSES_WRITE')

  const list = useInfiniteQuery({
    queryKey: ['payment-requests', status, search],
    queryFn: ({ pageParam }) => endpoints.paymentRequests({ status, q: search.trim(), page: pageParam }),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.requests.length === 50 ? all.length : undefined)
  })

  const close = () => { setDialog(null); setFormError(null); setNote(''); setReason('') }
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['payment-requests'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  }
  const fail = (e: unknown) => {
    const msg = isApiError(e) ? e.message : 'Действие не удалось'
    setFormError(msg)
    err(msg)
  }

  const confirm = useMutation({
    mutationFn: (a: { id: string; paidAmountMinor: number }) =>
      endpoints.confirmPayment(a.id, { paidAmountMinor: a.paidAmountMinor, method, note: note.trim() || undefined }),
    onSuccess: (r) => { ok(`Заявка ${r.request.number} оплачена, тариф выдан`); refresh(); close() },
    onError: fail
  })
  const cancel = useMutation({
    mutationFn: (a: { id: string; reason: string }) => endpoints.cancelPayment(a.id, a.reason),
    onSuccess: (r) => { ok(`Заявка ${r.request.number} отменена`); refresh(); close() },
    onError: fail
  })

  const rows = list.data?.pages.flatMap((p) => p.requests) ?? []
  const amountMinor = parseAmountMinor(amount)

  return (
    <div>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Статус заявки">
          {FILTERS.map((f) => (
            <button key={f.value} role="tab" aria-selected={status === f.value}
              className={`tab ${status === f.value ? 'active' : ''}`} onClick={() => setStatus(f.value)}>
              {f.label}
            </button>
          ))}
        </div>
        <Input placeholder="Номер, например OMX-4821" aria-label="Поиск по номеру заявки" value={search}
          onChange={(e) => setSearch(e.target.value)} style={{ maxWidth: 240 }} />
      </div>

      {list.isLoading ? <SkeletonRows rows={6} cols={7} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}
      {!list.isLoading && !list.isError ? (
        rows.length === 0 ? (
          <div className="table-wrap"><EmptyState title="Заявок нет" desc="Заявки появятся, когда пользователь нажмёт «Получить Pro/Max» в приложении" /></div>
        ) : (
          <>
            <DataTable headers={['Номер', 'Аккаунт', 'План', 'Сумма', 'Статус', 'Создана', 'Действует до', 'Оплата', '']}>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td className="mono">{row.number}</td>
                  <td className="mono dim" title={row.accountId}>{row.accountRef ?? row.accountId.slice(0, 8)}</td>
                  <td>{PLAN_NAMES[row.planId] ?? row.planId}</td>
                  <td className="num">{formatMoney(row.amountMinor, row.currency)}</td>
                  <td><StatusBadge status={row.status} /></td>
                  <td className="dim">{formatDateTime(row.createdAt)}</td>
                  <td className="dim">{formatDateTime(row.expiresAt)}</td>
                  <td className="dim">
                    {row.paidAmountMinor !== null
                      ? `${formatMoney(row.paidAmountMinor, row.currency)} · ${METHODS.find((m) => m.value === row.method)?.label ?? row.method ?? ''}`
                      : row.status === 'CANCELED' ? (row.note ?? '—') : '—'}
                  </td>
                  <td>
                    {canWrite && (row.status === 'PENDING' || row.status === 'EXPIRED') ? (
                      <Button size="sm" variant="primary"
                        onClick={() => { setAmount(String(row.amountMinor / 100)); setMethod('cash'); setDialog({ kind: 'confirm', row, step: 'form' }) }}>
                        Оплачено
                      </Button>
                    ) : null}
                    {canWrite && row.status === 'PENDING' ? (
                      <Button size="sm" variant="danger" onClick={() => setDialog({ kind: 'cancel', row })}>Отменить</Button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </DataTable>
            <LoadMore onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} disabled={!list.hasNextPage} />
          </>
        )
      ) : null}

      {dialog?.kind === 'confirm' && dialog.step === 'form' ? (
        <Modal title={`Заявка ${dialog.row.number}: оплата получена`}
          sub={`${PLAN_NAMES[dialog.row.planId] ?? dialog.row.planId}, ожидается ${formatMoney(dialog.row.amountMinor, dialog.row.currency)}`}
          onClose={close}>
          <form onSubmit={(e) => {
            e.preventDefault()
            if (amountMinor === null) { setFormError('Укажите сумму, например 149 или 149.50'); return }
            setFormError(null)
            setDialog({ kind: 'confirm', row: dialog.row, step: 'review' })
          }}>
            {formError ? <div className="form-error" role="alert">{formError}</div> : null}
            <Field label="Получено, TMT" htmlFor="pay-amount" hint="Сумма, которую действительно получили">
              <Input id="pay-amount" inputMode="decimal" value={amount} required onChange={(e) => setAmount(e.target.value)} />
            </Field>
            <Field label="Способ оплаты" htmlFor="pay-method">
              <Select id="pay-method" value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
                {METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              </Select>
            </Field>
            <Field label="Комментарий" htmlFor="pay-note" hint="Необязательно. Попадёт в аудит">
              <Input id="pay-note" value={note} maxLength={512} onChange={(e) => setNote(e.target.value)} />
            </Field>
            <div className="modal-actions">
              <Button type="button" onClick={close}>Отмена</Button>
              <Button type="submit" variant="primary">Далее</Button>
            </div>
          </form>
        </Modal>
      ) : null}

      {dialog?.kind === 'confirm' && dialog.step === 'review' && amountMinor !== null ? (
        <Modal title="Подтвердить выдачу тарифа?"
          sub="Тариф будет выдан или продлён на 30 дней сразу, отменить это действие нельзя" onClose={close}>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
          <p style={{ fontSize: 13.5, lineHeight: 1.6 }}>
            Заявка <b className="mono">{dialog.row.number}</b>, {PLAN_NAMES[dialog.row.planId] ?? dialog.row.planId}.<br />
            Получено: <b>{formatMoney(amountMinor, dialog.row.currency)}</b>
            {amountMinor !== dialog.row.amountMinor ? ` (по заявке ${formatMoney(dialog.row.amountMinor, dialog.row.currency)})` : ''},
            способ: {METHODS.find((m) => m.value === method)?.label}.
          </p>
          <div className="modal-actions">
            <Button onClick={() => setDialog({ kind: 'confirm', row: dialog.row, step: 'form' })}>Назад</Button>
            <Button variant="primary" loading={confirm.isPending}
              onClick={() => confirm.mutate({ id: dialog.row.id, paidAmountMinor: amountMinor })}>
              Да, оплата получена
            </Button>
          </div>
        </Modal>
      ) : null}

      {dialog?.kind === 'cancel' ? (
        <Modal title={`Отменить заявку ${dialog.row.number}?`} sub="Пользователь увидит статус «отменена»" onClose={close}>
          <form onSubmit={(e) => {
            e.preventDefault()
            const r = reason.trim()
            if (r.length < 3 || r.length > 256) { setFormError('Причина: 3–256 символов'); return }
            setFormError(null)
            cancel.mutate({ id: dialog.row.id, reason: r })
          }}>
            {formError ? <div className="form-error" role="alert">{formError}</div> : null}
            <Field label="Причина отмены" htmlFor="pay-cancel" hint="Записывается в аудит">
              <Input id="pay-cancel" value={reason} maxLength={256} required onChange={(e) => setReason(e.target.value)} />
            </Field>
            <div className="modal-actions">
              <Button type="button" onClick={close}>Назад</Button>
              <Button type="submit" variant="danger" loading={cancel.isPending}>Отменить заявку</Button>
            </div>
          </form>
        </Modal>
      ) : null}
      <ToastList items={toasts} />
    </div>
  )
}
