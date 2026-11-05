import { useState, type ReactNode } from 'react'
import {
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CreditCardOutlined,
  DollarOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  LineChartOutlined,
  MailOutlined,
  MessageOutlined,
  PhoneOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  ShopOutlined,
  TeamOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { Mascot } from '../../components/brand/BrandMark'

type Tile = { icon: ReactNode; tone: 'navy' | 'gold' | 'blue' | 'green' | 'coral' | 'white' }

const apps: Tile[] = [
  { icon: <SafetyCertificateOutlined />, tone: 'navy' },
  { icon: <FileSearchOutlined />, tone: 'white' },
  { icon: <WalletOutlined />, tone: 'gold' },
  { icon: <BellOutlined />, tone: 'coral' },
  { icon: <CreditCardOutlined />, tone: 'blue' },
  { icon: <BarChartOutlined />, tone: 'white' },
  { icon: <CheckCircleOutlined />, tone: 'green' },
  { icon: <AuditOutlined />, tone: 'navy' },
  { icon: <ShopOutlined />, tone: 'gold' },
  { icon: <TeamOutlined />, tone: 'blue' },
  { icon: <ClockCircleOutlined />, tone: 'white' },
  { icon: <FileTextOutlined />, tone: 'coral' },
  { icon: <LineChartOutlined />, tone: 'green' },
  { icon: <DollarOutlined />, tone: 'navy' },
  { icon: <MailOutlined />, tone: 'blue' },
  { icon: <SettingOutlined />, tone: 'white' },
]
const dock: Tile[] = [
  { icon: <PhoneOutlined />, tone: 'green' },
  { icon: <SafetyCertificateOutlined />, tone: 'blue' },
  { icon: <MessageOutlined />, tone: 'gold' },
  { icon: <BellOutlined />, tone: 'coral' },
]

const weekday = new Intl.DateTimeFormat('ko-KR', { weekday: 'long' })
const clock = new Intl.DateTimeFormat('ko-KR', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function Tiles({ items }: { items: Tile[] }) {
  return items.map((item, index) => (
    <span key={index} className={`dw-signin-tile is-${item.tone}`}>
      {item.icon}
    </span>
  ))
}

/** 펼치기 전 바깥 화면. 잠깐 보이는 장식이라 실제 기능과 연결하지 않고, 날짜와 시각만 실제 값을 쓴다. */
export function LoginOuterScreen() {
  const [now] = useState(() => new Date())
  return (
    <>
      <div className="dw-signin-home">
        <div className="dw-signin-widgets">
          <div className="dw-signin-widget is-date">
            <span>{weekday.format(now)}</span>
            <strong>{now.getDate()}</strong>
          </div>
          <div className="dw-signin-widget is-brand">
            <Mascot size={92} />
            <span>돈워리</span>
          </div>
        </div>
        <div className="dw-signin-apps">
          <Tiles items={apps} />
        </div>
      </div>
      <div className="dw-signin-rail">
        <i className="dw-signin-camera" />
        <span className="dw-signin-clock">{clock.format(now)}</span>
        <div className="dw-signin-dock">
          <Tiles items={dock} />
        </div>
        <span className="dw-signin-search">
          <SearchOutlined />
        </span>
      </div>
    </>
  )
}
