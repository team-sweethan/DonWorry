// 보고서 안 링크 이동 (#559) — 카드로 스크롤하고 잠깐 테두리로 강조한다
export function goToCard(target: string) {
  const card = document.getElementById(target)
  if (!card) return
  card.scrollIntoView({ behavior: 'smooth', block: 'center' })
  card.animate([{ boxShadow: '0 0 0 3px #A78BFA' }, { boxShadow: '0 0 0 0 transparent' }], {
    duration: 1600,
  })
}
