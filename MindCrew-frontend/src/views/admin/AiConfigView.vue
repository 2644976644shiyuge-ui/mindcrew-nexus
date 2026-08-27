<template>
  <div class="ai-config-page">

    <!-- ===== 顶部标题栏 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="title-row">
          <h2 class="page-title">AI 配置中心</h2>
          <el-tag v-if="modifiedCount > 0" type="warning" effect="dark" class="changed-badge">
            {{ modifiedCount }} 项待保存
          </el-tag>
        </div>
        <p class="page-desc">动态调整 RAG 召回、模型参数、缓存策略与安全阈值，配置即时生效无需重启</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="modifiedCount > 0"
          type="primary"
          :loading="savingAll"
          @click="handleSaveAll"
        >
          保存全部更改
        </el-button>
        <el-button type="danger" plain :loading="resetting" @click="handleResetAll">
          恢复全部默认
        </el-button>
      </div>
    </div>

    <!-- ===== 配置卡片网格 ===== -->
    <div v-loading="loading" class="config-grid">
      <div
        v-for="groupMeta in displayGroups"
        :key="groupMeta.key"
        class="config-card"
        :style="{ '--accent': groupMeta.color }"
      >
        <!-- 卡片头部 -->
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-accent" :style="{ background: groupMeta.color }"></span>
            <div>
              <div class="card-title">{{ groupMeta.label }}</div>
              <div class="card-subtitle">{{ groupMeta.desc }}</div>
            </div>
          </div>
          <div class="card-head-actions">
            <el-tag
              v-if="groupMeta.key === 'ppt' && pptTestResult"
              :type="pptTestResult.success ? 'success' : 'danger'"
              effect="light"
              size="small"
              class="connection-result"
            >
              {{ pptTestResult.success
                ? `已连通 · ${pptTestResult.latencyMs}ms`
                : '连接失败' }}
            </el-tag>
            <el-button
              v-if="groupMeta.key === 'ppt'"
              plain
              size="small"
              :loading="testingPpt"
              @click="handleTestPptConnection"
            >
              测试连接
            </el-button>
            <el-button
              text
              size="small"
              class="save-group-link"
              :loading="saving[groupMeta.key]"
              :style="{ color: groupMeta.color }"
              @click="handleSaveGroup(groupMeta.key)"
            >
              保存本组
            </el-button>
            <el-button text size="small" class="reset-link" @click="handleResetGroup(groupMeta.key)">
              重置默认
            </el-button>
          </div>
        </div>

        <!-- 配置项列表 -->
        <div class="config-rows">
          <div
            v-for="cfg in grouped[groupMeta.key] || []"
            :key="cfg.configKey"
            v-show="isConfigVisible(cfg)"
            class="config-row"
            :class="{ modified: isModified(cfg) }"
          >
            <!-- 标签区 -->
            <div class="row-label">
              <span class="label-text">{{ cfg.label }}</span>
              <el-tooltip v-if="cfg.description" :content="cfg.description" placement="top" :show-after="300">
                <el-icon class="info-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>

            <!-- 控件区 -->
            <div class="row-control">
              <!-- 模型选择 -->
              <template v-if="cfg.configKey === 'llm.model'">
                <el-select v-model="form[cfg.configKey]" style="width: 200px">
                  <el-option v-for="m in models" :key="m" :label="m" :value="m">
                    <span>{{ m }}</span>
                    <el-tag v-if="m === 'qwen-plus'" size="small" type="success" style="margin-left:8px">推荐</el-tag>
                  </el-option>
                </el-select>
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.enabled' || cfg.configKey === 'ppt_generation.fallback-on-error'">
                <el-switch
                  v-model="form[cfg.configKey]"
                  active-value="true"
                  inactive-value="false"
                  inline-prompt
                  active-text="开"
                  inactive-text="关"
                />
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.service-provider'">
                <el-select v-model="form[cfg.configKey]" style="width: 240px">
                  <el-option label="阿里云 Qwen-Doc-Turbo（推荐）" value="qwen-doc" />
                  <el-option label="Gamma 官方 API" value="gamma" />
                  <el-option label="自定义直出 API / PPT Agent" value="direct" />
                </el-select>
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.qwen-mode'">
                <el-select v-model="form[cfg.configKey]" style="width: 280px">
                  <el-option label="企业模板模式（原生可编辑）" value="general" />
                  <el-option label="创意图文模式（图片型页面）" value="creative" />
                </el-select>
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.qwen-template-id'">
                <el-select v-model="form[cfg.configKey]" style="width: 280px">
                  <el-option label="互联网 / 科技汇报" value="internet_01" />
                  <el-option label="总结汇报" value="summary_01" />
                  <el-option label="论文 / 研究报告" value="thesis_01" />
                  <el-option label="新闻 / 政企信息" value="news_01" />
                </el-select>
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.planner-provider'">
                <el-select v-model="form[cfg.configKey]" style="width: 240px">
                  <el-option label="阿里云百炼 / DashScope（推荐）" value="dashscope" />
                  <el-option label="OpenAI 兼容服务" value="openai-compatible" />
                  <el-option label="私有化 Qwen / vLLM" value="qwen-local" />
                </el-select>
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.api-url'">
                <el-input
                  v-model="form[cfg.configKey]"
                  :placeholder="pptApiUrlPlaceholder"
                  style="width: 360px"
                />
              </template>

              <template v-else-if="cfg.configKey === 'ppt_generation.model'">
                <el-select
                  v-model="form[cfg.configKey]"
                  filterable
                  allow-create
                  default-first-option
                  style="width: 240px"
                >
                  <el-option label="qwen-plus（均衡推荐）" value="qwen-plus" />
                  <el-option label="qwen3.7-plus（高质量）" value="qwen3.7-plus" />
                  <el-option label="qwen3.7-max（复杂重要汇报）" value="qwen3.7-max" />
                  <el-option label="qwen-flash（低成本快速）" value="qwen-flash" />
                </el-select>
              </template>

              <template v-else-if="cfg.configKey.includes('api-key')">
                <el-input
                  v-model="form[cfg.configKey]"
                  type="password"
                  autocomplete="new-password"
                  :placeholder="cfg.configured
                    ? '已安全配置；留空不修改，输入新值可替换'
                    : '请输入 API Key（保存后不可回显）'"
                  style="width: 280px"
                />
              </template>

              <!-- 视频理解方式：原生 vs 经济 -->
              <template v-else-if="cfg.configKey === 'video.mode'">
                <el-select v-model="form[cfg.configKey]" style="width: 320px">
                  <el-option label="原生理解（qwen-vl · 准确·成本高）" value="qwen-vl" />
                  <el-option label="经济模式（ASR+关键帧 · 口播/访谈类性价比高）" value="legacy" />
                </el-select>
              </template>

              <!-- 解析模型下拉（视频/图片/OCR）· 可输入自定义 -->
              <template v-else-if="cfg.configKey === 'video.model' || cfg.configKey === 'vision.model' || cfg.configKey === 'ocr.model'">
                <el-select v-model="form[cfg.configKey]" filterable allow-create default-first-option style="width: 220px">
                  <el-option
                    v-for="m in (cfg.configKey === 'video.model' ? VIDEO_MODEL_OPTIONS : cfg.configKey === 'ocr.model' ? OCR_MODEL_OPTIONS : VISION_MODEL_OPTIONS)"
                    :key="m" :label="m" :value="m"
                  />
                </el-select>
              </template>

              <!-- 重排协议：阿里云专有 vs 本地通用 -->
              <template v-else-if="cfg.configKey === 'reranker.protocol'">
                <el-select v-model="form[cfg.configKey]" style="width: 320px">
                  <el-option label="dashscope（阿里云 gte-rerank 专有格式）" value="dashscope" />
                  <el-option label="jina（本地 bge-reranker · Xinference/TEI/Cohere 通用）" value="jina" />
                </el-select>
              </template>

              <!-- 知识图谱召回开关（GraphRAG · 第三路召回，默认关） -->
              <template v-else-if="cfg.configKey === 'rag.graph_enabled'">
                <el-switch
                  v-model="form[cfg.configKey]"
                  active-value="1"
                  inactive-value="0"
                  inline-prompt
                  active-text="开"
                  inactive-text="关"
                />
              </template>

              <!-- 通用字符串配置 -->
              <template v-else-if="cfg.valueType === 'string'">
                <el-input
                  v-model="form[cfg.configKey]"
                  :type="isLongTextConfig(cfg) ? 'textarea' : 'text'"
                  :rows="isLongTextConfig(cfg) ? 2 : undefined"
                  resize="none"
                  :style="{ width: isLongTextConfig(cfg) ? '280px' : '200px' }"
                />
              </template>

              <!-- 浮点数（附进度条） -->
              <template v-else-if="cfg.valueType === 'float'">
                <div class="float-control">
                  <el-slider
                    v-model="formNum[cfg.configKey]"
                    :min="cfg.minValue ? Number(cfg.minValue) : 0"
                    :max="cfg.maxValue ? Number(cfg.maxValue) : 1"
                    :step="0.05"
                    :show-tooltip="false"
                    class="param-slider"
                  />
                  <el-input-number
                    v-model="formNum[cfg.configKey]"
                    :min="cfg.minValue ? Number(cfg.minValue) : undefined"
                    :max="cfg.maxValue ? Number(cfg.maxValue) : undefined"
                    :step="0.05"
                    :precision="2"
                    controls-position="right"
                    size="small"
                    style="width: 90px"
                  />
                </div>
              </template>

              <!-- 整数 -->
              <template v-else>
                <el-input-number
                  v-model="formNum[cfg.configKey]"
                  :min="cfg.minValue ? Number(cfg.minValue) : undefined"
                  :max="cfg.maxValue ? Number(cfg.maxValue) : undefined"
                  :step="1"
                  controls-position="right"
                  style="width: 130px"
                />
              </template>
            </div>

            <!-- 默认值提示 -->
            <div class="row-default">
              <span class="default-label">默认</span>
              <span class="default-val">{{ cfg.defaultValue }}</span>
              <span v-if="isModified(cfg)" class="modified-dot" :style="{ background: groupMeta.color }"></span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import {
  aiConfigApi,
  type AiConfig,
  type PptConnectionTestResult
} from '@/api/aiConfig'

