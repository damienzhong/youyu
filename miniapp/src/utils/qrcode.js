/**
 * 轻量 QR 码生成器（纯 JS，无 DOM / 无平台依赖），供 AA 账本邀请页把邀请链接渲染成二维码。
 *
 * 本文件是 Kazuhiko Arase 的 "QRCode for JavaScript" 的**内联移植**（原实现按 JIS X 0510:1999，
 * 见 node_modules/qrcode-terminal/vendor/QRCode）：把原来分散的 CommonJS 模块合并为单个 ES 模块，
 * 去掉了浏览器 / Flash 相关代码，仅保留「编码 → 位图矩阵」这条纯逻辑路径。算法与查表未做任何改动，
 * 因此产出的矩阵与成熟实现一致、可被标准扫码器识别（单测用 qrcode-reader 回环校验）。
 *
 * 原始版权与许可（MIT）：
 *   QRCode for JavaScript — Copyright (c) 2009 Kazuhiko Arase — http://www.d-project.com/
 *   Licensed under the MIT license: http://www.opensource.org/licenses/mit-license.php
 *   "QR Code" 是 DENSO WAVE INCORPORATED 的注册商标。
 *
 * 对外只暴露一个纯函数 {@link qrMatrix}：文本 → { count, isDark(row,col) } 位图矩阵，
 * 由页面自行按矩阵在 canvas 上绘制模块。字符按 UTF-8 字节编码（8bit byte 模式），ASCII 链接无损。
 */

// ---- GF(256) 对数 / 指数表（Reed-Solomon 用）----
const EXP_TABLE = new Array(256)
const LOG_TABLE = new Array(256)
for (let i = 0; i < 8; i++) EXP_TABLE[i] = 1 << i
for (let i = 8; i < 256; i++) {
  EXP_TABLE[i] = EXP_TABLE[i - 4] ^ EXP_TABLE[i - 5] ^ EXP_TABLE[i - 6] ^ EXP_TABLE[i - 8]
}
for (let i = 0; i < 255; i++) LOG_TABLE[EXP_TABLE[i]] = i

function glog(n) {
  if (n < 1) throw new Error('glog(' + n + ')')
  return LOG_TABLE[n]
}
function gexp(n) {
  while (n < 0) n += 255
  while (n >= 256) n -= 255
  return EXP_TABLE[n]
}

// ---- 纠错等级 / 模式 / 掩码常量 ----
const EC_LEVEL = { L: 1, M: 0, Q: 3, H: 2 }
const MODE_8BIT_BYTE = 1 << 2

// ---- 多项式（RS）----
function QRPolynomial(num, shift) {
  if (num.length === undefined) throw new Error(num.length + '/' + shift)
  let offset = 0
  while (offset < num.length && num[offset] === 0) offset++
  this.num = new Array(num.length - offset + shift)
  for (let i = 0; i < num.length - offset; i++) this.num[i] = num[i + offset]
}
QRPolynomial.prototype = {
  get(index) {
    return this.num[index]
  },
  getLength() {
    return this.num.length
  },
  multiply(e) {
    const num = new Array(this.getLength() + e.getLength() - 1)
    for (let i = 0; i < this.getLength(); i++) {
      for (let j = 0; j < e.getLength(); j++) {
        num[i + j] ^= gexp(glog(this.get(i)) + glog(e.get(j)))
      }
    }
    return new QRPolynomial(num, 0)
  },
  mod(e) {
    if (this.getLength() - e.getLength() < 0) return this
    const ratio = glog(this.get(0)) - glog(e.get(0))
    const num = new Array(this.getLength())
    for (let i = 0; i < this.getLength(); i++) num[i] = this.get(i)
    for (let x = 0; x < e.getLength(); x++) num[x] ^= gexp(glog(e.get(x)) + ratio)
    return new QRPolynomial(num, 0).mod(e)
  }
}

// ---- 位缓冲 ----
function QRBitBuffer() {
  this.buffer = []
  this.length = 0
}
QRBitBuffer.prototype = {
  put(num, length) {
    for (let i = 0; i < length; i++) this.putBit(((num >>> (length - i - 1)) & 1) === 1)
  },
  getLengthInBits() {
    return this.length
  },
  putBit(bit) {
    const bufIndex = Math.floor(this.length / 8)
    if (this.buffer.length <= bufIndex) this.buffer.push(0)
    if (bit) this.buffer[bufIndex] |= 0x80 >>> this.length % 8
    this.length++
  }
}

