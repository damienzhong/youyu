import { http } from '../utils/request'

/** 截图识别是否开启 + 今日剩余额度：{ enabled, remaining, dailyLimit, maxImages }。 */
export function screenshotStatus() {
  return http.get('/imports/screenshot/status')
}

/**
 * 识别截图（只预览，不入账）。images: [{ data: base64, mime: 'image/jpeg' }]，建议一次一张。
 * 返回 { shots: [...], remaining, dailyLimit }。
 */
export function recognizeScreenshots(images) {
  return http.post('/imports/screenshot/recognize', { images })
}

/** 24 小时内整批撤销截图入账的流水，返回 { undone }。 */
export function undoScreenshotBatch(batch) {
  return http.post('/imports/screenshot/undo', { batch })
}
