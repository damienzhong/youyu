/**
 * 轻量网络态标志（Offline_Sync_System）：解耦请求层与 Pinia store。
 *
 * stores/net.js 在初始化与网络变化时调用 setOnline() 写入此处；offlineHttp 读 isOnline()，
 * 从而请求层无需 import pinia store（避免「store 尚未初始化」的时序耦合）。默认在线。
 */

let _online = true

/** 更新在线态（由 net store 驱动）。 */
export function setOnline(v) {
  _online = !!v
}

/** 读取当前在线态。 */
export function isOnline() {
  return _online
}
