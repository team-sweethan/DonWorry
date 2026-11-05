import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { createServer } from 'vite'

// 첫 조회에서는 빈 목록 안내나 가짜 상세 링크 대신 접근 가능한 로딩 자리만 보여 준다.
test('adminDirectory_whenFirstLoading_showsSkeletonWithoutEmptyResult', async () => {
  const server = await createServer({
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    for (const [file, name, path, label] of [
      ['AdminUsersPage', 'AdminUsersPage', '/admin/users', '계정 목록을 불러오는 중'],
      ['AdminMerchantsPage', 'AdminMerchantsPage', '/admin/merchants', '가맹점 목록을 불러오는 중'],
    ]) {
      const module = await server.ssrLoadModule(`/src/pages/admin/${file}.tsx`)
      const html = renderToStaticMarkup(
        createElement(MemoryRouter, { initialEntries: [path!] }, createElement(module[name!])),
      )
      assert.ok(html.includes(`aria-label="${label}"`))
      assert.match(html, /role="status"/)
      assert.match(html, /directory-table-skeleton/)
      assert.doesNotMatch(html, /아직 등록된|검색 조건에 맞는/)
      assert.doesNotMatch(html, /href="\/admin\/(users|merchants)\/\d/)
    }
    const { DirectoryDetailSkeleton } = await server.ssrLoadModule(
      '/src/pages/admin/DirectoryLoading.tsx',
    )
    for (const [kind, label] of [
      ['user', '계정 상세를 불러오는 중'],
      ['merchant', '가맹점 상세를 불러오는 중'],
    ]) {
      const html = renderToStaticMarkup(createElement(DirectoryDetailSkeleton, { kind }))
      assert.ok(html.includes(`aria-label="${label}"`))
      assert.match(html, /role="status"/)
      assert.match(html, /aria-hidden="true"/)
      assert.doesNotMatch(html, /<button|<input|<a\s/)
    }
  } finally {
    await server.close()
  }
})
