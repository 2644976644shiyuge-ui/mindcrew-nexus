<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  CopyDocument,
  Delete,
  Download,
  Paperclip,
  Plus,
} from '@element-plus/icons-vue'
import {
  digitalEmployeeApi,
  type DigitalEmployeeDetail,
  type DeliverableDraft,
  type PptProviderStatus,
} from '@/api/digitalEmployee'
import { chatApi } from '@/api/chat'
import { pptApi, type PptTask, type PptTaskStatus } from '@/api/ppt'
import { ElMessage } from 'element-plus'

interface ClarifyState {
  question: string
  options: string[]
  allowSkip: boolean
  answered: boolean
  chosen?: string
}

interface ChatMsg {
  role: string
  content: string
  id?: number
  sources?: any[]
  clarify?: ClarifyState
  /** 澄清二次请求需要带回用户最初的问题，避免只发送一个脱离语境的选项。 */
  originalQuestion?: string
  /** 🆕 流式期间实时显示"正在读的文档"，done 后清空 */
  liveSources?: { docName: string; excerpt: string; score: number }[]
}

const route = useRoute()
const router = useRouter()
const employeeId = Number(route.params.id)

const detail = ref<DigitalEmployeeDetail | null>(null)
const sessions = ref<any[]>([])
const currentConvId = ref<number | null>(null)
const messages = ref<ChatMsg[]>([])
const input = ref('')
const streaming = ref(false)
const exporting = ref(false)
const webSearch = ref(true)
const listRef = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const pendingFiles = ref<{ name: string; objectName: string }[]>([])
const uploadingAttachments = ref(false)
const uploadingAttachmentCount = ref(0)
let currentSse: EventSource | null = null
let pptPollTimer: number | undefined
let unavailableNoticeShown = false

const isPptScenario = computed(
  () => detail.value?.primaryScenario === 'ppt_authoring',
)
const pptTasks = ref<PptTask[]>([])
const submittingPpt = ref(false)
const retryingPptId = ref<number | null>(null)
const downloadingPptId = ref<number | null>(null)
const cancelingPptId = ref<number | null>(null)
const previewingPptId = ref<number | null>(null)
const revisionBaseTaskId = ref<number | null>(null)
const pptPreviewVisible = ref(false)
const pptPreviewUrl = ref('')
const pptPreviewTitle = ref('')
const composerBusy = computed(() =>
  uploadingAttachments.value || (isPptScenario.value ? submittingPpt.value : streaming.value),
)

const lastAssistantMsg = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m?.role === 'assistant' && m.content?.trim()) return m
  }
  return null
})

/** 流式输出中不能导出（无 messageId、内容不完整） */
const canExportDeliverable = computed(
  () =>
    !streaming.value &&
    !!currentConvId.value &&
    !!lastAssistantMsg.value?.content?.trim(),
)

const draftVisible = ref(false)
const draftLoading = ref(false)
const draftExporting = ref(false)
const deliverableDraft = ref<DeliverableDraft | null>(null)
const pptProviderStatus = ref<PptProviderStatus | null>(null)
const reviewAcknowledged = ref(false)
const expertEdit = ref(false)

const pptModes = [
  { value: 'auto', name: '智能生成', desc: '自动完成结构、版式与视觉，最快交付' },
  { value: 'guided', name: '引导生成', desc: '保留更多人工确认，适合重要汇报' },
  { value: 'corporate', name: '企业模板', desc: '优先遵循管理员配置的品牌规范' },
]

const pptStyles = [
  { value: 'business', name: '商务专业', desc: '稳健、清晰，适合经营汇报', tone: 'blue' },
  { value: 'consulting', name: '咨询报告', desc: '结论先行，强调图表和逻辑', tone: 'navy' },
  { value: 'technology', name: '科技创新', desc: '深色渐变，适合产品与方案', tone: 'violet' },
  { value: 'government', name: '正式汇报', desc: '克制规范，适合政府和国企', tone: 'red' },
  { value: 'minimal', name: '极简留白', desc: '高管风格，突出关键结论', tone: 'gray' },
  { value: 'brand', name: '品牌自适应', desc: '根据公司色和模板自动设计', tone: 'green' },
]

const pptPromptExamples = [
  '根据上传资料生成一份 12 页董事会汇报，结论先行，重点说明问题、原因和下一步行动',
  '制作一份面向客户的解决方案 PPT，突出业务价值、实施路径和成功案例',
  '把本次项目复盘整理成管理层汇报，包含关键指标、风险和后续计划',
]

const blockingChecks = computed(() =>
  deliverableDraft.value?.qualityChecks?.filter((check) => check.status === 'BLOCK') ?? [],
)

const pptAudience = computed({
  get: () => deliverableDraft.value?.presentation?.audience || '',
  set: (value: string) => {
    const profile = ensurePresentationProfile()
    if (profile) profile.audience = value
  },
})

const pptPurpose = computed({
  get: () => deliverableDraft.value?.presentation?.purpose || '',
  set: (value: string) => {
    const profile = ensurePresentationProfile()
    if (profile) profile.purpose = value
  },
})

function plainChatContent(content: string) {
  if (!content) return ''
  return content
    .replace(/```[\w-]*\n?/g, '')
    .replace(/```/g, '')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '• ')
    .replace(/^\s*>\s?/gm, '')
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/__(.*?)__/g, '$1')
    .replace(/\*([^*\n]+)\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[([^\]]*)]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)]\(([^)]+)\)/g, '$1（$2）')
    .replace(/^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/gm, '')
    .replace(/^\s*\|(.+)\|\s*$/gm, (_line, cells: string) =>
      cells.split('|').map((cell) => cell.trim()).filter(Boolean).join('　|　'),
    )
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function canExportMsg(m: ChatMsg) {
  return !streaming.value && !!m.content?.trim() && !!currentConvId.value
}

const draftFormat = computed<'docx' | 'pptx'>(() =>
  deliverableDraft.value?.draftType === 'ppt' ? 'pptx' : 'docx',
)

async function loadMeta() {
  detail.value = await digitalEmployeeApi.detail(employeeId)
  webSearch.value = detail.value.webSearch !== false
  sessions.value = await digitalEmployeeApi.sessions(employeeId)
  const requestedConversationId = Number(route.query.conversationId)
  const hasRequestedConversation = Number.isInteger(requestedConversationId)
    && requestedConversationId > 0

  if (hasRequestedConversation) {
    const requestedSession = sessions.value.find(
      session => Number(session.id) === requestedConversationId,
    )
    if (requestedSession) {
      try {
        await openSession(requestedConversationId, true)
        return
      } catch (error) {
        if (!isConversationUnavailable(error)) throw error
      }
    }
    await recoverUnavailableConversation(requestedConversationId)
    return
  }

  const initialSession = sessions.value[0]
  if (!initialSession) return
  try {
    await openSession(Number(initialSession.id))
  } catch (error) {
    if (!isConversationUnavailable(error)) throw error
    await recoverUnavailableConversation(Number(initialSession.id))
  }
}

function isConversationUnavailable(error: any) {
  const status = Number(error?.response?.status)
  const message = String(
    error?.response?.data?.message
      || error?.message
      || '',
  )
  return status === 403
    || status === 404
    || /会话不存在|无权访问|conversation\s+(?:not\s+found|unavailable)/i.test(message)
}

function notifyConversationRecovered() {
  if (unavailableNoticeShown) return
  unavailableNoticeShown = true
  ElMessage.warning('原会话已不可用，已为你切换到新对话')
}

async function recoverUnavailableConversation(staleConversationId: number) {
  currentConvId.value = null
  messages.value = []
  pptTasks.value = []
  revisionBaseTaskId.value = null
  sessions.value = sessions.value.filter(
    session => Number(session.id) !== staleConversationId,
  )

  // A stale deep link must not strand the entire employee workspace. Try the
  // remaining visible sessions first, then create a clean session as fallback.
  for (const session of [...sessions.value]) {
    const candidateId = Number(session.id)
    try {
      await openSession(candidateId, true)
      notifyConversationRecovered()
      return
    } catch (error) {
      if (!isConversationUnavailable(error)) throw error
      sessions.value = sessions.value.filter(item => Number(item.id) !== candidateId)
    }
  }

  const conv = await digitalEmployeeApi.newSession(employeeId, '新对话')
  sessions.value.unshift(conv)
  currentConvId.value = Number(conv.id)
  router.replace({
    query: {
      ...route.query,
      conversationId: String(conv.id),
      mode: undefined,
    },
  })
  notifyConversationRecovered()
}

async function openSession(convId: number, silentError = false) {
  revisionBaseTaskId.value = null
  const res = await digitalEmployeeApi.sessionHistory(
    employeeId,
    convId,
    { current: 1, size: 100 },
    { silentError },
  )
  currentConvId.value = convId
  if (String(route.query.conversationId || '') !== String(convId)) {
    router.replace({ query: { ...route.query, conversationId: String(convId), mode: undefined } })
  }
  if (isPptScenario.value) await loadPptTasks(convId)
  const rows = res?.records ?? []
  messages.value = rows.map((m: any, index: number) => {
      const sources = parseSources(m.sources)
      const clarifySource = sources.find((source: any) => source?.type === 'clarify')
      let originalQuestion = ''
      for (let i = index - 1; i >= 0; i--) {
        if (rows[i]?.role === 'user') {
          originalQuestion = rows[i].content || ''
          break
        }
      }
      // 如果澄清卡片之后已经有用户消息，说明历史会话中该卡片已被回答。
      const clarifyAnswered = clarifySource
        ? rows.slice(index + 1).some((row: any) => row?.role === 'user')
        : false
      return {
        role: m.role,
        content: m.content,
        id: m.id,
        sources,
        originalQuestion,
        clarify: clarifySource ? {
          question: clarifySource.question || m.content || '',
          options: Array.isArray(clarifySource.options) ? clarifySource.options : [],
          allowSkip: clarifySource.allowSkip !== false,
          answered: clarifyAnswered,
        } : undefined,
      }
    })
    .filter((message: ChatMsg) =>
      !isPptScenario.value
      || !message.sources?.some(source => source?.type === 'ppt_task'),
    )
  await scrollBottom()
}

