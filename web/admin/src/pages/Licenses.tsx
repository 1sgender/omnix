import { useState } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import type { LicenseRow, PlanRow } from '../api/types'
import {
  Button, DataTable, Drawer, EmptyState, ErrorState, Field, Input, LoadMore,
  Modal, Select, SkeletonRows, StatusBadge, ToastList, formatDateTime, formatMoney
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'
import { KeyRound, Plus } from 'lucide-react'

const STATUS_TABS = ['ALL', 'ACTIVE', 'ISSUED', 'EXPIRED', 'DISABLED', 'REVOKED'] as const
type StatusTab = (typeof STATUS_TABS)[number]

export default function Licenses() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<StatusTab>('ALL')
  const [issueOpen, setIssueOpen] = useState(false)
  const [detailId, setDetailId] = useState<string | null>(null)

  const list = useInfiniteQuery({
    queryKey: ['licenses', status],
    queryFn: ({ pageParam }) => endpoints.licenses({ status, page: pageParam }),
    initialPageParam: 0,
    getNextPageParam: (last, all) =>
      last.licenses.length === 50 ? all.length : undefined
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['licenses'] })
    void queryClient.invalidateQueries({ queryKey: ['license'] })
  }

  return (
    <div>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Фильтр по статусу">
          {STATUS_TABS.map((tab) => (
            <button
              key={tab}
              role="tab"
              aria-selected={status === tab}
              className={`tab ${status === tab ? 'active' : ''}`}
              onClick={() => setStatus(tab)}
            >
              {tab === 'ALL' ? 'Все' : tab}
            </button>
          ))}
        </div>
        <div style={{ flex: 1 }} />
        {can('LICENSES_WRITE') ? (
          <Button variant="primary" onClick={() => setIssueOpen(true)}>
            <Plus size={15} /> Выдать лицензию
          </Button>
        ) : null}
      </div>

      {list.isLoading ? <SkeletonRows rows={7} cols={7} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}

      {list.data ? (
        list.data.pages.every((p) => p.licenses.length === 0) ? (
          <div className="table-wrap"><EmptyState title="Лицензий нет" desc="Выдайте первую лицензию кнопкой выше" /></div>
        ) : (
          <>
            <DataTable headers={['Код', 'Статус', 'План', 'Биллинг', 'Выдана', 'Истекает', 'Аккаунт']}>
              {list.data.pages.flatMap((page) =>
                page.licenses.map((license) => (
                  <tr
                    key={license.id}
                    className="row-link"
                    onClick={() => setDetailId(license.id)}
                    tabIndex={0}
                    onKeyDown={(e) => { if (e.key === 'Enter') setDetailId(license.id) }}
                  >
                    <td className="mono">••••{license.codeHint}</td>
                    <td><StatusBadge status={license.status} /></td>
                    <td>{license.planId}</td>
                    <td><span className="badge muted solid">{license.billingStatus}</span></td>
                    <td className="dim">{formatDateTime(license.issuedAt)}</td>
                    <td className="dim">{formatDateTime(license.expiresAt)}</td>
                    <td className="mono dim">{license.accountId ? license.accountId.slice(0, 8) : '—'}</td>
                  </tr>
                ))
              )}
            </DataTable>
            <LoadMore onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} disabled={!list.hasNextPage} />
          </>
        )
      ) : null}

      {issueOpen ? <IssueModal onClose={() => setIssueOpen(false)} onIssued={(msg) => { invalidate(); ok(msg) }} onErr={err} /> : null}
      {detailId ? (
        <LicenseDetail
          id={detailId}
          onClose={() => setDetailId(null)}
          onChanged={(msg) => { invalidate(); ok(msg) }}
          onErr={err}
        />
      ) : null}
      <ToastList items={toasts} />
    </div>
  )
}

/* ── Выдача: код показывается РОВНО один раз ─────────────────────────────── */

