import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isShopUrlValid } from '../src/utils/shopUrl.ts'

test('shopUrl_whenHttpAbsolute_acceptsAndPreservesCaseSensitiveParts', () => {
  for (const value of [
    'https://shop.example.com/Products?item=ABC',
    ' http://localhost:8080/shop ',
    'HTTPS://SHOP.EXAMPLE.COM',
    'https://[::1]/shop',
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
    'https:///shop',
    'https://shop.example.com/a b',
    'https://shop.example.com/' + 'a'.repeat(2048),
  ])
    assert.equal(isShopUrlValid(value), false, value)
})