function parseSources(s: any): any[] {
  if (!s) return []
  if (Array.isArray(s)) return s
  try {
    return JSON.parse(s)
  } catch {
    return []
  }
}

async function ensureConversation(): Promise<number | null> {
  if (currentConvId.value) return currentConvId.value
  const conv = await digitalEmployeeApi.newSession(employeeId, '新对话')
  sessions.value.unshift(conv)
  currentConvId.value = conv.id
  router.replace({ query: { ...route.query, conversationId: String(conv.id), mode: undefined } })
  return conv.id
}

async function newChat() {
  try {
    const conv = await digitalEmployeeApi.newSession(employeeId, '新对话')
    sessions.value.unshift(conv)
    currentConvId.value = conv.id
    router.replace({ query: { ...route.query, conversationId: String(conv.id), mode: undefined } })
    messages.value = []
    pptTasks.value = []
    pendingFiles.value = []
    revisionBaseTaskId.value = null
  } catch {
    ElMessage.error('创建对话失败')
  }
}

function scrollBottom() {
  return nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

// 智能滚动：用户向上翻页时停止自动滚，避免打断阅读
const userScrolledUp = ref(false)
const showJumpToBottom = ref(false)
function isNearBottom() {
  const el = listRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 80
}
function onMessagesScroll() {
  const near = isNearBottom()
  userScrolledUp.value = !near
  showJumpToBottom.value = !near
}
function jumpToBottom() {
  scrollBottom().then(() => {
    userScrolledUp.value = false
    showJumpToBottom.value = false
  })
}
function scrollBottomIfNear() {
  // 用户在底部 → 自动滚；用户上翻了 → 不滚
  if (userScrolledUp.value) return
  scrollBottom()
}

function pickFile() {
  fileInput.value?.click()
}

async function onFilesSelected(ev: Event) {
  const inputEl = ev.target as HTMLInputElement
  const files = inputEl.files
  if (!files?.length) return
  const selected = Array.from(files)
  const token = localStorage.getItem('token') || ''
  uploadingAttachments.value = true
  uploadingAttachmentCount.value = selected.length
  try {
    for (const file of selected) {
      const fd = new FormData()
      fd.append('file', file)
      try {
        const res = await fetch('/api/v2/chat/upload-attachment', {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
          body: fd,
        })
        const json = await res.json()
        const data = json.data ?? json
        const objectName = data.objectName ?? data.object_name
        if (res.ok && objectName) {
          pendingFiles.value.push({ name: file.name, objectName })
        } else {
          ElMessage.error(`${file.name}：${json.message || '上传失败'}`)
        }
      } catch {
        ElMessage.error(`${file.name}：上传失败`)
      } finally {
        uploadingAttachmentCount.value -= 1
      }
    }
  } finally {
    uploadingAttachments.value = false
    uploadingAttachmentCount.value = 0
    inputEl.value = ''
  }
}

function removePending(i: number) {
  pendingFiles.value.splice(i, 1)
}

interface SendOptions {
  message?: string
  allowClarify?: boolean
}

async function send(options: SendOptions = {}) {
  const text = (options.message ?? input.value).trim()
  if (!text || composerBusy.value) return
  if (isPptScenario.value) {
    await submitPptFromChat(text)
    return
  }
  try {
    await ensureConversation()
  } catch {
    ElMessage.error('无法创建对话，请稍后重试')
    return
  }
  const attachJson = pendingFiles.value.length
    ? JSON.stringify(pendingFiles.value.map((f) => ({ objectName: f.objectName, name: f.name })))
    : undefined
  if (options.message === undefined) input.value = ''
  pendingFiles.value = []
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '', originalQuestion: text })
  const aiMsg = messages.value[messages.value.length - 1]!
  // 🆕 实时检索：AI 正在读的文档片段（流式期间显示）
  aiMsg.liveSources = []
  streaming.value = true
  // 用户刚发新消息 → 强制滚到底（确保能看到完整回复）
  await scrollBottom()
  userScrolledUp.value = false
  showJumpToBottom.value = false

  const url = digitalEmployeeApi.streamUrl(
    employeeId,
    text,
    currentConvId.value ?? undefined,
    webSearch.value,
    attachJson,
    options.allowClarify,
  )
  const sse = new EventSource(url)
  currentSse = sse
  let generationErrorMessage = ''

  sse.addEventListener('token', (e) => {
    const d = JSON.parse(e.data)
    aiMsg.content += d.content ?? ''
    scrollBottomIfNear()
  })

  // 自纠错完成后后端会发送完整替代答案；直接覆盖旧草稿，避免两版内容拼接。
  sse.addEventListener('answer_replace', (e) => {
    const d = JSON.parse(e.data)
    const replacement = typeof d.content === 'string'
      ? d.content
      : (typeof d.answer === 'string' ? d.answer : null)
    if (replacement === null) return
    aiMsg.content = replacement
    scrollBottomIfNear()
  })

  sse.addEventListener('clarify', (e) => {
    const d = JSON.parse(e.data)
    const question = typeof d.question === 'string' ? d.question : ''
    aiMsg.clarify = {
      question,
      options: Array.isArray(d.options) ? d.options : [],
      allowSkip: d.allowSkip !== false,
      answered: false,
    }
    aiMsg.originalQuestion = text
    if (question) aiMsg.content = question
    scrollBottomIfNear()
  })

  // 🆕 实时检索：把 AI 正在读的文档片段推入 aiMsg.liveSources
  sse.addEventListener('retrieval_sources', (e) => {
    try {
      const d = JSON.parse(e.data)
      const sources = Array.isArray(d.sources) ? d.sources : []
      if (!sources.length) return
      aiMsg.liveSources = sources.map((s: any) => ({
        docName: s.docName || '未知文档',
        excerpt: s.excerpt || '',
        score: typeof s.score === 'number' ? s.score : 0
      }))
      scrollBottomIfNear()
    } catch {}
  })

  sse.addEventListener('done', async (e) => {
    try {
      const d = JSON.parse(e.data)
      if (d.conversationId) currentConvId.value = d.conversationId
      if (d.messageId) aiMsg.id = d.messageId
      if (d.sources) aiMsg.sources = parseSources(d.sources)
      // done.answer 与后端最终落库内容一致，应覆盖所有中间 token。
      if (typeof d.answer === 'string') aiMsg.content = d.answer
    } catch { /* ignore */ }
    // 🆕 done 后清理 liveSources（实时检索结束，正式来源转交给 sources 列表）
    aiMsg.liveSources = []
    cleanup()
    sessions.value = await digitalEmployeeApi.sessions(employeeId)
  })

  // 后端会通过命名 error 事件返回可操作原因。EventSource 也会把网络断开
  // 送到同一事件类型，因此只在事件确实携带 JSON data 时读取业务错误。
  sse.addEventListener('error', (event) => {
    const data = (event as MessageEvent).data
    if (typeof data !== 'string' || !data) return
    try {
      const payload = JSON.parse(data)
      generationErrorMessage = typeof payload?.message === 'string'
        ? payload.message
        : ''
      if (!aiMsg.content && generationErrorMessage) {
        aiMsg.content = generationErrorMessage
      }
    } catch { /* 网络 error 事件没有业务 JSON，交给 onerror 兜底 */ }
  })

  sse.onerror = () => {
    if (!aiMsg.content) {
      aiMsg.content = generationErrorMessage || '连接中断，请稍后重试。'
    }
    aiMsg.liveSources = []
    cleanup()
  }
}

function originalQuestionFor(msg: ChatMsg) {
  if (msg.originalQuestion?.trim()) return msg.originalQuestion.trim()
  const index = messages.value.indexOf(msg)
  for (let i = index - 1; i >= 0; i--) {
    const candidate = messages.value[i]
    if (candidate?.role === 'user') return candidate.content?.trim() || ''
  }
  return msg.clarify?.question?.trim() || ''
}

function answerClarify(msg: ChatMsg, option: string) {
  if (!msg.clarify || msg.clarify.answered || streaming.value) return
  msg.clarify.answered = true
  msg.clarify.chosen = option
  const originalQuestion = originalQuestionFor(msg)
  void send({
    message: `${originalQuestion || msg.clarify.question}（补充：${option}）`,
    allowClarify: false,
  })
}

function skipClarify(msg: ChatMsg) {
  if (!msg.clarify || msg.clarify.answered || streaming.value) return
  msg.clarify.answered = true
  msg.clarify.chosen = '__skip__'
  void send({
    message: originalQuestionFor(msg) || msg.clarify.question,
    allowClarify: false,
  })
}

function pptDefaults() {
  let config: Record<string, unknown> = {}
  try {
    config = detail.value?.scenarioConfig ? JSON.parse(detail.value.scenarioConfig) : {}
  } catch {
    config = {}
  }
  const styleMap: Record<string, string> = {
    商务简洁: 'business',
    咨询风: 'consulting',
    科技感: 'technology',
    政府汇报: 'government',
  }
  const slideCount = Number(config.slideCount)
  return {
    pageCount: Number.isFinite(slideCount) && slideCount >= 4 && slideCount <= 40
      ? slideCount : 12,
    visualStyle: styleMap[String(config.deckStyle || '')] || 'business',
    audience: String(config.audience || '').trim() || undefined,
    purpose: String(config.purpose || '').trim() || undefined,
  }
}

async function submitPptFromChat(text: string) {
  submittingPpt.value = true
  try {
    const conversationId = await ensureConversation()
    if (!conversationId) throw new Error('conversation unavailable')
    const files = [...pendingFiles.value]
    const defaults = pptDefaults()
    const task = await pptApi.create({
      prompt: text,
      employeeId,
      conversationId,
      baseTaskId: revisionBaseTaskId.value ?? undefined,
      pageCount: defaults.pageCount,
      language: 'zh-CN',
      visualStyle: defaults.visualStyle,
      audience: defaults.audience,
      purpose: defaults.purpose,
      attachments: files.length
        ? files.map(file => ({ objectName: file.objectName, name: file.name }))
        : undefined,
    })
    pptTasks.value = [task, ...pptTasks.value.filter(item => item.id !== task.id)]
    input.value = ''
    pendingFiles.value = []
    revisionBaseTaskId.value = null
    sessions.value = await digitalEmployeeApi.sessions(employeeId)
    await scrollBottom()
    ElMessage.success('已开始生成，您可以继续提交其他演示文稿')
  } catch (error: any) {
    ElMessage.error(error?.message || 'PPT 任务提交失败，请稍后重试')
  } finally {
    submittingPpt.value = false
  }
}