function IssueModal({ onClose, onIssued, onErr }: {
  onClose: () => void; onIssued: (msg: string) => void; onErr: (msg: string) => void
}) {
  const plans = useQuery({ queryKey: ['plans'], queryFn: endpoints.plans })
  const [planId, setPlanId] = useState('')
  const [expiresAt, setExpiresAt] = useState('')
  const [accountRef, setAccountRef] = useState('')
  const [issued, setIssued] = useState<{ code: string; expires: string | null } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const issue = useMutation({
    mutationFn: () => endpoints.issueLicense({
      plan_id: planId,
      one_time: true,
      ...(expiresAt ? { expires_at: new Date(expiresAt).toISOString() } : {}),
      ...(accountRef.trim() ? { account_ref: accountRef.trim() } : {})
    }),
    onSuccess: (response) => {
      setIssued({ code: response.code, expires: response.expires_at })
      onIssued('Лицензия выдана — код показан один раз')
    },
    onError: (e) => onErr(isApiError(e) ? e.message : 'Не удалось выдать лицензию')
  })

  const activePlans: PlanRow[] = plans.data?.plans.filter((p) => p.active) ?? []

  return (
    <Modal
      title={issued ? 'Лицензия выдана' : 'Выдать лицензию'}
      sub={issued ? undefined : 'Одноразовый код активации — покажем один раз'}
      onClose={onClose}
    >
      {issued ? (
        <div>
          <p style={{ fontSize: 13, color: 'var(--tx-2)' }}>
            Скопируйте код сейчас — повторно он не отображается:
          </p>
          <div className="code-reveal">
            {issued.code}
            <button
              className="btn sm" style={{ marginLeft: 10 }}
              onClick={() => { void navigator.clipboard.writeText(issued.code) }}
            >
              Копировать
            </button>
          </div>
          <p style={{ fontSize: 12.5, color: 'var(--tx-3)' }}>
            Действует до: {formatDateTime(issued.expires)}
          </p>
          <div className="modal-actions">
            <Button variant="primary" onClick={onClose}>Готово</Button>
          </div>
        </div>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault()
            setError(null)
            if (!planId) { setError('Выберите план'); return }
            if (expiresAt && Number.isNaN(new Date(expiresAt).getTime())) { setError('Некорректная дата'); return }
            issue.mutate()
          }}
        >
          {error ? <div className="form-error" role="alert">{error}</div> : null}
          <Field label="План" htmlFor="issue-plan" hint={plans.isLoading ? 'Загружаем каталог…' : undefined}>
            <Select id="issue-plan" value={planId} onChange={(e) => setPlanId(e.target.value)} required>
              <option value="" disabled>Выберите план…</option>
              {activePlans.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.displayName ?? p.id} · {p.durationDays} дн. · {p.amountMinor === 0 ? 'бесплатно' : formatMoney(p.amountMinor, p.currency)}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Действует до" htmlFor="issue-exp" hint="Необязательно — по умолчанию срок плана">
            <Input id="issue-exp" type="date" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} />
          </Field>
          <Field label="Внешний реф аккаунта" htmlFor="issue-ref" hint="Необязательно: привязка к пользователю">
            <Input id="issue-ref" value={accountRef} onChange={(e) => setAccountRef(e.target.value)} placeholder="например: owner" />
          </Field>
          <div className="modal-actions">
            <Button type="button" onClick={onClose}>Отмена</Button>
            <Button type="submit" variant="primary" loading={issue.isPending}>
              <KeyRound size={14} /> Выдать
            </Button>
          </div>
        </form>
      )}
      {issued ? null : (
        <p style={{ fontSize: 12, color: 'var(--tx-3)', marginTop: 12 }}>
          После выдачи скопируйте код — в базе хранится только хэш.
        </p>
      )}
    </Modal>
  )
}

/* ── Детали + действия ───────────────────────────────────────────────────── */

