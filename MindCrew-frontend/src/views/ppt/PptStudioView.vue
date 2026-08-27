<template>
  <div class="ppt-studio" :class="{ embedded }">
    <div class="studio-shell">
      <header class="studio-header">
        <div>
          <p class="eyebrow">{{ embedded ? 'DIGITAL EMPLOYEE · PPT' : 'PRESENTATION WORKSPACE' }}</p>
          <h1>{{ embedded ? '生成 PPT' : '演示文稿工作台' }}</h1>
          <p class="subtitle">
            {{ embedded
              ? '由当前数字员工在后台完成大纲、分页和版式，生成期间可以继续对话。'
              : '描述要汇报的内容，系统将在后台完成大纲、分页和版式。' }}
          </p>
        </div>
        <div class="header-summary">
          <div class="summary-cell">
            <span>处理中</span>
            <strong>{{ activeCount }}</strong>
          </div>
          <div class="summary-cell">
            <span>已完成</span>
            <strong>{{ completedCount }}</strong>
          </div>
        </div>
      </header>

      <div class="workspace-grid">
        <section class="panel create-panel">
          <div class="panel-heading">
            <div>
              <span class="step-mark">新建</span>
              <h2>创建演示文稿</h2>
            </div>
            <span class="quiet-note">提交后可离开本页</span>
          </div>

          <label class="field-label" for="ppt-prompt">描述汇报内容</label>
          <div class="prompt-field" :class="{ focused: promptFocused }">
            <textarea
              id="ppt-prompt"
              v-model="form.prompt"
              maxlength="20000"
              placeholder="例如：为下周经营会议制作一份 12 页季度复盘，面向管理层，重点说明收入、成本、项目进展、风险和下季度计划。"
              @focus="promptFocused = true"
              @blur="promptFocused = false"
              @keydown.meta.enter.prevent="submit"
              @keydown.ctrl.enter.prevent="submit"
            ></textarea>
            <div class="prompt-footer">
              <span>{{ form.prompt.length.toLocaleString() }} / 20,000</span>
              <span>⌘ / Ctrl + Enter 提交</span>
            </div>
          </div>

          <div class="example-row">
            <span>常用示例</span>
            <button v-for="item in examples" :key="item.label" type="button" @click="form.prompt = item.prompt">
              {{ item.label }}
            </button>
          </div>

          <button class="advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
            <span>生成设置</span>
            <span class="setting-summary">{{ form.pageCount }} 页 · {{ styleLabel }}</span>
            <el-icon :class="{ open: advancedOpen }"><ArrowDown /></el-icon>
          </button>

          <div v-show="advancedOpen" class="advanced-grid">
            <label>
              <span>目标页数</span>
              <el-select v-model="form.pageCount">
                <el-option v-for="count in [8, 10, 12, 15, 20]" :key="count" :label="`${count} 页`" :value="count" />
              </el-select>
            </label>
            <label>
              <span>版式风格</span>
              <el-select v-model="form.visualStyle">
                <el-option label="标准商务" value="business" />
                <el-option label="咨询汇报" value="consulting" />
                <el-option label="极简正式" value="minimal" />
                <el-option label="政府汇报" value="government" />
              </el-select>
            </label>
            <label>
              <span>汇报对象（可选）</span>
              <el-input v-model="form.audience" maxlength="200" placeholder="例如：公司管理层" />
            </label>
            <label>
              <span>汇报目的（可选）</span>
              <el-input v-model="form.purpose" maxlength="200" placeholder="例如：季度经营复盘" />
            </label>
          </div>

          <div class="submit-row">
            <p><el-icon><CircleCheck /></el-icon> 后台运行，不占用当前页面</p>
            <el-button
              type="primary"
              size="large"
              :loading="submitting"
              :disabled="!form.prompt.trim()"
              @click="submit"
            >
              {{ submitting ? '正在提交' : '开始生成' }}
            </el-button>
          </div>
        </section>

        <section class="panel task-panel">
          <div class="panel-heading">
            <div>
              <span class="step-mark">任务</span>
              <h2>最近生成</h2>
            </div>
            <el-button text :loading="loading" @click="loadTasks">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>

          <div v-if="loading && !tasks.length" class="task-empty">正在加载任务...</div>
          <div v-else-if="!tasks.length" class="task-empty">
            <el-icon><Document /></el-icon>
            <strong>暂无生成任务</strong>
            <span>提交第一份演示文稿后，进度会显示在这里。</span>
          </div>
          <div v-else class="task-list">
            <article v-for="task in tasks" :key="task.id" class="task-item">
              <div class="task-topline">
                <div class="task-title-wrap">
                  <span class="file-icon"><el-icon><Document /></el-icon></span>
                  <div>
                    <h3>{{ task.title }}</h3>
                    <p>{{ task.pageCount }} 页 · {{ formatTime(task.createTime) }}</p>
                  </div>
                </div>
                <span class="status-badge" :class="task.status">{{ statusText(task.status) }}</span>
              </div>

              <div v-if="isActive(task.status)" class="progress-block">
                <div class="progress-meta">
                  <span>{{ task.stage || '正在处理' }}</span>
                  <span>{{ task.progress }}%</span>
                </div>
                <el-progress :percentage="task.progress" :stroke-width="6" :show-text="false" />
                <p>任务在服务器后台执行，关闭或切换页面不会中断。</p>
              </div>

              <div v-else-if="task.status === 'failed'" class="failure-block">
                <p>{{ task.errorMessage || '生成失败，请稍后重试。' }}</p>
                <el-button size="small" :loading="retryingId === task.id" @click="retry(task.id)">重新生成</el-button>
              </div>

              <div v-else-if="task.status === 'canceled'" class="failure-block">
                <p>任务已取消。</p>
              </div>

              <div v-else class="complete-row">
                <div>
                  <span>{{ task.providerName || '演示文稿服务' }}</span>
                  <span v-if="task.fileSize">{{ formatSize(task.fileSize) }}</span>
                  <span v-if="task.fallbackUsed">基础应急版，非阿里商用版</span>
                </div>
                <el-button
                  v-if="task.fallbackUsed"
                  size="small"
                  :loading="retryingId === task.id"
                  @click="retry(task.id)"
                >
                  重试阿里商用版
                </el-button>
                <el-button type="primary" plain size="small" :loading="downloadingId === task.id" @click="download(task)">
                  <el-icon><Download /></el-icon> 下载 PPTX
                </el-button>
              </div>
            </article>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pptApi, type PptTask, type PptTaskStatus } from '@/api/ppt'

