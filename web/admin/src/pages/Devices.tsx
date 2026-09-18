import { useState } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import {
  Button, DataTable, EmptyState, ErrorState, LoadMore, Modal, NotCollected,
  SkeletonRows, StatusBadge, ToastList, formatDateTime
} from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'

export default function Devices() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()
  const [revokeId, setRevokeId] = useState<{ id: string; accountId: string } | null>(null)

  const list = useInfiniteQuery({
    queryKey: ['devices'],
    queryFn: ({ pageParam }) => endpoints.devices(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.devices.length === 50 ? all.length : undefined)
  })

  const revoke = useMutation({
    mutationFn: (tokenId: string) => endpoints.revokeDevice(tokenId),
    onSuccess: () => {
      ok('Токен устройства отозван')
      setRevokeId(null)
      void queryClient.invalidateQueries({ queryKey: ['devices'] })
    },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось отозвать')
  })

  return (
    <div>
      <p style={{ color: 'var(--tx-3)', fontSize: 12.5, marginBottom: 14 }}>
        «Устройство» = API-токен устройства. Модель, прошивка и батарея клиентом не телеметрируются.
      </p>

      {list.isLoading ? <SkeletonRows rows={7} cols={6} /> : null}
      {list.isError ? <ErrorState error={list.error} retry={() => void list.refetch()} /> : null}

      {list.data ? (
        list.data.pages.every((p) => p.devices.length === 0) ? (
          <div className="table-wrap"><EmptyState title="Устройств нет" desc="Токены появятся после первой активации" /></div>
        ) : (
          <>
            <DataTable headers={['Токен', 'Аккаунт', 'Статус', 'Выдан', 'Использован', 'Модель', '']}>
              {list.data.pages.flatMap((page) =>
                page.devices.map((device) => (
                  <tr key={device.tokenId}>
                    <td className="mono">{device.tokenId.slice(0, 8)}…</td>
                    <td className="mono dim">{device.accountId.slice(0, 8)}…</td>
                    <td><StatusBadge status={device.status} /></td>
                    <td className="dim">{formatDateTime(device.issuedAt)}</td>
                    <td className="dim">{formatDateTime(device.lastUsedAt)}</td>
                    <td><NotCollected /></td>
                    <td style={{ textAlign: 'right' }}>
                      {can('DEVICES_REVOKE') && device.status === 'ACTIVE' ? (
                        <Button size="sm" variant="danger" onClick={() => setRevokeId({ id: device.tokenId, accountId: device.accountId })}>
                          Отозвать
                        </Button>
                      ) : null}
                    </td>
                  </tr>
                ))
              )}
            </DataTable>
            <LoadMore onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} disabled={!list.hasNextPage} />
          </>
        )
      ) : null}

      {revokeId ? (
        <Modal
          title="Отозвать токен устройства?"
          sub={`Устройство аккаунта ${revokeId.accountId.slice(0, 8)}… потеряет доступ — потребуется повторная активация`}
          onClose={() => setRevokeId(null)}
        >
          <div className="modal-actions">
            <Button onClick={() => setRevokeId(null)}>Отмена</Button>
            <Button variant="danger" loading={revoke.isPending} onClick={() => revoke.mutate(revokeId.id)}>
              Отозвать
            </Button>
          </div>
        </Modal>
      ) : null}
      <ToastList items={toasts} />
    </div>
  )
}
