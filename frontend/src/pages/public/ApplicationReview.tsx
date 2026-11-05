import { Button } from 'antd'
import { normalizeApplication, type ApplicationInput } from '../../api/merchantApplications'

export function ApplicationReview({
  values,
  disabled,
  onEdit,
}: {
  values: ApplicationInput
  disabled: boolean
  onEdit: (field: keyof ApplicationInput) => void
}) {
  const normalized = normalizeApplication(values)
  const groups: {
    title: string
    items: { field: keyof ApplicationInput; label: string; value: string }[]
  }[] = [
    {
      title: '가맹점 정보',
      items: [
        { field: 'merchantName', label: '가맹점명', value: normalized.merchantName },
        {
          field: 'businessRegistrationNumber',
          label: '사업자등록번호',
          value: values.businessRegistrationNumber,
        },
        { field: 'shopUrl', label: '쇼핑몰 주소', value: normalized.shopUrl },
      ],
    },
    {
      title: '담당자 정보',
      items: [
        { field: 'contactName', label: '담당자명', value: normalized.contactName },
        { field: 'email', label: '이메일', value: normalized.email },
        { field: 'phone', label: '연락처', value: values.phone },
      ],
    },
    {
      title: '계정 · 요청 사항',
      items: [
        { field: 'requestedLoginId', label: '로그인 아이디', value: normalized.requestedLoginId },
        { field: 'inquiry', label: '문의·요청 사항', value: normalized.inquiry || '없음' },
        { field: 'consent', label: '개인정보 동의', value: '동의함' },
      ],
    },
  ]
  return (
    <div className="dw-application-review">
      {groups.map(({ title, items }) => (
        <section className="dw-application-section" key={title} aria-label={title}>
          <h2>{title}</h2>
          <dl>
            {items.map(({ field, label, value }) => (
              <div className="dw-application-review-row" key={field}>
                <div>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
                <Button
                  type="link"
                  disabled={disabled}
                  aria-label={`${label} 수정`}
                  onClick={() => onEdit(field)}
                >
                  수정
                </Button>
              </div>
            ))}
          </dl>
        </section>
      ))}
      <p className="dw-application-account-note">
        비밀번호는 승인 후 메일의 링크에서 직접 설정합니다.
      </p>
    </div>
  )
}
