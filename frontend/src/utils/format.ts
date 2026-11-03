/** 표시 규칙: 한국어 원화, 화면에 들어오는 날짜는 API 확정 후 Asia/Seoul 변환 처리. */
export const won = (v: number) => `${new Intl.NumberFormat('ko-KR').format(v)}원`
