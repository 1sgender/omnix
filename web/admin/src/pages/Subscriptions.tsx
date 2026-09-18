import { useInfiniteQuery } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import {
  DataTable, EmptyState, ErrorState, LoadMore, SkeletonRows, StatusBadge,
  formatDateTime, formatMoney
} from '../components/ui'

export default function Subscriptions() {
  const list = useInfiniteQuery({
    queryKey: ['subscriptions'],
    queryFn: ({ pageParam }) => endpoints.subscriptions(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.orders.length === 50 ? all.length : undefined)
  })

  if (list.isLoading) return <SkeletonRows rows={6} cols={7} />
  if (list.isError) return <ErrorState error={list.error} retry={() => void list.refetch()} />

  return (
    <div>
      {list.data?.pages.every((p) => p.orders.length === 0) ? (
        <div className="table-wrap"><EmptyState title="Заказов нет" desc="Платёжные заказы появятся после первого чекаута" /></div>
      ) : (
        <>
          <DataTable headers={['Заказ', 'Аккаунт', 'План', 'Провайдер', 'Статус', 'Сумма', 'Создан', 'Оплачен']}>
            {list.data?.pages.flatMap((page) =>
              page.orders.map((order) => (
                <tr key={order.id}>
                  <td className="mono">{order.id.slice(0, 8)}…</td>
                  <td className="mono dim">{order.accountId.slice(0, 8)}…</td>
                  <td>{order.planId}</td>
                  <td>{order.provider}</td>
                  <td><StatusBadge status={order.status} /></td>
                  <td className="num">{formatMoney(order.amountMinor, order.currency)}</td>
                  <td className="dim">{formatDateTime(order.createdAt)}</td>
                  <td className="dim">{formatDateTime(order.paidAt)}</td>
                </tr>
              ))
            ) ?? null}
          </DataTable>
          <LoadMore onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} disabled={!list.hasNextPage} />
        </>
      )}
    </div>
  )
}
