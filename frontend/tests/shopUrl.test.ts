import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isShopUrlValid } from '../src/utils/shopUrl.ts'

test('shopUrl_whenHttpAbsolute_acceptsAndPreservesCaseSensitiveParts', () => {
  for (const value of [
    'https://shop.example.com/Products?item=ABC',
    ' http://localhost:8080/shop ',
    'HTTPS://SHOP.EXAMPLE.COM',
    'https://[::1]/shop',
    'https://[::1]:8443/shop',
    'https://shop.example.com./Products',
    'https://my-shop.example.com',
    'https://shop.example.com?contact=a@b.example#contact',
    'https://쇼핑몰.한국/Products?item=ABC',
    'https://쇼핑몰.한국:8443/상품?item=ABC',
    'https://xn--2j1bq83a3qd.xn--3e0b707e/Products?item=ABC',
  ])
    assert.equal(isShopUrlValid(value), true, value)
})

test('shopUrl_whenUnsafeOrMalformed_rejectsBeforeSubmitOrLinkRendering', () => {
  for (const value of [
    '',
    ' ',
    '/shop',
    'shop.example.com',
    'javascript:alert(1)',
    'ftp://shop.example.com',
    'https://user:pass@shop.example.com',
    'https://user@shop.example.com',
    'https://user:pass@쇼핑몰.한국',
    'https://@쇼핑몰.한국',
    'https:///shop',
    'https://shop.example.com/a b',
    'https://shop.example.com/' + 'a'.repeat(2048),
  ])
    assert.equal(isShopUrlValid(value), false, value)
})

// 브라우저 URL 파서가 허용해도 서버 호스트 문법에서 거절되는 주소는 제출 전에 차단한다.
test('shopUrl_whenHostnameSyntaxInvalid_rejectsBeforeConfirmation', () => {
  for (const value of [
    'https://exa_mple.com',
    'https://_shop.example.com',
    'https://-shop.example.com',
    'https://shop-.example.com',
    'https://shop..example.com',
    'https://.example.com',
    'https://exa%5fmple.com',
    'https://ex%61mple.com',
  ])
    assert.equal(isShopUrlValid(value), false, value)
})
