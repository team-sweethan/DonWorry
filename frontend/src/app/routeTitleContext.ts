import { createContext } from 'react'

/** 본문이 403·404 등으로 대체되면 경로 제목 대신 실제 보이는 화면의 제목을 쓴다. */
export const RouteTitleOverrideContext = createContext<(title: string | null) => void>(() => {})
