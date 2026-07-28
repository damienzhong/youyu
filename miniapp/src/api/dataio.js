import { API_BASE, STORAGE_KEYS } from '../utils/config'

/** 从 Content-Disposition 解析文件名，失败给默认名。 */
function parseFilename(disposition, format) {
  if (disposition) {
    const m = /filename="?([^";]+)"?/i.exec(disposition)
    if (m && m[1]) return m[1]
  }
  const d = new Date()
  const date = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  return `youyu-export-${date}.${format}`
}

/**
 * 导出全部数据为文件。format: 'json' | 'csv'。
 * H5：带鉴权拉取 Blob 后触发浏览器下载。
 * 小程序：downloadFile 到临时文件后 openDocument 打开/转存。
 */
export function exportData(format) {
  const token = uni.getStorageSync(STORAGE_KEYS.token)
  const url = `${API_BASE}/export?format=${format}`
  const header = token ? { Authorization: `Bearer ${token}` } : {}

  // #ifdef H5
  return fetch(url, { headers: header }).then((res) => {
    if (!res.ok) throw new Error('导出失败')
    const filename = parseFilename(res.headers.get('content-disposition'), format)
    return res.blob().then((blob) => {
      const objectUrl = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = objectUrl
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
    })
  })
  // #endif

  // #ifndef H5
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url,
      header,
      success: (r) => {
        if (r.statusCode === 200) {
          uni.openDocument({ filePath: r.tempFilePath, showMenu: true, success: resolve, fail: resolve })
        } else {
          reject(new Error('导出失败'))
        }
      },
      fail: () => reject(new Error('导出失败'))
    })
  })
  // #endif
}

/**
 * 用导出的 JSON 文本还原数据（POST /api/import，原始 JSON body）。
 * 返回 { accounts, categories, transactions } 记录数汇总。
 */
export function importRestore(jsonText) {
  const token = uni.getStorageSync(STORAGE_KEYS.token)
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE}/import`,
      method: 'POST',
      data: jsonText,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data)
        else reject(res.data || { message: '导入失败' })
      },
      fail: () => reject({ message: '网络异常，请重试' })
    })
  })
}