// ---- RS 分块表（版本 1..40 × L/M/Q/H）----
const RS_BLOCK_TABLE = [
  [1, 26, 19], [1, 26, 16], [1, 26, 13], [1, 26, 9],
  [1, 44, 34], [1, 44, 28], [1, 44, 22], [1, 44, 16],
  [1, 70, 55], [1, 70, 44], [2, 35, 17], [2, 35, 13],
  [1, 100, 80], [2, 50, 32], [2, 50, 24], [4, 25, 9],
  [1, 134, 108], [2, 67, 43], [2, 33, 15, 2, 34, 16], [2, 33, 11, 2, 34, 12],
  [2, 86, 68], [4, 43, 27], [4, 43, 19], [4, 43, 15],
  [2, 98, 78], [4, 49, 31], [2, 32, 14, 4, 33, 15], [4, 39, 13, 1, 40, 14],
  [2, 121, 97], [2, 60, 38, 2, 61, 39], [4, 40, 18, 2, 41, 19], [4, 40, 14, 2, 41, 15],
  [2, 146, 116], [3, 58, 36, 2, 59, 37], [4, 36, 16, 4, 37, 17], [4, 36, 12, 4, 37, 13],
  [2, 86, 68, 2, 87, 69], [4, 69, 43, 1, 70, 44], [6, 43, 19, 2, 44, 20], [6, 43, 15, 2, 44, 16],
  [4, 101, 81], [1, 80, 50, 4, 81, 51], [4, 50, 22, 4, 51, 23], [3, 36, 12, 8, 37, 13],
  [2, 116, 92, 2, 117, 93], [6, 58, 36, 2, 59, 37], [4, 46, 20, 6, 47, 21], [7, 42, 14, 4, 43, 15],
  [4, 133, 107], [8, 59, 37, 1, 60, 38], [8, 44, 20, 4, 45, 21], [12, 33, 11, 4, 34, 12],
  [3, 145, 115, 1, 146, 116], [4, 64, 40, 5, 65, 41], [11, 36, 16, 5, 37, 17], [11, 36, 12, 5, 37, 13],
  [5, 109, 87, 1, 110, 88], [5, 65, 41, 5, 66, 42], [5, 54, 24, 7, 55, 25], [11, 36, 12],
  [5, 122, 98, 1, 123, 99], [7, 73, 45, 3, 74, 46], [15, 43, 19, 2, 44, 20], [3, 45, 15, 13, 46, 16],
  [1, 135, 107, 5, 136, 108], [10, 74, 46, 1, 75, 47], [1, 50, 22, 15, 51, 23], [2, 42, 14, 17, 43, 15],
  [5, 150, 120, 1, 151, 121], [9, 69, 43, 4, 70, 44], [17, 50, 22, 1, 51, 23], [2, 42, 14, 19, 43, 15],
  [3, 141, 113, 4, 142, 114], [3, 70, 44, 11, 71, 45], [17, 47, 21, 4, 48, 22], [9, 39, 13, 16, 40, 14],
  [3, 135, 107, 5, 136, 108], [3, 67, 41, 13, 68, 42], [15, 54, 24, 5, 55, 25], [15, 43, 15, 10, 44, 16],
  [4, 144, 116, 4, 145, 117], [17, 68, 42], [17, 50, 22, 6, 51, 23], [19, 46, 16, 6, 47, 17],
  [2, 139, 111, 7, 140, 112], [17, 74, 46], [7, 54, 24, 16, 55, 25], [34, 37, 13],
  [4, 151, 121, 5, 152, 122], [4, 75, 47, 14, 76, 48], [11, 54, 24, 14, 55, 25], [16, 45, 15, 14, 46, 16],
  [6, 147, 117, 4, 148, 118], [6, 73, 45, 14, 74, 46], [11, 54, 24, 16, 55, 25], [30, 46, 16, 2, 47, 17],
  [8, 132, 106, 4, 133, 107], [8, 75, 47, 13, 76, 48], [7, 54, 24, 22, 55, 25], [22, 45, 15, 13, 46, 16],
  [10, 142, 114, 2, 143, 115], [19, 74, 46, 4, 75, 47], [28, 50, 22, 6, 51, 23], [33, 46, 16, 4, 47, 17],
  [8, 152, 122, 4, 153, 123], [22, 73, 45, 3, 74, 46], [8, 53, 23, 26, 54, 24], [12, 45, 15, 28, 46, 16],
  [3, 147, 117, 10, 148, 118], [3, 73, 45, 23, 74, 46], [4, 54, 24, 31, 55, 25], [11, 45, 15, 31, 46, 16],
  [7, 146, 116, 7, 147, 117], [21, 73, 45, 7, 74, 46], [1, 53, 23, 37, 54, 24], [19, 45, 15, 26, 46, 16],
  [5, 145, 115, 10, 146, 116], [19, 75, 47, 10, 76, 48], [15, 54, 24, 25, 55, 25], [23, 45, 15, 25, 46, 16],
  [13, 145, 115, 3, 146, 116], [2, 74, 46, 29, 75, 47], [42, 54, 24, 1, 55, 25], [23, 45, 15, 28, 46, 16],
  [17, 145, 115], [10, 74, 46, 23, 75, 47], [10, 54, 24, 35, 55, 25], [19, 45, 15, 35, 46, 16],
  [17, 145, 115, 1, 146, 116], [14, 74, 46, 21, 75, 47], [29, 54, 24, 19, 55, 25], [11, 45, 15, 46, 46, 16],
  [13, 145, 115, 6, 146, 116], [14, 74, 46, 23, 75, 47], [44, 54, 24, 7, 55, 25], [59, 46, 16, 1, 47, 17],
  [12, 151, 121, 7, 152, 122], [12, 75, 47, 26, 76, 48], [39, 54, 24, 14, 55, 25], [22, 45, 15, 41, 46, 16],
  [6, 151, 121, 14, 152, 122], [6, 75, 47, 34, 76, 48], [46, 54, 24, 10, 55, 25], [2, 45, 15, 64, 46, 16],
  [17, 152, 122, 4, 153, 123], [29, 74, 46, 14, 75, 47], [49, 54, 24, 10, 55, 25], [24, 45, 15, 46, 46, 16],
  [4, 152, 122, 18, 153, 123], [13, 74, 46, 32, 75, 47], [48, 54, 24, 14, 55, 25], [42, 45, 15, 32, 46, 16],
  [20, 147, 117, 4, 148, 118], [40, 75, 47, 7, 76, 48], [43, 54, 24, 22, 55, 25], [10, 45, 15, 67, 46, 16],
  [19, 148, 118, 6, 149, 119], [18, 75, 47, 31, 76, 48], [34, 54, 24, 34, 55, 25], [20, 45, 15, 61, 46, 16]
]

