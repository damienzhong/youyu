/**
 * 微信小程序真机预览（无需开发者工具 GUI）。
 *
 * 流程：读取已编译产物 dist/build/mp-weixin，用代码上传私钥调用微信 CI，
 * 生成预览二维码到 preview-qr.png，用手机微信扫码即可在真机运行体验版。
 *
 * 前置：
 *   1. 先编译： npm run build:mp-weixin
 *   2. 私钥放在 .keys/private.key（或用环境变量 WX_PRIVATE_KEY 指定路径）
 *   3. 当前机器公网 IP 已加入后台「开发设置-小程序代码上传」的 IP 白名单
 *
 * 运行： npm run preview:mp-weixin
 */
// —— Node 22+/25 兼容垫片 ——
// 新版 Node 暴露了一个全局 localStorage，但未配 --localstorage-file 时其方法为 undefined，
// miniprogram-ci 调用 localStorage.getItem 会抛 "getItem is not a function"。
// 这里在加载 miniprogram-ci 之前，用内存实现覆盖，规避该不完整全局。
;(function polyfillLocalStorage() {
  const needsFix =
    typeof globalThis.localStorage === 'undefined' ||
    typeof globalThis.localStorage.getItem !== 'function'
  if (!needsFix) return
  const store = new Map()
  const shim = {
    getItem: (k) => (store.has(String(k)) ? store.get(String(k)) : null),
    setItem: (k, v) => void store.set(String(k), String(v)),
    removeItem: (k) => void store.delete(String(k)),
    clear: () => store.clear(),
    key: (i) => Array.from(store.keys())[i] ?? null,
    get length() {
      return store.size
    }
  }
  try {
    globalThis.localStorage = shim
  } catch (_) {
    Object.defineProperty(globalThis, 'localStorage', { value: shim, configurable: true })
  }
})()

const fs = require('fs')
const path = require('path')
const ci = require('miniprogram-ci')

const ROOT = path.resolve(__dirname, '..')
const PROJECT_PATH = path.join(ROOT, 'dist/build/mp-weixin')
const KEY_PATH = process.env.WX_PRIVATE_KEY || path.join(ROOT, '.keys/private.key')
const QR_OUT = path.join(ROOT, 'preview-qr.png')

function readAppId() {
  const manifest = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/manifest.json'), 'utf8'))
  const appid = manifest['mp-weixin'] && manifest['mp-weixin'].appid
  if (!appid) throw new Error('manifest.json 未配置 mp-weixin.appid')
  return appid
}

async function main() {
  if (!fs.existsSync(PROJECT_PATH)) {
    throw new Error(`未找到编译产物：${PROJECT_PATH}\n请先运行 npm run build:mp-weixin`)
  }
  if (!fs.existsSync(KEY_PATH)) {
    throw new Error(`未找到上传私钥：${KEY_PATH}\n请把 .key 放到 .keys/private.key 或设置 WX_PRIVATE_KEY`)
  }

  const appid = readAppId()
  console.log(`[preview] appid=${appid}`)
  console.log(`[preview] project=${PROJECT_PATH}`)

  const project = new ci.Project({
    appid,
    type: 'miniProgram',
    projectPath: PROJECT_PATH,
    privateKeyPath: KEY_PATH,
    ignores: ['node_modules/**/*']
  })

  const result = await ci.preview({
    project,
    desc: '有余 - 本地预览',
    setting: { es6: true, minify: true },
    qrcodeFormat: 'image',
    qrcodeOutputDest: QR_OUT,
    onProgressUpdate: (info) => {
      const msg = typeof info === 'string' ? info : (info && info._msg) || ''
      if (msg) console.log('[ci]', msg)
    }
  })

  console.log('\n[preview] 成功！二维码已保存：', QR_OUT)
  if (result && result.subPackageInfo) {
    const total = result.subPackageInfo.find((s) => s.name === '__FULL__')
    if (total) console.log(`[preview] 包大小：${(total.size / 1024).toFixed(1)} KB`)
  }
  console.log('[preview] 用手机微信「扫一扫」打开 preview-qr.png 即可真机预览。')
}

main().catch((e) => {
  console.error('\n[preview] 失败：', e && e.message ? e.message : e)
  process.exit(1)
})
