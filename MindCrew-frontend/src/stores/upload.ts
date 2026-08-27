/**
 * 全局上传队列 store（任务 1.10）
 *
 * 核心能力：
 *   1. 多文件队列：addFiles / removeItem / retry / clearDone
 *   2. 并行调度：MAX_PARALLEL 控制同时上传数
 *   3. 前端预校验：大小 / 格式 / 同名重复
 *   4. 后台续传：弹窗关闭、路由切换都不中断（GlobalUploadDock 始终挂载在 MainLayout）
 *   5. 完成回调：onItemDone 让 KnowledgeView 自动刷新列表
 */
import { ref, computed, reactive } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import { knowledgeApi } from '@/api/knowledge'

export type UploadStatus = 'pending' | 'uploading' | 'processing' | 'done' | 'failed'

export interface UploadItem {
  id: string                       // 前端 uuid
  file: File
  name: string
  relPath?: string                 // 文件夹上传时的相对路径（webkitRelativePath）
  size: number
  ext: string
  category: string
  description: string
  status: UploadStatus
  progress: number                 // 0~100
  errorMsg: string
  kbId: number | null              // 后端返回的真实 id
  startedAt: number | null
  finishedAt: number | null
}

/** 全类型统一上限（MB）· 与后端 multipart max-file-size(1300MB)对齐 */
const SIZE_LIMIT_MB_DEFAULT = 1300
const SIZE_LIMIT_MB: Record<string, number> = {
  // 文档
  pdf: 1300, doc: 1300, docx: 1300, ppt: 1300, pptx: 1300,
  xls: 1300, xlsx: 1300, csv: 1300, wps: 1300,
  html: 1300, htm: 1300, txt: 1300, md: 1300, markdown: 1300,
  // 图片
  jpg: 1300, jpeg: 1300, png: 1300, webp: 1300, bmp: 1300, gif: 1300,
  // 音频
  mp3: 1300, wav: 1300, m4a: 1300, aac: 1300, flac: 1300, opus: 1300, ogg: 1300, amr: 1300,
  // 视频
  mp4: 1300, mov: 1300, mkv: 1300, avi: 1300, flv: 1300, webm: 1300, m4v: 1300,
}

const SUPPORTED_EXTS = new Set(Object.keys(SIZE_LIMIT_MB))
const MAX_PARALLEL = 3            // 同时跑的上传数