function getRsBlockTable(typeNumber, ecLevel) {
  switch (ecLevel) {
    case EC_LEVEL.L: return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 0]
    case EC_LEVEL.M: return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 1]
    case EC_LEVEL.Q: return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 2]
    case EC_LEVEL.H: return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 3]
    default: return undefined
  }
}
function getRSBlocks(typeNumber, ecLevel) {
  const rsBlock = getRsBlockTable(typeNumber, ecLevel)
  if (rsBlock === undefined) {
    throw new Error('bad rs block @ typeNumber:' + typeNumber + '/ecLevel:' + ecLevel)
  }
  const length = rsBlock.length / 3
  const list = []
  for (let i = 0; i < length; i++) {
    const count = rsBlock[i * 3 + 0]
    const totalCount = rsBlock[i * 3 + 1]
    const dataCount = rsBlock[i * 3 + 2]
    for (let j = 0; j < count; j++) list.push({ totalCount, dataCount })
  }
  return list
}

// ---- 对齐图案位置表 / BCH / 掩码 / 字符计数位 ----
const PATTERN_POSITION_TABLE = [
  [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46],
  [6, 28, 50], [6, 30, 54], [6, 32, 58], [6, 34, 62], [6, 26, 46, 66], [6, 26, 48, 70],
  [6, 26, 50, 74], [6, 30, 54, 78], [6, 30, 56, 82], [6, 30, 58, 86], [6, 34, 62, 90],
  [6, 28, 50, 72, 94], [6, 26, 50, 74, 98], [6, 30, 54, 78, 102], [6, 28, 54, 80, 106],
  [6, 32, 58, 84, 110], [6, 30, 58, 86, 114], [6, 34, 62, 90, 118], [6, 26, 50, 74, 98, 122],
  [6, 30, 54, 78, 102, 126], [6, 26, 52, 78, 104, 130], [6, 30, 56, 82, 108, 134],
  [6, 34, 60, 86, 112, 138], [6, 30, 58, 86, 114, 142], [6, 34, 62, 90, 118, 146],
  [6, 30, 54, 78, 102, 126, 150], [6, 24, 50, 76, 102, 128, 154], [6, 28, 54, 80, 106, 132, 158],
  [6, 32, 58, 84, 110, 136, 162], [6, 26, 54, 82, 110, 138, 166], [6, 30, 58, 86, 114, 142, 170]
]
const G15 = (1 << 10) | (1 << 8) | (1 << 5) | (1 << 4) | (1 << 2) | (1 << 1) | (1 << 0)
const G18 = (1 << 12) | (1 << 11) | (1 << 10) | (1 << 9) | (1 << 8) | (1 << 5) | (1 << 2) | (1 << 0)
const G15_MASK = (1 << 14) | (1 << 12) | (1 << 10) | (1 << 4) | (1 << 1)

