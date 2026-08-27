<script setup lang="ts">
/**
 * 全局上传弹窗 · 任务 15.3
 * 挂在 MainLayout · 由 uploadStore.dialogVisible 控制开关
 *
 * 任何页面（KnowledgeView / CollectionDetailView / etc.）调
 *   uploadStore.dialogVisible = true
 * 都能触发此弹窗。store.targetCollectionId 决定上传到哪个知识库。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { useUploadStore } from '@/stores/upload'
import { kbCategoryApi } from '@/api/kbCategory'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'

const uploadStore = useUploadStore()

const visible = computed({
  get: () => uploadStore.dialogVisible,
  set: (v: boolean) => { uploadStore.dialogVisible = v },
})

const isDragging   = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const folderInputRef = ref<HTMLInputElement>()

const form = ref<{ category: string; description: string }>({ category: '', description: '' })

// 文档分类功能暂时下线（置 true 即可恢复）
const SHOW_CATEGORY = false

// 分类字典
const categoryOptions = ref<{ label: string; value: string }[]>([
  { label: '技术', value: 'tech' }, { label: '产品', value: 'product' },
  { label: '法务', value: 'legal' }, { label: '财务', value: 'finance' },
  { label: '培训', value: 'training' }, { label: '人事', value: 'hr' },
  { label: '客户', value: 'customer' }, { label: '其他', value: 'other' },
])

// 知识库列表（用于显示当前上传目标 + 切换）
const collections = ref<KnowledgeCollection[]>([])
const currentCollectionName = computed(() => {
  if (!uploadStore.targetCollectionId) return null
  return collections.value.find(c => c.id === uploadStore.targetCollectionId)?.name || `#${uploadStore.targetCollectionId}`
})

const triggerFileInput   = () => fileInputRef.value?.click()
const triggerFolderInput = () => folderInputRef.value?.click()

// 系统/隐藏文件（文件夹上传时会出现）：过滤掉不进入队列
const SYSTEM_FILE_RE = /(^|[\/\\])(\.DS_Store|\.git|__MACOSX|\.svn|\.hg|Thumbs\.db)([\/\\]|$)/
const handleFileChange = (e: Event) => {
  const input = e.target as HTMLInputElement
  let files = Array.from(input.files || [])
  if (files.length) {
    files = files.filter(f => !SYSTEM_FILE_RE.test((f as any).webkitRelativePath || f.name))
    addFilesToQueue(files)
  }
  input.value = ''
}

const handleDrop = async (e: DragEvent) => {
  isDragging.value = false
  const items = e.dataTransfer?.items
  if (items && items.length && (items[0] as any).webkitGetAsEntry) {
    const all: File[] = []
    const promises: Promise<void>[] = []
    for (let i = 0; i < items.length; i++) {
      const entry = (items[i] as any).webkitGetAsEntry?.()
      if (entry) promises.push(traverseEntry(entry, all))
    }
    await Promise.all(promises)
    if (all.length) addFilesToQueue(all)
  } else {
    const files = Array.from(e.dataTransfer?.files || [])
    if (files.length) addFilesToQueue(files)
  }
}

function traverseEntry(entry: any, out: File[]): Promise<void> {
  return new Promise((resolve) => {
    if (entry.isFile) {
      entry.file((f: File) => {
        if (entry.fullPath && entry.fullPath !== '/' + f.name) {
          try {
            Object.defineProperty(f, 'name', {
              value: entry.fullPath.replace(/^\//, '').replace(/\//g, '_'),
            })
          } catch (_) {}
        }
        out.push(f)
        resolve()
      }, () => resolve())
    } else if (entry.isDirectory) {
      const reader = entry.createReader()
      const all: Promise<void>[] = []
      const readBatch = () => {
        reader.readEntries((entries: any[]) => {
          if (!entries.length) { Promise.all(all).then(() => resolve()); return }
          entries.forEach(e => all.push(traverseEntry(e, out)))
          readBatch()
        }, () => resolve())
      }
      readBatch()
    } else { resolve() }
  })
}

function addFilesToQueue(files: File[]) {
  if (files.length > 100) {
    ElMessage.warning(`一次最多 100 个文件，本次取前 100 个（共 ${files.length}）`)
    files = files.slice(0, 100)
  }
  const { added, rejected } = uploadStore.addFiles(files, form.value.category, form.value.description)
  if (added.length > 0) ElMessage.success(`已加入队列 ${added.length} 个文件`)
  if (rejected.length > 0) {
    const sample = rejected.slice(0, 3).map(r => `${r.name}：${r.reason}`).join('\n')
    const more = rejected.length > 3 ? `\n…（共 ${rejected.length} 个跳过）` : ''
    ElMessageBox.alert(sample + more, '部分文件未加入队列', { type: 'warning', confirmButtonText: '知道了' })
  }
}

const onDialogClose = () => {
  // 后台运行：不清队列，仅重置表单
  form.value = { category: '', description: '' }
}

// 文件类型工具
const getFileExt = (name: string) => name.split('.').pop()?.toLowerCase() || ''
const getFileIcon = (ext: string) => {
  if (['pdf'].includes(ext)) return 'Document'
  if (['docx','doc'].includes(ext)) return 'Edit'
  if (['md','markdown'].includes(ext)) return 'Memo'
  return 'Document'
}
const getFileColor = (ext: string) => {
  if (['pdf'].includes(ext)) return '#f87171'
  if (['docx','doc'].includes(ext)) return '#38bdf8'
  if (['md','markdown'].includes(ext)) return '#71717a'
  return '#94a3b8'
}
const formatFileSize = (bytes: number) => {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

onMounted(async () => {
  // 加载分类字典
  try {
    const res: any = await kbCategoryApi.list()
    const arr = res?.data ?? res ?? []
    if (Array.isArray(arr) && arr.length) {
      categoryOptions.value = arr.map((c: any) => ({ label: c.name, value: c.code }))
    }
  } catch { /* keep fallback */ }
  // 加载知识库列表（用于显示当前上传目标）
  try {
    const res: any = await collectionApi.list()
    collections.value = res?.data ?? res ?? []
  } catch { /* ignore */ }
})

