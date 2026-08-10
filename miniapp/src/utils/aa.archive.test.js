/**
 * AA 账本归档 / 解档生命周期前端工具单元测试（需求 8.3、8.4、8.5）。
 * 覆盖：归档只读判定、归档 / 解档入口开放条件（仅 AA + OWNER）、操作文案、
 * 未结清强制归档二次确认错误识别（AA_LEDGER_UNSETTLED）。
 */
import { describe, it, expect } from 'vitest'
import {
  isArchived,
  canToggleArchive,
  archiveActionLabel,
  isUnsettledArchiveError
} from './aa'

describe('isArchived', () => {
  it('true only when archived === true', () => {
    expect(isArchived({ archived: true })).toBe(true)
    expect(isArchived({ archived: false })).toBe(false)
    expect(isArchived({})).toBe(false)
    expect(isArchived(null)).toBe(false)
    expect(isArchived(undefined)).toBe(false)
  })
})

describe('canToggleArchive', () => {
  it('allows only AA ledgers owned by the current user', () => {
    expect(canToggleArchive({ type: 'AA', role: 'OWNER' })).toBe(true)
  })
  it('rejects non-owner AA members', () => {
    expect(canToggleArchive({ type: 'AA', role: 'MEMBER' })).toBe(false)
  })
  it('rejects non-AA ledgers (archive not supported)', () => {
    expect(canToggleArchive({ type: 'PERSONAL', role: 'OWNER' })).toBe(false)
    expect(canToggleArchive({ type: 'COLLABORATIVE', role: 'OWNER' })).toBe(false)
  })
  it('rejects null / undefined ledger', () => {
    expect(canToggleArchive(null)).toBe(false)
    expect(canToggleArchive(undefined)).toBe(false)
  })
})

describe('archiveActionLabel', () => {
  it('shows 解档 when archived, 归档账本 otherwise', () => {
    expect(archiveActionLabel({ archived: true })).toBe('解档')
    expect(archiveActionLabel({ archived: false })).toBe('归档账本')
    expect(archiveActionLabel(null)).toBe('归档账本')
  })
})

describe('isUnsettledArchiveError', () => {
  it('true for AA_LEDGER_UNSETTLED (needs force confirm)', () => {
    expect(isUnsettledArchiveError({ code: 'AA_LEDGER_UNSETTLED' })).toBe(true)
  })
  it('false for other error codes or nullish', () => {
    expect(isUnsettledArchiveError({ code: 'AA_ARCHIVE_NOT_SUPPORTED' })).toBe(false)
    expect(isUnsettledArchiveError({ code: 'NETWORK_ERROR' })).toBe(false)
    expect(isUnsettledArchiveError({})).toBe(false)
    expect(isUnsettledArchiveError(null)).toBe(false)
    expect(isUnsettledArchiveError(undefined)).toBe(false)
  })
})