withDefaults(defineProps<{
  embedded?: boolean
}>(), {
  embedded: false,
})

const tasks = ref<PptTask[]>([])
const loading = ref(false)
const submitting = ref(false)
const retryingId = ref<number | null>(null)
const downloadingId = ref<number | null>(null)
const advancedOpen = ref(false)
const promptFocused = ref(false)
let timer: number | undefined

const form = reactive({
  prompt: '',
  pageCount: 12,
  visualStyle: 'business',
  audience: '',
  purpose: '',
})

const examples = [
  { label: '季度经营复盘', prompt: '制作一份季度经营复盘，面向公司管理层，说明核心经营数据、重点项目进展、成本变化、主要风险和下季度行动计划。' },
  { label: '项目立项汇报', prompt: '制作一份项目立项汇报，说明业务背景、用户痛点、解决方案、实施计划、资源投入、预期收益和风险控制。' },
  { label: '客户解决方案', prompt: '制作一份面向企业客户的解决方案，说明现状与挑战、总体方案、核心能力、落地路径、服务保障和合作计划。' },
]

const styleLabels: Record<string, string> = {
  business: '标准商务',
  consulting: '咨询汇报',
  minimal: '极简正式',
  government: '政府汇报',
}
const styleLabel = computed(() => styleLabels[form.visualStyle] || '标准商务')
const activeCount = computed(() => tasks.value.filter(task => isActive(task.status)).length)
const completedCount = computed(() => tasks.value.filter(task => task.status === 'completed').length)

