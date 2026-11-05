import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  formatBusinessNumber,
  formatPhone,
  formatApplicationEdit,
  isBusinessNumberValid,
  loginIdChecks,
  passwordChecks,
} from '../src/pages/public/applicationFormat.ts'

// 입력 도중과 붙여넣기 모두 숫자를 잃지 않고 사업자번호 구분자를 넣는다.
test('formatBusinessNumber_whenTyping_formatsWithoutDiscardingInput', () => {
  for (const [input, expected] of [
    ['', ''],
    ['1', '1'],
    ['1234', '123-4'],
    ['123456', '123-45-6'],
    ['1234567890', '123-45-67890'],
    ['123 45 67890', '123-45-67890'],
    ['12345678901', '12345678901'],
    ['123a456789', '123a456789'],
  ])
    assert.equal(formatBusinessNumber(input!), expected)
})

// 국내 패턴만 포맷하고 해외 번호·알 수 없는 접두사·과한 길이는 그대로 둔다.
test('formatPhone_whenKnownKoreanPattern_formatsOnlySupportedNumbers', () => {
  for (const [input, expected] of [
    ['01012345678', '010-1234-5678'],
    ['0111234567', '011-123-4567'],
    ['021234567', '02-123-4567'],
    ['0212345678', '02-1234-5678'],
    ['0311234567', '031-123-4567'],
    ['0441234567', '044-123-4567'],
    ['07012345678', '070-1234-5678'],
    ['15881234', '1588-1234'],
    ['16441234', '1644-1234'],
    ['18001234', '1800-1234'],
    ['0101', '010-1'],
    ['0101234', '010-123-4'],
    ['+1 415 555 2671', '+1 415 555 2671'],
    ['123456789', '123456789'],
    ['010123456789', '010123456789'],
    ['159912345', '159912345'],
    ['010-1234-5678', '010-1234-5678'],
    ['010ABC', '010ABC'],
  ])
    assert.equal(formatPhone(input!), expected, input)
})

// 체크섬은 알려진 번호와 마지막 검증 숫자 변조를 구분한다.
test('isBusinessNumberValid_whenChecksumDiffers_returnsFalse', () => {
  assert.equal(isBusinessNumberValid('220-81-62517'), true)
  assert.equal(isBusinessNumberValid('1248100998'), true)
  for (const value of ['2208162518', '1234567890', '0000000000', '220816251', '22081a2517']) {
    assert.equal(isBusinessNumberValid(value), false, value)
  }
})

// 중간 삽입·범위 교체·전체 붙여넣기 후 커서는 같은 숫자 뒤를 가리킨다.
test('formatApplicationEdit_whenEditingMiddle_preservesLogicalCaret', () => {
  assert.deepEqual(
    formatApplicationEdit('1239456789', 4, 4, '123-45-6789', 'insertText', formatBusinessNumber),
    { value: '123-94-56789', start: 5, end: 5 },
  )
  assert.deepEqual(
    formatApplicationEdit('010-923-5678', 5, 5, '010-1234-5678', 'insertText', formatPhone),
    { value: '010-923-5678', start: 5, end: 5 },
  )
  assert.deepEqual(
    formatApplicationEdit('1234567890', 10, 10, '', 'insertFromPaste', formatBusinessNumber),
    { value: '123-45-67890', start: 12, end: 12 },
  )
  assert.deepEqual(formatApplicationEdit('123456', 2, 4, '', 'insertText', formatBusinessNumber), {
    value: '123-45-6',
    start: 2,
    end: 5,
  })
})

// 자동 구분자에 붙은 삭제 키도 숫자를 지우며 구분자를 되살리는 루프가 없다.
test('formatApplicationEdit_whenDeletingSeparator_deletesAdjacentDigit', () => {
  assert.deepEqual(
    formatApplicationEdit(
      '12345-67890',
      3,
      3,
      '123-45-67890',
      'deleteContentBackward',
      formatBusinessNumber,
    ),
    { value: '124-56-7890', start: 2, end: 2 },
  )
  assert.deepEqual(
    formatApplicationEdit(
      '12345-67890',
      3,
      3,
      '123-45-67890',
      'deleteContentForward',
      formatBusinessNumber,
    ),
    { value: '123-56-7890', start: 3, end: 3 },
  )
})

// 아이디 피드백은 서버로 보내는 trim 이후 값의 문자와 길이만 검사한다.
test('loginIdChecks_whenInvalid_reportsEachRule', () => {
  assert.deepEqual(
    loginIdChecks(' sweet_owner ').map((item) => item.met),
    [true, true],
  )
  assert.deepEqual(
    loginIdChecks('Ab').map((item) => item.met),
    [false, false],
  )
  assert.deepEqual(
    loginIdChecks('a'.repeat(31)).map((item) => item.met),
    [true, false],
  )
})

// 비밀번호는 코드포인트 길이와 UTF-8 바이트 길이를 각각 검사한다.
test('passwordChecks_whenUnicodeAndBoundaryValues_matchesExistingPolicy', () => {
  assert.equal(
    passwordChecks('abc12345').every((item) => item.met),
    true,
  )
  assert.equal(
    passwordChecks('a1' + '가'.repeat(23)).every((item) => item.met),
    true,
  )
  assert.equal(passwordChecks('a1' + '가'.repeat(24))[3]?.met, false)
  assert.equal(passwordChecks('a1' + '😀'.repeat(18))[3]?.met, false)
  assert.equal(
    passwordChecks('a1' + 'b'.repeat(62)).every((item) => item.met),
    true,
  )
  assert.equal(passwordChecks('a1' + 'b'.repeat(63))[2]?.met, false)
  assert.equal(
    passwordChecks('').every((item) => item.met),
    false,
  )
})
