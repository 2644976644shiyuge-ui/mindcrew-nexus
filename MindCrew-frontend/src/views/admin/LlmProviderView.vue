<template>
  <div class="provider-page">
    <!-- 顶部 -->
    <header class="page-header">
      <div class="page-header-l">
        <div class="title-row">
          <h2 class="page-title">大模型 Provider</h2>
          <span class="title-tag">跨厂商 · 一键切换</span>
        </div>
        <p class="page-desc">
          管理 LLM 厂商配置，全部走 OpenAI 兼容协议（DashScope / DeepSeek / OpenAI / 本地 Ollama / 自建 vLLM）。
          切换激活后立即生效，无需重启。
        </p>
      </div>
      <div class="page-header-r">
        <button class="btn-primary" @click="openCreate">
          <el-icon :size="14"><Plus /></el-icon>
          新建 Provider
        </button>
      </div>
    </header>

    <!-- 卡片网格 -->
    <main class="provider-grid" v-loading="loading">
      <article
        v-for="p in providers"
        :key="p.id"
        class="provider-card"
        :class="{ 'is-active': p.isActive === 1, disabled: p.enabled === 0 }"
      >
        <header class="card-head">
          <div class="card-title-row">
            <h3 class="card-title">{{ p.name }}</h3>
            <span v-if="p.isActive === 1" class="badge active">✓ 激活</span>
            <span v-if="p.enabled === 0" class="badge disabled-badge">禁用</span>
          </div>
          <div class="card-actions">
            <el-tooltip content="编辑" placement="top">
              <button class="icon-btn" @click="openEdit(p)"><el-icon :size="14"><Edit /></el-icon></button>
            </el-tooltip>
            <el-tooltip content="测试连通" placement="top">
              <button class="icon-btn" @click="testOne(p)" :disabled="testing === p.id">
                <el-icon :size="14"><Connection /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip v-if="p.isActive !== 1" content="设为激活" placement="top">
              <button class="icon-btn primary" @click="activateOne(p)">
                <el-icon :size="14"><CircleCheck /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <button class="icon-btn danger" @click="deleteOne(p)" :disabled="p.isActive === 1">
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </el-tooltip>
          </div>
        </header>

        <p class="card-desc">{{ p.description || '（无描述）' }}</p>

        <div class="card-fields">
          <div class="field">
            <span class="field-label">Endpoint</span>
            <span class="field-value mono">{{ p.baseUrl }}</span>
          </div>
          <div class="field">
            <span class="field-label">Chat Model</span>
            <span class="field-value">{{ p.chatModel || '-' }}</span>
          </div>
          <div class="field" v-if="p.embeddingModel">
            <span class="field-label">Embedding</span>
            <span class="field-value">{{ p.embeddingModel }}<span v-if="p.embeddingDim" class="dim">@{{ p.embeddingDim }}d</span></span>
          </div>
          <div class="field">
            <span class="field-label">API Key</span>
            <span class="field-value mono">
              <template v-if="p.apiKeySet">{{ p.apiKeyMasked }}</template>
              <span v-else class="field-empty">未设置</span>
            </span>
          </div>
          <div class="field">
            <span class="field-label">Temperature</span>
            <span class="field-value mono">{{ Number(p.temperature).toFixed(2) }}</span>
          </div>
        </div>

        <footer class="card-foot" v-if="p.lastTestAt">
          <span class="test-result" :class="{ ok: p.lastTestOk === 1, bad: p.lastTestOk === 0 }">
            <el-icon :size="11">
              <CircleCheck v-if="p.lastTestOk === 1" />
              <CircleClose v-else />
            </el-icon>
            上次测试 · {{ p.lastTestMsg }}
          </span>
        </footer>
      </article>

      <div v-if="!loading && providers.length === 0" class="empty">
        <div class="empty-ring"></div>
        <p>暂无 Provider，点右上角新建</p>
      </div>
    </main>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- 模型端点配置 · vision / video / asr / tts / reranker / voice_chat -->
    <!-- ═══════════════════════════════════════════════════════════════ -->
    <section class="endpoint-section" v-if="!loading">
      <header class="section-header">
        <div>
          <h3 class="section-title">模型接入（本地化）</h3>
          <p class="section-desc">图片理解 / 视频理解 / 语音识别 / 语音合成 / 重排序 / 语音对话 &mdash; 每个类型可独立切换模型，立即生效。</p>
        </div>
      </header>

      <div v-for="modelType in MODEL_TYPE_ORDER" :key="modelType" class="endpoint-group">
        <div class="group-label">{{ MODEL_TYPE_LABELS[modelType] }}<code class="type-badge">{{ modelType }}</code></div>
        <div class="endpoint-row" v-if="endpointsByType[modelType]?.length">
          <article
            v-for="ep in endpointsByType[modelType]"
            :key="ep.id"
            class="provider-card endpoint-card"
            :class="{ 'is-active': ep.isActive === 1, disabled: ep.enabled === 0 }"
          >
            <header class="card-head">
              <div class="card-title-row">
                <h3 class="card-title">{{ ep.name }}</h3>
                <span v-if="ep.isActive === 1" class="badge active">✓ 激活</span>
                <span v-if="ep.enabled === 0" class="badge disabled-badge">禁用</span>
              </div>
              <div class="card-actions">
                <el-tooltip content="编辑" placement="top">
                  <button class="icon-btn" @click="openEpEdit(ep)"><el-icon :size="14"><Edit /></el-icon></button>
                </el-tooltip>
                <el-tooltip content="测试连通" placement="top">
                  <button class="icon-btn" @click="testEpOne(ep)" :disabled="testingEp === ep.id">
                    <el-icon :size="14"><Connection /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip v-if="ep.isActive !== 1" content="设为激活" placement="top">
                  <button class="icon-btn primary" @click="activateEp(ep)">
                    <el-icon :size="14"><CircleCheck /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip content="删除" placement="top">
                  <button class="icon-btn danger" @click="deleteEp(ep)" :disabled="ep.isActive === 1">
                    <el-icon :size="14"><Delete /></el-icon>
                  </button>
                </el-tooltip>
              </div>
            </header>
            <p class="card-desc">{{ ep.description || '（无描述）' }}</p>
            <div class="card-fields">
              <div class="field">
                <span class="field-label">Endpoint</span>
                <span class="field-value mono">{{ ep.baseUrl }}</span>
              </div>
              <div class="field">
                <span class="field-label">Model</span>
                <span class="field-value">{{ ep.modelName }}</span>
              </div>
              <div class="field">
                <span class="field-label">API Key</span>
                <span class="field-value mono">
                  <template v-if="ep.apiKeySet">{{ ep.apiKeyMasked }}</template>
                  <span v-else class="field-empty">未设置</span>
                </span>
              </div>
              <div class="field" v-if="ep.extraParams">
                <span class="field-label">扩展参数</span>
                <span class="field-value mono" style="font-size:11px">{{ ep.extraParams }}</span>
              </div>
            </div>
            <footer class="card-foot" v-if="ep.lastTestAt">
              <span class="test-result" :class="{ ok: ep.lastTestOk === 1, bad: ep.lastTestOk === 0 }">
                <el-icon :size="11"><CircleCheck v-if="ep.lastTestOk === 1" /><CircleClose v-else /></el-icon>
                上次测试 · {{ ep.lastTestMsg }}
              </span>
            </footer>
          </article>
        </div>
        <div v-else class="endpoint-empty">暂无配置</div>
        <button class="btn-add-endpoint" @click="openEpCreate(modelType)">
          <el-icon :size="12"><Plus /></el-icon> 新增
        </button>
      </div>
    </section>

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing.id ? '编辑：' + editing.name : '新建 Provider'"
      width="720px"
      :close-on-click-modal="false"
      class="provider-dialog"
    >
      <div class="form-grid">
        <div class="form-row-2col">
          <div>
            <label>展示名 <span class="req">*</span></label>
            <el-input v-model="editing.name" placeholder="如：DashScope · 主用" maxlength="50" />
          </div>
          <div>
            <label>协议类型</label>
            <el-input v-model="editing.providerType" placeholder="openai_compatible" disabled />
          </div>
        </div>

        <div class="form-row">
          <label>Base URL <span class="req">*</span></label>
          <el-input v-model="editing.baseUrl" placeholder="如：https://dashscope.aliyuncs.com/compatible-mode" />
          <div class="form-hint-mini">
            支持厂商根地址、带版本地址或完整 chat/completions 地址；系统会识别已有 v1/v3/v4/v1beta，
            不会重复拼接版本。适用于百炼、智谱、DeepSeek、OpenAI、Ollama、vLLM 等 OpenAI 兼容服务。
          </div>
        </div>

        <div class="form-row">
          <label>API Key {{ editing.id ? '' : '（首次创建建议填写）' }}</label>
          <el-input
            v-model="editing.apiKey"
            type="password"
            show-password
            :placeholder="editing.id ? '留空表示保留原值，输入空格清除' : '填入厂商提供的 API Key'"
          />
          <div class="form-hint-mini">服务端使用 AES-256-GCM 加密存储，不会明文回显</div>
        </div>

        <div class="form-row-2col">
          <div>
            <label>Chat Model</label>
            <el-input v-model="editing.chatModel" placeholder="qwen-plus / gpt-4o / deepseek-chat ..." />
          </div>
          <div>
            <label>Temperature</label>
            <el-input-number v-model="editing.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
          </div>
        </div>

        <div class="form-row-2col">
          <div>
            <label>Embedding Model（可空）</label>
            <el-input v-model="editing.embeddingModel" placeholder="text-embedding-v3 / bge-m3 ..." />
          </div>
          <div>
            <label>Embedding 维度</label>
            <el-input-number v-model="editing.embeddingDim" :min="0" :max="8192" :step="64" />
          </div>
        </div>

        <div class="form-row">
          <label>备注</label>
          <el-input v-model="editing.description" type="textarea" :rows="2" maxlength="300" />
        </div>

        <div class="form-row-2col">
          <div>
            <label>排序权重</label>
            <el-input-number v-model="editing.sortOrder" :min="0" :max="9999" />
          </div>
          <div class="toggle-row">
            <el-switch v-model="enabledBool" />
            <div>
              <div class="toggle-label">启用</div>
              <div class="toggle-hint">禁用后无法被激活</div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <button class="btn-ghost" @click="dialogVisible = false">取消</button>
        <button class="btn-ghost" @click="handleTest" :disabled="submitting">
          <el-icon :size="13"><Connection /></el-icon>
          {{ testingNow ? '测试中…' : '测试连通' }}
        </button>
        <button class="btn-primary" @click="handleSubmit" :disabled="submitting">
          {{ editing.id ? '保存' : '创建' }}
        </button>
      </template>
    </el-dialog>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- 模型端点编辑弹窗 -->
    <!-- ═══════════════════════════════════════════════════════════════ -->
    <el-dialog
      v-model="epDialogVisible"
      :title="epEditing.id ? '编辑：' + epEditing.name : '新建 ' + MODEL_TYPE_LABELS[epEditing.modelType] + ' 端点'"
      width="720px"
      :close-on-click-modal="false"
      class="provider-dialog"
    >
      <div class="form-grid">
        <div class="form-row">
          <label>展示名 <span class="req">*</span></label>
          <el-input v-model="epEditing.name" placeholder="如：千问 VL · 图片理解" maxlength="50" />
        </div>
        <div class="form-row-2col">
          <div>
            <label>模型类型</label>
            <el-input :model-value="MODEL_TYPE_LABELS[epEditing.modelType] + ' (' + epEditing.modelType + ')'" disabled />
          </div>
          <div>
            <label>协议类型</label>
            <el-select v-model="epEditing.providerType" style="width:100%">
              <el-option label="DashScope / 阿里云百炼" value="dashscope" />
              <el-option label="OpenAI 兼容" value="openai_compatible" />
              <el-option label="本地部署" value="local" />
            </el-select>
          </div>
        </div>
        <div class="form-row">
          <label>API 地址 <span class="req">*</span></label>
          <el-input v-model="epEditing.baseUrl" placeholder="https://dashscope.aliyuncs.com/compatible-mode" />
        </div>
        <div class="form-row">
          <label>API Key {{ epEditing.id ? '' : '（建议填写）' }}</label>
          <el-input v-model="epEditing.apiKey" type="password" show-password
            :placeholder="epEditing.id ? '留空=保留原值' : '填入 API Key'" />
          <div class="form-hint-mini">AES-256-GCM 加密存储，不会明文回显</div>
        </div>
        <div class="form-row-2col">
          <div>
            <label>模型名 <span class="req">*</span></label>
            <el-input v-model="epEditing.modelName" placeholder="qwen-vl-max / paraformer-v2 / gte-rerank ..." />
          </div>
          <div>
            <label>排序</label>
            <el-input-number v-model="epEditing.sortOrder" :min="0" :max="9999" />
          </div>
        </div>
        <div class="form-row">
          <label>扩展参数 (JSON)</label>
          <el-input v-model="epEditing.extraParams" type="textarea" :rows="2"
            placeholder='{"max_tokens":2000,"temperature":0.7}' />
        </div>
        <div class="form-row">
          <label>备注</label>
          <el-input v-model="epEditing.description" type="textarea" :rows="2" maxlength="300" />
        </div>
        <div class="form-row-2col">
          <div class="toggle-row">
            <el-switch v-model="epEnabledBool" />
            <div>
              <div class="toggle-label">启用</div>
              <div class="toggle-hint">禁用后无法被激活</div>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="epDialogVisible = false">取消</button>
        <button class="btn-ghost" @click="handleEpTest" :disabled="epSubmitting">
          <el-icon :size="13"><Connection /></el-icon>
          {{ epTestingNow ? '测试中…' : '测试连通' }}
        </button>
        <button class="btn-primary" @click="handleEpSubmit" :disabled="epSubmitting">
          {{ epEditing.id ? '保存' : '创建' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, CircleCheck, CircleClose, Connection } from '@element-plus/icons-vue'
import { llmProviderApi, type LlmProvider } from '@/api/llmProvider'
import { modelEndpointApi, type ModelEndpoint, MODEL_TYPE_LABELS, MODEL_TYPE_ORDER } from '@/api/modelEndpoint'

const loading = ref(false)
const testing = ref<number | null>(null)
const testingNow = ref(false)
const providers = ref<LlmProvider[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editing = reactive<LlmProvider>(initProvider())

const enabledBool = computed({
  get: () => editing.enabled === 1,
  set: v => { editing.enabled = v ? 1 : 0 }
})

function initProvider(): LlmProvider {
  return {
    name: '',
    providerType: 'openai_compatible',
    baseUrl: '',
    apiKey: '',
    chatModel: '',
    embeddingModel: '',
    embeddingDim: undefined,
    temperature: 0.7,
    description: '',
    enabled: 1,
    sortOrder: 100,
  }
}

async function load() {
  loading.value = true
  try {
    const res: any = await llmProviderApi.list()
    providers.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { loading.value = false }
}

function openCreate() {
  Object.assign(editing, initProvider())
  dialogVisible.value = true
}

function openEdit(p: LlmProvider) {
  Object.assign(editing, p, { apiKey: '' })   // apiKey 留空 = 保留原值
  if (typeof editing.temperature === 'string') editing.temperature = Number(editing.temperature)
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!editing.name?.trim()) return ElMessage.warning('请填展示名')
  if (!editing.baseUrl?.trim()) return ElMessage.warning('请填 Base URL')
  submitting.value = true
  try {
    if (editing.id) {
      await llmProviderApi.update(editing.id, editing)
      ElMessage.success('已保存')
    } else {
      await llmProviderApi.create(editing)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

async function handleTest() {
  testingNow.value = true
  try {
    let res: any
    if (editing.id) {
      // 已保存的 provider；如果填了新 apiKey 用它覆盖测试
      res = await llmProviderApi.testById(editing.id, editing.apiKey || undefined)
    } else {
      // 未保存的临时测试
      if (!editing.baseUrl || !editing.chatModel) {
        return ElMessage.warning('需要 Base URL + Chat Model 才能测试')
      }
      res = await llmProviderApi.testRaw(editing)
    }
    const r = res?.data ?? res
    if (r.success) ElMessage.success('连通正常 · ' + r.message)
    else ElMessage.error('测试失败 · ' + r.message)
  } catch (e: any) {
    ElMessage.error('测试异常：' + (e?.message || ''))
  } finally { testingNow.value = false }
}

async function testOne(p: LlmProvider) {
  testing.value = p.id!
  try {
    const res: any = await llmProviderApi.testById(p.id!)
    const r = res?.data ?? res
    if (r.success) ElMessage.success(p.name + ' · ' + r.message)
    else ElMessage.error(p.name + ' · ' + r.message)
    await load()
  } catch (e: any) {
    ElMessage.error('测试异常：' + (e?.message || ''))
  } finally { testing.value = null }
}

async function activateOne(p: LlmProvider) {
  try {
    await ElMessageBox.confirm(
      `确定切换到 "${p.name}"？\nChat 和 Embedding 调用将立即使用新配置（无需重启）。`,
      '切换激活 Provider', { type: 'warning' }
    )
    await llmProviderApi.setActive(p.id!)
    ElMessage.success(`已切换激活 Provider 为 "${p.name}"`)
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('切换失败：' + (e?.message || ''))
  }
}

async function deleteOne(p: LlmProvider) {
  if (p.isActive === 1) return ElMessage.warning('不能删除激活的 Provider')
  try {
    await ElMessageBox.confirm(`确定删除 "${p.name}"？`, '提示', { type: 'warning' })
    await llmProviderApi.delete(p.id!)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

// ════════════════════════════════════════════════════════
// 模型端点
// ════════════════════════════════════════════════════════
const endpoints = ref<ModelEndpoint[]>([])
const endpointsByType = computed(() => {
  const map: Record<string, ModelEndpoint[]> = {}
  for (const t of MODEL_TYPE_ORDER) map[t] = []
  for (const ep of endpoints.value) {
    if (map[ep.modelType]) map[ep.modelType]!.push(ep)
  }
  return map
})

const epDialogVisible = ref(false)
const epSubmitting = ref(false)
const epTestingNow = ref(false)
const testingEp = ref<number | null>(null)
const epEditing = reactive<ModelEndpoint>(initEp())

const epEnabledBool = computed({
  get: () => epEditing.enabled === 1,
  set: v => { epEditing.enabled = v ? 1 : 0 }
})

function initEp(modelType?: string): ModelEndpoint {
  return {
    name: '',
    modelType: modelType || 'vision',
    providerType: 'dashscope',
    baseUrl: '',
    apiKey: '',
    modelName: '',
    extraParams: '',
    description: '',
    enabled: 1,
    sortOrder: 100,
  }
}

async function loadEndpoints() {
  try {
    const res: any = await modelEndpointApi.list()
    endpoints.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('模型端点加载失败：' + (e?.message || ''))
  }
}

function openEpCreate(modelType: string) {
  Object.assign(epEditing, initEp(modelType))
  epDialogVisible.value = true
}

function openEpEdit(ep: ModelEndpoint) {
  Object.assign(epEditing, ep, { apiKey: '' })
  epDialogVisible.value = true
}

async function handleEpSubmit() {
  if (!epEditing.name?.trim()) return ElMessage.warning('请填展示名')
  if (!epEditing.baseUrl?.trim()) return ElMessage.warning('请填 API 地址')
  if (!epEditing.modelName?.trim()) return ElMessage.warning('请填模型名')
  epSubmitting.value = true
  try {
    if (epEditing.id) {
      await modelEndpointApi.update(epEditing.id, epEditing)
      ElMessage.success('已保存')
    } else {
      await modelEndpointApi.create(epEditing)
      ElMessage.success('已创建')
    }
    epDialogVisible.value = false
    await loadEndpoints()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { epSubmitting.value = false }
}

async function handleEpTest() {
  epTestingNow.value = true
  try {
    let res: any
    if (epEditing.id) {
      res = await modelEndpointApi.testById(epEditing.id, epEditing.apiKey || undefined)
    } else {
      if (!epEditing.baseUrl || !epEditing.modelName) {
        return ElMessage.warning('需要 API 地址 + 模型名')
      }
      res = await modelEndpointApi.testRaw(epEditing)
    }
    const r = res?.data ?? res
    if (r.success) ElMessage.success('连通正常 · ' + r.message)
    else ElMessage.error('测试失败 · ' + r.message)
  } catch (e: any) {
    ElMessage.error('测试异常：' + (e?.message || ''))
  } finally { epTestingNow.value = false }
}

async function testEpOne(ep: ModelEndpoint) {
  testingEp.value = ep.id!
  try {
    const res: any = await modelEndpointApi.testById(ep.id!)
    const r = res?.data ?? res
    if (r.success) ElMessage.success(ep.name + ' · ' + r.message)
    else ElMessage.error(ep.name + ' · ' + r.message)
    await loadEndpoints()
  } catch (e: any) {
    ElMessage.error('测试异常：' + (e?.message || ''))
  } finally { testingEp.value = null }
}

async function activateEp(ep: ModelEndpoint) {
  try {
    await ElMessageBox.confirm(
      `确定将「${MODEL_TYPE_LABELS[ep.modelType]}」的激活端点切换到 "${ep.name}"？`,
      '切换激活', { type: 'warning' }
    )
    await modelEndpointApi.setActive(ep.id!)
    ElMessage.success(`已切换「${MODEL_TYPE_LABELS[ep.modelType]}」激活端点`)
    await loadEndpoints()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('切换失败：' + (e?.message || ''))
  }
}

async function deleteEp(ep: ModelEndpoint) {
  if (ep.isActive === 1) return ElMessage.warning('不能删除激活的端点')
  try {
    await ElMessageBox.confirm(`确定删除 "${ep.name}"？`, '提示', { type: 'warning' })
    await modelEndpointApi.delete(ep.id!)
    ElMessage.success('已删除')
    await loadEndpoints()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

onMounted(() => { load(); loadEndpoints() })
</script>

<style scoped>
.provider-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 28px 32px 48px;
  background: var(--bg-page);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 28px;
}
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.02em;
}
.title-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  background: linear-gradient(135deg, #e0e7ff, #c7d2fe);
  color: #4338ca;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  border-radius: 10px;
  box-shadow: var(--shadow-brand);
  border: none;
  cursor: pointer;
  transition: filter 200ms;
}
.btn-primary:hover:not(:disabled) { filter: brightness(1.06); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 8px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 500;
  border-radius: 10px;
  cursor: pointer;
}
.btn-ghost:hover { background: var(--bg-hover); }

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 18px;
}
.provider-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px 20px 14px;
  box-shadow: var(--shadow-card);
  transition: all 200ms;
}
.provider-card:hover {
  border-color: var(--brand-soft-2);
  box-shadow: var(--shadow-md);
}
.provider-card.is-active {
  border-color: var(--brand);
  background: linear-gradient(180deg, var(--brand-soft) 0%, var(--bg-surface) 30%);
  box-shadow: 0 0 0 1px var(--brand), var(--shadow-md);
}
.provider-card.disabled { opacity: 0.55; }

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 6px;
}
.card-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.card-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-1);
}
.badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}
.badge.active {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand));
  color: #fff;
}
.badge.disabled-badge {
  background: var(--bg-subtle);
  color: var(--ink-3);
}
.card-actions { display: flex; gap: 4px; }
.icon-btn {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--ink-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 180ms;
}
.icon-btn:hover { background: var(--bg-hover); border-color: var(--line); color: var(--ink-1); }
.icon-btn.primary:hover {
  background: var(--brand-soft);
  border-color: var(--brand);
  color: var(--brand);
}
.icon-btn.danger:hover { background: var(--danger-soft); border-color: var(--danger); color: var(--danger); }
.icon-btn:disabled { opacity: 0.35; cursor: not-allowed; }