function getBCHDigit(data) {
  let digit = 0
  while (data !== 0) {
    digit++
    data >>>= 1
  }
  return digit
}
function getBCHTypeInfo(data) {
  let d = data << 10
  while (getBCHDigit(d) - getBCHDigit(G15) >= 0) d ^= G15 << (getBCHDigit(d) - getBCHDigit(G15))
  return ((data << 10) | d) ^ G15_MASK
}
function getBCHTypeNumber(data) {
  let d = data << 12
  while (getBCHDigit(d) - getBCHDigit(G18) >= 0) d ^= G18 << (getBCHDigit(d) - getBCHDigit(G18))
  return (data << 12) | d
}
function getPatternPosition(typeNumber) {
  return PATTERN_POSITION_TABLE[typeNumber - 1]
}
function getMask(maskPattern, i, j) {
  switch (maskPattern) {
    case 0: return (i + j) % 2 === 0
    case 1: return i % 2 === 0
    case 2: return j % 3 === 0
    case 3: return (i + j) % 3 === 0
    case 4: return (Math.floor(i / 2) + Math.floor(j / 3)) % 2 === 0
    case 5: return ((i * j) % 2) + ((i * j) % 3) === 0
    case 6: return (((i * j) % 2) + ((i * j) % 3)) % 2 === 0
    case 7: return (((i * j) % 3) + ((i + j) % 2)) % 2 === 0
    default: throw new Error('bad maskPattern:' + maskPattern)
  }
}
function getErrorCorrectPolynomial(ecLength) {
  let a = new QRPolynomial([1], 0)
  for (let i = 0; i < ecLength; i++) a = a.multiply(new QRPolynomial([1, gexp(i)], 0))
  return a
}
function getLengthInBits(mode, type) {
  if (type >= 1 && type < 10) return mode === MODE_8BIT_BYTE ? 8 : 8
  if (type < 27) return mode === MODE_8BIT_BYTE ? 16 : 16
  if (type < 41) return mode === MODE_8BIT_BYTE ? 16 : 16
  throw new Error('type:' + type)
}

