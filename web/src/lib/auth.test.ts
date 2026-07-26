import { describe, it, expect } from 'vitest'
import { ApiError } from '@/lib/http'
import { toAuthFeedback, validateCredentials } from '@/lib/auth'

describe('validateCredentials', () => {
  it('accepts a valid username and password', () => {
    expect(validateCredentials('alice', 'password1')).toEqual({})
  })

  it('trims username before length check', () => {
    // 去空白后为空 -> 账号错误
    expect(validateCredentials('   ', 'password1').username).toBeTruthy()
    // 去空白后有效 -> 无错误
    expect(validateCredentials('  bob  ', 'password1')).toEqual({})
  })

  it('rejects username longer than 64 chars', () => {
    const long = 'a'.repeat(65)
    expect(validateCredentials(long, 'password1').username).toBeTruthy()
  })

  it('accepts username of exactly 64 chars', () => {
    const exact = 'a'.repeat(64)
    expect(validateCredentials(exact, 'password1').username).toBeUndefined()
  })

  it('rejects password shorter than 8 chars', () => {
    expect(validateCredentials('alice', 'short').password).toBeTruthy()
  })

  it('rejects password longer than 64 chars', () => {
    expect(validateCredentials('alice', 'p'.repeat(65)).password).toBeTruthy()
  })

  it('accepts password at the 8 and 64 boundaries', () => {
    expect(validateCredentials('alice', 'p'.repeat(8))).toEqual({})
    expect(validateCredentials('alice', 'p'.repeat(64))).toEqual({})
  })
})

describe('toAuthFeedback', () => {
  it('maps USERNAME_TAKEN to the username field', () => {
    const fb = toAuthFeedback(new ApiError('USERNAME_TAKEN', 'x', undefined, 409))
    expect(fb.field).toBe('username')
    expect(fb.message).toContain('占用')
  })

  it('maps PASSWORD_WEAK to the password field', () => {
    const fb = toAuthFeedback(new ApiError('PASSWORD_WEAK', 'x', undefined, 400))
    expect(fb.field).toBe('password')
  })

  it('maps BAD_CREDENTIALS to a form-level error', () => {
    const fb = toAuthFeedback(new ApiError('BAD_CREDENTIALS', 'x', undefined, 401))
    expect(fb.field).toBe('form')
    expect(fb.message).toContain('账号或口令')
  })

  it('maps ACCOUNT_LOCKED to a form-level lock hint', () => {
    const fb = toAuthFeedback(new ApiError('ACCOUNT_LOCKED', 'x', undefined, 423))
    expect(fb.field).toBe('form')
    expect(fb.message).toContain('锁定')
  })

  it('routes FIELD_REQUIRED to the field named in the error body', () => {
    expect(toAuthFeedback(new ApiError('FIELD_REQUIRED', 'x', 'password', 400)).field).toBe('password')
    expect(toAuthFeedback(new ApiError('FIELD_REQUIRED', 'x', 'username', 400)).field).toBe('username')
  })

  it('falls back to a generic form error for non-ApiError values', () => {
    const fb = toAuthFeedback(new Error('boom'))
    expect(fb.field).toBe('form')
    expect(fb.message).toBeTruthy()
  })
})
