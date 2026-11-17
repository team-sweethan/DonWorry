import {
  DeleteOutlined,
  EditOutlined,
  FileOutlined,
  InboxOutlined,
  MailOutlined,
  SearchOutlined,
  SendOutlined,
  StarOutlined,
} from '@ant-design/icons'
import type { ReactNode } from 'react'
import './booth-mail.css'

/**
 * 부스 시연용 — 사장님이 받는 메일 두 통을 메일함 모양 한 화면에 보여 준다 (로컬 전용).
 * 문구는 배포 서버가 실제로 보낸 메일 그대로이고, 링크 토큰과 로그인 아이디만 가렸다.
 * 특정 메일 서비스의 로고 · 상표는 쓰지 않는다.
 */
const SENDER_NAME = '돈워리'
const SENDER_ADDRESS = 'donworry2026@gmail.com'
const SITE = 'https://donworry.shinhanacademy.co.kr'

const folders = [
  { icon: <InboxOutlined />, label: '받은메일함', count: 2, current: true },
  { icon: <StarOutlined />, label: '중요메일함' },
  { icon: <SendOutlined />, label: '보낸메일함' },
  { icon: <FileOutlined />, label: '임시보관함' },
  { icon: <DeleteOutlined />, label: '휴지통' },
]

function MailLink({ path }: { path: string }) {
  return (
    <p className="booth-mail-link">
      {SITE}
      {path}#token=<span>••••••••••••••••••••••••••••••••</span>
    </p>
  )
}

function Message({
  className,
  subject,
  time,
  children,
}: {
  className: string
  subject: string
  time: string
  children: ReactNode
}) {
  return (
    <article className={`booth-mail-message ${className}`}>
      <div className="booth-mail-actions" aria-hidden="true">
        <span>답장</span>
        <span>전체답장</span>
        <span>전달</span>
        <i />
        <span>삭제</span>
        <span>스팸신고</span>
        <span>안읽음</span>
      </div>
      <h2>
        <StarOutlined className="booth-mail-star" />
        {subject}
      </h2>
      <p className="booth-mail-from">
        <span className="booth-mail-from-label">보낸사람</span>
        <span className="booth-mail-chip">
          {SENDER_NAME} &lt;{SENDER_ADDRESS}&gt;
        </span>
      </p>
      <p className="booth-mail-date">{time}</p>
      <div className="booth-mail-body">{children}</div>
    </article>
  )
}

export function BoothMailPage() {
  return (
    <main className="booth-mail">
      <header className="booth-mail-top">
        <strong className="booth-mail-brand">
          <MailOutlined /> 메일
        </strong>
        <span className="booth-mail-search">
          <SearchOutlined /> 메일 검색
        </span>
        <span className="booth-mail-me">소담 케이크 사장님</span>
      </header>

      <div className="booth-mail-frame">
        <nav className="booth-mail-side" aria-label="메일함">
          <span className="booth-mail-write">
            <EditOutlined /> 메일 쓰기
          </span>
          <ul>
            {folders.map((folder) => (
              <li key={folder.label} className={folder.current ? 'is-current' : undefined}>
                {folder.icon}
                <span>{folder.label}</span>
                {folder.count && <b>{folder.count}</b>}
              </li>
            ))}
          </ul>
        </nav>

        <section className="booth-mail-main">
          <ol className="booth-mail-list" aria-label="받은 메일">
            <li>
              <StarOutlined className="booth-mail-star" />
              <span className="booth-mail-list-from">{SENDER_NAME}</span>
              <span className="booth-mail-list-subject">
                [돈워리] 이용 승인 · 첫 비밀번호를 설정해 주세요
                <em> 이용 신청이 승인되어 가맹점과 사장님 계정이 발급되었습니다.</em>
              </span>
              <time>오전 11:01</time>
            </li>
            <li>
              <StarOutlined className="booth-mail-star" />
              <span className="booth-mail-list-from">{SENDER_NAME}</span>
              <span className="booth-mail-list-subject">
                [돈워리] 이용 신청 접수 · 이메일을 확인해 주세요
                <em> 이용 신청이 접수되었습니다. 아래 링크를 열고 이메일 확인 버튼을…</em>
              </span>
              <time>오전 11:00</time>
            </li>
          </ol>

          <div className="booth-mail-reading">
            <Message
              className="booth-mail-verify"
              subject="[돈워리] 이용 신청 접수 · 이메일을 확인해 주세요"
              time="2026년 11월 3일 (화) 오전 11:00"
            >
              <p>이용 신청이 접수되었습니다. 아래 링크를 열고 이메일 확인 버튼을 눌러 주세요.</p>
              <MailLink path="/apply/verify" />
              <p className="booth-mail-rule">
                링크는 발급 후 24시간 동안 한 번만 사용할 수 있습니다. 재발송하면 이전 링크는
                무효입니다.
              </p>
              <p>
                이메일 확인 후 관리자가 검토합니다. 신청·이메일 확인만으로 계정이 발급되지 않으며
                결제 연동은 별도 진행합니다.
              </p>
              <p>본인이 신청하지 않았다면 이 메일을 무시해 주세요.</p>
            </Message>

            <Message
              className="booth-mail-approve"
              subject="[돈워리] 이용 승인 · 첫 비밀번호를 설정해 주세요"
              time="2026년 11월 3일 (화) 오전 11:01"
            >
              <p>이용 신청이 승인되어 가맹점과 사장님 계정이 발급되었습니다.</p>
              <p>로그인 아이디: sodam</p>
              <p>아래 링크에서 본인이 사용할 비밀번호를 설정한 뒤 로그인해 주세요.</p>
              <MailLink path="/apply/password" />
              <p className="booth-mail-rule">
                링크는 발급 후 72시간 동안 한 번만 사용할 수 있습니다.
                <br />
                관리자가 비밀번호를 초기화하면 이 링크는 사용할 수 없습니다.
              </p>
              <p>
                결제 연동은 별도 진행합니다. 로그인 후 [시크릿 키 발급]에서 키와 가맹점 코드를
                확인해 쇼핑몰 서버에 설정해 주세요.
                <br />
                쇼핑몰 서버에서 서명된 연결 확인 요청을 보내 연동을 완료한 뒤 신규 결제를 요청할 수
                있습니다.
              </p>
              <p className="booth-mail-caution">시크릿 키를 메일이나 메신저로 보내지 마세요.</p>
            </Message>
          </div>

          <p className="booth-mail-foot">
            배포 서버가 실제로 보낸 메일의 문구입니다. 링크 토큰과 로그인 아이디는 가렸고, 메일함
            화면은 예시입니다.
          </p>
        </section>
      </div>
    </main>
  )
}
