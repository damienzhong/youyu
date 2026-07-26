/**
 * 加载状态与「加载失败」处理的共享契约（需求 11.4 / 11.5）。
 *
 * 各数据页（首页、流水、报表、账户、分类、记一笔）在重新加载数据时遵循同一套行为：
 *  - 常规网络下拉取最新数据并呈现（11.4）。
 *  - 加载失败时：显示错误提示、**保留上一次已加载的数据**（不清空）、提供重试入口（11.5）。
 *
 * 本 composable 把该契约收敛为一处可复用、可单测的实现：
 *  - `loading`：本次是否正在加载（用于首屏「加载中…」）。
 *  - `loaded`：是否**曾经**成功加载过一次；失败时保持 true，从而模板据此继续渲染旧数据。
 *  - `error` ：失败提示文案（成功时清空）。
 *  - `data`  ：最近一次成功加载的数据；失败时保持不变（保留旧数据）。
 *  - `load` / `retry`：拉取数据；`retry` 即再次 `load`（用户点「重试」）。
 */
import { ref, type Ref } from 'vue'

export interface Loadable<T> {
  loading: Ref<boolean>
  loaded: Ref<boolean>
  error: Ref<string>
  data: Ref<T | null>
  load: () => Promise<void>
  retry: () => Promise<void>
}

/**
 * 构造一个遵循 11.5 契约的可加载状态。
 *
 * @param fetcher 拉取数据的异步函数（内部失败会抛出，交由本 composable 归一化为提示）。
 * @param fallbackMessage 当抛出的不是 Error（无 message）时的兜底提示。
 */
export function useLoadable<T>(
  fetcher: () => Promise<T>,
  fallbackMessage = '加载失败，请重试',
): Loadable<T> {
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref('')
  const data = ref<T | null>(null) as Ref<T | null>

  async function load(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      data.value = await fetcher()
      loaded.value = true
    } catch (e) {
      // 需求 11.5：失败时**不**清空 data / 不重置 loaded，从而保留上一次已加载的数据；
      // 仅设置错误提示，供模板展示错误条与「重试」按钮。
      error.value = e instanceof Error && e.message ? e.message : fallbackMessage
    } finally {
      loading.value = false
    }
  }

  return { loading, loaded, error, data, load, retry: load }
}
