import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import { ErrorState, PageLoader, StatusBadge, formatNumber } from '../components/ui'
import type { DashboardResponse } from '../api/types'

function StatCard({ label, value, foot }: { label: string; value: number; foot?: ReactNode }) {
  return (
    <div className="card hoverable">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{formatNumber(value)}</div>
      {foot ? <div className="stat-foot">{foot}</div> : null}
    </div>
  )
}

function ProviderHealth({ providers }: { providers: DashboardResponse['providers'] }) {
  const entries = Object.entries(providers)
  if (entries.length === 0) {
    return <div className="card"><span className="dim">Провайдеры не сконфигурированы</span></div>
  }
  return (
    <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
      {entries.map(([id, p]) => (
        <div key={id} className="card hoverable" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontWeight: 600, fontSize: 14 }}>{id}</div>
            <div style={{ fontSize: 12, color: 'var(--tx-3)', marginTop: 2 }}>circuit: {p.circuit}</div>
          </div>
          <StatusBadge status={p.status} />
        </div>
      ))}
    </div>
  )
}

export default function Dashboard() {
  const { data, error, isLoading, refetch } = useQuery({
    queryKey: ['dashboard'],
    queryFn: endpoints.dashboard,
    refetchInterval: 30_000
  })

  if (isLoading) {
    return (
      <div className="grid grid-stats">
        {[...Array(6)].map((_, i) => (
          <div key={i} className="card"><div className="skeleton" style={{ height: 12, width: '60%' }} /><div className="skeleton" style={{ height: 26, width: '40%', marginTop: 10 }} /></div>
        ))}
      </div>
    )
  }
  if (error) return <ErrorState error={error} retry={() => void refetch()} />
  if (!data) return <PageLoader />

  const errorRate = data.requests.today > 0
    ? Math.round((data.requests.errorsToday / data.requests.today) * 100)
    : 0

  return (
    <div>
      <div className="grid grid-stats">
        <StatCard label="Пользователи" value={data.users.total} />
        <StatCard label="Лицензии активны" value={data.licenses.active} foot={`из ${data.licenses.issued} выданных`} />
        <StatCard label="Устройства" value={data.devices.tokensActive} foot="активных токенов" />
        <StatCard label="Запросы сегодня" value={data.requests.today} foot={`${data.requests.errorsToday} ошибок (${errorRate}%)`} />
        <StatCard label="Токены сегодня" value={data.requests.tokensToday} foot="in + out" />
        <StatCard label="Заказы в ожидании" value={data.billing.ordersPending} foot="биллинг" />
      </div>

      <h2 className="section-title">AI-провайдеры</h2>
      <ProviderHealth providers={data.providers} />

      <p style={{ marginTop: 20, fontSize: 12.5, color: 'var(--tx-3)' }}>
        Локальные выполнения на устройствах сервером не наблюдаются — данные остаются на телефоне пользователя.
      </p>
    </div>
  )
}
