/**
 * 文字头像工具（Offline 无关）：颜色调色板 + 取色/取首字。
 *
 * 项目不存头像图片，头像统一按「昵称首字 + 用户自选颜色」渲染。颜色可在账号页设置（像主题色一样从
 * 色板里挑），并随 /me、/members 下发；未设置时回退品牌绿。
 */

/** 头像可选颜色（与主题色板同源，供账号页色板选择）。 */
export const AVATAR_COLORS = [
  '#12a150', // 有余绿
  '#0ea5e9', // 晴空蓝
  '#f97316', // 日暮橙
  '#7c6cf0', // 静谧紫
  '#0e9aa7', // 孔雀青
  '#b23a4b', // 勃艮第
  '#5b7fb0', // 黛山灰
  '#b3873a' // 香槟金
]

/** 未设置时的默认头像色（品牌绿）。 */
export const DEFAULT_AVATAR_COLOR = '#12a150'

/** 归一头像色：合法 #RRGGBB 原样返回，否则回退默认。 */
export function avatarColorOf(color) {
  return typeof color === 'string' && /^#[0-9a-fA-F]{6}$/.test(color) ? color : DEFAULT_AVATAR_COLOR
}

/** 取展示名首字（首个 Unicode 码点，大写）；空名回退 '?'。 */
export function avatarInitial(name) {
  const s = (name == null ? '' : String(name)).trim()
  if (!s) return '?'
  return [...s][0].toUpperCase()
}
