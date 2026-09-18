import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import {
  DataTable, EmptyState, ErrorState, LoadMore, SkeletonRows, StatusBadge, formatDateTime
} from '../components/ui'

const COMPONENTS = ['', 'CLOUD', 'LICENSE', 'BILLING', 'CLIP'] as const

export default function Logs() {
  const [component, setComponent] = useState<string>('')

  const list = useInfiniteQuery({
    queryKey: ['logs', component],
    queryFn: ({ pageParam }) => endpoints.logs({ component: component || undefined, page: pageParam }),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.logs.length === 50 ? all.length : undefined)
  })

  return (
    <div>
      <div className="toolbar">
        <div className="tabs" role="tablist" aria-label="Компонент">
          {COMPONENTS.map((c) => (
            <button
              key={c || 'all'}
              role="tab"
              aria-selected={component === c}
              className={`tab ${component === c ? 'active' : ''}`}
              onClick={() => setComponent(c)}
            >
              {c || 'Все'}
            </button>
          ))}
        </div>
      </div>

      {list.isLoading ? <SkeletonRows rows={8} cols={6} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}

      {list.data ? (
        list.data.pages.every((p) => p.logs.length === 0) ? (
          <div className="table-wrap"><EmptyState title="Логов нет" desc="События этого компонента пока не происходили" /></div>
        ) : (
          <>
            <DataTable headers={['Время', 'Компонент', 'Тип', 'Инициатор', 'Результат', 'Задержка', 'Request ID']}>
              {list.data.pages.flatMap((page) =>
                page.logs.map((log, i) => (
                  <tr key={`${log.time}-${i}`}>
                    <td className="dim">{formatDateTime(log.time)}</td>
                    <td>{log.component}</td>
                    <td>{log.type}</td>
                    <td className="dim">{log.actor}</td>
                    <td><StatusBadge status={log.result} /></td>
                    <td className="num">{log.latencyMs === null ? '—' : `${log.latencyMs} мс`}</td>
                    <td className="mono dim">{log.requestId ?? '—'}</td>
                  </tr>
                ))
              )}
            </DataTable>
            <LoadMore onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} disabled={!list.hasNextPage} />
          </>
        )
      ) : null}
    </div>
  )
}
