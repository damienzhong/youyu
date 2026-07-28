<script setup>
import { ref } from 'vue'
import { exportData, importRestore } from '../../api/dataio'

const busy = ref(false)

async function doExport(format) {
  if (busy.value) return
  busy.value = true
  try {
    await exportData(format)
    // #ifndef H5
    uni.showToast({ title: '已导出', icon: 'success' })
    // #endif
  } catch (e) {
    uni.showToast({ title: e.message || '导出失败', icon: 'none' })
  } finally {
    busy.value = false
  }
}

function pickAndImport() {
  // #ifdef H5
  uni.chooseFile({
    count: 1,
    extension: ['.json'],
    success: (res) => {
      const file = res.tempFiles && res.tempFiles[0]
      if (!file) return
      const reader = new FileReader()
      reader.onload = () => confirmImport(reader.result)
      reader.onerror = () => uni.showToast({ title: '读取文件失败', icon: 'none' })
      reader.readAsText(file)
    }
  })
  // #endif

  // #ifndef H5
  wx.chooseMessageFile({
    count: 1,
    type: 'file',
    extension: ['json'],
    success: (res) => {
      const fp = res.tempFiles[0].path
      uni.getFileSystemManager().readFile({
        filePath: fp,
        encoding: 'utf-8',
        success: (r) => confirmImport(r.data),
        fail: () => uni.showToast({ title: '读取文件失败', icon: 'none' })
      })
    }
  })
  // #endif
}

function confirmImport(text) {
  uni.showModal({
    title: '确认导入',
    content: '将把该文件中的账户、分类、交易还原到当前账号。确定继续？',
    success: async (r) => {
      if (!r.confirm) return
      busy.value = true
      try {
        const result = await importRestore(text)
        const a = result?.accounts ?? 0
        const c = result?.categories ?? 0
        const t = result?.transactions ?? 0
        uni.showModal({
          title: '导入完成',
          content: `账户 ${a} · 分类 ${c} · 交易 ${t}`,
          showCancel: false
        })
      } catch (e) {
        uni.showToast({ title: e.message || '导入失败', icon: 'none' })
      } finally {
        busy.value = false
      }
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 导出 -->
    <view class="card">
      <text class="c-title">数据导出</text>
      <text class="c-desc">导出你的全部账户、分类与交易，数据完全自持，随时可带走。</text>
      <button class="btn primary" :disabled="busy" @click="doExport('json')">导出 JSON</button>
      <button class="btn" :disabled="busy" @click="doExport('csv')">导出 CSV</button>
    </view>

    <!-- 导入 -->
    <view class="card">
      <text class="c-title">数据导入</text>
      <text class="c-desc">选择之前导出的 JSON 文件，把数据还原到当前账号（用于迁移或备份恢复）。</text>
      <button class="btn primary" :disabled="busy" @click="pickAndImport">选择文件导入</button>
      <text class="tip">仅支持有余自身导出的 JSON 文件。</text>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 40rpx 36rpx;
  margin-bottom: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.c-title {
  font-size: 32rpx;
  font-weight: 800;
  color: #1f2937;
}
.c-desc {
  font-size: 26rpx;
  color: #6b7280;
  line-height: 1.6;
}
.btn {
  background: #f0f2f5;
  color: #4b5563;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.btn.primary {
  background: #16a34a;
  color: #fff;
}
.tip {
  font-size: 22rpx;
  color: #bbb;
  text-align: center;
}
</style>
