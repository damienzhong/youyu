/**
 * 安全返回：有上一页则返回；若当前页处于导航栈底（H5 刷新/冷启动直接进入、栈被重置等），
 * navigateBack 会是空操作导致“点返回无反应”，此时回退到指定兜底页。
 *
 * @param {string} fallbackUrl 兜底页（栈底时打开），默认首页。reLaunch 对普通页/ tab 页均可用。
 */
export function safeBack(fallbackUrl = '/pages/index/index') {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  if (pages && pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.reLaunch({ url: fallbackUrl })
  }
}
