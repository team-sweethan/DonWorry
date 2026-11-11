import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import {
  getCheckoutResult,
  getCheckoutSession,
  payCheckout,
  type CheckoutResult,
  type CheckoutSession,
} from '../../api/checkout'
import './CheckoutPage.css'

const POLL_MS = 2000
const DEVICE_KEY = 'donworry_checkout_device'
const won = (value: number) => `${value.toLocaleString('ko-KR')}원`
const issuerLabel = (country: string) => (country === 'KR' ? '국내 발급' : `해외 발급 (${country})`)

// 결제창 기기 식별자. 브라우저마다 한 번 만들고 FDS 입력(새 기기)에만 쓴다.
function deviceId() {
  try {
    const saved = window.localStorage.getItem(DEVICE_KEY)
    if (saved) return saved
    const created = crypto.randomUUID()
    window.localStorage.setItem(DEVICE_KEY, created)
    return created
  } catch {
    return crypto.randomUUID()
  }
}

// 이동 신호를 보낸 뒤 이 시간 안에 가맹점 화면이 넘어가지 않으면 직접 이동 버튼을 보인다.
const LEAVE_FALLBACK_MS = 4000

// 가맹점 화면 안 레이어(iframe)로 열렸으면 가맹점 페이지가 이동하도록 알리고, 단독으로 열렸으면 직접 이동한다.
function leave(url: string) {
  if (window.parent !== window) {
    window.parent.postMessage({ type: 'donworry-checkout', redirectUrl: url }, new URL(url).origin)
    return
  }
  window.location.assign(url)
}

