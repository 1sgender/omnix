import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import {
  Button, DataTable, EmptyState, ErrorState, Input, LoadMore, SkeletonRows, formatDateTime
} from '../components/ui'

export default function Audit() {
  const [actionInput, setActionInput] = useState('')
  const [actorInput, setActorInput] = useState('')
  const [action, setAction] = useState('')
  const [actor, setActor] = useState('')

  const list = useInfiniteQuery({
    queryKey: ['audit', action, actor],
    queryFn: ({ pageParam }) => endpoints.audit({
      action: action || undefined, actor: actor || undefined, page: pageParam
    }),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.events.length === 50 ? all.length : undefined)
  })

  return (
    <div>
      <div className="toolbar">
        <form
          className="grow search"
          onSubmit={(e) => { e.preventDefault(); setAction(actionInput.trim()); setActor(actorInput.trim()) }}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
            <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
          </svg>
          <Input
            placeholder="Действие (license.disable, admin.login…)"
            value={actionInput}
            onChange={(e) => setActionInput(e.target.value)}
            aria-label="Фильтр по действию"
          />
        </form>
        <form
          className="grow search"
          onSubmit={(e) => { e.preventDefault(); setAction(actionInput.trim()); setActor(actorInput.trim()) }}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
            <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
          </svg>
          <Input
            placeholder="Инициатор (admin:admin…)"
            value={actorInput}
            onChange={(e) => setActorInput(e.target.value)}
            aria-label="Фильтр по инициатору"
          />
        </form>
        {action || actor ? (
          <Button size="sm" variant="ghost" onClick={() => { setAction(''); setActor(''); setActionInput(''); setActorInput('') }}>
            Сбросить
          </Button>
        ) : null}
      </div>

      {list.isLoading ? <SkeletonRows rows={8} cols={6} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}

      {list.data ? (
        list.data.pages.every((p) => p.events.length === 0) ? (
          <div className="table-wrap"><EmptyState title="Событий нет" desc="По этим фильтрам история пуста" /></div>
        ) : (
          <>
            <DataTable headers={['Время', 'Инициатор', 'Действие', 'Объект', 'Было → стало', 'IP']}>
              {list.data.pages.flatMap((page) =>
                page.events.map((event, i) => (
                  <tr key={`${event.time}-${i}`}>
                    <td className="dim">{formatDateTime(event.time)}</td>
                    <td style={{ fontWeight: 550 }}>{event.actor}</td>
                    <td><span className="badge info solid">{event.action}</span></td>
                    <td className="dim">{event.entityType}{event.entityId ? ` · ${event.entityId.slice(0, 8)}…` : ''}</td>
                    <td className="mono dim" style={{ maxWidth: 340, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {event.oldValue !== '{}' ? `${event.oldValue} → ` : ''}{event.newValue}
                    </td>
                    <td className="mono dim">{event.remoteAddress ?? '—'}</td>
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
