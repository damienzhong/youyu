<script setup lang="ts">
/**
 * 数据导出页（任务 10.5，需求 8.1/8.2）。
 *
 * 导出接口需带 Authorization Bearer 头且返回文件下载，因此用带鉴权的请求
 * 以 Blob 拉取（见 ledger.exportData），再在客户端触发下载，避免普通 <a href>
 * 无法携带令牌的问题。基础功能免费、无付费门槛（需求 8.3/10.2）。
 */
import { ref } from 'vue'
import { exportData, triggerDownload, type ExportFormat } from '@/lib/ledger'

/** 正在导出的格式（用于按钮禁用/加载态）；null 表示空闲。 */
const busy = ref<ExportFormat | null>(null)
const errorMsg = ref('')
const successMsg = ref('')

async function onExport(format: ExportFormat) {
  if (busy.value) return
  busy.value = format
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const { blob, filename } = await exportData(format)
    triggerDownload(blob, filename)
    successMsg.value = `已导出 ${format.toUpperCase()} 文件：${filename}`
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '导出失败，请稍后重试'
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <section class="export">
    <header class="head">
      <h1>数据导出</h1>
    </header>

    <div class="card intro">
      <p>把你的全部数据（账户、流水、分类）导出为 CSV 或 JSON，随时带走。</p>
      <p class="text-muted small">导出永久免费，文件仅包含你自己的数据。</p>
    </div>

    <div v-if="errorMsg" class="banner banner-err" role="alert">{{ errorMsg }}</div>
    <div v-if="successMsg" class="banner banner-ok" role="status">{{ successMsg }}</div>

    <div class="actions">
      <button class="btn btn-block" type="button" :disabled="busy !== null" @click="onExport('csv')">
        {{ busy === 'csv' ? '导出中…' : '导出 CSV' }}
      </button>
      <button class="btn btn-block btn-outline" type="button" :disabled="busy !== null" @click="onExport('json')">
        {{ busy === 'json' ? '导出中…' : '导出 JSON' }}
      </button>
    </div>

    <ul class="notes text-muted">
      <li>CSV 适合用 Excel / 表格软件打开查看。</li>
      <li>JSON 保留完整结构，适合备份与迁移。</li>
    </ul>
  </section>
</template>

<style scoped>
.export {
  padding-bottom: 24px;
}
.head {
  margin-bottom: 16px;
}
.head h1 {
  margin: 0;
  font-size: 22px;
  color: var(--color-primary);
}
.intro {
  margin-bottom: 16px;
}
.intro p {
  margin: 0 0 6px;
}
.intro p:last-child {
  margin-bottom: 0;
}
.small {
  font-size: 13px;
}
.actions {
  display: grid;
  gap: 12px;
  margin-bottom: 20px;
}
.btn-outline {
  background: var(--color-surface);
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
}
.btn-outline:active {
  background: #ecfdf5;
}
.btn:disabled {
  opacity: 0.6;
}
.notes {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  display: grid;
  gap: 6px;
}
.banner {
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}
.banner-ok {
  background: #ecfdf5;
  color: var(--color-primary-dark);
}
</style>
