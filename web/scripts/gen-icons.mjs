// 生成 PWA 占位图标（纯色圆角方块 + 中心「余」字近似）。
// 这里输出的是纯色 PNG，作为可用的占位；后续可替换为设计稿图标。
import { deflateSync } from 'node:zlib'
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, '..', 'public')
mkdirSync(outDir, { recursive: true })

// 品牌绿 #16a34a
const R = 0x16
const G = 0xa3
const B = 0x4a

function crc32(buf) {
  let c = ~0
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i]
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1
  }
  return ~c >>> 0
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii')
  const lenBuf = Buffer.alloc(4)
  lenBuf.writeUInt32BE(data.length, 0)
  const body = Buffer.concat([typeBuf, data])
  const crcBuf = Buffer.alloc(4)
  crcBuf.writeUInt32BE(crc32(body), 0)
  return Buffer.concat([lenBuf, body, crcBuf])
}

function makePng(size) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])

  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // color type: truecolor RGB
  ihdr[10] = 0
  ihdr[11] = 0
  ihdr[12] = 0

  const rowLen = size * 3 + 1
  const raw = Buffer.alloc(rowLen * size)
  for (let y = 0; y < size; y++) {
    const off = y * rowLen
    raw[off] = 0 // filter: none
    for (let x = 0; x < size; x++) {
      const p = off + 1 + x * 3
      raw[p] = R
      raw[p + 1] = G
      raw[p + 2] = B
    }
  }
  const idat = deflateSync(raw)

  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

for (const size of [192, 512]) {
  const png = makePng(size)
  writeFileSync(join(outDir, `pwa-${size}x${size}.png`), png)
  console.log(`wrote pwa-${size}x${size}.png (${png.length} bytes)`)
}