// ===================== 分组元数据 =====================
const groupOrder = [
  {
    key: 'rag',
    label: 'RAG 检索参数',
    desc: '控制召回数量与融合策略',
    icon: '🔍',
    color: '#2563eb',
    lightBg: '#eff6ff'
  },
  {
    key: 'llm',
    label: 'LLM 模型参数',
    desc: '生成温度等参数（模型在「大模型 Provider」切换）',
    icon: '🤖',
    color: '#0a0a0a',
    lightBg: '#f5f3ff'
  },
  {
    key: 'ppt',
    label: 'PPT Agent',
    desc: '像切换大模型一样配置 AI PPT 服务商',
    icon: '📊',
    color: '#e11d48',
    lightBg: '#fff1f2'
  },
  {
    key: 'cache',
    label: '缓存策略',
    desc: '高频问题缓存触发与过期',
    icon: '⚡',
    color: '#059669',
    lightBg: '#ecfdf5'
  },
  {
    key: 'safety',
    label: '安全阈值',
    desc: '置信度兜底触发条件',
    icon: '🛡️',
    color: '#d97706',
    lightBg: '#fffbeb'
  },
  {
    key: 'parse',
    label: '解析模型',
    desc: '视频/图片理解所用模型',
    icon: '🎬',
    color: '#0ea5e9',
    lightBg: '#ecfeff'
  },
  {
    key: 'model',
    label: '模型接入（本地化）',
    desc: '向量 / 图片 / 重排的接入地址·模型·协议；留空则跟随对话 Provider',
    icon: '🔌',
    color: '#0d9488',
    lightBg: '#f0fdfa'
  },
  {
    key: 'golden',
    label: 'Golden Pair 范例',
    desc: '把相似的已审核问答作为参考范例注入（动态 few-shot）',
    icon: '✨',
    color: '#6366f1',
    lightBg: '#eef2ff'
  }
]

