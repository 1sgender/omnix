import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { BrandMark } from '../components/shell'
import { Button, Field, Input } from '../components/ui'
import { isApiError } from '../api/client'
import { useAuth } from '../state/auth'

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (busy) return
    setError(null)
    setBusy(true)
    try {
      await login(username.trim(), password)
      navigate('/', { replace: true })
    } catch (err) {
      setError(isApiError(err) ? err.message : 'Не удалось войти')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={submit} aria-label="Вход в Control Plane">
        <div className="login-mark"><BrandMark size={56} /></div>
        <h1 style={{ fontSize: 19, textAlign: 'center' }}>OMNIX Control Plane</h1>
        <p style={{ textAlign: 'center', color: 'var(--tx-3)', fontSize: 13, margin: '4px 0 26px' }}>
          Панель управления сервером
        </p>

        {error ? <div className="form-error" role="alert">{error}</div> : null}

        <Field label="Оператор" htmlFor="login-username">
          <Input
            id="login-username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            required
          />
        </Field>
        <Field label="Пароль" htmlFor="login-password">
          <Input
            id="login-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </Field>

        <Button type="submit" variant="primary" loading={busy} style={{ width: '100%', marginTop: 6 }}>
          Войти
        </Button>
      </form>
    </div>
  )
}
