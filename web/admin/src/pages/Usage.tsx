import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import {
  DataTable, ErrorState, NotCollected, SkeletonRows, formatNumber
} from '../components/ui'

const PERIODS = [7, 30, 90] as const

export default function Usage() {
  const [days, setDays] = useState<number>(7)

  const summary = useQuery({ queryKey: ['usage', days], queryFn: () => endpoints.usage(days) })
  const cost = useQuery({ queryKey: ['usage-cost', days], queryFn: () => endpoints.usageCost(days) })

  return (
    <div>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Период">
          {PERIODS.map((p) => (
            <button key={p} role="tab" aria-selected={days === p} className={`tab ${days === p ? 'active' : ''}`} onClick={() => setDays(p)}>
              {p} дней
            </button>
          ))}
        </div>
      </div>

      {summary.isLoading ? (
        <div className="grid grid-stats">{[...Array(5)].map((_, i) => <div key={i} className="card"><div className="skeleton" style={{ height: 26, width: '50%' }} /></div>)}</div>
      ) : summary.isError ? (
        <ErrorState error={summary.error} retry={() => void summary.refetch()} />
      ) : summary.data ? (
        <div className="grid grid-stats">
          <div className="card hoverable"><div className="stat-label">Запросы (облако)</div><div className="stat-value">{formatNumber(summary.data.requests)}</div></div>
          <div className="card hoverable"><div className="stat-label">Ошибки</div><div className="stat-value">{formatNumber(summary.data.errors)}</div></div>
          <div className="card hoverable"><div className="stat-label">In-токены</div><div className="stat-value">{formatNumber(summary.data.inputTokens)}</div></div>
          <div className="card hoverable"><div className="stat-label">Out-токены</div><div className="stat-value">{formatNumber(summary.data.outputTokens)}</div></div>
          <div className="card hoverable"><div className="stat-label">Локальные</div><div className="stat-value" style={{ fontSize: 15, paddingTop: 6 }}><NotCollected label="на устройстве" /></div><div className="stat-foot">сервером не наблюдаются</div></div>
        </div>
      ) : null}

      <h2 className="section-title">По провайдерам</h2>
      {summary.isLoading ? <SkeletonRows rows={3} cols={5} /> : (
        <DataTable headers={['Провайдер', 'Запросы', 'Ошибки', 'In-токены', 'Out-токены']}>
          {summary.data?.byProvider.map((p) => (
            <tr key={p.provider}>
              <td style={{ fontWeight: 550 }}>{p.provider}</td>
              <td className="num">{formatNumber(p.requests)}</td>
              <td className="num">{formatNumber(p.errors)}</td>
              <td className="num">{formatNumber(p.inputTokens)}</td>
              <td className="num">{formatNumber(p.outputTokens)}</td>
            </tr>
          )) ?? null}
        </DataTable>
      )}

      <h2 className="section-title">Стоимость</h2>
      {cost.isLoading ? <SkeletonRows rows={3} cols={5} /> : cost.isError ? (
        <ErrorState error={cost.error} retry={() => void cost.refetch()} />
      ) : cost.data ? (
        <div>
          <div className="grid grid-stats" style={{ marginBottom: 16 }}>
            <div className="card"><div className="stat-label">Всего, USD</div><div className="stat-value">${cost.data.totalUsd.toFixed(4)}</div></div>
            <div className="card"><div className="stat-label">По известным тарифам</div><div className="stat-value">${cost.data.knownUsd.toFixed(4)}</div></div>
          </div>
          <DataTable headers={['Провайдер', 'In-токены', 'Out-токены', '$/1M in', '$/1M out', 'Стоимость']}>
            {cost.data.lines.map((l) => (
              <tr key={l.provider}>
                <td style={{ fontWeight: 550 }}>{l.provider}</td>
                <td className="num">{formatNumber(l.inputTokens)}</td>
                <td className="num">{formatNumber(l.outputTokens)}</td>
                <td className="num">{l.usdPerMillionInput === null ? <span className="dim">не задан</span> : `$${l.usdPerMillionInput}`}</td>
                <td className="num">{l.usdPerMillionOutput === null ? <span className="dim">не задан</span> : `$${l.usdPerMillionOutput}`}</td>
                <td className="num">${l.costUsd.toFixed(4)}</td>
              </tr>
            ))}
          </DataTable>
          {cost.data.unknownProviders.length > 0 ? (
            <p style={{ marginTop: 10, fontSize: 12.5, color: 'var(--warn)' }}>
              Без тарифов: {cost.data.unknownProviders.join(', ')} — задайте цены в Настройках → Cost.
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