// 解析模型可选项（与百炼模型广场对应）
const VIDEO_MODEL_OPTIONS = ['qwen3-vl-plus', 'qwen3-vl-flash', 'qwen-vl-max']
const VISION_MODEL_OPTIONS = ['qwen-vl-max', 'qwen3-vl-plus', 'qwen3-vl-flash']
const OCR_MODEL_OPTIONS = ['qwen3.5-ocr', 'qwen-vl-ocr', 'qwen-vl-ocr-latest']

const groupMetaMap = Object.fromEntries(groupOrder.map(group => [group.key, group]))

// ===================== 数据 =====================
const loading = ref(true)
const grouped = ref<Record<string, AiConfig[]>>({})
const models = ref<string[]>([])
const resetting = ref(false)
const savingAll = ref(false)
const saving = reactive<Record<string, boolean>>({})
const testingPpt = ref(false)
const pptTestResult = ref<PptConnectionTestResult | null>(null)

const form = reactive<Record<string, string>>({})
const formNum = reactive<Record<string, number>>({})

const displayGroups = computed(() => {
  const keys = Object.keys(grouped.value)
  const orderedKeys = [
    ...groupOrder.map(group => group.key).filter(key => keys.includes(key)),
    ...keys.filter(key => !groupMetaMap[key])
  ]

  return orderedKeys.map(key => {
    const fallback = grouped.value[key]?.[0]
    return groupMetaMap[key] || {
      key,
      label: fallback?.groupName || key,
      desc: '接口返回的系统配置分组',
      icon: '⚙️',
      color: '#64748b',
      lightBg: '#f8fafc'
    }
  })
})