export function CheckoutPage() {
  const { sessionKey = '' } = useParams()
  const [session, setSession] = useState<CheckoutSession | null>(null)
  const [card, setCard] = useState('A')
  // [카드 추가] 모의 등록 (#766). 가상 카드사에서 등록했다고 가정하고 이 결제창에서만 쓴다(카드번호는 받지 않음)
  const [registering, setRegistering] = useState(false)
  const [cardAdded, setCardAdded] = useState(false)
  const [phase, setPhase] = useState<
    'loading' | 'ready' | 'paying' | 'waiting' | 'leaving' | 'error'
  >('loading')
  const [error, setError] = useState('')
  // 가맹점 화면이 이동 신호를 받지 못하면(주소가 성공 주소와 다른 출처 등) 직접 이동할 버튼을 보인다
  const [stuckUrl, setStuckUrl] = useState<string | null>(null)
  const busy = useRef(false)

  // 결과 · 취소 · 만료 뒤 쇼핑몰로 돌아가는 공통 경로 (PR #425 리뷰)
  // 이동 신호를 보내고 이동 중 화면을 보이며, 가맹점 화면이 넘어가지 않으면 직접 이동 링크를 보인다
  const goTo = (url: string) => {
    setPhase('leaving')
    leave(url)
    window.setTimeout(() => setStuckUrl(url), LEAVE_FALLBACK_MS)
  }

  const follow = (result: CheckoutResult) => {
    if (result.redirectUrl) {
      goTo(result.redirectUrl)
      return
    }
    setPhase('waiting')
  }

  useEffect(() => {
    const controller = new AbortController()
    getCheckoutSession(sessionKey, controller.signal)
      .then((loaded) => {
        setSession(loaded)
        if (loaded.result) follow(loaded.result)
        else setPhase(loaded.expired ? 'error' : 'ready')
        if (loaded.expired) setError('결제 가능 시간이 지났습니다. 쇼핑몰에서 다시 결제해 주세요.')
      })
      .catch(() => {
        if (controller.signal.aborted) return
        setPhase('error')
        setError('결제창을 찾을 수 없습니다.')
      })
    return () => controller.abort()
  }, [sessionKey])

  // 레이어(iframe)로 열렸으면 결제창 높이를 가맹점 화면에 알려 iframe 높이를 맞추게 한다(스크롤 없이 보이게).
  const returnOrigin = session?.returnOrigin
  useEffect(() => {
    if (!returnOrigin || window.parent === window) return
    const post = () =>
      window.parent.postMessage(
        { type: 'donworry-checkout-resize', height: document.documentElement.scrollHeight },
        returnOrigin,
      )
    const observer = new ResizeObserver(post)
    observer.observe(document.body)
    post()
    return () => observer.disconnect()
  }, [returnOrigin])

  // 승인 전 처리 중이면 결과가 나올 때까지 다시 조회한다.
  useEffect(() => {
    if (phase !== 'waiting') return
    const controller = new AbortController()
    const timer = window.setInterval(() => {
      getCheckoutResult(sessionKey, controller.signal)
        .then((result) => {
          if (!result.inProgress) follow(result)
        })
        .catch(() => undefined)
    }, POLL_MS)
    return () => {
      window.clearInterval(timer)
      controller.abort()
    }
  }, [phase, sessionKey])

  const pay = async () => {
    if (busy.current) return
    busy.current = true
    setPhase('paying')
    setError('')
    try {
      follow(await payCheckout(sessionKey, card, deviceId()))
    } catch (e) {
      busy.current = false
      setPhase('ready')
      setError(
        e instanceof ApiError && e.status === 409
          ? '이미 진행 중인 결제가 있거나 결제 가능 시간이 지났습니다.'
          : '결제를 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    }
  }

  return (
    <div className="checkout-bg">
      <section className="checkout-win" aria-labelledby="checkout-title">
        <header className="checkout-top">
          <b id="checkout-title" className="checkout-brand">
            돈워리 <span>결제</span>
          </b>
          {session && <span className="checkout-muted">{session.orderRef}</span>}
        </header>

        {session && (
          <dl className="checkout-summary">
            <div className="checkout-row">
              <dt>가맹점</dt>
              <dd>
                <b>{session.merchantName}</b>
              </dd>
            </div>
            <div className="checkout-row">
              <dt>상품</dt>
              <dd>{session.orderName}</dd>
            </div>
            {session.buyerEmail && (
              <div className="checkout-row">
                <dt>구매자</dt>
                <dd>{session.buyerEmail}</dd>
              </div>
            )}
            <div className="checkout-row checkout-row--amount">
              <dt>결제 금액</dt>
              <dd>
                <b>{won(session.amount)}</b>
              </dd>
            </div>
          </dl>
        )}

        {phase === 'loading' && (
          <p className="checkout-body checkout-muted">결제창을 불러오는 중입니다.</p>
        )}

        {session && (phase === 'ready' || phase === 'paying') && (
          <div className="checkout-body">
            <fieldset className="checkout-cards" disabled={phase === 'paying'}>
              <legend>결제 카드</legend>
              <p className="checkout-muted">시연용 가상 카드입니다. 실제로 청구되지 않습니다.</p>
              {(cardAdded && session.addableCard
                ? [...session.cards, session.addableCard]
                : session.cards
              ).map((option) => (
                <label key={option.code} className="checkout-card">
                  <input
                    type="radio"
                    name="card"
                    value={option.code}
                    checked={card === option.code}
                    onChange={() => setCard(option.code)}
                  />
                  <span
                    className="checkout-card__face"
                    style={{ background: option.color }}
                    aria-hidden="true"
                  />
                  <span>
                    <b>{option.label}</b>
                    <br />
                    <span className="checkout-muted">
                      {option.masked} · {issuerLabel(option.issuerCountry)}
                    </span>
                  </span>
                </label>
              ))}
              {session.addableCard &&
                !cardAdded &&
                (registering ? (
                  <div className="checkout-register" role="group" aria-label="카드 추가">
                    <b>{session.addableCard.label} 등록 (모의)</b>
                    <span className="checkout-muted">
                      가상 카드사에서 새 카드를 등록했다고 가정합니다. 카드번호는 입력하지 않습니다.
                    </span>
                    <div className="checkout-register__actions">
                      <button
                        type="button"
                        className="checkout-pay"
                        onClick={() => {
                          if (!session.addableCard) return
                          setCardAdded(true)
                          setRegistering(false)
                          setCard(session.addableCard.code)
                        }}
                      >
                        등록
                      </button>
                      <button
                        type="button"
                        className="checkout-cancel"
                        onClick={() => setRegistering(false)}
                      >
                        취소
                      </button>
                    </div>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="checkout-add"
                    onClick={() => setRegistering(true)}
                  >
                    + 카드 추가
                  </button>
                ))}
            </fieldset>
            <p className="checkout-note">
              결제 보안 확인을 위해 접속 IP와 기기 정보가 사용됩니다. 카드번호는 쇼핑몰에 전달되지
              않습니다.
            </p>
            {error && (
              <p className="checkout-error" role="alert">
                {error}
              </p>
            )}
            {phase === 'paying' ? (
              <div className="checkout-processing" role="status">
                <span className="checkout-spin" aria-hidden="true" />
                <b>결제를 처리하고 있습니다</b>
                <span className="checkout-muted">
                  FDS가 위험 점수를 계산하는 중입니다. 창을 닫지 마세요.
                </span>
              </div>
            ) : (
              <>
                <button type="button" className="checkout-pay" onClick={pay}>
                  {won(session.amount)} 결제하기
                </button>
                <button
                  type="button"
                  className="checkout-cancel"
                  onClick={() => goTo(session.cancelUrl)}
                >
                  결제 취소
                </button>
              </>
            )}
          </div>
        )}

        {(phase === 'waiting' || phase === 'leaving') && (
          <div className="checkout-result" role="status">
            <span className="checkout-spin" aria-hidden="true" />
            <b>{phase === 'waiting' ? '결제를 처리하고 있습니다' : '쇼핑몰 화면으로 이동합니다'}</b>
            <span className="checkout-muted">잠시만 기다려 주세요.</span>
            {phase === 'leaving' && stuckUrl && (
              // 쇼핑몰 화면 전체를 결과 주소로 옮긴다. 사용자가 누른 링크라 iframe 안에서도 상위 화면 이동이 허용된다
              <a className="checkout-cancel" href={stuckUrl} target="_top" rel="noopener">
                쇼핑몰 화면으로 이동
              </a>
            )}
          </div>
        )}

        {phase === 'error' && (
          <div className="checkout-result" role="alert">
            <b>{error}</b>
            {session && (
              <button
                type="button"
                className="checkout-cancel"
                onClick={() => goTo(session.cancelUrl)}
              >
                쇼핑몰로 돌아가기
              </button>
            )}
          </div>
        )}
      </section>
    </div>
  )
}
