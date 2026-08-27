<script setup lang="ts">
/**
 * 全局上传 dock · 任务 1.10
 * 挂在 MainLayout，全应用范围可见。
 * 离开知识库页面、切到任何菜单都不打断上传。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUploadStore } from '@/stores/upload'
import { ArrowUp, ArrowDown, Close, UploadFilled, Loading, CircleCheck, CircleClose } from '@element-plus/icons-vue'

const uploadStore = useUploadStore()
const router = useRouter()

// 没有任何任务时不渲染
const shouldShow = computed(() => uploadStore.queue.length > 0)

const progressPct = computed(() => {
  const { total, done } = uploadStore.stats
  if (!total) return 0
  return Math.round((done / total) * 100)
})

const statusLine = computed(() => {
  const s = uploadStore.stats
  if (s.uploading > 0) return `上传中 ${s.uploading} / ${s.total}`
  if (s.failed > 0 && !uploadStore.hasActive) return `完成 · ${s.done} 成功 · ${s.failed} 失败`
  if (uploadStore.allDone) return `已完成 ${s.done} 个文件`
  return `${s.total} 个文件`
})

const goKnowledgeAndOpen = () => {
  // 跳到知识库页面并打开上传弹窗（弹窗会读 store 显示同一队列）
  uploadStore.dialogVisible = true
  if (router.currentRoute.value.path !== '/knowledge') {
    router.push('/knowledge')
  }
}
</script>

<template>
  <Transition name="dock-slide">
    <div v-if="shouldShow" class="upload-dock" :class="{ minimized: uploadStore.dockMinimized }">
      <div class="dock-header" @click="uploadStore.dockMinimized = !uploadStore.dockMinimized">
        <el-icon class="dock-icon" :class="{ spinning: uploadStore.hasActive }">
          <Loading v-if="uploadStore.hasActive" />
          <CircleClose v-else-if="uploadStore.stats.failed > 0" color="#ef4444" />
          <CircleCheck v-else-if="uploadStore.allDone" color="#34d399" />
          <UploadFilled v-else />
        </el-icon>
        <div class="dock-title-wrap">
          <div class="dock-title">{{ statusLine }}</div>
          <div class="dock-sub">点击{{ uploadStore.dockMinimized ? '展开' : '收起' }}</div>
        </div>
        <el-icon class="dock-toggle" @click.stop="uploadStore.dockMinimized = !uploadStore.dockMinimized">
          <ArrowDown v-if="uploadStore.dockMinimized" />
          <ArrowUp v-else />
        </el-icon>
        <el-icon
          v-if="!uploadStore.hasActive"
          class="dock-close"
          title="清空已完成"
          @click.stop="uploadStore.clearAll()"
        ><Close /></el-icon>
      </div>

      <!-- 进度条（始终显示，迷你版） -->
      <el-progress
        :percentage="progressPct"
        :show-text="false"
        :stroke-width="3"
        :status="uploadStore.stats.failed > 0 ? 'exception' : (uploadStore.allDone ? 'success' : '')"
      />

      <!-- 展开时的迷你列表 -->
      <div v-show="!uploadStore.dockMinimized" class="dock-body">
        <div v-for="row in uploadStore.queue.slice(0, 8)" :key="row.id" class="dock-row">
          <span class="dr-name" :title="row.name">{{ row.name }}</span>
          <span class="dr-status" :class="'st-' + row.status">
            <template v-if="row.status === 'uploading'">{{ row.progress }}%</template>
            <template v-else-if="row.status === 'pending'">…</template>
            <template v-else-if="row.status === 'done'">✓</template>
            <template v-else-if="row.status === 'failed'">✗</template>
            <template v-else>处理</template>
          </span>
        </div>
        <div v-if="uploadStore.queue.length > 8" class="dock-more">
          …等 {{ uploadStore.queue.length }} 个文件
        </div>
        <el-button
          link
          size="small"
          style="width:100%;margin-top:6px"
          @click="goKnowledgeAndOpen"
        >打开完整队列</el-button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.upload-dock {
  position: fixed;
  right: 20px;
  bottom: 20px;
  width: 320px;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.4);
  z-index: 9000;
  overflow: hidden;
}
.upload-dock.minimized { width: 260px; }

.dock-header {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  background: linear-gradient(180deg, rgba(56,189,248,0.08) 0%, transparent 100%);
}
.dock-icon { font-size: 18px; color: #38bdf8; }
.dock-icon.spinning { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.dock-title-wrap { flex: 1; min-width: 0; }
.dock-title {
  font-size: 13px; color: #e2e8f0; font-weight: 600;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.dock-sub { font-size: 11px; color: #64748b; }
.dock-toggle, .dock-close {
  width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center;
  color: #94a3b8;
  border-radius: 50%;
  transition: background 0.15s, color 0.15s;
}
.dock-toggle:hover, .dock-close:hover { background: #334155; color: #e2e8f0; }
.dock-close:hover { color: #ef4444; }

.dock-body {
  padding: 8px 12px 12px;
  border-top: 1px solid rgba(148,163,184,0.08);
}
.dock-row {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 0;
  font-size: 12px;
  border-bottom: 1px solid rgba(148,163,184,0.05);
}
.dock-row:last-of-type { border-bottom: none; }
.dr-name { flex: 1; color: #cbd5e1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dr-status { flex-shrink: 0; font-weight: 600; }
.dr-status.st-done { color: #34d399; }
.dr-status.st-failed { color: #ef4444; }
.dr-status.st-uploading, .dr-status.st-processing { color: #38bdf8; }
.dr-status.st-pending { color: #64748b; }
.dock-more { text-align: center; font-size: 11px; color: #64748b; padding: 4px 0; }

/* 动画 */
.dock-slide-enter-active, .dock-slide-leave-active {
  transition: transform 0.3s ease, opacity 0.3s ease;
}
.dock-slide-enter-from, .dock-slide-leave-to {
  transform: translateY(20px);
  opacity: 0;
}
</style>