// 未保存修改数
const modifiedCount = computed(() => {
  let count = 0
  for (const items of Object.values(grouped.value)) {
    for (const cfg of items) {
      if (isModified(cfg)) count++
    }
  }
  return count
})

// ===================== 初始化 =====================
onMounted(async () => {
  await Promise.all([loadConfigs(), loadModels()])
  loading.value = false
})

// 不在本页展示的配置项：模型名称改由「大模型 Provider」统一管理，避免与 Provider 冲突
const HIDDEN_KEYS = new Set(['llm.model'])

const loadConfigs = async () => {
  const raw = await aiConfigApi.listAll()
  // 过滤掉隐藏项（值仍保留在后端，作为未激活 Provider 时的兜底默认）
  for (const key of Object.keys(raw)) {
    raw[key] = (raw[key] || []).filter(cfg => !HIDDEN_KEYS.has(cfg.configKey))
  }
  grouped.value = raw
  for (const key of Object.keys(form)) delete form[key]
  for (const key of Object.keys(formNum)) delete formNum[key]
  for (const items of Object.values(grouped.value)) {
    for (const cfg of items) {
      if (cfg.valueType === 'string') {
        form[cfg.configKey] = cfg.configValue
      } else {
        formNum[cfg.configKey] = Number(cfg.configValue)
      }
    }
  }
}

const loadModels = async () => {
  models.value = await aiConfigApi.getModels()
}

// ===================== 工具方法 =====================
const isModified = (cfg: AiConfig) => {
  const current = cfg.valueType === 'string'
    ? form[cfg.configKey]
    : String(formNum[cfg.configKey])
  return current !== cfg.configValue
}