function uuid(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

function getExt(name: string): string {
  const i = name.lastIndexOf('.')
  return i < 0 ? '' : name.slice(i + 1).toLowerCase()
}

export const useUploadStore = defineStore('upload', () => {
  // ─── 队列 ───────────────────────────────────────────
  const queue = reactive<UploadItem[]>([])

  // 完成回调（KnowledgeView 注册，让列表自动刷新）
  const doneCallbacks = ref<Array<(item: UploadItem) => void>>([])
  function onItemDone(cb: (item: UploadItem) => void) {
    doneCallbacks.value.push(cb)
    return () => {
      const i = doneCallbacks.value.indexOf(cb)
      if (i >= 0) doneCallbacks.value.splice(i, 1)
    }
  }

  // ─── UI 状态 ────────────────────────────────────────
  const dialogVisible = ref(false)      // 主上传对话框
  const dockMinimized = ref(false)      // 右下角 dock 折叠/展开
  /** 当前选中的目标知识库 id（任务 15）· null 视为散文档 */
  const targetCollectionId = ref<number | null>(null)

  // ─── 统计 ───────────────────────────────────────────
  const stats = computed(() => ({
    total: queue.length,
    pending: queue.filter(q => q.status === 'pending').length,
    uploading: queue.filter(q => q.status === 'uploading' || q.status === 'processing').length,
    done: queue.filter(q => q.status === 'done').length,
    failed: queue.filter(q => q.status === 'failed').length,
  }))
  const hasActive = computed(() => stats.value.pending + stats.value.uploading > 0)
  const allDone = computed(() => queue.length > 0 && !hasActive.value)

  // ─── 添加文件 ───────────────────────────────────────
  /**
   * @returns { added, rejected } · rejected 含 reason 给前端 toast
   */
  function addFiles(
    files: File[],
    category = '',
    description = ''
  ): { added: UploadItem[]; rejected: Array<{ name: string; reason: string }> } {
    const added: UploadItem[] = []
    const rejected: Array<{ name: string; reason: string }> = []

    for (const f of files) {
      // 文件夹上传（webkitdirectory）时 File 自带 webkitRelativePath（如 "docs/a.pdf"）
      const relPath = (f as any).webkitRelativePath || ''
      const displayName = relPath || f.name
      const ext = getExt(displayName)
      if (!ext || !SUPPORTED_EXTS.has(ext)) {
        rejected.push({ name: displayName, reason: '不支持的格式 .' + (ext || '?') })
        continue
      }
      const limitMb = SIZE_LIMIT_MB[ext] ?? SIZE_LIMIT_MB_DEFAULT
      if (f.size > limitMb * 1024 * 1024) {
        rejected.push({ name: displayName, reason: `超过 ${limitMb}MB 上限（实际 ${(f.size / 1024 / 1024).toFixed(1)}MB）` })
        continue
      }
      // 同名+同大小 → 视为重复（文件夹模式用相对路径区分不同目录下的同名文件）
      const dupKey = relPath || f.name
      const dup = queue.find(q =>
        (q.relPath || q.name) === dupKey &&
        q.size === f.size &&
        (q.status === 'pending' || q.status === 'uploading' || q.status === 'done')
      )
      if (dup) {
        rejected.push({ name: displayName, reason: '已在队列中（同名同大小）' })
        continue
      }
      added.push({
        id: uuid(),
        file: f,
        name: displayName,
        relPath: relPath || undefined,
        size: f.size,
        ext,
        category,
        description,
        status: 'pending',
        progress: 0,
        errorMsg: '',
        kbId: null,
        startedAt: null,
        finishedAt: null,
      })
    }
    queue.push(...added)
    if (added.length > 0) tick()
    return { added, rejected }
  }

  // ─── 单条操作 ───────────────────────────────────────
  function removeItem(id: string) {
    const i = queue.findIndex(q => q.id === id)
    if (i < 0) return
    const it = queue[i]
    if (!it) return
    // 已在传 → 不允许移除（避免 race），需要先等完成
    if (it.status === 'uploading') return
    queue.splice(i, 1)
  }

  function retry(id: string) {
    const it = queue.find(q => q.id === id)
    if (!it) return
    if (it.status !== 'failed') return
    it.status = 'pending'
    it.progress = 0
    it.errorMsg = ''
    tick()
  }

  function clearDone() {
    for (let i = queue.length - 1; i >= 0; i--) {
      if (queue[i]?.status === 'done') queue.splice(i, 1)
    }
  }

  function clearAll() {
    // 只清非进行中的
    for (let i = queue.length - 1; i >= 0; i--) {
      if (queue[i]?.status !== 'uploading') queue.splice(i, 1)
    }
  }

  // ─── 暂停/继续 ──────────────────────────────────────
  // 暂停 = 阻止新上传 + abort 正在传的请求（被 abort 的条目回到 pending，resume 后重传）
  const paused = ref(false)
  /** 每个在传条目的 AbortController，用于暂停时中断 */
  const controllers = new Map<string, AbortController>()

  function pauseUploads() {
    paused.value = true
    // 中断所有在传请求；uploadOne 的 catch 会把它们置回 pending
    for (const c of controllers.values()) {
      try { c.abort() } catch { /* ignore */ }
    }
  }
  function resumeUploads() { paused.value = false; tick() }

  // ─── 调度 ───────────────────────────────────────────
  function tick() {
    if (paused.value) return
    const running = queue.filter(q => q.status === 'uploading').length
    const slots = MAX_PARALLEL - running
    if (slots <= 0) return
    const toStart = queue.filter(q => q.status === 'pending').slice(0, slots)
    toStart.forEach(item => uploadOne(item))
  }

  async function uploadOne(item: UploadItem) {
    item.status = 'uploading'
    item.progress = 0
    item.startedAt = Date.now()
    const controller = new AbortController()
    controllers.set(item.id, controller)
    try {
      const kbId = await knowledgeApi.upload(
        item.file,
        item.category,
        item.description,
        (pct) => { item.progress = pct },
        targetCollectionId.value,  // 任务 15：把目标知识库带上
        controller.signal          // 暂停时用它 abort
      )
      item.kbId = kbId
      item.progress = 100
      item.status = 'done'
      item.finishedAt = Date.now()
      doneCallbacks.value.forEach(cb => {
        try { cb(item) } catch (e) { console.warn('[upload] doneCallback 异常', e) }
      })
    } catch (e: any) {
      if (axios.isCancel(e) || e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError') {
        // 被暂停中断 → 回到 pending，等待"继续"后重新上传（非失败）
        item.status = 'pending'
        item.progress = 0
      } else {
        item.status = 'failed'
        item.finishedAt = Date.now()
        // 取最有用的错误信息
        item.errorMsg =
          e?.response?.data?.message ||
          e?.response?.data?.msg ||
          (typeof e?.response?.data === 'string' ? e.response.data : '') ||
          e?.message ||
          '上传失败'
      }
    } finally {
      controllers.delete(item.id)
      // 队列里下一个候补（paused 时 tick() 自身会 return，不会重启）
      tick()
    }
  }

  return {
    // 状态
    queue,
    dialogVisible,
    dockMinimized,
    targetCollectionId,
    // 计算
    stats,
    hasActive,
    allDone,
    paused,
    // 操作
    addFiles,
    removeItem,
    retry,
    clearDone,
    clearAll,
    onItemDone,
    pauseUploads,
    resumeUploads,
  }
})
