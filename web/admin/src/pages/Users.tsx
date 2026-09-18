import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { endpoints } from '../api/endpoints'
import {
  DataTable, EmptyState, ErrorState, LoadMore, SkeletonRows, StatusBadge,
  formatDateTime, formatNumber
} from '../components/ui'

export default function Users() {
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')

  const list = useInfiniteQuery({
    queryKey: ['users', query],
    queryFn: ({ pageParam }) => endpoints.users({ q: query || undefined, page: pageParam }),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.users.length === 50 ? all.length : undefined)
  })

  return (
    <div>
      <div className="toolbar">
        <div className="grow search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
            <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
          </svg>
          <form
            onSubmit={(e) => { e.preventDefault(); setQuery(search.trim()) }}
            role="search"
          >
            <input
              className="input"
              placeholder="Поиск по внешнему рефу…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label="Поиск пользователей"
            />
          </form>
        </div>
      </div>

      {list.isLoading ? <SkeletonRows rows={7} cols={6} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}

      {list.data ? (
        list.data.pages.every((p) => p.users.length === 0) ? (
          <div className="table-wrap">
            <EmptyState
              title={query ? 'Ничего не найдено' : 'Пользователей нет'}
              desc={query ? 'Попробуйте другой запрос' : 'Аккаунты появятся после первой активации устройства'}
            />
          </div>
        ) : (
          <>
            <DataTable headers={['Реф', 'Статус', 'Лицензии', 'Активные', 'Создан', 'Активность']}>
              {list.data.pages.flatMap((page) =>
                page.users.map((user) => (
                  <tr
                    key={user.id}
                    className="row-link"
                    onClick={() => navigate(`/users/${user.id}`)}
                    tabIndex={0}
                    onKeyDown={(e) => { if (e.key === 'Enter') navigate(`/users/${user.id}`) }}
                  >
                    <td style={{ fontWeight: 550 }}>{user.externalRef ?? <span className="dim">—</span>}</td>
                    <td><StatusBadge status={user.status} /></td>
                    <td className="num">{formatNumber(user.licenses)}</td>
                    <td className="num">{formatNumber(user.activeLicenses)}</td>
                    <td className="dim">{formatDateTime(user.createdAt)}</td>
                    <td className="dim">{formatDateTime(user.lastActiveAt)}</td>
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
