import { Alert, Button, Typography } from 'antd'

export function IntegrationCredentials({
  merchantCode,
  secret,
  onCopyError,
}: {
  merchantCode: string
  secret: string
  onCopyError: (message: string) => void
}) {
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
    } catch {
      onCopyError('클립보드에 복사할 수 없습니다. 값을 직접 선택해 복사해 주세요.')
    }
  }
  return (
    <>
      <Alert
        type="warning"
        showIcon
        message="가맹점 코드와 시크릿 키를 모두 쇼핑몰 서버에 설정하세요"
        description="키는 쇼핑몰 서버에만 보관하세요. 이 창을 닫거나 다른 화면으로 이동하면 표시된 키를 지웁니다."
      />
      <Typography.Paragraph style={{ marginTop: 16 }}>
        가맹점 코드
        <br />
        <Typography.Text code>{merchantCode}</Typography.Text>
      </Typography.Paragraph>
      <Button onClick={() => void copy(merchantCode)}>가맹점 코드 복사</Button>
      <Typography.Paragraph style={{ overflowWrap: 'anywhere', marginTop: 16 }}>
        시크릿 키 (Base64)
        <br />
        <Typography.Text code>{secret}</Typography.Text>
      </Typography.Paragraph>
      <Button onClick={() => void copy(secret)}>시크릿 키 복사</Button>
      <Typography.Paragraph style={{ marginTop: 16 }}>
        두 값을 반영해 쇼핑몰 서버를 배포한 뒤 연결 확인 요청을 보내 주세요. 204 응답을 받으면 이
        창을 닫고 새로고침해 연동 완료 상태를 확인하세요.
      </Typography.Paragraph>
    </>
  )
}