// ---- 掩码惩罚评分：选出失真最小的掩码（与标准实现一致）----
function getLostPoint(modules, moduleCount) {
  const isDark = (r, c) => modules[r][c]
  let lostPoint = 0

  // LEVEL1：同色相邻块
  for (let row = 0; row < moduleCount; row++) {
    for (let col = 0; col < moduleCount; col++) {
      let sameCount = 0
      const dark = isDark(row, col)
      for (let r = -1; r <= 1; r++) {
        if (row + r < 0 || moduleCount <= row + r) continue
        for (let c = -1; c <= 1; c++) {
          if (col + c < 0 || moduleCount <= col + c) continue
          if (r === 0 && c === 0) continue
          if (dark === isDark(row + r, col + c)) sameCount++
        }
      }
      if (sameCount > 5) lostPoint += 3 + sameCount - 5
    }
  }

  // LEVEL2：2x2 同色
  for (let row = 0; row < moduleCount - 1; row++) {
    for (let col = 0; col < moduleCount - 1; col++) {
      let count = 0
      if (isDark(row, col)) count++
      if (isDark(row + 1, col)) count++
      if (isDark(row, col + 1)) count++
      if (isDark(row + 1, col + 1)) count++
      if (count === 0 || count === 4) lostPoint += 3
    }
  }

  // LEVEL3：类定位图案（1011101）
  for (let row = 0; row < moduleCount; row++) {
    for (let col = 0; col < moduleCount - 6; col++) {
      if (
        isDark(row, col) && !isDark(row, col + 1) && isDark(row, col + 2) &&
        isDark(row, col + 3) && isDark(row, col + 4) && !isDark(row, col + 5) && isDark(row, col + 6)
      ) {
        lostPoint += 40
      }
    }
  }
  for (let col = 0; col < moduleCount; col++) {
    for (let row = 0; row < moduleCount - 6; row++) {
      if (
        isDark(row, col) && !isDark(row + 1, col) && isDark(row + 2, col) &&
        isDark(row + 3, col) && isDark(row + 4, col) && !isDark(row + 5, col) && isDark(row + 6, col)
      ) {
        lostPoint += 40
      }
    }
  }

  // LEVEL4：黑白比例偏离 50%
  let darkCount = 0
  for (let col = 0; col < moduleCount; col++) {
    for (let row = 0; row < moduleCount; row++) if (isDark(row, col)) darkCount++
  }
  const ratio = Math.abs((100 * darkCount) / moduleCount / moduleCount - 50) / 5
  lostPoint += ratio * 10
  return lostPoint
}

const PAD0 = 0xec
const PAD1 = 0x11

function createBytes(buffer, rsBlocks) {
  let offset = 0
  let maxDcCount = 0
  let maxEcCount = 0
  const dcdata = new Array(rsBlocks.length)
  const ecdata = new Array(rsBlocks.length)

  for (let r = 0; r < rsBlocks.length; r++) {
    const dcCount = rsBlocks[r].dataCount
    const ecCount = rsBlocks[r].totalCount - dcCount
    maxDcCount = Math.max(maxDcCount, dcCount)
    maxEcCount = Math.max(maxEcCount, ecCount)

    dcdata[r] = new Array(dcCount)
    for (let i = 0; i < dcdata[r].length; i++) dcdata[r][i] = 0xff & buffer.buffer[i + offset]
    offset += dcCount

    const rsPoly = getErrorCorrectPolynomial(ecCount)
    const rawPoly = new QRPolynomial(dcdata[r], rsPoly.getLength() - 1)
    const modPoly = rawPoly.mod(rsPoly)
    ecdata[r] = new Array(rsPoly.getLength() - 1)
    for (let x = 0; x < ecdata[r].length; x++) {
      const modIndex = x + modPoly.getLength() - ecdata[r].length
      ecdata[r][x] = modIndex >= 0 ? modPoly.get(modIndex) : 0
    }
  }

  let totalCodeCount = 0
  for (let y = 0; y < rsBlocks.length; y++) totalCodeCount += rsBlocks[y].totalCount

  const data = new Array(totalCodeCount)
  let index = 0
  for (let z = 0; z < maxDcCount; z++) {
    for (let s = 0; s < rsBlocks.length; s++) if (z < dcdata[s].length) data[index++] = dcdata[s][z]
  }
  for (let xx = 0; xx < maxEcCount; xx++) {
    for (let t = 0; t < rsBlocks.length; t++) if (xx < ecdata[t].length) data[index++] = ecdata[t][xx]
  }
  return data
}