async function loadPptTasks(conversationId = currentConvId.value) {
  if (!conversationId) {
    pptTasks.value = []
    return
  }
  try {
    const tasks = await pptApi.list(50, { employeeId, conversationId })
    if (currentConvId.value === conversationId) {
      pptTasks.value = tasks
    }
  } catch {
    // 页面首次加载可能与会话切换并发，保留当前任务，下一轮自动重试。
  }
}

function isActivePpt(status: PptTaskStatus) {
  return status === 'queued' || status === 'generating'
}

function pptStatusText(status: PptTaskStatus) {
  return {
    queued: '已进入队列',
    generating: '正在生成',
    completed: '已完成',
    failed: '生成失败',
    canceled: '已取消',
  }[status]
}

function pptWarnings(task: PptTask) {
  if (!task.warnings) return []
  try {
    const value = JSON.parse(task.warnings)
    return Array.isArray(value) ? value.filter(item => typeof item === 'string') : []
  } catch {
    return [task.warnings]
  }
}

function pptAttachmentNames(task: PptTask) {
  if (!task.attachments) return []
  try {
    const value = JSON.parse(task.attachments)
    return Array.isArray(value)
      ? value.map(item => item?.name).filter(Boolean)
      : []
  } catch {
    return []
  }
}

async function retryPpt(taskId: number) {
  retryingPptId.value = taskId
  try {
    const task = await pptApi.retry(taskId)
    pptTasks.value = pptTasks.value.map(item => item.id === taskId ? task : item)
    ElMessage.success('已重新开始生成')
  } catch {
    ElMessage.error('重新生成失败，请稍后重试')
  } finally {
    retryingPptId.value = null
  }
}

async function cancelPpt(taskId: number) {
  cancelingPptId.value = taskId
  try {
    const task = await pptApi.cancel(taskId)
    pptTasks.value = pptTasks.value.map(item => item.id === taskId ? task : item)
    ElMessage.success('任务已取消')
  } catch {
    ElMessage.error('取消失败，任务可能已经结束')
  } finally {
    cancelingPptId.value = null
  }
}

function revisePpt(task: PptTask) {
  revisionBaseTaskId.value = task.id
  input.value = ''
  nextTick(() => {
    document.querySelector<HTMLTextAreaElement>('.composer textarea')?.focus()
  })
}

function clearRevisionBase() {
  revisionBaseTaskId.value = null
}

async function openPptPreview(task: PptTask) {
  previewingPptId.value = task.id
  try {
    const response = await pptApi.preview(task.id)
    const blob = response.data instanceof Blob
      ? response.data
      : new Blob([response.data], { type: 'application/pdf' })
    closePptPreview()
    pptPreviewUrl.value = URL.createObjectURL(blob)
    pptPreviewTitle.value = `${task.title} · v${task.versionNo || 1}`
    pptPreviewVisible.value = true
  } catch {
    ElMessage.error('在线预览暂不可用，请下载 PPTX 查看')
  } finally {
    previewingPptId.value = null
  }
}

function closePptPreview() {
  pptPreviewVisible.value = false
  if (pptPreviewUrl.value) URL.revokeObjectURL(pptPreviewUrl.value)
  pptPreviewUrl.value = ''
  pptPreviewTitle.value = ''
}

async function downloadPptTask(task: PptTask) {
  downloadingPptId.value = task.id
  try {
    const response = await pptApi.download(task.id)
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
    const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
    if (head[0] !== 0x50 || head[1] !== 0x4b) {
      throw new Error('invalid pptx')
    }
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = task.fileName || `${task.title}.pptx`
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('PPT 下载失败，请稍后重试')
  } finally {
    downloadingPptId.value = null
  }
}

function cleanup() {
  streaming.value = false
  currentSse?.close()
  currentSse = null
}

function handleComposerEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  send()
}

async function feedback(msg: ChatMsg, rating: number) {
  if (!msg.id) return
  await chatApi.submitFeedback(msg.id, rating)
  ElMessage.success(rating > 0 ? '感谢反馈' : '已记录')
}

function exportMd() {
  if (!currentConvId.value) {
    ElMessage.warning('请先开始对话')
    return
  }
  window.open(digitalEmployeeApi.exportMarkdownUrl(employeeId, currentConvId.value), '_blank')
}

async function downloadDeliverable(format: 'docx' | 'pptx' | 'auto', messageId?: number) {
  if (exporting.value) return
  if (!currentConvId.value) {
    ElMessage.warning('请先开始对话')
    return
  }
  const msgId = messageId ?? lastAssistantMsg.value?.id
  if (messageId == null && !lastAssistantMsg.value?.content?.trim()) {
    ElMessage.warning('没有可导出的助手内容')
    return
  }
  const url = digitalEmployeeApi.exportDeliverableUrl(
    employeeId,
    currentConvId.value,
    format,
    msgId,
  )
  const token = localStorage.getItem('token') || ''
  try {
    exporting.value = true
    const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } })
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      let message = ''
      try {
        const err = text ? JSON.parse(text) : {}
        message = err.message || err.msg || err.error || ''
      } catch {
        message = text
      }
      ElMessage.error(message || `导出失败（HTTP ${res.status}）`)
      return
    }
    const ct = (res.headers.get('Content-Type') || '').toLowerCase()
    if (ct.includes('application/json')) {
      const err = await res.json().catch(() => ({}))
      ElMessage.error(err.message || '导出失败')
      return
    }
    const blob = await res.blob()
    if (format === 'pptx' || format === 'auto') {
      const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
      const isZip = head[0] === 0x50 && head[1] === 0x4b
      if (!isZip) {
        ElMessage.error('下载的不是有效 PPT 文件，请重新登录后重试或联系管理员')
        return
      }
    }
    const name = resolveDownloadName(
      res.headers.get('Content-Disposition') || '',
      `export.${format === 'pptx' ? 'pptx' : 'docx'}`,
    )
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = name
    a.click()
    URL.revokeObjectURL(a.href)
    const providerName = decodeHeaderValue(res.headers.get('X-PPT-Provider-Name') || '')
    const usedFallback = res.headers.get('X-PPT-Fallback') === 'true'
    ElMessage.success(
      providerName
        ? (usedFallback
          ? `Qwen 调用失败，已由${providerName}回退生成`
          : `已由${providerName}生成并下载`)
        : '已开始下载',
    )
  } catch {
    ElMessage.error('导出失败')
  } finally {
    exporting.value = false
  }
}

function resolveDownloadName(disposition: string, fallback: string) {
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star?.[1]) return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''))
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  const raw = plain?.[1]?.trim()
  if (!raw) return fallback
  return decodeRfc2047Filename(raw) || raw || fallback
}