const loadTasks = async () => {
  loading.value = true
  try {
    tasks.value = await pptApi.list(30)
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  if (!form.prompt.trim() || submitting.value) return
  submitting.value = true
  try {
    const task = await pptApi.create({
      prompt: form.prompt.trim(),
      pageCount: form.pageCount,
      language: 'zh-CN',
      visualStyle: form.visualStyle,
      audience: form.audience.trim() || undefined,
      purpose: form.purpose.trim() || undefined,
    })
    tasks.value = [task, ...tasks.value.filter(item => item.id !== task.id)]
    form.prompt = ''
    ElMessage.success('任务已进入后台，可以继续处理其他工作')
  } finally {
    submitting.value = false
  }
}

const retry = async (id: number) => {
  retryingId.value = id
  try {
    const task = await pptApi.retry(id)
    tasks.value = tasks.value.map(item => item.id === id ? task : item)
    ElMessage.success('任务已重新进入后台队列')
  } finally {
    retryingId.value = null
  }
}

const download = async (task: PptTask) => {
  downloadingId.value = task.id
  try {
    const response = await pptApi.download(task.id)
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = task.fileName || `${task.title}.pptx`
    a.click()
    URL.revokeObjectURL(url)
  } finally {
    downloadingId.value = null
  }
}

const isActive = (status: PptTaskStatus) => status === 'queued' || status === 'generating'
const statusText = (status: PptTaskStatus) => ({
  queued: '排队中',
  generating: '生成中',
  completed: '已完成',
  failed: '失败',
  canceled: '已取消',
}[status])

const formatTime = (value: string) => {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
const formatSize = (bytes: number) => bytes >= 1024 * 1024
  ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
  : `${Math.max(1, Math.round(bytes / 1024))} KB`

onMounted(() => {
  loadTasks()
  timer = window.setInterval(loadTasks, 5000)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.ppt-studio {
  height: 100%;
  overflow: auto;
  background: #f5f6f8;
  color: #172033;
}
.ppt-studio.embedded { background: #f6f7f5; }
.ppt-studio.embedded .studio-shell { max-width: none; padding: 24px 28px 48px; }
.ppt-studio.embedded .studio-header { margin-bottom: 20px; }
.ppt-studio.embedded h1 { font-size: 24px; }
.ppt-studio.embedded .eyebrow { color: #60789f; }
.ppt-studio.embedded .panel,
.ppt-studio.embedded .header-summary {
  border-color: #e1e4e3;
  box-shadow: 0 4px 16px rgba(31, 41, 55, .035);
}
.ppt-studio.embedded .step-mark {
  color: #526b9f;
  background: #eef2f7;
}
.ppt-studio.embedded .prompt-field.focused {
  border-color: #9eafc6;
  box-shadow: 0 0 0 3px rgba(82, 107, 159, .07);
}
.ppt-studio.embedded .submit-row :deep(.el-button--primary) {
  border-color: #526b9f;
  background: #526b9f;
  box-shadow: 0 3px 8px rgba(82, 107, 159, .12);
}
.studio-shell { max-width: 1440px; margin: 0 auto; padding: 34px 40px 56px; }
.studio-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 28px; margin-bottom: 26px; }
.eyebrow { margin: 0 0 8px; color: #315efb; font-size: 11px; line-height: 1; font-weight: 700; letter-spacing: .14em; }
h1 { margin: 0; font-size: 30px; line-height: 1.25; letter-spacing: -.025em; color: #121a2b; }
.subtitle { margin: 10px 0 0; font-size: 14px; color: #687386; }
.header-summary { display: flex; min-width: 230px; background: #fff; border: 1px solid #e1e5eb; border-radius: 8px; }
.summary-cell { flex: 1; padding: 13px 18px; display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.summary-cell + .summary-cell { border-left: 1px solid #e8ebf0; }
.summary-cell span { font-size: 12px; color: #7a8495; }
.summary-cell strong { font-size: 19px; color: #172033; }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(420px, .92fr); gap: 20px; align-items: start; }
.panel { background: #fff; border: 1px solid #dde2e9; border-radius: 10px; box-shadow: 0 2px 8px rgba(18, 26, 43, .035); }
.create-panel, .task-panel { padding: 24px; }
.panel-heading { min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
.panel-heading > div { display: flex; align-items: center; gap: 10px; }
.panel-heading h2 { margin: 0; font-size: 17px; color: #172033; }
.step-mark { display: inline-flex; padding: 3px 7px; border-radius: 4px; background: #eef3ff; color: #315efb; font-size: 11px; font-weight: 700; }
.quiet-note { font-size: 12px; color: #7b8596; }
.field-label { display: block; margin-bottom: 9px; color: #3d4758; font-size: 13px; font-weight: 600; }
.prompt-field { border: 1px solid #ccd3dd; border-radius: 8px; background: #fff; transition: border-color .16s, box-shadow .16s; }
.prompt-field.focused { border-color: #315efb; box-shadow: 0 0 0 3px rgba(49, 94, 251, .09); }
.prompt-field textarea { display: block; width: 100%; min-height: 190px; padding: 16px 17px; border: 0; outline: 0; resize: vertical; border-radius: 8px 8px 0 0; color: #1e293b; font: 14px/1.75 inherit; box-sizing: border-box; }
.prompt-field textarea::placeholder { color: #9aa3b1; }
.prompt-footer { display: flex; justify-content: space-between; padding: 9px 14px; border-top: 1px solid #edf0f4; color: #929baa; font-size: 11px; }
.example-row { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin: 13px 0 20px; }
.example-row > span { margin-right: 2px; font-size: 12px; color: #7b8595; }
.example-row button { padding: 5px 9px; border: 1px solid #e2e6ec; border-radius: 5px; background: #f8f9fb; color: #536074; font: 12px inherit; cursor: pointer; }
.example-row button:hover { border-color: #b9c5dc; color: #315efb; background: #f4f7ff; }
.advanced-toggle { width: 100%; display: flex; align-items: center; gap: 10px; padding: 13px 0; border: 0; border-top: 1px solid #e8ebf0; border-bottom: 1px solid #e8ebf0; background: transparent; color: #374151; font: 13px inherit; font-weight: 600; cursor: pointer; }
.advanced-toggle .setting-summary { margin-left: auto; color: #8a94a4; font-weight: 400; }
.advanced-toggle .el-icon { color: #8a94a4; transition: transform .18s; }
.advanced-toggle .el-icon.open { transform: rotate(180deg); }
.advanced-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding: 18px 0 4px; }
.advanced-grid label > span { display: block; margin-bottom: 7px; color: #596579; font-size: 12px; }
.advanced-grid :deep(.el-select), .advanced-grid :deep(.el-input) { width: 100%; }
.submit-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 22px; }
.submit-row p { display: flex; align-items: center; gap: 6px; margin: 0; color: #6d7788; font-size: 12px; }
.submit-row p .el-icon { color: #16a36a; }
.submit-row :deep(.el-button--primary) { min-width: 126px; border-radius: 6px; background: #315efb; border-color: #315efb; font-weight: 600; }
.task-panel { min-height: 520px; }
.task-empty { min-height: 380px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; color: #8b95a5; text-align: center; }
.task-empty .el-icon { font-size: 30px; color: #a9b2c0; }
.task-empty strong { color: #4d586a; font-size: 14px; }
.task-empty span { font-size: 12px; }
.task-list { display: flex; flex-direction: column; gap: 12px; }
.task-item { padding: 16px; border: 1px solid #e3e7ed; border-radius: 8px; background: #fff; }
.task-topline { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.task-title-wrap { min-width: 0; display: flex; gap: 11px; }
.file-icon { flex: 0 0 34px; height: 34px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid #dce3ef; border-radius: 6px; color: #315efb; background: #f6f8fc; }
.task-title-wrap h3 { margin: 0 0 5px; max-width: 340px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #263144; font-size: 13px; font-weight: 600; }
.task-title-wrap p { margin: 0; color: #8a94a4; font-size: 11px; }
.status-badge { flex-shrink: 0; padding: 3px 7px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.status-badge.queued { background: #f2f4f7; color: #596579; }
.status-badge.generating { background: #eef3ff; color: #315efb; }
.status-badge.completed { background: #eaf8f2; color: #16825d; }
.status-badge.failed { background: #fff0f0; color: #c73e3e; }
.progress-block { margin-top: 14px; padding-top: 12px; border-top: 1px solid #eef0f3; }
.progress-meta { display: flex; justify-content: space-between; margin-bottom: 7px; color: #596579; font-size: 11px; }
.progress-block :deep(.el-progress-bar__outer) { background: #edf0f5; }
.progress-block :deep(.el-progress-bar__inner) { background: #315efb; }
.progress-block p { margin: 7px 0 0; color: #969ead; font-size: 10px; }
.failure-block, .complete-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 13px; padding-top: 12px; border-top: 1px solid #eef0f3; }
.failure-block p { margin: 0; color: #a94444; font-size: 11px; line-height: 1.5; }
.complete-row > div { display: flex; flex-wrap: wrap; gap: 6px 12px; color: #7f8998; font-size: 10px; }
.complete-row :deep(.el-button) { border-radius: 5px; }
@media (max-width: 1100px) {
  .workspace-grid { grid-template-columns: 1fr; }
  .task-panel { min-height: 0; }
}
@media (max-width: 768px) {
  .studio-shell { padding: 22px 16px 40px; }
  .studio-header { align-items: flex-start; flex-direction: column; }
  .header-summary { width: 100%; }
  .create-panel, .task-panel { padding: 18px; }
  .advanced-grid { grid-template-columns: 1fr; }
  .submit-row { align-items: stretch; flex-direction: column; }
  .submit-row .el-button { width: 100%; }
}
</style>