function createData(typeNumber, ecLevel, dataBytes) {
  const rsBlocks = getRSBlocks(typeNumber, ecLevel)
  const buffer = new QRBitBuffer()

  buffer.put(MODE_8BIT_BYTE, 4)
  buffer.put(dataBytes.length, getLengthInBits(MODE_8BIT_BYTE, typeNumber))
  for (let i = 0; i < dataBytes.length; i++) buffer.put(dataBytes[i], 8)

  let totalDataCount = 0
  for (let i = 0; i < rsBlocks.length; i++) totalDataCount += rsBlocks[i].dataCount

  if (buffer.getLengthInBits() > totalDataCount * 8) {
    throw new Error(
      'code length overflow. (' + buffer.getLengthInBits() + '>' + totalDataCount * 8 + ')'
    )
  }
  if (buffer.getLengthInBits() + 4 <= totalDataCount * 8) buffer.put(0, 4)
  while (buffer.getLengthInBits() % 8 !== 0) buffer.putBit(false)
  while (true) {
    if (buffer.getLengthInBits() >= totalDataCount * 8) break
    buffer.put(PAD0, 8)
    if (buffer.getLengthInBits() >= totalDataCount * 8) break
    buffer.put(PAD1, 8)
  }
  return createBytes(buffer, rsBlocks)
}

// ---- 矩阵编织：定位/分隔/对齐/时序/格式/版本信息 + 数据（含掩码）----
function makeMatrix(typeNumber, ecLevel, dataCache, maskPattern, test) {
  const moduleCount = typeNumber * 4 + 17
  const modules = new Array(moduleCount)
  for (let row = 0; row < moduleCount; row++) modules[row] = new Array(moduleCount).fill(null)

  function setupPositionProbePattern(row, col) {
    for (let r = -1; r <= 7; r++) {
      if (row + r <= -1 || moduleCount <= row + r) continue
      for (let c = -1; c <= 7; c++) {
        if (col + c <= -1 || moduleCount <= col + c) continue
        if (
          (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
          (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
          (r >= 2 && r <= 4 && c >= 2 && c <= 4)
        ) {
          modules[row + r][col + c] = true
        } else {
          modules[row + r][col + c] = false
        }
      }
    }
  }

  setupPositionProbePattern(0, 0)
  setupPositionProbePattern(moduleCount - 7, 0)
  setupPositionProbePattern(0, moduleCount - 7)

  // 对齐图案
  const pos = getPatternPosition(typeNumber)
  for (let i = 0; i < pos.length; i++) {
    for (let j = 0; j < pos.length; j++) {
      const row = pos[i]
      const col = pos[j]
      if (modules[row][col] !== null) continue
      for (let r = -2; r <= 2; r++) {
        for (let c = -2; c <= 2; c++) {
          modules[row + r][col + c] =
            Math.abs(r) === 2 || Math.abs(c) === 2 || (r === 0 && c === 0)
        }
      }
    }
  }

  // 时序图案
  for (let r = 8; r < moduleCount - 8; r++) {
    if (modules[r][6] !== null) continue
    modules[r][6] = r % 2 === 0
  }
  for (let c = 8; c < moduleCount - 8; c++) {
    if (modules[6][c] !== null) continue
    modules[6][c] = c % 2 === 0
  }

  // 格式信息
  const dataInfo = (ecLevel << 3) | maskPattern
  const bits = getBCHTypeInfo(dataInfo)
  for (let v = 0; v < 15; v++) {
    const mod = !test && ((bits >> v) & 1) === 1
    if (v < 6) modules[v][8] = mod
    else if (v < 8) modules[v + 1][8] = mod
    else modules[moduleCount - 15 + v][8] = mod
  }
  for (let h = 0; h < 15; h++) {
    const mod = !test && ((bits >> h) & 1) === 1
    if (h < 8) modules[8][moduleCount - h - 1] = mod
    else if (h < 9) modules[8][15 - h - 1 + 1] = mod
    else modules[8][15 - h - 1] = mod
  }
  modules[moduleCount - 8][8] = !test

  // 版本信息（v>=7）
  if (typeNumber >= 7) {
    const vbits = getBCHTypeNumber(typeNumber)
    for (let i = 0; i < 18; i++) {
      const mod = !test && ((vbits >> i) & 1) === 1
      modules[Math.floor(i / 3)][(i % 3) + moduleCount - 8 - 3] = mod
    }
    for (let x = 0; x < 18; x++) {
      const mod = !test && ((vbits >> x) & 1) === 1
      modules[(x % 3) + moduleCount - 8 - 3][Math.floor(x / 3)] = mod
    }
  }

  // 数据映射（zigzag + 掩码）
  let inc = -1
  let row = moduleCount - 1
  let bitIndex = 7
  let byteIndex = 0
  for (let col = moduleCount - 1; col > 0; col -= 2) {
    if (col === 6) col--
    while (true) {
      for (let c = 0; c < 2; c++) {
        if (modules[row][col - c] === null) {
          let dark = false
          if (byteIndex < dataCache.length) {
            dark = ((dataCache[byteIndex] >>> bitIndex) & 1) === 1
          }
          if (getMask(maskPattern, row, col - c)) dark = !dark
          modules[row][col - c] = dark
          bitIndex--
          if (bitIndex === -1) {
            byteIndex++
            bitIndex = 7
          }
        }
      }
      row += inc
      if (row < 0 || moduleCount <= row) {
        row -= inc
        inc = -inc
        break
      }
    }
  }

  return { modules, moduleCount }
}

/** 文本 → UTF-8 字节数组（8bit byte 模式的数据源）。ASCII 链接逐字节即码点。 */
function utf8Bytes(text) {
  const s = String(text == null ? '' : text)
  const out = []
  for (let i = 0; i < s.length; i++) {
    let code = s.charCodeAt(i)
    if (code < 0x80) {
      out.push(code)
    } else if (code < 0x800) {
      out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f))
    } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < s.length) {
      // 代理对 → 单个码点
      const hi = code
      const lo = s.charCodeAt(++i)
      code = 0x10000 + ((hi - 0xd800) << 10) + (lo - 0xdc00)
      out.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f)
      )
    } else {
      out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f))
    }
  }
  return out
}

