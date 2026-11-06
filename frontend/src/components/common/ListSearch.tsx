import { useState } from 'react'
import { Input } from 'antd'

/** 입력창을 다시 만들지 않고 URL의 검색어만 따라가 검색 뒤에도 포커스를 유지한다. */
export function ListSearch({
  label,
  placeholder,
  keyword,
  onSearch,
}: {
  label: string
  placeholder: string
  keyword: string
  onSearch: (keyword: string) => void
}) {
  const [value, setValue] = useState(keyword)
  const [syncedKeyword, setSyncedKeyword] = useState(keyword)
  if (syncedKeyword !== keyword) {
    setSyncedKeyword(keyword)
    setValue(keyword)
  }
  return (
    <Input.Search
      aria-label={label}
      placeholder={placeholder}
      value={value}
      maxLength={100}
      allowClear
      onChange={(event) => setValue(event.target.value)}
      onSearch={onSearch}
    />
  )
}
