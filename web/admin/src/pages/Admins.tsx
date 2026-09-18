import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import type { AdminRow } from '../api/types'
import {
  Badge, Button, DataTable, Field, Input, Modal, Select, SkeletonRows,
  StatusBadge, ToastList, formatDateTime
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { Plus } from 'lucide-react'

const ROLE_HINTS: Record<string, string> = {
  SUPER_ADMIN: 'всё, включая управление операторами',
  ADMIN: 'всё, кроме управления операторами',
  SUPPORT: 'просмотр + лицензии',
  VIEWER: 'только чтение'
}

export default function Admins() {
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [statusTarget, setStatusTarget] = useState<{ row: AdminRow; next: 'ACTIVE' | 'DISABLED' } | null>(null)
  const [passwordTarget, setPasswordTarget] = useState<AdminRow | null>(null)

  const query = useQuery({ queryKey: ['admins'], queryFn: endpoints.admins })

  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['admins'] })

  const create = useMutation({
    mutationFn: (body: { username: string; password: string; role: string }) => endpoints.createAdmin(body),
    onSuccess: () => { ok('Оператор создан'); setCreateOpen(false); invalidate() },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось создать')
  })

  const setStatus = useMutation({
    mutationFn: (args: { id: string; status: 'ACTIVE' | 'DISABLED' }) => endpoints.setAdminStatus(args.id, args.status),
    onSuccess: (_d, vars) => {
      ok(vars.status === 'DISABLED' ? 'Оператор отключён, сессии отозваны' : 'Оператор включён')
      setStatusTarget(null)
      invalidate()
    },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось изменить статус')
  })

  const rotate = useMutation({
    mutationFn: (args: { id: string; password: string }) => endpoints.setAdminPassword(args.id, args.password),
    onSuccess: () => { ok('Пароль сменён, сессии отозваны'); setPasswordTarget(null) },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось сменить пароль')
  })

  if (query.isLoading) return <SkeletonRows rows={3} cols={5} />
  if (query.isError) return <ErrorStateInline retry={() => void query.refetch()} />

  return (
    <div>
      <div className="toolbar">
        <div style={{ flex: 1 }} />
        <Button variant="primary" onClick={() => setCreateOpen(true)}>
          <Plus size={15} /> Новый оператор
        </Button>
      </div>

      <DataTable headers={['Оператор', 'Роль', 'Статус', 'Создан', '']}>
        {query.data?.admins.map((row) => (
          <tr key={row.id}>
            <td style={{ fontWeight: 550 }}>{row.username}</td>
            <td><Badge tone={row.role === 'SUPER_ADMIN' ? 'info' : 'muted'}>{row.role}</Badge></td>
            <td><StatusBadge status={row.status} /></td>
            <td className="dim">{formatDateTime(row.createdAt)}</td>
            <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
              <Button size="sm" variant="ghost" onClick={() => setPasswordTarget(row)}>Сменить пароль</Button>
              {row.status === 'ACTIVE' ? (
                <Button size="sm" variant="danger" style={{ marginLeft: 8 }} onClick={() => setStatusTarget({ row, next: 'DISABLED' })}>
                  Отключить
                </Button>
              ) : (
                <Button size="sm" style={{ marginLeft: 8 }} onClick={() => setStatusTarget({ row, next: 'ACTIVE' })}>
                  Включить
                </Button>
              )}
            </td>
          </tr>
        )) ?? null}
      </DataTable>

      {createOpen ? <CreateModal onClose={() => setCreateOpen(false)} onSave={(body) => create.mutate(body)} loading={create.isPending} /> : null}

      {statusTarget ? (
        <Modal
          title={statusTarget.next === 'DISABLED' ? 'Отключить оператора?' : 'Включить оператора?'}
          sub={`${statusTarget.row.username} (${statusTarget.row.role})`}
          onClose={() => setStatusTarget(null)}
        >
          {statusTarget.next === 'DISABLED' ? (
            <p style={{ fontSize: 13, color: 'var(--tx-2)', marginBottom: 4 }}>
              Все его сессии будут отозваны немедленно.
            </p>
          ) : null}
          <div className="modal-actions">
            <Button onClick={() => setStatusTarget(null)}>Отмена</Button>
            <Button
              variant={statusTarget.next === 'DISABLED' ? 'danger' : 'primary'}
              loading={setStatus.isPending}
              onClick={() => setStatus.mutate({ id: statusTarget.row.id, status: statusTarget.next })}
            >
              {statusTarget.next === 'DISABLED' ? 'Отключить' : 'Включить'}
            </Button>
          </div>
        </Modal>
      ) : null}

      {passwordTarget ? <PasswordModal row={passwordTarget} onClose={() => setPasswordTarget(null)} onSave={(password) => rotate.mutate({ id: passwordTarget.id, password })} loading={rotate.isPending} /> : null}
      <ToastList items={toasts} />
    </div>
  )
}

function ErrorStateInline({ retry }: { retry: () => void }) {
  return <div className="center-state"><div className="title">Не удалось загрузить операторов</div><Button size="sm" onClick={retry}>Повторить</Button></div>
}

function CreateModal({ onClose, onSave, loading }: {
  onClose: () => void; onSave: (body: { username: string; password: string; role: string }) => void; loading: boolean
}) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('SUPPORT')
  const [error, setError] = useState<string | null>(null)

  return (
    <Modal title="Новый оператор" sub="Аккаунт для входа в Control Plane" onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          if (!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$/.test(username)) {
            setError('Имя: 3–64 символа, латиница, цифры, _ . -')
            return
          }
          if (password.length < 12) {
            setError('Пароль — минимум 12 символов')
            return
          }
          onSave({ username, password, role })
        }}
      >
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <Field label="Имя" htmlFor="op-username">
          <Input id="op-username" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="off" required />
        </Field>
        <Field label="Пароль" htmlFor="op-password" hint="Минимум 12 символов">
          <Input id="op-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />
        </Field>
        <Field label="Роль" htmlFor="op-role" hint={ROLE_HINTS[role]}>
          <Select id="op-role" value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="SUPER_ADMIN">SUPER_ADMIN</option>
            <option value="ADMIN">ADMIN</option>
            <option value="SUPPORT">SUPPORT</option>
            <option value="VIEWER">VIEWER</option>
          </Select>
        </Field>
        <div className="modal-actions">
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" loading={loading}>Создать</Button>
        </div>
      </form>
    </Modal>
  )
}

function PasswordModal({ row, onClose, onSave, loading }: {
  row: AdminRow; onClose: () => void; onSave: (password: string) => void; loading: boolean
}) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  return (
    <Modal title={`Сменить пароль: ${row.username}`} sub="Все сессии оператора будут отозваны" onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (password.length < 12) { setError('Пароль — минимум 12 символов'); return }
          onSave(password)
        }}
      >
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <Field label="Новый пароль" htmlFor="op-newpass" hint="Минимум 12 символов">
          <Input id="op-newpass" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus autoComplete="new-password" required />
        </Field>
        <div className="modal-actions">
          <Button type="button" onClick={onClose}>Отмена</Button>
          <Button type="submit" variant="primary" loading={loading}>Сменить</Button>
        </div>
      </form>
    </Modal>
  )
}