/** 选出恰能容纳 dataBytes 的最小版本（1..40）。容不下则抛错。 */
function chooseTypeNumber(dataByteLen, ecLevel) {
  for (let typeNumber = 1; typeNumber <= 40; typeNumber++) {
    const rsBlocks = getRSBlocks(typeNumber, ecLevel)
    let totalDataCount = 0
    for (let i = 0; i < rsBlocks.length; i++) totalDataCount += rsBlocks[i].dataCount
    // 4 位模式 + 字符计数位 + 数据位 ≤ 数据容量
    const bits = 4 + getLengthInBits(MODE_8BIT_BYTE, typeNumber) + dataByteLen * 8
    if (bits <= totalDataCount * 8) return typeNumber
  }
  throw new Error('data too long for QR (bytes=' + dataByteLen + ')')
}

/**
 * 生成 QR 位图矩阵。
 * @param {string} text 待编码文本（如邀请链接）
 * @param {{ ecLevel?: 'L'|'M'|'Q'|'H' }} [opts] 纠错等级，默认 'M'
 * @returns {{ count:number, isDark:(row:number,col:number)=>boolean }}
 *          count 为每边模块数，isDark(r,c) 为该模块是否为黑。
 * @throws {Error} 文本为空或超出版本 40 容量时抛出。
 */
export function qrMatrix(text, opts) {
  const s = String(text == null ? '' : text)
  if (!s) throw new Error('qrMatrix: empty text')
  const ecLevel = EC_LEVEL[(opts && opts.ecLevel) || 'M']
  if (ecLevel === undefined) throw new Error('qrMatrix: bad ecLevel')

  const dataBytes = utf8Bytes(s)
  const typeNumber = chooseTypeNumber(dataBytes.length, ecLevel)
  const dataCache = createData(typeNumber, ecLevel, dataBytes)

  // 选最优掩码：用 test=true 生成各掩码矩阵评分，取最低失真。
  let bestPattern = 0
  let minLost = Infinity
  for (let p = 0; p < 8; p++) {
    const { modules, moduleCount } = makeMatrix(typeNumber, ecLevel, dataCache, p, true)
    const lost = getLostPoint(modules, moduleCount)
    if (lost < minLost) {
      minLost = lost
      bestPattern = p
    }
  }

  const { modules, moduleCount } = makeMatrix(typeNumber, ecLevel, dataCache, bestPattern, false)
  return {
    count: moduleCount,
    isDark(row, col) {
      if (row < 0 || col < 0 || row >= moduleCount || col >= moduleCount) return false
      return modules[row][col] === true
    }
  }
}

export default qrMatrix
