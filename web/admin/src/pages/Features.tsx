import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { endpoints } from '../api/endpoints'
import type { FlagRow } from '../api/types'
import { Badge, ErrorState, SkeletonRows, ToastList } from '../components/ui'
import { useToasts } from '../hooks/useToasts'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'

export default function Features() {
  const { can } = useAuth()
  const { toasts, ok, err } = useToasts()
  const queryClient = useQueryClient()

  const query = useQuery({ queryKey: ['flags'], queryFn: endpoints.flags })
  const update = useMutation({
    mutationFn: (args: { key: string; body: { enabled: boolean; rolloutPercent?: number } }) =>
      endpoints.putFlag(args.key, args.body),
    onSuccess: () => {
      ok('Флаг обновлён')
      void queryClient.invalidateQueries({ queryKey: ['flags'] })
    },
    onError: (e) => err(isApiError(e) ? e.message : 'Не удалось обновить флаг')
  })

  if (query.isLoading) return <SkeletonRows rows={4} cols={3} />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  if (!query.data) return null

  return (
    <div className="table-wrap">
      {query.data.flags.map((flag) => (
        <FlagRowView
          key={flag.key}
          flag={flag}
          editable={can('FEATURES_WRITE')}
          pending={update.isPending && update.variables?.key === flag.key}
          onChange={(enabled, rolloutPercent) => update.mutate({ key: flag.key, body: { enabled, rolloutPercent } })}
        />
      ))}
      <ToastList items={toasts} />
    </div>
  )
}

function FlagRowView({ flag, editable, pending, onChange }: {
  flag: FlagRow
  editable: boolean
  pending: boolean
  onChange: (enabled: boolean, rolloutPercent?: number) => void
}) {
  const [rollout, setRollout] = useState(flag.rolloutPercent)

  return (
    <div className="flag-row">
      <div className="info">
        <div className="name" style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {flag.key}
          {flag.enabled ? <Badge tone="ok">вкл · {flag.rolloutPercent}%</Badge> : <Badge tone="muted">выкл</Badge>}
        </div>
        <div className="desc">{flag.description || 'Описание не задано'}</div>
      </div>
      {editable ? (
        <>
          <input
            className="range"
            type="range" min={0} max={100} step={5}
            value={rollout}
            onChange={(e) => setRollout(Number(e.target.value))}
            onMouseUp={() => onChange(true, rollout)}
            onTouchEnd={() => onChange(true, rollout)}
            onKeyUp={() => onChange(true, rollout)}
            aria-label={`Процент раскатки ${flag.key}`}
            disabled={pending}
          />
          <button
            className="btn sm"
            onClick={() => onChange(!flag.enabled, flag.enabled ? 0 : 100)}
            disabled={pending}
            aria-pressed={flag.enabled}
          >
            {flag.enabled ? 'Выключить' : 'Включить'}
          </button>
        </>
      ) : null}
    </div>
  )
}