function LicenseDetail({ id, onClose, onChanged, onErr }: {
  id: string; onClose: () => void; onChanged: (msg: string) => void; onErr: (msg: string) => void
}) {
  const { can } = useAuth()
  const query = useQuery({ queryKey: ['license', id], queryFn: () => endpoints.license(id) })
  const [confirmAction, setConfirmAction] = useState<'disable' | null>(null)
  const [extendDays, setExtendDays] = useState('30')
  const [extendOpen, setExtendOpen] = useState(false)

  const act = useMutation({
    mutationFn: (args: { action: 'disable' | 'enable' | 'extend'; body?: unknown }) =>
      endpoints.licenseAction(id, args.action, args.body),
    onSuccess: (_res, vars) => {
      onChanged(vars.action === 'extend' ? `Срок продлён на ${String((vars.body as { days: number }).days)} дн.` :
        vars.action === 'disable' ? 'Лицензия отключена' : 'Лицензия включена')
      setConfirmAction(null)
      setExtendOpen(false)
      void query.refetch()
    },
    onError: (e) => onErr(isApiError(e) ? e.message : 'Действие не удалось')
  })

  const license: LicenseRow | undefined = query.data

  return (
    <Drawer title="Лицензия" sub={license ? `••••${license.codeHint}` : undefined} onClose={onClose}>
      {query.isLoading ? <div className="skeleton" style={{ height: 180 }} /> : null}
      {query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      {license ? (
        <div>
          <dl className="kv">
            <dt>Статус</dt><dd><StatusBadge status={license.status} /></dd>
            <dt>План</dt><dd>{license.planId}</dd>
            <dt>Биллинг</dt><dd>{license.billingStatus}</dd>
            <dt>Выдана</dt><dd>{formatDateTime(license.issuedAt)}</dd>
            <dt>Начало</dt><dd>{formatDateTime(license.startsAt)}</dd>
            <dt>Истекает</dt><dd>{formatDateTime(license.expiresAt)}</dd>
            <dt>Активирована</dt><dd>{formatDateTime(license.redeemedAt)}</dd>
            <dt>Аккаунт</dt><dd className="mono">{license.accountId ?? '—'}</dd>
            <dt>ID</dt><dd className="mono">{license.id}</dd>
          </dl>

          {can('LICENSES_WRITE') ? (
            <>
              <h3 className="section-title">Действия</h3>
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                {license.status === 'ACTIVE' || license.status === 'ISSUED' ? (
                  <Button size="sm" onClick={() => setConfirmAction('disable')}>Отключить</Button>
                ) : null}
                {license.status === 'DISABLED' ? (
                  <Button size="sm" onClick={() => act.mutate({ action: 'enable' })} loading={act.isPending}>Включить</Button>
                ) : null}
                <Button size="sm" onClick={() => setExtendOpen(true)}>Продлить…</Button>
              </div>
            </>
          ) : null}
        </div>
      ) : null}

      {confirmAction === 'disable' && license ? (
        <Modal title="Отключить лицензию?" sub={`••••${license.codeHint} перестанет действовать немедленно`} onClose={() => setConfirmAction(null)}>
          <div className="modal-actions">
            <Button onClick={() => setConfirmAction(null)}>Отмена</Button>
            <Button variant="danger" loading={act.isPending} onClick={() => act.mutate({ action: 'disable' })}>
              Отключить
            </Button>
          </div>
        </Modal>
      ) : null}

      {extendOpen ? (
        <Modal title="Продлить лицензию" sub="Добавить дни к текущему сроку" onClose={() => setExtendOpen(false)}>
          <Field label="Дней" htmlFor="extend-days" hint="1–3650">
            <Input
              id="extend-days" type="number" min={1} max={3650} value={extendDays}
              onChange={(e) => setExtendDays(e.target.value)}
            />
          </Field>
          <div className="modal-actions">
            <Button onClick={() => setExtendOpen(false)}>Отмена</Button>
            <Button
              variant="primary" loading={act.isPending}
              onClick={() => {
                const days = Number(extendDays)
                if (!Number.isInteger(days) || days < 1 || days > 3650) return
                act.mutate({ action: 'extend', body: { days } })
              }}
            >
              Продлить
            </Button>
          </div>
        </Modal>
      ) : null}
    </Drawer>
  )
}