const isLongTextConfig = (cfg: AiConfig) =>
  cfg.configKey.includes('msg') ||
  cfg.label.includes('话术') ||
  cfg.configValue.length > 24 ||
  cfg.defaultValue.length > 24

const isConfigVisible = (cfg: AiConfig) => {
  if (!cfg.configKey.startsWith('ppt_generation.')) return true
  const provider = form['ppt_generation.service-provider'] || 'qwen-doc'
  if (cfg.configKey === 'ppt_generation.theme-id') return provider === 'gamma'
  if (cfg.configKey === 'ppt_generation.qwen-mode') return provider === 'qwen-doc'
  if (cfg.configKey === 'ppt_generation.qwen-template-id') {
    return provider === 'qwen-doc'
      && (form['ppt_generation.qwen-mode'] || 'general') === 'general'
  }
  if ([
    'ppt_generation.planner-provider',
    'ppt_generation.model',
    'ppt_generation.model-base-url',
    'ppt_generation.model-api-key'
  ].includes(cfg.configKey)) {
    return provider === 'direct'
  }
  return true
}

const pptApiUrlPlaceholder = computed(() => {
  const provider = form['ppt_generation.service-provider'] || 'qwen-doc'
  if (provider === 'qwen-doc') {
    return '可留空，或填写百炼业务空间 compatible-mode/v1 地址'
  }
  if (provider === 'gamma') {
    return '可留空，默认 https://public-api.gamma.app'
  }
  return '例如 http://ppt-agent:3100/v1/presentations/generate'
})

const collectGroup = (groupKey: string) => {
  const items = grouped.value[groupKey] || []
  const params: Record<string, string> = {}
  for (const cfg of items) {
    params[cfg.configKey] = cfg.valueType === 'string'
      ? (form[cfg.configKey] ?? '')
      : String(formNum[cfg.configKey] ?? 0)
  }
  return params
}

const collectAll = () => {
  const params: Record<string, string> = {}
  for (const items of Object.values(grouped.value)) {
    for (const cfg of items) {
      params[cfg.configKey] = cfg.valueType === 'string'
        ? (form[cfg.configKey] ?? '')
        : String(formNum[cfg.configKey] ?? 0)
    }
  }
  return params
}

// ===================== 操作 =====================
const handleSaveGroup = async (groupKey: string) => {
  saving[groupKey] = true
  try {
    await aiConfigApi.batchUpdate(collectGroup(groupKey))
    await loadConfigs()
    ElMessage.success('已保存，配置立即生效')
  } finally {
    saving[groupKey] = false
  }
}

const handleSaveAll = async () => {
  savingAll.value = true
  try {
    await aiConfigApi.batchUpdate(collectAll())
    await loadConfigs()
    ElMessage.success('全部配置已保存，立即生效')
  } finally {
    savingAll.value = false
  }
}

const handleTestPptConnection = async () => {
  testingPpt.value = true
  pptTestResult.value = null
  try {
    const result = await aiConfigApi.testPptConnection(collectGroup('ppt'))
    pptTestResult.value = result
    if (result.success) {
      ElMessage.success(`${result.providerName} 连接成功（${result.latencyMs}ms）`)
    } else {
      ElMessage.error(result.message || '连接失败，请检查 API 地址、密钥和网络')
    }
  } finally {
    testingPpt.value = false
  }
}

const handleResetGroup = async (groupKey: string) => {
  const meta = groupOrder.find(g => g.key === groupKey)!
  await ElMessageBox.confirm(`确认将「${meta.label}」恢复为默认值？`, '提示', { type: 'warning' })
  await aiConfigApi.resetGroup(groupKey)
  await loadConfigs()
  ElMessage.success('已恢复默认值')
}