const goToOrphan = () => {
  uploadStore.targetCollectionId = null
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="currentCollectionName ? `上传到「${currentCollectionName}」` : '上传文档（散文档）'"
    width="720px"
    :close-on-click-modal="false"
    @close="onDialogClose"
  >
    <!-- 当前上传目标提示 -->
    <div class="target-info" :class="{ orphan: !currentCollectionName }">
      <div>
        <el-icon size="14"><UploadFilled /></el-icon>
        <span v-if="currentCollectionName">
          文档将上传到 <b>{{ currentCollectionName }}</b> 知识库 · 权限继承
        </span>
        <span v-else>
          <b>未指定知识库</b> · 上传后为散文档（仅本人可见，无法 API 访问）
        </span>
      </div>
      <el-button v-if="currentCollectionName" link size="small" @click="goToOrphan">
        改为散文档
      </el-button>
    </div>

    <el-form label-width="80px">
      <!-- 拖拽区 -->
      <el-form-item>
        <div
          class="drop-zone"
          :class="{ dragging: isDragging }"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          @click="triggerFileInput"
        >
          <input ref="fileInputRef" type="file" multiple
                 accept=".pdf,.docx,.doc,.pptx,.ppt,.xlsx,.xls,.csv,.wps,.html,.htm,.jpg,.jpeg,.png,.webp,.bmp,.gif,.mp3,.wav,.m4a,.aac,.flac,.opus,.ogg,.amr,.mp4,.mov,.mkv,.avi,.flv,.webm,.m4v,.txt,.md,.markdown"
                 style="display:none" @change="handleFileChange" />
          <input ref="folderInputRef" type="file" multiple webkitdirectory directory
                 style="display:none" @change="handleFileChange" />
          <el-icon size="32" color="#334155"><UploadFilled /></el-icon>
          <div class="drop-text">
            拖拽文件 / 文件夹到此，或<span class="drop-link">点击选择文件</span>
            <span class="drop-link" @click.stop="triggerFolderInput" style="margin-left:8px">
              选择文件夹
            </span>
          </div>
          <div class="drop-hint">
            PDF / Word / PowerPoint / Excel / 图片 / 音频 / 视频 / 文本（最多 100 个 · 单文件 ≤ 1300MB）
          </div>
        </div>
      </el-form-item>

      <el-form-item v-if="SHOW_CATEGORY" label="默认分类">
        <el-select v-model="form.category" placeholder="留空 · AI 自动判断" clearable style="width:100%">
          <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
      </el-form-item>

      <el-form-item label="默认描述">
        <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选 · 留空则后端自动生成" />
      </el-form-item>

      <!-- 队列 -->
      <el-form-item v-if="uploadStore.queue.length > 0" label="队列">
        <div class="queue-toolbar">
          <span class="queue-stat">
            总 {{ uploadStore.stats.total }} ·
            <span style="color:#38bdf8">进行 {{ uploadStore.stats.uploading + uploadStore.stats.pending }}</span> ·
            <span style="color:#34d399">完成 {{ uploadStore.stats.done }}</span>
            <span v-if="uploadStore.stats.failed > 0" style="color:#ef4444"> · 失败 {{ uploadStore.stats.failed }}</span>
          </span>
          <el-button v-if="!uploadStore.paused" link size="small" type="warning"
                     :disabled="!uploadStore.hasActive" @click="uploadStore.pauseUploads">暂停</el-button>
          <el-button v-else link size="small" type="success" @click="uploadStore.resumeUploads">继续</el-button>
          <el-button link size="small" :disabled="uploadStore.stats.done === 0" @click="uploadStore.clearDone">清除已完成</el-button>
          <el-button link size="small" type="danger" :disabled="uploadStore.hasActive" @click="uploadStore.clearAll">清空</el-button>
        </div>
        <div class="queue-list">
          <div v-for="row in uploadStore.queue" :key="row.id" class="queue-row" :class="'st-' + row.status">
            <el-icon size="16" :color="getFileColor(row.ext)" class="qr-icon">
              <component :is="getFileIcon(row.ext)" />
            </el-icon>
            <div class="qr-main">
              <div class="qr-name" :title="row.name">{{ row.name }}</div>
              <div class="qr-meta">
                <span>{{ formatFileSize(row.size) }}</span>
                <span class="qr-status">
                  <span v-if="row.status === 'pending'">待上传</span>
                  <span v-else-if="row.status === 'uploading'">上传中 {{ row.progress }}%</span>
                  <span v-else-if="row.status === 'processing'">服务端处理</span>
                  <span v-else-if="row.status === 'done'" style="color:#34d399">✓ 成功</span>
                  <span v-else-if="row.status === 'failed'" style="color:#ef4444" :title="row.errorMsg">✗ {{ row.errorMsg || '失败' }}</span>
                </span>
              </div>
              <el-progress v-if="row.status === 'uploading' || row.status === 'pending'"
                           :percentage="row.progress" :show-text="false" :stroke-width="3" color="#38bdf8" />
            </div>
            <div class="qr-actions">
              <el-button v-if="row.status === 'failed'" link size="small" @click="uploadStore.retry(row.id)">重试</el-button>
              <el-button v-if="row.status !== 'uploading'" link size="small" type="danger"
                         @click="uploadStore.removeItem(row.id)">移除</el-button>
            </div>
          </div>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">
        {{ uploadStore.hasActive ? '后台运行' : '关闭' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.target-info {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px;
  background: rgba(0,0,0,0.06);
  border: 1px solid rgba(0,0,0,0.2);
  border-radius: 8px;
  margin-bottom: 16px;
  font-size: 12.5px; color: var(--ink-2, #475569);
}
.target-info.orphan {
  background: rgba(245,158,11,0.08);
  border-color: rgba(245,158,11,0.3);
  color: #b45309;
}
.target-info b { color: #0a0a0a; font-weight: 700; }
.target-info.orphan b { color: #b45309; }

.drop-zone {
  width: 100%; min-height: 130px;
  border: 2px dashed var(--border, #e2e8f0); border-radius: 12px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; cursor: pointer; transition: 0.15s;
  background: var(--bg-elevated, #f8fafc); position: relative;
}
.drop-zone:hover, .drop-zone.dragging {
  border-color: #38bdf8; background: rgba(56,189,248,0.06);
}
.drop-text { font-size: 13px; color: #64748b; }
.drop-link { color: #38bdf8; cursor: pointer; }
.drop-hint { font-size: 11px; color: #94a3b8; }

.queue-toolbar { display: flex; align-items: center; gap: 12px; width: 100%; padding: 0 4px 6px; font-size: 12px; }
.queue-stat { color: #94a3b8; flex: 1; }
.queue-list {
  width: 100%; max-height: 260px; overflow-y: auto;
  border: 1px solid var(--border, #e2e8f0); border-radius: 8px;
  background: var(--bg-elevated, #f8fafc);
}
.queue-row {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; border-bottom: 1px solid rgba(148,163,184,0.08);
  transition: background 0.15s;
}
.queue-row:last-child { border-bottom: none; }
.queue-row:hover { background: rgba(56,189,248,0.04); }
.queue-row.st-done { opacity: 0.7; }
.queue-row.st-failed { background: rgba(239,68,68,0.04); }
.qr-icon { flex-shrink: 0; }
.qr-main { flex: 1; min-width: 0; }
.qr-name { font-size: 13px; color: var(--ink-1, #0f172a); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.qr-meta { font-size: 11px; color: #64748b; display: flex; gap: 12px; margin-top: 2px; }
.qr-status { color: #94a3b8; }
.qr-actions { flex-shrink: 0; }
</style>
