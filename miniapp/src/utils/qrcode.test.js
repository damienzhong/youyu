/**
 * QR 编码器单测：结构不变量 + 与成熟解码器（qrcode-reader）的回环校验。
 *
 * 回环是关键——它证明我们内联移植的编码器产出的矩阵能被标准扫码器还原成原文，
 * 从而保证真机扫码可用（矩阵正确性不依赖真机即可验证）。
 */
import { describe, it, expect } from 'vitest'
import QrDecoder from 'qrcode-reader'
import { qrMatrix } from './qrcode'

/** 把 {count,isDark} 矩阵渲染成带 quiet zone 的 RGBA 图（每模块 scale 像素），供解码器读取。 */
function renderToImageData(m, scale = 6, quiet = 4) {
  const modules = m.count
  const size = (modules + quiet * 2) * scale
  const data = new Uint8ClampedArray(size * size * 4)
  // 先整体填白
  for (let i = 0; i < data.length; i += 4) {
    data[i] = 255
    data[i + 1] = 255
    data[i + 2] = 255
    data[i + 3] = 255
  }
  for (let r = 0; r < modules; r++) {
    for (let c = 0; c < modules; c++) {
      if (!m.isDark(r, c)) continue
      const x0 = (c + quiet) * scale
      const y0 = (r + quiet) * scale
      for (let y = y0; y < y0 + scale; y++) {
        for (let x = x0; x < x0 + scale; x++) {
          const p = (y * size + x) * 4
          data[p] = 0
          data[p + 1] = 0
          data[p + 2] = 0
          data[p + 3] = 255
        }
      }
    }
  }
  return { width: size, height: size, data }
}

function decodeMatrix(m) {
  const img = renderToImageData(m)
  const reader = new QrDecoder()
  let out = null
  let err = null
  reader.callback = (e, res) => {
    err = e
    out = res
  }
  reader.decode({ width: img.width, height: img.height }, img.data)
  if (err) throw err instanceof Error ? err : new Error(String(err))
  return out && out.result != null ? out.result : out
}

describe('qrMatrix — 结构不变量', () => {
  it('模块数符合 version 公式 (4*v+17)，且为奇数', () => {
    const m = qrMatrix('https://example.com')
    expect((m.count - 17) % 4).toBe(0)
    expect(m.count % 2).toBe(1)
  })

  it('三个定位图案的中心 3x3 均为黑', () => {
    const m = qrMatrix('YOUYU-INVITE-TEST')
    const n = m.count
    const centers = [
      [3, 3],
      [3, n - 4],
      [n - 4, 3]
    ]
    for (const [r, c] of centers) {
      for (let dr = -1; dr <= 1; dr++) {
        for (let dc = -1; dc <= 1; dc++) {
          expect(m.isDark(r + dr, c + dc)).toBe(true)
        }
      }
    }
  })

  it('空文本抛错', () => {
    expect(() => qrMatrix('')).toThrow()
  })
})

describe('qrMatrix — 回环解码（真机可扫的证明）', () => {
  const samples = [
    'HELLO',
    'ABCDEFGHJKLMNP',
    '/pages/invitelanding/invitelanding?code=ABCDEFGH',
    'https://youyu.example.com/join?code=Q7K2M9XY'
  ]
  for (const text of samples) {
    it(`编码后可被 qrcode-reader 还原：${text}`, () => {
      const m = qrMatrix(text, { ecLevel: 'M' })
      const decoded = decodeMatrix(m)
      expect(decoded).toBe(text)
    })
  }
})
