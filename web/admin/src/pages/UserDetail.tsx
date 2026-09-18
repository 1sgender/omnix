import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import {
  DataTable, ErrorState, PageLoader, StatusBadge, formatDateTime, formatNumber
} from '../components/ui'

export default function UserDetail() {
  const { id } = useParams<{ id: string }>()
  const query = useQuery({
    queryKey: ['user', id],
    queryFn: () => endpoints.user(id!),
    enabled: Boolean(id)
  })

  if (query.isLoading) return <PageLoader />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  if (!query.data) return null

  const { account, licenses, devices, usage } = query.data

  return (
    <div>
      <div className="card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          <h2 style={{ fontSize: 17 }}>{account.externalRef ?? 'Без рефа'}</h2>
          <StatusBadge status={account.status} />
          <span className="dim mono" style={{ fontSize: 12 }}>{account.id}</span>
        </div>
        <div className="grid grid-stats" style={{ marginTop: 16 }}>
          <div><div className="stat-label">Запросы</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.requests)}</div></div>
          <div><div className="stat-label">Ошибки</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.errors)}</div></div>
          <div><div className="stat-label">In-токены</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.inputTokens)}</div></div>
          <div><div className="stat-label">Out-токены</div><div className="stat-value" style={{ fontSize: 22 }}>{formatNumber(usage.outputTokens)}</div></div>
        </div>
      </div>

      <h2 className="section-title">Лицензии ({licenses.length})</h2>
      <DataTable headers={['Код', 'Статус', 'План', 'Истекает']}>
        {licenses.map((l) => (
          <tr key={l.id}>
            <td className="mono">••••{l.codeHint}</td>
            <td><StatusBadge status={l.status} /></td>
            <td>{l.planId}</td>
            <td className="dim">{formatDateTime(l.expiresAt)}</td>
          </tr>
        ))}
      </DataTable>

      <h2 className="section-title">Устройства ({devices.length})</h2>
      <DataTable headers={['Токен', 'Статус', 'Выдан', 'Последнее использование', 'Биндинг']}>
        {devices.map((d) => (
          <tr key={d.tokenId}>
            <td className="mono">{d.tokenId.slice(0, 8)}…</td>
            <td><StatusBadge status={d.status} /></td>
            <td className="dim">{formatDateTime(d.issuedAt)}</td>
            <td className="dim">{formatDateTime(d.lastUsedAt)}</td>
            <td className="mono dim">{d.deviceBinding ? `${d.deviceBinding.slice(0, 12)}…` : '—'}</td>
          </tr>
        ))}
      </DataTable>

      <p style={{ marginTop: 14 }}>
        <Link to="/users" className="btn ghost sm">← Все пользователи</Link>
      </p>
    </div>
  )
}