function decodeHeaderValue(value: string) {
  if (!value) return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function decodeRfc2047Filename(raw: string) {
  const m = /^=\?UTF-8\?Q\?(.+)\?=$/i.exec(raw.replace(/^_+|_+$/g, '').replace(/_/g, '?'))
  if (!m?.[1]) return ''
  try {
    const percent = m[1].replace(/=([0-9A-F]{2})/gi, '%$1').replace(/_/g, ' ')
    return decodeURIComponent(percent)
  } catch {
    return ''
  }
}

async function openDraftWorkbench(messageId?: number) {
  if (!currentConvId.value) {
    ElMessage.warning('请先开始对话')
    return
  }
  const msgId = messageId ?? lastAssistantMsg.value?.id
  if (!msgId && !lastAssistantMsg.value?.content?.trim()) {
    ElMessage.warning('没有可生成交付物的助手内容')
    return
  }
  draftVisible.value = true
  draftLoading.value = true
  expertEdit.value = false
  reviewAcknowledged.value = false
  try {
    const [draft, providerStatus] = await Promise.all([
      digitalEmployeeApi.buildDeliverableDraft(employeeId, currentConvId.value, msgId),
      digitalEmployeeApi.pptProviderStatus(employeeId),
    ])
    deliverableDraft.value = draft
    pptProviderStatus.value = providerStatus
  } catch {
    ElMessage.error('生成交付物草稿失败')
  } finally {
    draftLoading.value = false
  }
}

function usePptPrompt(prompt: string) {
  input.value = prompt
}

function ensurePresentationProfile() {
  if (!deliverableDraft.value) return null
  deliverableDraft.value.presentation ||= {
    generationMode: 'auto',
    visualStyle: 'business',
    editable: true,
    includeSpeakerNotes: true,
    preferVisuals: true,
  }
  return deliverableDraft.value.presentation
}

function setPptMode(mode: string) {
  const profile = ensurePresentationProfile()
  if (profile) profile.generationMode = mode
}

function setPptStyle(style: string) {
  const profile = ensurePresentationProfile()
  if (profile) profile.visualStyle = style
}

function addSlide() {
  if (!deliverableDraft.value) return
  deliverableDraft.value.slides ||= []
  deliverableDraft.value.slides.push({
    title: `第 ${deliverableDraft.value.slides.length + 1} 页`,
    bullets: [''],
    speakerNotes: '',
    layout: 'content',
  })
}

function removeSlide(index: number) {
  deliverableDraft.value?.slides?.splice(index, 1)
}

function moveSlide(index: number, offset: -1 | 1) {
  moveItem(deliverableDraft.value?.slides, index, offset)
}

function duplicateSlide(index: number) {
  const slides = deliverableDraft.value?.slides
  const source = slides?.[index]
  if (!slides || !source) return
  slides.splice(index + 1, 0, {
    ...source,
    title: `${source.title || `第 ${index + 1} 页`}（副本）`,
    bullets: [...(source.bullets || [])],
  })
}

function addBullet(slide: NonNullable<DeliverableDraft['slides']>[number], slideIndex: number) {
  slide.bullets ||= []
  if (slide.bullets.length < 6) {
    slide.bullets.push('')
    return
  }
  const slides = deliverableDraft.value?.slides
  if (!slides) return
  slides.splice(slideIndex + 1, 0, {
    title: `${slide.title || `第 ${slideIndex + 1} 页`}（续1）`,
    bullets: [''],
    speakerNotes: slide.speakerNotes || '',
    layout: 'content',
  })
  ElMessage.info('为保证版式清晰，已自动创建续页')
}

function removeBullet(slide: NonNullable<DeliverableDraft['slides']>[number], index: number) {
  slide.bullets?.splice(index, 1)
}

function addSection() {
  if (!deliverableDraft.value) return
  deliverableDraft.value.sections ||= []
  deliverableDraft.value.sections.push({
    title: '新增章节',
    clauses: [''],
  })
}

function removeSection(index: number) {
  deliverableDraft.value?.sections?.splice(index, 1)
}

function moveSection(index: number, offset: -1 | 1) {
  moveItem(deliverableDraft.value?.sections, index, offset)
}

function duplicateSection(index: number) {
  const sections = deliverableDraft.value?.sections
  const source = sections?.[index]
  if (!sections || !source) return
  sections.splice(index + 1, 0, {
    ...source,
    title: `${source.title || `章节 ${index + 1}`}（副本）`,
    clauses: [...(source.clauses || [])],
  })
}

function moveItem<T>(items: T[] | undefined, index: number, offset: -1 | 1) {
  if (!items) return
  const target = index + offset
  if (target < 0 || target >= items.length) return
  const [item] = items.splice(index, 1)
  if (item !== undefined) items.splice(target, 0, item)
}

function addClause(section: NonNullable<DeliverableDraft['sections']>[number]) {
  section.clauses ||= []
  section.clauses.push('')
}

function removeClause(section: NonNullable<DeliverableDraft['sections']>[number], index: number) {
  section.clauses?.splice(index, 1)
}

async function exportEditedDraft() {
  if (!currentConvId.value || !deliverableDraft.value) return
  if ((blockingChecks.value.length || deliverableDraft.value.warnings?.length) && !reviewAcknowledged.value) {
    ElMessage.warning('请先确认已人工复核质量风险，再导出交付物')
    return
  }
  draftExporting.value = true
  try {
    const response: any = await digitalEmployeeApi.exportDraft(
      employeeId,
      currentConvId.value,
      draftFormat.value,
      deliverableDraft.value,
    )
    const blob: Blob = response?.data instanceof Blob ? response.data : response
    const disp = response?.headers?.['content-disposition'] || response?.headers?.['Content-Disposition'] || ''
    const fallback = `${deliverableDraft.value.title || 'export'}.${draftFormat.value}`
    const name = resolveDownloadName(disp, fallback)
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = name
    a.click()
    URL.revokeObjectURL(a.href)
    const providerName = decodeHeaderValue(
      response?.headers?.['x-ppt-provider-name'] || response?.headers?.['X-PPT-Provider-Name'] || '',
    )
    const usedFallback = String(
      response?.headers?.['x-ppt-fallback'] || response?.headers?.['X-PPT-Fallback'] || '',
    ) === 'true'
    ElMessage.success(
      providerName
        ? (usedFallback
          ? `Qwen 调用失败，已由${providerName}回退生成`
          : `已由${providerName}生成 PPTX`)
        : '已按编辑后的草稿生成文件',
    )
  } catch {
    ElMessage.error('导出草稿失败')
  } finally {
    draftExporting.value = false
  }
}

function back() {
  router.push({ name: 'DigitalEmployeeList' })
}

function refSources(msg: ChatMsg) {
  if (!msg.sources?.length) return []
  return msg.sources.filter((s: any) => s.name && s.type !== 'db_result' && s.type !== 'clarify')
}

/** 会话列表时间显示：今天 HH:mm / 昨天 / MM-DD / YYYY-MM-DD */
function shortTime(t?: string) {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return t.slice(0, 10)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const yesterday = new Date(now); yesterday.setDate(now.getDate() - 1)
  const isYesterday = d.toDateString() === yesterday.toDateString()
  const pad = (n: number) => String(n).padStart(2, '0')
  if (sameDay) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (isYesterday) return '昨天'
  if (d.getFullYear() === now.getFullYear()) return `${pad(d.getMonth()+1)}-${pad(d.getDate())}`
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`
}

// ── Markdown 渲染（与 ChatView 一致，AI 消息才使用） ──
const renderMd = (content: string, sourceCount = 0) => {
  if (!content) return ''
  const unwrapped = content.replace(/`(\s*(?:\[\d{1,3}\]\s*){1,8})`/g, '$1')
  return injectCitations(marked.parse(unwrapped) as string, sourceCount)
}
const injectCitations = (html: string, sourceCount = 0): string => {
  const parts = html.split(/(<pre[\s\S]*?<\/pre>|<code[\s\S]*?<\/code>)/gi)
  return parts.map((seg, idx) => {
    if (idx % 2 === 1) return seg
    return seg.replace(/\[(\d{1,3})\]/g, (_m, n) => {
      const num = Number(n)
      if (num >= 1 && num <= sourceCount) {
        return `<sup class="cite-badge" data-cite="${num}" title="悬停看来源 · 点击查看原文">${num}</sup>`
      }
      return _m
    })
  }).join('')
}

onMounted(() => {
  // Request errors are surfaced once by the shared interceptor. Expected
  // stale-conversation errors are recovered inside loadMeta without stacking
  // duplicate "加载失败" notifications.
  void loadMeta().catch(() => {})
  pptPollTimer = window.setInterval(() => {
    if (isPptScenario.value && pptTasks.value.some(task => isActivePpt(task.status))) {
      loadPptTasks()
    }
  }, 4000)
})

onBeforeUnmount(() => {
  cleanup()
  closePptPreview()
  if (pptPollTimer) window.clearInterval(pptPollTimer)
})
</script>

<template>
  <div class="chat-page">
    <header class="chat-header">
      <el-button class="back-button" :icon="ArrowLeft" text @click="back">返回</el-button>
      <div class="title-block">
        <h2>{{ detail?.name || '数字员工' }}</h2>
        <p>{{ detail?.summary }}</p>
      </div>
      <el-button
        v-if="!isPptScenario"
        class="primary-deliverable"
        type="primary"
        size="small"
        :icon="Download"
        :disabled="!canExportDeliverable"
        @click="openDraftWorkbench()"
      >
        交付物工作台
      </el-button>
      <el-dropdown v-if="!isPptScenario" trigger="click" @command="(c: string) => c === 'md' ? exportMd() : downloadDeliverable(c as 'docx' | 'pptx' | 'auto')">
        <el-button class="header-action" size="small" :icon="Download" :disabled="exporting || (!canExportDeliverable && !currentConvId)">
          更多导出
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="auto" :disabled="!canExportDeliverable">
              {{ isPptScenario ? '下载 PPT (.pptx)' : '下载 Word (.docx)' }}（推荐）
            </el-dropdown-item>
            <el-dropdown-item command="docx" :disabled="!canExportDeliverable">Word 合同/方案 (.docx)</el-dropdown-item>
            <el-dropdown-item command="pptx" :disabled="!canExportDeliverable">演示文稿 (.pptx)</el-dropdown-item>
            <el-dropdown-item command="md" :disabled="!currentConvId" divided>Markdown 会话记录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button class="header-action" size="small" @click="newChat">新对话</el-button>
    </header>

    <div class="chat-body">
      <aside class="sessions">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="sess-item"
          :class="{ active: s.id === currentConvId }"
          @click="openSession(s.id)"
        >
          {{ s.title || '对话' }}
        </div>
        <p v-if="!sessions.length" class="sess-empty">暂无历史，发送消息将自动新建对话</p>
      </aside>

      <main class="chat-main">
      <div ref="listRef" class="messages" @scroll="onMessagesScroll">
        <button
          v-show="showJumpToBottom"
          class="jump-to-bottom"
          type="button"
          aria-label="跳到底部"
          @click="jumpToBottom"
        >
          <el-icon><ArrowDown /></el-icon>
          跳到底部
        </button>
        <section v-if="isPptScenario && !messages.length && !pptTasks.length" class="ppt-welcome">
          <span class="ppt-welcome-label">PRESENTATION SERVICE</span>
          <h3>描述需求，直接获得 PPT</h3>
          <p>输入用途和重点即可；页数、结构与版式沿用该数字员工的企业配置，无需切换模式或再次确认。</p>
          <div class="prompt-examples">
            <button v-for="example in pptPromptExamples" :key="example" type="button" @click="usePptPrompt(example)">
              {{ example }}
            </button>
          </div>
        </section>
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">
            <!-- 🆕 实时检索：AI 正在读的文档片段（流式期间显示） -->
            <div v-if="m.role === 'assistant' && m.liveSources && m.liveSources.length" class="live-reading">
              <div class="live-reading-head">
                <span class="live-dot"></span>
                <span class="live-text">正在阅读 {{ m.liveSources.length }} 个文档片段</span>
              </div>
              <div v-for="(src, si) in m.liveSources" :key="si" class="live-source">
                <span class="live-source-icon"><el-icon><Document /></el-icon></span>
                <div class="live-source-body">
                  <div class="live-source-name">{{ src.docName }}</div>
                  <div v-if="src.excerpt" class="live-source-excerpt">{{ src.excerpt }}</div>
                </div>
                <span class="live-source-score" v-if="src.score > 0">{{ (src.score * 100).toFixed(0) }}%</span>
              </div>
            </div>
            <div v-if="m.role === 'assistant'" class="md-body content bubble-content" v-html="renderMd(m.content, refSources(m).length)"></div>
            <div v-else class="content">{{ plainChatContent(m.content) }}</div>
            <div v-if="m.role === 'assistant' && m.clarify" class="clarify-card">
              <div class="clarify-label">请选择更符合你意图的一项：</div>
              <div class="clarify-options">
                <button
                  v-for="option in m.clarify.options"
                  :key="option"
                  type="button"
                  class="clarify-option"
                  :class="{ chosen: m.clarify.chosen === option }"
                  :disabled="m.clarify.answered || streaming"
                  @click="answerClarify(m, option)"
                >
                  {{ option }}
                </button>
                <button
                  v-if="m.clarify.allowSkip && !m.clarify.answered"
                  type="button"
                  class="clarify-skip"
                  :disabled="streaming"
                  @click="skipClarify(m)"
                >
                  跳过，直接回答
                </button>
              </div>
              <div v-if="m.clarify.answered" class="clarify-result">
                {{ m.clarify.chosen === '__skip__' ? '已跳过澄清' : `已选择：${m.clarify.chosen || ''}` }}
              </div>
            </div>
            <ul v-if="m.role === 'assistant' && refSources(m).length" class="sources">
              <li v-for="(s, j) in refSources(m)" :key="j">{{ s.name }}</li>
            </ul>
            <!-- 隐藏消息底部操作栏（交付物/有用/无用按钮），与智能助手保持一致 -->
            <!--
            <div v-if="!isPptScenario && m.role === 'assistant' && (m.id || m.content?.trim())" class="fb">
              ...
            </div>
            -->
          </div>
        </div>

        <article v-for="task in [...pptTasks].reverse()" :key="`ppt-${task.id}`" class="ppt-turn">
          <div class="ppt-request">
            <p>{{ task.prompt }}</p>
            <div v-if="pptAttachmentNames(task).length" class="ppt-request-files">
              <span v-for="name in pptAttachmentNames(task)" :key="name">
                <el-icon><Paperclip /></el-icon>{{ name }}
              </span>
            </div>
          </div>
          <div class="ppt-task-card" :class="task.status">
            <div class="ppt-task-heading">
              <div class="ppt-file-mark">P</div>
              <div>
                <strong>{{ task.title }}</strong>
                <span>
                  {{ task.pageCount }} 页 · v{{ task.versionNo || 1 }}
                  <template v-if="task.operationType === 'revise'"> · 修改版</template>
                  · {{ pptStatusText(task.status) }}
                </span>
              </div>
              <span class="ppt-status" :class="task.status">{{ pptStatusText(task.status) }}</span>
            </div>
            <template v-if="isActivePpt(task.status)">
              <div class="ppt-progress-meta">
                <span>{{ task.stage || '正在处理' }}</span>
                <span>{{ task.progress }}%</span>
              </div>
              <el-progress :percentage="task.progress" :stroke-width="6" :show-text="false" />
              <div class="ppt-active-actions">
                <p class="ppt-task-note">任务在后台运行，您可以继续输入下一份需求或离开页面。</p>
                <el-button
                  v-if="task.cancellable"
                  link
                  size="small"
                  :loading="cancelingPptId === task.id"
                  @click="cancelPpt(task.id)"
                >
                  取消生成
                </el-button>
              </div>
            </template>
            <div v-else-if="task.status === 'failed'" class="ppt-failed">
              <p>{{ task.errorMessage || '生成过程中发生异常，请重新生成。' }}</p>
              <el-button size="small" :loading="retryingPptId === task.id" @click="retryPpt(task.id)">
                重新生成
              </el-button>
            </div>
            <div v-else-if="task.status === 'canceled'" class="ppt-canceled">
              本次生成已取消。原需求和附件仍保留，您可以重新描述后再次生成。
            </div>
            <div v-else class="ppt-complete">
              <div>
                <span>{{ task.providerName || '演示文稿服务' }}</span>
                <span v-if="task.fallbackUsed" class="emergency-label">基础应急版，非阿里商用版</span>
              </div>
              <div class="ppt-card-actions">
                <el-button
                  v-if="task.fallbackUsed"
                  size="small"
                  :loading="retryingPptId === task.id"
                  @click="retryPpt(task.id)"
                >
                  重试阿里商用版
                </el-button>
                <el-button
                  v-if="task.previewable"
                  size="small"
                  :loading="previewingPptId === task.id"
                  @click="openPptPreview(task)"
                >
                  在线预览
                </el-button>
                <el-button size="small" @click="revisePpt(task)">修改此版</el-button>
                <el-button
                  type="primary"
                  size="small"
                  :icon="Download"
                  :loading="downloadingPptId === task.id"
                  @click="downloadPptTask(task)"
                >
                  下载 PPTX
                </el-button>
              </div>
            </div>
            <ul v-if="pptWarnings(task).length" class="ppt-warnings">
              <li v-for="warning in pptWarnings(task)" :key="warning">{{ warning }}</li>
            </ul>
          </div>
        </article>
        <div v-if="streaming && !isPptScenario" class="hint">正在生成内容…</div>
      </div>
      <footer class="composer">
        <div class="composer-tools">
          <el-switch v-if="!isPptScenario" v-model="webSearch" active-text="联网" inactive-text="联网关" size="small" />
          <el-button :icon="Paperclip" size="small" @click="pickFile">附件</el-button>
          <span v-if="uploadingAttachments" class="upload-status">
            正在上传 {{ uploadingAttachmentCount }} 个附件…
          </span>
          <span v-for="(f, idx) in pendingFiles" :key="f.objectName" class="chip">
            {{ f.name }}
            <el-button link type="danger" @click="removePending(idx)">×</el-button>
          </span>
        </div>
        <div v-if="revisionBaseTaskId" class="revision-context">
          <span>正在修改 v{{ pptTasks.find(task => task.id === revisionBaseTaskId)?.versionNo || 1 }}，直接描述要改什么</span>
          <el-button link size="small" @click="clearRevisionBase">取消修改</el-button>
        </div>
        <input ref="fileInput" type="file" multiple accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md" style="display: none" @change="onFilesSelected" />
        <div class="composer-row">
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            class="composer-input"
            :placeholder="isPptScenario
              ? '例如：根据上传的经营数据，生成一份面向董事会的 12 页经营分析 PPT…'
              : '输入问题；合同/PPT/招投标等场景请尽量说明背景与要求…'"
            @keydown.enter.exact="handleComposerEnter"
          />
          <el-button class="send-button" type="primary" :loading="composerBusy" @click="send()">
            {{ isPptScenario ? (revisionBaseTaskId ? '提交修改' : '生成 PPT') : '发送' }}
          </el-button>
        </div>
      </footer>
    </main>
    </div>

    <el-drawer
      v-model="pptPreviewVisible"
      class="ppt-preview-drawer"
      size="78%"
      :title="pptPreviewTitle"
      @closed="closePptPreview"
    >
      <iframe v-if="pptPreviewUrl" class="ppt-preview-frame" :src="pptPreviewUrl" title="PPT 在线预览"></iframe>
    </el-drawer>

    <el-drawer
      v-model="draftVisible"
      class="deliverable-drawer"
      size="76%"
      :title="deliverableDraft?.draftType === 'ppt' ? 'AI PPT 生成工作台' : '合同/文档交付物工作台'"
    >
      <div v-loading="draftLoading" class="draft-panel">
        <template v-if="deliverableDraft">
          <div class="draft-toolbar">
            <el-input v-model="deliverableDraft.title" class="draft-title-input" size="large" placeholder="输入交付物标题">
              <template #prepend>文件标题</template>
            </el-input>
            <div
              v-if="deliverableDraft.draftType === 'ppt' && pptProviderStatus"
              class="ppt-provider-status"
              :class="{ warning: !pptProviderStatus.enabled || !pptProviderStatus.configured }"
            >
              <span class="provider-dot"></span>
              <div>
                <strong>{{ pptProviderStatus.providerName }}</strong>
                <small v-if="pptProviderStatus.enabled && pptProviderStatus.configured">
                  已就绪 · {{ pptProviderStatus.mode === 'general' ? '可编辑模板模式' : '创意图文模式' }}
                </small>
                <small v-else-if="!pptProviderStatus.enabled">API 未开启，将使用内置渲染器</small>
                <small v-else>API Key 未配置，导出时将自动回退</small>
              </div>
            </div>
            <el-button
              :icon="Download"
              type="primary"
              :loading="draftExporting"
              :disabled="(blockingChecks.length > 0 || !!deliverableDraft.warnings?.length) && !reviewAcknowledged"
              @click="exportEditedDraft"
            >
              导出 {{ draftFormat === 'pptx' ? 'PPTX' : 'DOCX' }}
            </el-button>
          </div>

          <div class="quality-summary" :class="deliverableDraft.readiness === 'READY' ? 'ready' : 'review'">
            <div>
              <strong>交付质量 {{ deliverableDraft.qualityScore ?? 0 }} 分</strong>
              <span>{{ deliverableDraft.readiness === 'READY' ? '达到交付建议线' : '需要人工复核后交付' }}</span>
            </div>
            <el-progress
              :percentage="deliverableDraft.qualityScore ?? 0"
              :status="deliverableDraft.readiness === 'READY' ? 'success' : 'warning'"
            />
          </div>

          <div v-if="deliverableDraft.qualityChecks?.length" class="quality-checks">
            <div v-for="check in deliverableDraft.qualityChecks" :key="check.label" class="quality-check">
              <el-tag :type="check.status === 'PASS' ? 'success' : check.status === 'BLOCK' ? 'danger' : 'warning'" size="small">
                {{ check.status === 'PASS' ? '通过' : check.status === 'BLOCK' ? '阻断' : '提醒' }}
              </el-tag>
              <strong>{{ check.label }}</strong>
              <span>{{ check.message }}</span>
            </div>
          </div>

          <el-alert
            v-if="deliverableDraft.warnings?.length"
            type="warning"
            show-icon
            :closable="false"
            class="quality-alert"
          >
            <template #title>商用质量检查</template>
            <ul class="quality-list">
              <li v-for="w in deliverableDraft.warnings" :key="w">{{ w }}</li>
            </ul>
          </el-alert>

          <div v-if="deliverableDraft.draftType === 'ppt'" class="draft-list">
            <section class="ppt-config-panel">
              <div class="config-heading">
                <div>
                  <span>生成策略</span>
                  <h3>选择交付方式</h3>
                </div>
                <el-button text type="primary" @click="expertEdit = !expertEdit">
                  {{ expertEdit ? '返回快速模式' : '高级编辑' }}
                </el-button>
              </div>
              <div class="mode-grid">
                <button
                  v-for="mode in pptModes"
                  :key="mode.value"
                  type="button"
                  class="choice-card"
                  :class="{ selected: deliverableDraft.presentation?.generationMode === mode.value }"
                  @click="setPptMode(mode.value)"
                >
                  <strong>{{ mode.name }}</strong>
                  <span>{{ mode.desc }}</span>
                </button>
              </div>
              <div class="config-heading compact">
                <div>
                  <span>视觉系统</span>
                  <h3>选择演示风格</h3>
                </div>
              </div>
              <div class="style-grid">
                <button
                  v-for="style in pptStyles"
                  :key="style.value"
                  type="button"
                  class="style-card"
                  :class="[`tone-${style.tone}`, { selected: deliverableDraft.presentation?.visualStyle === style.value }]"
                  @click="setPptStyle(style.value)"
                >
                  <i></i>
                  <strong>{{ style.name }}</strong>
                  <span>{{ style.desc }}</span>
                </button>
              </div>
              <div class="brief-grid">
                <label>
                  <span>汇报对象</span>
                  <el-input v-model="pptAudience" placeholder="自动识别，也可填写：董事会 / 客户 / 政府" />
                </label>
                <label>
                  <span>汇报目标</span>
                  <el-input v-model="pptPurpose" placeholder="自动识别，也可填写：争取预算 / 项目复盘" />
                </label>
              </div>
            </section>

            <div class="draft-head">
              <strong>{{ expertEdit ? '逐页高级编辑' : 'AI 生成大纲' }}</strong>
              <span>{{ deliverableDraft.slides?.length || 0 }} 页</span>
              <el-button v-if="expertEdit" class="compact-add-btn" size="small" plain :icon="Plus" @click="addSlide">新增页面</el-button>
            </div>
            <div v-if="!expertEdit" class="outline-list">
              <div v-for="(slide, si) in deliverableDraft.slides" :key="si" class="outline-item">
                <span>{{ String(si + 1).padStart(2, '0') }}</span>
                <div>
                  <strong>{{ slide.title || `第 ${si + 1} 页` }}</strong>
                  <p>{{ (slide.bullets || []).slice(0, 2).join(' · ') || '系统将根据上下文补充视觉内容' }}</p>
                </div>
                <el-tag size="small" effect="plain">{{ slide.layout || '智能版式' }}</el-tag>
              </div>
            </div>
            <el-card v-for="(slide, si) in expertEdit ? deliverableDraft.slides : []" :key="si" class="draft-card" shadow="never">
              <template #header>
                <div class="draft-card-head">
                  <div class="card-index"><span>{{ si + 1 }}</span> 第 {{ si + 1 }} 页</div>
                  <div class="card-actions">
                    <el-button-group size="small">
                      <el-button :icon="ArrowUp" :disabled="si === 0" title="上移" @click="moveSlide(si, -1)" />
                      <el-button :icon="ArrowDown" :disabled="si === (deliverableDraft.slides?.length || 0) - 1" title="下移" @click="moveSlide(si, 1)" />
                      <el-button :icon="CopyDocument" title="复制页面" @click="duplicateSlide(si)" />
                    </el-button-group>
                    <el-button size="small" plain type="danger" :icon="Delete" @click="removeSlide(si)">删除</el-button>
                  </div>
                </div>
              </template>
              <el-form label-position="top">
                <el-form-item label="页面标题">
                  <el-input v-model="slide.title" />
                </el-form-item>
                <el-form-item label="页面要点">
                  <div class="line-editor">
                    <div v-for="(_b, bi) in slide.bullets" :key="bi" class="line-row">
                      <span class="line-number">{{ bi + 1 }}</span>
                      <el-input
                        v-model="slide.bullets[bi]"
                        type="textarea"
                        :autosize="{ minRows: 2, maxRows: 6 }"
                        resize="none"
                        placeholder="输入本页要点，建议一句话表达一个观点"
                      />
                      <el-button class="line-delete" circle plain type="danger" :icon="Delete" title="删除要点" @click="removeBullet(slide, bi)" />
                    </div>
                    <el-button class="compact-add-btn" size="small" plain :icon="Plus" @click="addBullet(slide, si)">
                      {{ (slide.bullets?.length || 0) >= 6 ? '添加到续页' : '添加要点' }}
                    </el-button>
                  </div>
                </el-form-item>
                <el-form-item label="演讲备注">
                  <el-input
                    v-model="slide.speakerNotes"
                    class="notes-input"
                    type="textarea"
                    :autosize="{ minRows: 5, maxRows: 12 }"
                    resize="vertical"
                    placeholder="补充讲述逻辑、数据口径和需要强调的结论"
                  />
                </el-form-item>
              </el-form>
            </el-card>
          </div>

          <div v-else class="draft-list">
            <div class="draft-head">
              <strong>合同/文档章节</strong>
              <span>{{ deliverableDraft.sections?.length || 0 }} 个章节</span>
              <el-button class="compact-add-btn" size="small" plain :icon="Plus" @click="addSection">新增章节</el-button>
            </div>
            <el-card v-for="(section, si) in deliverableDraft.sections" :key="si" class="draft-card" shadow="never">
              <template #header>
                <div class="draft-card-head">
                  <div class="card-index"><span>{{ si + 1 }}</span> 章节 {{ si + 1 }}</div>
                  <div class="card-actions">
                    <el-button-group size="small">
                      <el-button :icon="ArrowUp" :disabled="si === 0" title="上移" @click="moveSection(si, -1)" />
                      <el-button :icon="ArrowDown" :disabled="si === (deliverableDraft.sections?.length || 0) - 1" title="下移" @click="moveSection(si, 1)" />
                      <el-button :icon="CopyDocument" title="复制章节" @click="duplicateSection(si)" />
                    </el-button-group>
                    <el-button size="small" plain type="danger" :icon="Delete" @click="removeSection(si)">删除</el-button>
                  </div>
                </div>
              </template>
              <el-form label-position="top">
                <el-form-item label="章节标题">
                  <el-input v-model="section.title" />
                </el-form-item>
                <el-form-item label="条款内容">
                  <div class="line-editor">
                    <div v-for="(_c, ci) in section.clauses" :key="ci" class="line-row">
                      <span class="line-number">{{ ci + 1 }}</span>
                      <el-input v-model="section.clauses[ci]" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" placeholder="输入完整条款内容" />
                      <el-button class="line-delete" circle plain type="danger" :icon="Delete" title="删除条款" @click="removeClause(section, ci)" />
                    </div>
                    <el-button class="compact-add-btn" size="small" plain :icon="Plus" @click="addClause(section)">添加条款</el-button>
                  </div>
                </el-form-item>
              </el-form>
            </el-card>

            <div class="draft-head risk-head">
              <strong>风险与修改建议</strong>
              <el-button class="compact-add-btn" size="small" plain :icon="Plus" @click="deliverableDraft.risks?.push({ level: '中', position: '', description: '', suggestion: '' })">
                新增风险
              </el-button>
            </div>
            <el-table v-if="deliverableDraft.risks?.length" :data="deliverableDraft.risks" border class="risk-table">
              <el-table-column label="级别" width="100">
                <template #default="{ row }">
                  <el-select v-model="row.level">
                    <el-option label="高" value="高" />
                    <el-option label="中" value="中" />
                    <el-option label="低" value="低" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="条款位置" min-width="150">
                <template #default="{ row }"><el-input v-model="row.position" /></template>
              </el-table-column>
              <el-table-column label="风险描述" min-width="220">
                <template #default="{ row }"><el-input v-model="row.description" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" /></template>
              </el-table-column>
              <el-table-column label="修改建议" min-width="220">
                <template #default="{ row }"><el-input v-model="row.suggestion" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" /></template>
              </el-table-column>
              <el-table-column width="70">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="deliverableDraft.risks?.splice($index, 1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <el-checkbox
            v-if="blockingChecks.length || deliverableDraft.warnings?.length"
            v-model="reviewAcknowledged"
            class="review-confirm"
          >
            我已人工核对事实、数据、合同主体和风险项，确认继续导出
          </el-checkbox>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.chat-page {
  --de-accent: #0071E3;
  --de-accent-hover: #0A84FF;
  --de-accent-soft: #E8F1FF;
  --de-border: #E8E8ED;
  --de-text: #263142;
  --de-muted: #738092;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: transparent;
}
.chat-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 12px 24px;
  background: rgba(255,255,255,.72);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border-bottom: 1px solid var(--de-border);
  box-shadow: 0 1px 3px rgba(31, 41, 55, 0.025);
}
.back-button {
  flex-shrink: 0;
  color: #667386;
}
.back-button:hover {
  color: var(--de-text);
  background: #f1f2f0;
}
.title-block {
  min-width: 0;
  flex: 1;
}
.primary-deliverable {
  min-width: 136px;
  height: 36px;
  border-color: var(--de-accent);
  border-radius: 8px;
  background: var(--de-accent);
  box-shadow: 0 3px 8px rgba(82, 107, 159, 0.14);
  font-weight: 600;
}
.primary-deliverable:not(.is-disabled):hover {
  border-color: var(--de-accent-hover);
  background: var(--de-accent-hover);
}
.header-action {
  height: 36px;
  padding: 0 14px;
  color: #5f6d80;
  border-color: #dde1e4;
  border-radius: 8px;
  background: #fff;
}
.header-action:not(.is-disabled):hover {
  color: #405675;
  border-color: #c7cdd3;
  background: #fafbfa;
}
.chat-body {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}
.title-block h2 {
  margin: 0;
  font-size: 18px;
  color: var(--de-text);
  font-weight: 650;
  letter-spacing: -0.01em;
}
.title-block p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--de-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sessions {
  flex: 0 0 200px;
  width: 200px;
  border-right: 1px solid var(--de-border);
  background: rgba(255,255,255,.6);
  overflow-y: auto;
  padding: 12px 10px;
}
.sess-empty {
  margin: 12px 8px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.5;
}
.sess-item {
  padding: 10px 12px;
  border-radius: 10px;
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
  margin-bottom: 3px;
  transition: background 180ms, color 180ms;
}
.sess-item:hover { background: rgba(0,0,0,.04); color: var(--ink-1); }
.sess-item.active {
  background: rgba(0,113,227,.10);
  color: #0071E3;
  font-weight: 600;
}
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px;
  position: relative;
}

/* 跳到底部按钮 */
.jump-to-bottom {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  font-size: 13px;
  color: #303b4b;
  background: #ffffff;
  border: 1px solid #d8dde6;
  border-radius: 18px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: background 0.15s, box-shadow 0.15s, transform 0.15s;
  z-index: 10;
}
.jump-to-bottom:hover {
  background: #f3f4f6;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  transform: translateX(-50%) translateY(-1px);
}
.ppt-welcome {
  max-width: 920px;
  margin: 36px auto 28px;
  padding: 34px;
  border: 1px solid #e2e6e7;
  border-radius: 16px;
  background:
    radial-gradient(circle at 92% 8%, rgba(99, 121, 158, 0.08), transparent 32%),
    linear-gradient(145deg, #fefefd 0%, #f5f7f7 100%);
  box-shadow: 0 10px 30px rgba(36, 49, 66, 0.045);
}
.ppt-welcome-label {
  color: #60789f;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.12em;
}
.ppt-welcome h3 {
  margin: 10px 0 8px;
  color: var(--de-text);
  font-size: 28px;
}
.ppt-welcome > p {
  max-width: 720px;
  margin: 0;
  color: #667085;
  line-height: 1.8;
}
.prompt-examples {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 24px;
}
.prompt-examples button {
  min-height: 88px;
  padding: 14px 16px;
  color: #344054;
  text-align: left;
  line-height: 1.55;
  border: 1px solid #e1e5e7;
  border-radius: 10px;
  background: rgba(255, 255, 254, 0.9);
  cursor: pointer;
}
.prompt-examples button:hover {
  color: #405a86;
  border-color: #b9c4d2;
  background: #fff;
  transform: translateY(-1px);
}
.msg {
  margin-bottom: 28px;
  display: flex;
}
.msg.user {
  justify-content: flex-end;
  margin-top: 10px;
  margin-bottom: 14px;
}
.bubble {
  max-width: 70%;
  padding: 0;
  border-radius: 0;
  font-size: 15px;
  line-height: 1.85;
  color: #1f2937;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 🆕 实时检索：AI 正在读的文档片段卡片 */
.live-reading {
  margin: 0 0 10px;
  padding: 10px 12px;
  background: rgba(88, 86, 214, .04);
  border: 1px dashed rgba(88, 86, 214, .25);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  animation: live-pulse 2s ease-in-out infinite;
}
@keyframes live-pulse {
  0%, 100% { border-color: rgba(88, 86, 214, .25); }
  50%      { border-color: rgba(88, 86, 214, .5); }
}
.live-reading-head {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; color: #5856D6; font-weight: 600;
}
.live-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: #5856D6;
  animation: live-blink 1.2s ease-in-out infinite;
}
@keyframes live-blink {
  0%, 100% { opacity: .3; transform: scale(0.85); }
  50%      { opacity: 1;  transform: scale(1.15); }
}
.live-text { font-size: 12px; color: #5856D6; }
.live-source {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 6px 8px;
  background: rgba(255, 255, 255, .6);
  border: 1px solid rgba(0, 0, 0, .05);
  border-radius: 8px;
  font-size: 12px;
}
.live-source-icon {
  width: 22px; height: 22px;
  border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(88, 86, 214, .12);
  color: #5856D6;
  font-size: 11px;
  flex-shrink: 0;
  margin-top: 1px;
}
.live-source-body { flex: 1; min-width: 0; }
.live-source-name {
  font-size: 12.5px; font-weight: 600; color: #374151;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.live-source-excerpt {
  margin-top: 2px; font-size: 11.5px; color: #6b7280; line-height: 1.5;
  overflow: hidden; display: -webkit-box;
  -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.live-source-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px; font-weight: 600; color: #22c55e;
  background: rgba(34, 197, 94, .1);
  padding: 2px 6px; border-radius: 10px;
  flex-shrink: 0; align-self: center;
}
.msg.user .bubble {
  background: transparent;
  color: #1f2937;
  border: 0;
}
/* 用户消息气泡：浅灰 + 圆角 22px，与 ChatView 完全一致 */
.msg.user .bubble .content {
  background: #F2F2F7;
  color: #1f2937;
  border-radius: 22px;
  padding: 10px 16px;
  font-size: 14.5px;
  line-height: 1.6;
  display: inline-block;
  transition: background 0.15s;
}
.msg.user .bubble .content:hover {
  background: #e9eef5;
}
.msg.assistant {
  margin-bottom: 32px;
  font-size: 15px;
  line-height: 1.75;
  color: #1f2937;
}
.msg.assistant .bubble p { margin: 0 0 14px 0; }
.msg.assistant .bubble ul,
.msg.assistant .bubble ol { margin: 8px 0 16px 0; padding-left: 24px; }
.msg.assistant .bubble li { margin: 6px 0; line-height: 1.75; }
.msg.assistant .bubble h1,
.msg.assistant .bubble h2,
.msg.assistant .bubble h3 { margin: 24px 0 12px 0; font-weight: 600; }
.clarify-card {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid #dfe6f0;
  border-radius: 12px;
  background: #f8faff;
}
.clarify-label {
  margin-bottom: 10px;
  color: #667386;
  font-size: 13px;
  font-weight: 600;
}
.clarify-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.clarify-option,
.clarify-skip {
  padding: 7px 12px;
  color: #315278;
  font: inherit;
  font-size: 13px;
  line-height: 1.4;
  border: 1px solid #cbd9ea;
  border-radius: 999px;
  background: #fff;
  cursor: pointer;
  transition: border-color 150ms, background 150ms, color 150ms;
}
.clarify-option:hover:not(:disabled) {
  color: #0067c9;
  border-color: #7eb4e8;
  background: #eef6ff;
}
.clarify-option.chosen {
  color: #fff;
  border-color: var(--de-accent);
  background: var(--de-accent);
}
.clarify-option:disabled {
  cursor: default;
  opacity: .72;
}
.clarify-skip {
  color: #738092;
  border-color: transparent;
  background: transparent;
}
.clarify-skip:hover:not(:disabled) {
  color: #3f4b5c;
  background: #edf0f5;
}
.clarify-result {
  margin-top: 9px;
  color: #738092;
  font-size: 12px;
}
.sources {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: #6b7280;
}
.fb {
  margin-top: 8px;
  border-top: 1px solid #f0f0f0;
  padding-top: 4px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
}
.ppt-turn {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 900px;
  margin: 0 auto 24px;
}
.ppt-request {
  align-self: flex-end;
  max-width: 72%;
  padding: 11px 15px;
  color: #fff;
  border-radius: 11px 11px 3px 11px;
  background: #5b709e;
  box-shadow: 0 2px 6px rgba(61, 78, 113, 0.12);
}
.ppt-request p {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.65;
}
.ppt-request-files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}
.ppt-request-files span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 240px;
  padding: 4px 7px;
  overflow: hidden;
  color: #edf2ff;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.08);
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}
.ppt-task-card {
  width: min(680px, 86%);
  padding: 16px 18px;
  border: 1px solid #dfe3e5;
  border-radius: 11px 11px 11px 3px;
  background: #fff;
  box-shadow: 0 3px 10px rgba(31, 41, 55, 0.035);
}
.ppt-task-card.completed {
  border-color: #cfdad3;
}
.ppt-task-card.failed {
  border-color: #e8d5d5;
}
.ppt-task-card.canceled {
  border-color: #dfe2e5;
  background: #fafafa;
}
.ppt-task-heading {
  display: flex;
  align-items: center;
  gap: 11px;
}
.ppt-file-mark {
  display: inline-flex;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-radius: 7px;
  background: #b45b4f;
  font-size: 14px;
  font-weight: 800;
}
.ppt-task-heading > div:nth-child(2) {
  min-width: 0;
  flex: 1;
}
.ppt-task-heading strong,
.ppt-task-heading span {
  display: block;
}
.ppt-task-heading strong {
  overflow: hidden;
  color: #273244;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.ppt-task-heading div span {
  margin-top: 4px;
  color: #7a8493;
  font-size: 11px;
}
.ppt-status {
  flex-shrink: 0;
  padding: 4px 8px;
  color: #6a7483;
  border-radius: 5px;
  background: #f1f3f4;
  font-size: 11px;
}
.ppt-status.completed {
  color: #3f7455;
  background: #edf5f0;
}
.ppt-status.failed {
  color: #9b4b4b;
  background: #faf0f0;
}
.ppt-status.canceled {
  color: #707985;
  background: #eef0f2;
}
.ppt-progress-meta {
  display: flex;
  justify-content: space-between;
  margin: 15px 0 7px;
  color: #687486;
  font-size: 12px;
}
.ppt-task-card :deep(.el-progress-bar__outer) {
  background: #edf0f2;
}
.ppt-task-card :deep(.el-progress-bar__inner) {
  background: #61769d;
}
.ppt-task-note {
  margin: 9px 0 0;
  color: #8a93a1;
  font-size: 11px;
}
.ppt-active-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.ppt-failed,
.ppt-complete {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  padding-top: 13px;
  border-top: 1px solid #edf0f1;
}
.ppt-failed p {
  margin: 0;
  color: #8d4b4b;
  font-size: 12px;
}
.ppt-complete > div {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 13px;
  color: #7b8593;
  font-size: 11px;
}
.emergency-label {
  color: #9a6425;
  font-weight: 600;
}
.ppt-card-actions {
  justify-content: flex-end;
}
.ppt-canceled {
  margin-top: 14px;
  padding-top: 13px;
  color: #747d89;
  border-top: 1px solid #edf0f1;
  font-size: 12px;
}
.ppt-warnings {
  margin: 12px 0 0;
  padding: 10px 12px 10px 28px;
  color: #815f2e;
  border-radius: 7px;
  background: #faf6ed;
  font-size: 11px;
  line-height: 1.6;
}
.ppt-complete :deep(.el-button--primary) {
  border-color: var(--de-accent);
  background: var(--de-accent);
}
.composer {
  padding: 10px 20px 14px;
  background: rgba(255,255,255,.72);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border-top: 1px solid var(--de-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.composer-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  background: #F2F2F7;
  border-radius: 22px;
  padding: 6px 6px 6px 16px;
  box-shadow: 0 0 0 1px #E8E8ED inset;
  transition: box-shadow 0.15s, transform 0.2s cubic-bezier(.22,1,.36,1);
}
.composer-row:focus-within {
  box-shadow:
    0 0 0 1px rgba(0, 113, 227, 0.5) inset,
    0 0 0 4px rgba(0, 113, 227, 0.12);
  transform: translateY(-1px);
}
.composer-row :deep(.composer-input) {
  flex: 1;
  min-width: 0;
}
.composer-row :deep(.el-textarea__inner) {
  color: #344054;
  border: 0 !important;
  border-radius: 8px !important;
  background: transparent !important;
  box-shadow: none !important;
  padding: 6px 0;
  line-height: 1.6;
  font-size: 14px;
  resize: none;
}
.composer-row :deep(.el-textarea__inner:focus) {
  box-shadow: none !important;
}
.composer-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 0 4px;
}
.upload-status {
  color: #526b9f;
  font-size: 12px;
}
.revision-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 11px;
  color: #405675;
  border: 1px solid #d8e0eb;
  border-radius: 8px;
  background: #f2f5f8;
  font-size: 12px;
}
.ppt-preview-frame {
  width: 100%;
  height: calc(100vh - 110px);
  border: 0;
  border-radius: 8px;
  background: #eef0f2;
}
.composer-tools :deep(.el-switch.is-checked .el-switch__core) {
  border-color: #6d82ab;
  background: #6d82ab;
}
.send-button {
  flex-shrink: 0;
  min-height: 38px;
  padding: 0 18px;
  border-color: var(--de-accent);
  border-radius: 9px;
  background: var(--de-accent);
  box-shadow: 0 3px 8px rgba(82, 107, 159, 0.12);
  font-weight: 600;
  letter-spacing: 0.06em;
}
.send-button:not(.is-disabled):hover {
  border-color: var(--de-accent-hover);
  background: var(--de-accent-hover);
}
.chip {
  font-size: 12px;
  background: #f3f4f6;
  padding: 2px 8px;
  border-radius: 6px;
}
.hint {
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.5;
  padding: 8px 12px;
  border: 1px solid #e8e9e7;
  background: #f8f8f6;
  border-radius: 8px;
}
.export-tip {
  font-size: 13px;
  color: #50647f;
  text-align: center;
  margin: 16px 0 8px;
  padding: 11px 14px;
  border: 1px solid #dfe5eb;
  border-radius: 9px;
  background: #f0f4f7;
}
.draft-panel {
  min-height: 360px;
  padding-bottom: 36px;
}
.draft-toolbar {
  position: sticky;
  top: -20px;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 12px;
  margin: -12px 0 16px;
  padding: 12px 0;
  background: rgba(255, 255, 255, 0.96);
  border-bottom: 1px solid #eef0f4;
  backdrop-filter: blur(8px);
}
.draft-title-input {
  flex: 1;
}
.ppt-provider-status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 230px;
  padding: 7px 10px;
  border: 1px solid #a7f3d0;
  border-radius: 10px;
  background: #ecfdf5;
}
.ppt-provider-status.warning {
  border-color: #fde68a;
  background: #fffbeb;
}
.ppt-provider-status .provider-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #10b981;
}
.ppt-provider-status.warning .provider-dot {
  background: #f59e0b;
}
.ppt-provider-status strong,
.ppt-provider-status small {
  display: block;
  line-height: 1.35;
}
.ppt-provider-status strong {
  color: #162033;
  font-size: 13px;
}
.ppt-provider-status small {
  color: #64748b;
  font-size: 11px;
}
.draft-toolbar > .el-button {
  min-width: 148px;
  height: 40px;
}
.quality-alert {
  margin-bottom: 14px;
}
.quality-summary {
  display: grid;
  grid-template-columns: minmax(220px, 320px) 1fr;
  gap: 20px;
  align-items: center;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid #fde68a;
  border-radius: 12px;
  background: #fffbeb;
}
.quality-summary.ready {
  border-color: #a7f3d0;
  background: #ecfdf5;
}
.quality-summary strong,
.quality-summary span {
  display: block;
}
.quality-summary span {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
}
.quality-checks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}
.quality-check {
  display: grid;
  grid-template-columns: auto minmax(90px, auto) 1fr;
  gap: 8px;
  align-items: center;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 12px;
  background: #fff;
}
.quality-check span:last-child {
  color: #6b7280;
}
.quality-list {
  margin: 6px 0 0;
  padding-left: 18px;
  line-height: 1.6;
}
.draft-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ppt-config-panel {
  padding: 22px;
  border: 1px solid #e3e7f2;
  border-radius: 16px;
  background: #f9faff;
}
.config-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.config-heading.compact {
  margin-top: 24px;
}
.config-heading span {
  color: #667085;
  font-size: 12px;
}
.config-heading h3 {
  margin: 3px 0 0;
  color: #101828;
  font-size: 17px;
}
.mode-grid,
.style-grid {
  display: grid;
  gap: 10px;
}
.mode-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.style-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.choice-card,
.style-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 92px;
  padding: 15px;
  color: #344054;
  text-align: left;
  border: 1px solid #e1e6f0;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: 0.18s ease;
}
.choice-card:hover,
.style-card:hover {
  border-color: #aab5ff;
  transform: translateY(-1px);
}
.choice-card.selected,
.style-card.selected {
  border-color: #5267f6;
  box-shadow: 0 0 0 2px rgba(82, 103, 246, 0.12);
}
.choice-card strong,
.style-card strong {
  color: #101828;
  font-size: 14px;
}
.choice-card span,
.style-card span {
  margin-top: 7px;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}
.style-card i {
  width: 36px;
  height: 8px;
  margin-bottom: 13px;
  border-radius: 20px;
  background: linear-gradient(90deg, #315efb, #8ca4ff);
}
.style-card.tone-navy i { background: linear-gradient(90deg, #0f2747, #4b6b91); }
.style-card.tone-violet i { background: linear-gradient(90deg, #312e81, #0a0a0a); }
.style-card.tone-red i { background: linear-gradient(90deg, #9f1d25, #e36a5c); }
.style-card.tone-gray i { background: linear-gradient(90deg, #344054, #98a2b3); }
.style-card.tone-green i { background: linear-gradient(90deg, #067647, #32d583); }
.brief-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 18px;
}
.brief-grid label > span {
  display: block;
  margin-bottom: 7px;
  color: #475467;
  font-size: 12px;
  font-weight: 600;
}
.outline-list {
  overflow: hidden;
  border: 1px solid #e4e7ed;
  border-radius: 14px;
  background: #fff;
}
.outline-item {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid #eef0f4;
}
.outline-item:last-child {
  border-bottom: 0;
}
.outline-item > span {
  color: #5267f6;
  font-size: 13px;
  font-weight: 800;
}
.outline-item strong {
  color: #1d2939;
}
.outline-item p {
  overflow: hidden;
  margin: 5px 0 0;
  color: #667085;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.draft-head,
.draft-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.draft-head {
  min-height: 36px;
  padding: 0 2px;
}
.draft-head > span {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}
.draft-card {
  border-radius: 12px;
  border-color: #e4e7ed;
  overflow: visible;
}
.draft-card :deep(.el-card__header) {
  padding: 12px 16px;
  background: #fafbfc;
}
.draft-card :deep(.el-card__body) {
  padding: 18px 20px 10px;
}
.draft-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.draft-card :deep(.el-form-item__label) {
  padding-bottom: 7px;
  color: #374151;
  font-weight: 600;
}
.card-index {
  display: flex;
  align-items: center;
  gap: 9px;
  font-weight: 600;
}
.card-index > span {
  display: inline-flex;
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-radius: 8px;
  background: #4f63f6;
  font-size: 12px;
}
.card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-actions :deep(.el-button) {
  width: auto !important;
}
.line-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.line-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.line-row .el-input,
.line-row .el-textarea {
  flex: 1;
}
.line-number {
  display: inline-flex;
  flex: 0 0 28px;
  height: 28px;
  margin-top: 6px;
  align-items: center;
  justify-content: center;
  color: #4f63f6;
  border-radius: 8px;
  background: #eef2ff;
  font-size: 12px;
  font-weight: 700;
}
.line-row :deep(.el-textarea__inner) {
  padding: 11px 13px;
  line-height: 1.65;
}
.line-delete {
  flex: 0 0 auto;
  width: 30px !important;
  height: 30px;
  margin-top: 5px;
}
.compact-add-btn {
  align-self: flex-start;
  width: auto !important;
  min-width: 0 !important;
  padding: 0 12px !important;
  color: #4056e8;
  border-color: #c7d2fe;
  background: #f8faff;
}
.notes-input :deep(.el-textarea__inner) {
  padding: 13px 14px;
  line-height: 1.75;
  background: #fbfcfe;
}
.risk-head {
  margin-top: 8px;
}
.risk-table {
  width: 100%;
}
.review-confirm {
  margin: 18px 0 8px;
  padding: 12px 14px;
  border: 1px solid #f59e0b;
  border-radius: 8px;
  background: #fffbeb;
}
:deep(.deliverable-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 18px 24px;
  border-bottom: 1px solid #ebeef5;
  font-weight: 700;
}
:deep(.deliverable-drawer .el-drawer__body) {
  padding: 20px 24px 40px;
}

@media (max-width: 1280px) {
  :deep(.deliverable-drawer) {
    width: 90% !important;
  }
  .quality-checks {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 860px) {
  :deep(.deliverable-drawer) {
    width: 100% !important;
  }
  .draft-toolbar,
  .quality-summary {
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }
  .draft-toolbar > .el-button {
    width: 100%;
  }
  .card-actions {
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .prompt-examples,
  .mode-grid,
  .style-grid,
  .brief-grid {
    grid-template-columns: 1fr;
  }
  .sessions {
    display: none;
  }
  .messages {
    padding: 18px 14px;
  }
  .ppt-request,
  .ppt-task-card {
    max-width: none;
    width: auto;
  }
  .ppt-request {
    margin-left: 36px;
  }
  .ppt-task-card {
    margin-right: 20px;
  }
  .ppt-complete,
  .ppt-failed {
    align-items: flex-start;
    flex-direction: column;
  }
  .ppt-active-actions,
  .ppt-card-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  :deep(.ppt-preview-drawer) {
    width: 100% !important;
  }
}
</style>