const handleResetAll = async () => {
  await ElMessageBox.confirm('确认将全部配置恢复为出厂默认值？', '警告', {
    type: 'warning',
    confirmButtonText: '确认重置',
    confirmButtonClass: 'el-button--danger'
  })
  resetting.value = true
  try {
    await aiConfigApi.resetAll()
    await loadConfigs()
    ElMessage.success('全部配置已恢复默认值')
  } finally {
    resetting.value = false
  }
}
</script>

<style scoped>
/* ===== 整体布局 ===== */
.ai-config-page {
  height: 100%;
  overflow-y: auto;
  padding: 16px 20px;
}

/* ===== 顶部标题栏 ===== */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 14px;
  gap: 16px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-1);
  margin: 0;
}

.changed-badge {
  font-size: 12px;
  border-radius: 20px;
}

.page-desc {
  font-size: 13px;
  color: var(--ink-3);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  align-items: center;
}

/* ===== 卡片网格 ===== */
.config-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}

/* ===== 单张配置卡片 ===== */
.config-card {
  background: var(--bg-surface);
  border-radius: 12px;
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.config-card:hover {
  border-color: var(--line-strong);
  box-shadow: 0 4px 18px rgba(11, 20, 38, 0.06);
}

/* ===== 卡片头部 ===== */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 18px;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--line);
}

.card-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.connection-result {
  max-width: 150px;
}

.save-group-link {
  font-size: 12px;
  font-weight: 600;
}

.card-accent {
  width: 4px;
  height: 22px;
  border-radius: 999px;
  flex-shrink: 0;
}

.card-title {
  font-size: 14.5px;
  font-weight: 700;
  color: var(--ink-1);
  line-height: 1.4;
  letter-spacing: 0.2px;
}

.card-subtitle {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 2px;
}

.reset-link {
  color: var(--ink-3) !important;
  font-size: 12px;
}
.reset-link:hover {
  color: var(--accent) !important;
}

/* ===== 配置行 ===== */
.config-rows {
  flex: 1;
  padding: 4px 0;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 18px;
  border-radius: 0;
  transition: background 0.15s;
}

.config-row:not(:last-child) {
  border-bottom: 1px solid var(--line-soft);
}

.config-row:hover {
  background: var(--bg-hover);
}

.config-row.modified {
  background: rgba(251, 191, 36, 0.06);
}

/* 标签区 */
.row-label {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 150px;
  flex-shrink: 0;
}

.label-text {
  font-size: 13px;
  color: var(--ink-1);
  font-weight: 600;
}

.info-icon {
  font-size: 13px;
  color: var(--ink-4);
  cursor: default;
  flex-shrink: 0;
}
.info-icon:hover {
  color: var(--ink-2);
}

/* 控件区 */
.row-control {
  flex: 1;
  min-width: 0;
}

.float-control {
  display: flex;
  align-items: center;
  gap: 10px;
}

.param-slider {
  flex: 1;
  max-width: 120px;
}

/* 默认值区 */
.row-default {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  min-width: 80px;
}

.default-label {
  font-size: 11px;
  color: var(--ink-4);
}

.default-val {
  display: inline-block;
  max-width: 180px;
  font-size: 12px;
  color: var(--ink-2);
  font-family: 'SF Mono', 'Fira Code', monospace;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  padding: 1px 6px;
  border-radius: 4px;
  white-space: normal;
  word-break: break-word;
  line-height: 1.45;
}

.modified-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-left: 2px;
  flex-shrink: 0;
}

/* ===== Element Plus 覆盖 ===== */
:deep(.el-input-number .el-input__inner) {
  font-size: 13px;
}

:deep(.el-slider__runway) {
  height: 4px;
}

:deep(.el-slider__button) {
  width: 14px;
  height: 14px;
  border-color: var(--accent, #2563eb);
}

:deep(.el-slider__bar) {
  background: var(--accent, #2563eb);
  height: 4px;
}

:deep(.el-select .el-input__inner) {
  font-size: 13px;
}
</style>