.card-desc { font-size: 13px; color: var(--ink-3); line-height: 1.6; margin-bottom: 12px; min-height: 36px; }

.card-fields { display: flex; flex-direction: column; gap: 6px; }
.field {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}
.field-label {
  flex-shrink: 0;
  width: 80px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-4);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.field-value { color: var(--ink-1); word-break: break-all; }
.field-value.mono { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.field-empty { color: var(--ink-4); font-style: italic; }
.dim { color: var(--ink-3); font-size: 10.5px; margin-left: 4px; font-family: 'JetBrains Mono', monospace; }

.card-foot {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}
.test-result {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--ink-3);
}
.test-result.ok { color: var(--success-ink); }
.test-result.bad { color: var(--danger); }

.empty {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--ink-3);
  gap: 14px;
}
.empty-ring {
  width: 48px;
  height: 48px;
  border: 3px solid var(--line);
  border-top-color: var(--brand);
  border-radius: 50%;
}

.form-grid { display: flex; flex-direction: column; gap: 16px; }
.form-row { display: flex; flex-direction: column; gap: 6px; }
.form-row label, .form-row-2col > div > label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-2);
}
.req { color: var(--danger); }
.form-hint-mini { font-size: 11px; color: var(--ink-4); margin-top: 2px; }
.form-row-2col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.form-row-2col > div { display: flex; flex-direction: column; gap: 6px; }
.toggle-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border-radius: 10px;
  border: 1px solid var(--line);
}
.toggle-label { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.toggle-hint { font-size: 11px; color: var(--ink-3); }

/* ════════════════════════════════════════════════════════ */
/* 模型端点区域 */
/* ════════════════════════════════════════════════════════ */
.endpoint-section { margin-top: 48px; }
.section-header { margin-bottom: 24px; }
.section-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.02em;
}
.section-desc { font-size: 13px; color: var(--ink-3); margin-top: 6px; max-width: 720px; }

.endpoint-group { margin-bottom: 20px; }
.group-label {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-2);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.type-badge {
  font-size: 10px;
  font-weight: 600;
  background: var(--bg-subtle);
  color: var(--ink-4);
  padding: 2px 8px;
  border-radius: 999px;
  font-family: 'JetBrains Mono', monospace;
}
.endpoint-row {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 18px;
  margin-bottom: 8px;
}
.endpoint-card { max-width: 100%; }
.endpoint-empty {
  font-size: 13px;
  color: var(--ink-4);
  font-style: italic;
  padding: 8px 0;
}
.btn-add-endpoint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 14px;
  background: transparent;
  border: 1px dashed var(--line);
  border-radius: 8px;
  color: var(--ink-3);
  font-size: 12px;
  cursor: pointer;
  transition: all 180ms;
}
.btn-add-endpoint:hover {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-soft);
}
</style>

<style>
.provider-dialog .el-dialog__footer { padding-top: 12px; }
.provider-dialog .el-dialog__footer > * + * { margin-left: 8px; }
</style>
