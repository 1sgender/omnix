import { useQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import { ErrorState, StatusBadge } from '../components/ui'

const COMPONENT_LABELS: Record<string, string> = {
  api: 'API', database: 'База данных', aiGateway: 'AI-шлюз',
  authentication: 'Аутентификация', licenseService: 'Лицензии'
}

export default function Health() {
  const { data, error, isLoading, refetch } = useQuery({
    queryKey: ['health'],
    queryFn: endpoints.health,
    refetchInterval: 15_000
  })

  if (isLoading) return <div className="grid grid-stats">{[...Array(5)].map((_, i) => <div key={i} className="card"><div className="skeleton" style={{ height: 14, width: '70%' }} /></div>)}</div>
  if (error) return <ErrorState error={error} retry={() => void refetch()} />
  if (!data) return null

  const core: [string, string][] = [
    ['api', data.api.status], ['database', data.database.status],
    ['aiGateway', data.aiGateway.status], ['authentication', data.authentication.status],
    ['licenseService', data.licenseService.status]
  ]

  return (
    <div>
      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))' }}>
        {core.map(([key, status]) => (
          <div key={key} className="card hoverable" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontWeight: 550 }}>{COMPONENT_LABELS[key]}</span>
            <StatusBadge status={status} />
          </div>
        ))}
      </div>

      <h2 className="section-title">Провайдеры</h2>
      <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
        {Object.entries(data.providers).map(([id, p]) => (
          <div key={id} className="card hoverable">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <strong>{id}</strong>
              <StatusBadge status={p.status} />
            </div>
            <div style={{ fontSize: 12.5, color: 'var(--tx-3)' }}>circuit: {p.circuit}</div>
            {p.permanentReason ? (
              <div style={{ fontSize: 12.5, color: 'var(--err)', marginTop: 4 }}>{p.permanentReason}</div>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  )
}
