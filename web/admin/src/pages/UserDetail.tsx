import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound } from 'lucide-react'
import { endpoints } from '../api/endpoints'
import { isApiError } from '../api/client'
import type { LicenseRow } from '../api/types'
import {
  Button, DataTable, ErrorState, Field, Input, Modal, PageLoader, Select,
  StatusBadge, ToastList, formatDateTime, formatNumber
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { useAuth } from '../state/auth'

type LicenseDialog =
  | { kind: 'extend' | 'plan' | 'disable' | 'revoke'; license: LicenseRow }
  | { kind: 'issue' }
  | { kind: 'block' | 'unblock' | 'bonus' }
  | { kind: 'device'; tokenId: string }
  | null

export default function UserDetail() {
  const { id } = useParams<{ id: string }>()
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<LicenseDialog>(null)
  const [days, setDays] = useState('30')
  const [planId, setPlanId] = useState('')
  const [reason, setReason] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [issuedCode, setIssuedCode] = useState<string | null>(null)
  const [noteText, setNoteText] = useState('')

  const query = useQuery({
    queryKey: ['user', id],
    queryFn: () => endpoints.user(id!),
    enabled: Boolean(id)
  })
  const plans = useQuery({ queryKey: ['plans'], queryFn: endpoints.plans, enabled: dialog?.kind === 'plan' || dialog?.kind === 'issue' })

  const close = () => { setDialog(null); setFormError(null); setReason(''); setIssuedCode(null) }
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['user', id] })
    void queryClient.invalidateQueries({ queryKey: ['licenses'] })
  }
  const fail = (e: unknown) => {
    const msg = isApiError(e) ? e.message : 'Действие не удалось'
    setFormError(msg)
    err(msg)
  }

  const licenseAction = useMutation({
    mutationFn: (a: { licenseId: string; action: 'disable' | 'enable' | 'extend' | 'change-plan'; body?: unknown; done: string }) =>
      endpoints.licenseAction(a.licenseId, a.action, a.body),
    onSuccess: (_r, a) => { ok(a.done); refresh(); close() },
    onError: fail
  })
  const revoke = useMutation({
    mutationFn: (a: { licenseId: string; reason: string }) => endpoints.revokeLicense(a.licenseId, a.reason),
    onSuccess: () => { ok('Лицензия отозвана'); refresh(); close() },
    onError: fail
  })
  const revokeDevice = useMutation({
    mutationFn: (tokenId: string) => endpoints.revokeDevice(tokenId),
    onSuccess: () => { ok('Устройство отключено, токен отозван'); refresh(); close() },
    onError: fail
  })
  const userAction = useMutation({
    mutationFn: async (a: { kind: 'block' | 'unblock' | 'bonus'; reason: string; days?: number }) => {
      if (a.kind === 'block') await endpoints.blockUser(id!, a.reason)
      else if (a.kind === 'unblock') await endpoints.unblockUser(id!, a.reason)
      else await endpoints.userBonus(id!, a.days ?? 0, a.reason)
    },
    onSuccess: (_r, a) => {
      ok(a.kind === 'block' ? 'Аккаунт заблокирован' : a.kind === 'unblock' ? 'Аккаунт разблокирован' : `Бонус: +${a.days} дн.`)
      refresh(); close()
    },
    onError: fail
  })
  const addNote = useMutation({
    mutationFn: (note: string) => endpoints.addUserNote(id!, note),
    onSuccess: () => { ok('Заметка добавлена'); setNoteText(''); refresh() },
    onError: (e: unknown) => err(isApiError(e) ? e.message : 'Не удалось добавить заметку')
  })
  const issue = useMutation({
    mutationFn: (a: { planId: string; accountRef: string }) =>
      endpoints.issueLicense({ plan_id: a.planId, one_time: true, account_ref: a.accountRef }),
    onSuccess: (r) => { setIssuedCode(r.code); ok('Лицензия выдана — код показан один раз'); refresh() },
    onError: fail
  })

  if (query.isLoading) return <PageLoader />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  if (!query.data) return null

  const { account, licenses, devices, usage, notes } = query.data
  const blocked = account.status === 'DISABLED'
  const canLicense = can('LICENSES_WRITE')
  const canDevice = can('DEVICES_REVOKE')
  const live = (l: LicenseRow) => l.status === 'ACTIVE' || l.status === 'ISSUED'
  const activePlans = plans.data?.plans.filter((p) => p.active) ?? []

  return (
    <div>
      <div className="card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          <h2 style={{ fontSize: 17 }}>{account.externalRef ?? 'Без рефа'}</h2>
          <StatusBadge status={account.status} />
          <span className="dim mono" style={{ fontSize: 12 }}>{account.id}</span>
          <div style={{ flex: 1 }} />
          {canLicense ? (
            <Button size="sm" onClick={() => { setDays('7'); setDialog({ kind: 'bonus' }) }}>Бонус-дни</Button>
          ) : null}
          {canLicense ? (
            blocked
              ? <Button size="sm" onClick={() => setDialog({ kind: 'unblock' })}>Разблокировать</Button>
              : <Button size="sm" variant="danger" onClick={() => setDialog({ kind: 'block' })}>Блокировать</Button>
          ) : null}
          {canLicense ? (
            <Button
              variant="primary" size="sm"
              disabled={!account.externalRef}
              title={account.externalRef ? undefined : 'У аккаунта нет внешнего рефа — выдайте лицензию на странице «Лицензии»'}
              onClick={() => { setPlanId(''); setDialog({ kind: 'issue' }) }}
            >
              <KeyRound size={14} /> Выдать лицензию
            </Button>
          ) : null}
        </div>
        <div className="grid grid-stats" style={{ marginTop: 16 }}>
          <div><div className="stat-label">Запросы</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.requests)}</div></div>
          <div><div className="stat-label">Ошибки</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.errors)}</div></div>
          <div><div className="stat-label">In-токены</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.inputTokens)}</div></div>
          <div><div className="stat-label">Out-токены</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.outputTokens)}</div></div>
        </div>
      </div>

      <h2 className="section-title">Лицензии ({licenses.length})</h2>
      <DataTable headers={['Код', 'Статус', 'План', 'Истекает', ...(canLicense ? ['Действия'] : [])]}>
        {licenses.map((l) => (
          <tr key={l.id}>
            <td className="mono">••••{l.codeHint}</td>
            <td><StatusBadge status={l.status} /></td>
            <td>{l.planId}</td>
            <td className="dim">{formatDateTime(l.expiresAt)}</td>
            {canLicense ? (
              <td>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {live(l) ? <Button size="sm" onClick={() => { setDays('30'); setDialog({ kind: 'extend', license: l }) }}>Продлить</Button> : null}
                  {live(l) ? <Button size="sm" onClick={() => { setPlanId(l.planId); setDialog({ kind: 'plan', license: l }) }}>Сменить план</Button> : null}
                  {live(l) ? <Button size="sm" onClick={() => setDialog({ kind: 'disable', license: l })}>Отключить</Button> : null}
                  {l.status === 'DISABLED' ? (
                    <Button size="sm" loading={licenseAction.isPending}
                      onClick={() => licenseAction.mutate({ licenseId: l.id, action: 'enable', done: 'Лицензия включена' })}>
                      Включить
                    </Button>
                  ) : null}
                  {live(l) ? <Button size="sm" variant="danger" onClick={() => setDialog({ kind: 'revoke', license: l })}>Отозвать</Button> : null}
                </div>
              </td>
            ) : null}
          </tr>
        ))}
      </DataTable>

      <h2 className="section-title">Устройства ({devices.length})</h2>
      <DataTable headers={['Токен', 'Статус', 'Выдан', 'Последнее использование', 'Биндинг', ...(canDevice ? ['Действия'] : [])]}>
        {devices.map((d) => (
          <tr key={d.tokenId}>
            <td className="mono">{d.tokenId.slice(0, 8)}…</td>
            <td><StatusBadge status={d.status} /></td>
            <td className="dim">{formatDateTime(d.issuedAt)}</td>
            <td className="dim">{formatDateTime(d.lastUsedAt)}</td>
            <td className="mono dim">{d.deviceBinding ? `${d.deviceBinding.slice(0, 12)}…` : '—'}</td>
            {canDevice ? (
              <td>
                {d.status === 'ACTIVE'
                  ? <Button size="sm" variant="danger" onClick={() => setDialog({ kind: 'device', tokenId: d.tokenId })}>Выйти</Button>
                  : null}
              </td>
            ) : null}
          </tr>
        ))}
      </DataTable>

      <h2 className="section-title">Заметки ({notes.length})</h2>
      {canLicense ? (
        <form
          style={{ display: 'flex', gap: 8, marginBottom: 12 }}
          onSubmit={(e) => {
            e.preventDefault()
            const t = noteText.trim()
            if (t.length < 1 || t.length > 2000) { err('Заметка: 1–2000 символов'); return }
            addNote.mutate(t)
          }}
        >
          <Input
            aria-label="Новая заметка" placeholder="Заметка об аккаунте…" value={noteText} maxLength={2000}
            onChange={(e) => setNoteText(e.target.value)}
          />
          <Button type="submit" variant="primary" loading={addNote.isPending} disabled={!noteText.trim()}>Добавить</Button>
        </form>
      ) : null}
      {notes.length === 0 ? <p className="dim" style={{ fontSize: 13 }}>Заметок пока нет</p> : null}
      {notes.map((n) => (
        <div key={n.id} className="card" style={{ marginBottom: 8 }}>
          <div className="dim" style={{ fontSize: 12, marginBottom: 4 }}>{n.author} · {formatDateTime(n.createdAt)}</div>
          <div style={{ whiteSpace: 'pre-wrap', fontSize: 14 }}>{n.note}</div>
        </div>
      ))}

      <p style={{ marginTop: 14 }}>
        <Link to="/users" className="btn ghost sm">← Все пользователи</Link>
      </p>

      {dialog?.kind === 'block' || dialog?.kind === 'unblock' || dialog?.kind === 'bonus' ? (
        <Modal
          title={dialog.kind === 'block' ? 'Заблокировать аккаунт?' : dialog.kind === 'unblock' ? 'Разблокировать аккаунт?' : 'Бонус-дни'}
          sub={dialog.kind === 'block'
            ? 'Доступ по токенам и лицензиям будет закрыт немедленно; можно разблокировать обратно'
            : dialog.kind === 'unblock' ? 'Аккаунт снова получит доступ' : 'Добавить дни к действующей лицензии аккаунта'}
          onClose={close}
        >
          <form
            onSubmit={(e) => {
              e.preventDefault()
              const r = reason.trim()
              if (r.length < 3 || r.length > 256) { setFormError('Причина: 3–256 символов'); return }
              if (dialog.kind === 'bonus') {
                const n = Number(days)
                if (!Number.isInteger(n) || n < 1 || n > 3650) { setFormError('Дней: целое от 1 до 3650'); return }
                setFormError(null)
                userAction.mutate({ kind: 'bonus', reason: r, days: n })
                return
              }
              setFormError(null)
              userAction.mutate({ kind: dialog.kind, reason: r })
            }}
          >
            {formError ? <div className="form-error" role="alert">{formError}</div> : null}
            {dialog.kind === 'bonus' ? (
              <Field label="Дней" htmlFor="u-bonus" hint="1–3650">
                <Input id="u-bonus" type="number" min={1} max={3650} value={days} onChange={(e) => setDays(e.target.value)} />
              </Field>
            ) : null}
            <Field label="Причина" htmlFor="u-reason" hint="Записывается в аудит">
              <Input id="u-reason" value={reason} maxLength={256} required onChange={(e) => setReason(e.target.value)} />
            </Field>
            <div className="modal-actions">
              <Button type="button" onClick={close}>Отмена</Button>
              <Button type="submit" variant={dialog.kind === 'block' ? 'danger' : 'primary'} loading={userAction.isPending}>
                {dialog.kind === 'block' ? 'Заблокировать' : dialog.kind === 'unblock' ? 'Разблокировать' : 'Начислить'}
              </Button>
            </div>
          </form>
        </Modal>
      ) : null}

      {dialog?.kind === 'extend' ? (
        <Modal title="Продлить лицензию" sub={`••••${dialog.license.codeHint} · добавить дни к сроку`} onClose={close}>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
          <Field label="Дней" htmlFor="u-extend" hint="1–3650">
            <Input id="u-extend" type="number" min={1} max={3650} value={days} onChange={(e) => setDays(e.target.value)} />
          </Field>
          <div className="modal-actions">
            <Button onClick={close}>Отмена</Button>
            <Button
              variant="primary" loading={licenseAction.isPending}
              onClick={() => {
                const n = Number(days)
                if (!Number.isInteger(n) || n < 1 || n > 3650) { setFormError('Дней: целое от 1 до 3650'); return }
                setFormError(null)
                licenseAction.mutate({ licenseId: dialog.license.id, action: 'extend', body: { days: n }, done: `Срок продлён на ${n} дн.` })
              }}
            >
              Продлить
            </Button>
          </div>
        </Modal>
      ) : null}

      {dialog?.kind === 'plan' ? (
        <Modal title="Сменить план" sub={`••••${dialog.license.codeHint} · сейчас: ${dialog.license.planId}`} onClose={close}>
          {formError ? <div className="form-error" role="alert">{formError}</div> : null}
          <Field label="Новый план" htmlFor="u-plan">
            <Select id="u-plan" value={planId} onChange={(e) => setPlanId(e.target.value)}>
              {activePlans.map((p) => <option key={p.id} value={p.id}>{p.displayName ?? p.id}</option>)}
            </Select>
          </Field>
          <div className="modal-actions">
            <Button onClick={close}>Отмена</Button>
            <Button
              variant="primary" loading={licenseAction.isPending}
              disabled={!planId || planId === dialog.license.planId}
              onClick={() => licenseAction.mutate({ licenseId: dialog.license.id, action: 'change-plan', body: { planId }, done: `План изменён на ${planId}` })}
            >
              Сменить
            </Button>
          </div>
        </Modal>
      ) : null}

      {dialog?.kind === 'disable' ? (
        <Modal title="Отключить лицензию?" sub={`••••${dialog.license.codeHint} перестанет действовать немедленно; можно включить обратно`} onClose={close}>
          <div className="modal-actions">
            <Button onClick={close}>Отмена</Button>
            <Button variant="danger" loading={licenseAction.isPending}
              onClick={() => licenseAction.mutate({ licenseId: dialog.license.id, action: 'disable', done: 'Лицензия отключена' })}>
              Отключить
            </Button>
          </div>
        </Modal>
      ) : null}

      {dialog?.kind === 'revoke' ? (
        <Modal title="Отозвать лицензию?" sub={`••••${dialog.license.codeHint} — действие необратимо`} onClose={close}>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              const r = reason.trim()
              if (r.length < 3 || r.length > 256) { setFormError('Причина: 3–256 символов'); return }
              setFormError(null)
              revoke.mutate({ licenseId: dialog.license.id, reason: r })
            }}
          >
            {formError ? <div className="form-error" role="alert">{formError}</div> : null}
            <Field label="Причина отзыва" htmlFor="u-revoke" hint="Записывается в аудит">
              <Input id="u-revoke" value={reason} maxLength={256} required onChange={(e) => setReason(e.target.value)} />
            </Field>
            <div className="modal-actions">
              <Button type="button" onClick={close}>Отмена</Button>
              <Button type="submit" variant="danger" loading={revoke.isPending}>Отозвать</Button>
            </div>
          </form>
        </Modal>
      ) : null}

      {dialog?.kind === 'device' ? (
        <Modal title="Выйти с устройства?" sub="Токен будет отозван, приложение на устройстве потребует новой активации" onClose={close}>
          <div className="modal-actions">
            <Button onClick={close}>Отмена</Button>
            <Button variant="danger" loading={revokeDevice.isPending} onClick={() => revokeDevice.mutate(dialog.tokenId)}>
              Отозвать токен
            </Button>
          </div>
        </Modal>
      ) : null}

      {dialog?.kind === 'issue' ? (
        <Modal
          title={issuedCode ? 'Лицензия выдана' : 'Выдать лицензию'}
          sub={`Для ${account.externalRef ?? ''}`}
          onClose={close}
        >
          {issuedCode ? (
            <div>
              <p style={{ fontSize: 13, color: 'var(--tx-2)' }}>Скопируйте код сейчас — повторно он не отображается:</p>
              <div className="code-reveal">
                {issuedCode}
                <button className="btn sm" style={{ marginLeft: 10 }} onClick={() => { void navigator.clipboard.writeText(issuedCode) }}>
                  Копировать
                </button>
              </div>
              <div className="modal-actions"><Button variant="primary" onClick={close}>Готово</Button></div>
            </div>
          ) : (
            <>
              {formError ? <div className="form-error" role="alert">{formError}</div> : null}
              <Field label="План" htmlFor="u-issue-plan">
                <Select id="u-issue-plan" value={planId} onChange={(e) => setPlanId(e.target.value)}>
                  <option value="" disabled>Выберите план…</option>
                  {activePlans.map((p) => <option key={p.id} value={p.id}>{p.displayName ?? p.id} · {p.durationDays} дн.</option>)}
                </Select>
              </Field>
              <div className="modal-actions">
                <Button onClick={close}>Отмена</Button>
                <Button
                  variant="primary" loading={issue.isPending} disabled={!planId}
                  onClick={() => issue.mutate({ planId, accountRef: account.externalRef! })}
                >
                  Выдать
                </Button>
              </div>
            </>
          )}
        </Modal>
      ) : null}

      <ToastList items={toasts} />
    </div>
  )
}
