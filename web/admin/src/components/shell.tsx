/**
 * App shell: сайдбар (навигация по правам из /me) + топбар + контент.
 * Mobile: сайдбар в overlay, burger в топбаре.
 */
import { useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import {
  Activity, BadgeDollarSign, Boxes, Flag, Gauge, KeyRound, LogOut, Menu,
  ScrollText, ServerCog, Settings2, ShieldCheck, Users, Radio
} from 'lucide-react'
import type { AdminPermission } from '../api/types'
import { useAuth } from '../state/auth'

interface NavEntry { to: string; label: string; icon: ReactNode; permission: AdminPermission }

const NAV: { section: string; items: NavEntry[] }[] = [
  {
    section: 'Обзор',
    items: [
      { to: '/', label: 'Дашборд', icon: <Gauge size={16} />, permission: 'DASHBOARD_READ' },
      { to: '/health', label: 'Здоровье системы', icon: <Activity size={16} />, permission: 'DASHBOARD_READ' }
    ]
  },
  {
    section: 'Продукт',
    items: [
      { to: '/licenses', label: 'Лицензии', icon: <KeyRound size={16} />, permission: 'LICENSES_READ' },
      { to: '/users', label: 'Пользователи', icon: <Users size={16} />, permission: 'USERS_READ' },
      { to: '/devices', label: 'Устройства', icon: <Radio size={16} />, permission: 'DEVICES_READ' },
      { to: '/subscriptions', label: 'Подписки', icon: <BadgeDollarSign size={16} />, permission: 'SUBSCRIPTIONS_READ' }
    ]
  },
  {
    section: 'Инфраструктура',
    items: [
      { to: '/providers', label: 'AI-провайдеры', icon: <ServerCog size={16} />, permission: 'PROVIDERS_READ' },
      { to: '/usage', label: 'Использование', icon: <Boxes size={16} />, permission: 'USAGE_READ' }
    ]
  },
  {
    section: 'Наблюдаемость',
    items: [
      { to: '/logs', label: 'Логи', icon: <ScrollText size={16} />, permission: 'LOGS_READ' },
      { to: '/audit', label: 'Аудит', icon: <ShieldCheck size={16} />, permission: 'AUDIT_READ' }
    ]
  },
  {
    section: 'Управление',
    items: [
      { to: '/features', label: 'Фиче-флаги', icon: <Flag size={16} />, permission: 'FEATURES_READ' },
      { to: '/settings', label: 'Настройки', icon: <Settings2 size={16} />, permission: 'SETTINGS_READ' },
      { to: '/admins', label: 'Операторы', icon: <Users size={16} />, permission: 'ADMINS_MANAGE' }
    ]
  }
]

const PAGE_TITLES: Record<string, [string, string]> = {
  '/': ['Дашборд', 'Сводное состояние OMNIX'],
  '/health': ['Здоровье системы', 'Статус компонентов и AI-провайдеров'],
  '/licenses': ['Лицензии', 'Выдача, активация и жизненный цикл'],
  '/users': ['Пользователи', 'Аккаунты и их лицензии'],
  '/devices': ['Устройства', 'API-токены устройств'],
  '/subscriptions': ['Подписки', 'Платёжные заказы'],
  '/providers': ['AI-провайдеры', 'Роутинг и состояние провайдеров'],
  '/usage': ['Использование', 'Запросы, токены и стоимость'],
  '/logs': ['Логи', 'События компонентов'],
  '/audit': ['Аудит', 'Действия операторов'],
  '/features': ['Фиче-флаги', 'Постепенные раскатки'],
  '/settings': ['Настройки сервера', 'Секции конфигурации'],
  '/admins': ['Операторы', 'Аккаунты Control Plane']
}

export function BrandMark({ size = 34 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden focusable="false">
      <circle cx="32" cy="32" r="14.5" fill="none" stroke="#E9EDF4" strokeWidth="3.2" opacity="0.92" />
      <ellipse cx="32" cy="32" rx="25" ry="9" fill="none" stroke="#E9EDF4" strokeWidth="1.4" opacity="0.34" transform="rotate(-24 32 32)" />
      <circle cx="49.5" cy="22.5" r="2.6" fill="#E9EDF4" opacity="0.85" />
    </svg>
  )
}

export function AppShell({ children }: { children: ReactNode }) {
  const { me, logout, can } = useAuth()
  const [navOpen, setNavOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()

  const title = PAGE_TITLES[location.pathname] ?? (
    location.pathname.startsWith('/users/')
      ? ['Пользователь', 'Профиль аккаунта']
      : ['Control Plane', '']
  )

  const sections = NAV.map((s) => ({
    ...s,
    items: s.items.filter((i) => can(i.permission))
  })).filter((s) => s.items.length > 0)

  return (
    <div className="shell">
      <button
        className="btn ghost sm burger"
        onClick={() => setNavOpen(true)}
        aria-label="Открыть меню"
        style={{ display: 'none' }}
        id="burger-open"
      />
      <aside className={`sidebar ${navOpen ? 'open' : ''}`} aria-label="Навигация">
        <div className="brand">
          <span className="brand-mark"><BrandMark /></span>
          <div>
            <div className="brand-name">OMNIX</div>
            <div className="brand-sub">Control Plane</div>
          </div>
        </div>
        <nav className="nav">
          {sections.map((section) => (
            <div key={section.section}>
              <div className="nav-label">{section.section}</div>
              {section.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                  onClick={() => setNavOpen(false)}
                >
                  {item.icon}
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="user-box">
          <span className="brand-mark" style={{ width: 28, height: 28, borderRadius: 8 }}>
            <BrandMark size={18} />
          </span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="user-name" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{me?.actor ?? '…'}</div>
            <div className="user-role">{me?.role ?? ''}</div>
          </div>
          <button
            className="btn ghost sm"
            onClick={async () => { await logout(); navigate('/login') }}
            aria-label="Выйти"
            title="Выйти"
          >
            <LogOut size={15} />
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <button
            className="btn ghost sm"
            onClick={() => setNavOpen(true)}
            aria-label="Меню"
            ref={(el) => { if (el) el.classList.add('burger') }}
          >
            <Menu size={17} />
          </button>
          <div>
            <h1>{title[0]}</h1>
            <div className="sub">{title[1]}</div>
          </div>
          <div className="topbar-right">
            <span className="badge muted solid" style={{ fontSize: 10.5 }}>{me?.role}</span>
          </div>
        </header>
        <main className="content">
          <div className="page" key={location.pathname}>{children}</div>
        </main>
      </div>

      {navOpen ? (
        <div className="drawer-overlay" style={{ zIndex: 39 }} onMouseDown={() => setNavOpen(false)} />
      ) : null}
    </div>
  )
}
