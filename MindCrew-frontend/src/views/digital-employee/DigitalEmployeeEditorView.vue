<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { digitalEmployeeApi, type DigitalEmployeeDetail, type ScenarioTemplate } from '@/api/digitalEmployee'
import { collectionApi } from '@/api/collection'
import { departmentApi } from '@/api/orgAcl'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => route.name === 'DigitalEmployeeEdit')
const id = computed(() => (isEdit.value ? Number(route.params.id) : null))

const saving = ref(false)
const optimizing = ref(false)
const advancedOpen = ref(false)
const accessOpen = ref(false)
const scenarios = ref<ScenarioTemplate[]>([])
const collections = ref<any[]>([])
const departments = ref<any[]>([])

const form = ref({
  name: '',
  avatar: '🤖',
  summary: '',
  systemPrompt: '',
  modelProvider: 'default',
  modelName: '自研模型',
  webSearch: false,
  memoryEnabled: true,
  primaryScenario: 'general_qa',
  visibility: 'public',
  kbOnlyReply: false,
  collectionIds: [] as number[],
  aclEntries: [] as { principalType: string; principalId: number; permission: string }[],
})

const scenarioParams = ref<Record<string, string | number>>({})
const exportBranding = ref({
  exportCompanyName: '',
  exportDocIdPrefix: 'HT',
  exportFooterNote: '',
  pptPrimaryColor: '#315EFB',
  pptAccentColor: '#F59E0B',
})
const selectedDeptId = ref<number | null>(null)

const EXPORT_KEYS = [
  'exportCompanyName',
  'exportDocIdPrefix',
  'exportFooterNote',
  'pptPrimaryColor',
  'pptAccentColor',
] as const

const activeScenario = computed(() =>
  scenarios.value.find((s) => s.id === form.value.primaryScenario))

const readinessWarnings = computed(() => {
  const warnings: string[] = []
  if (form.value.kbOnlyReply && !form.value.collectionIds.length) {
    warnings.push('开启仅知识库回复时必须绑定知识库')
  }
  return warnings
})

function parseScenarioConfig(json?: string) {
  if (!json) return {}
  try {
    return JSON.parse(json) as Record<string, string | number>
  } catch {
    return {}
  }
}

watch(
  () => form.value.primaryScenario,
  () => {
    const fields = activeScenario.value?.configFields ?? []
    const next: Record<string, string | number> = {}
    for (const f of fields) {
      const cur = scenarioParams.value[f.key]
      if (cur !== undefined && cur !== '') next[f.key] = cur
      else if (f.defaultValue) next[f.key] = f.type === 'number' ? Number(f.defaultValue) : f.defaultValue
    }
    scenarioParams.value = next
  },
)

async function load() {
  const [sc, cols, depts] = await Promise.all([
    digitalEmployeeApi.scenarioTemplates(),
    collectionApi.list(),
    departmentApi.list(),
  ])
  scenarios.value = sc
  collections.value = cols
  departments.value = depts?.data ?? depts ?? []

  if (id.value) {
    const d = await digitalEmployeeApi.adminDetail(id.value)
    form.value = {
      name: d.name,
      avatar: d.avatar || '🤖',
      summary: d.summary || '',
      systemPrompt: d.systemPrompt || '',
      modelProvider: d.modelProvider || 'default',
      modelName: d.modelName || '自研模型',
      webSearch: d.webSearch === true,
      memoryEnabled: d.memoryEnabled !== false,
      primaryScenario: d.primaryScenario || 'general_qa',
      visibility: d.visibility || 'public',
      kbOnlyReply: !!d.kbOnlyReply,
      collectionIds: d.collectionIds || [],
      aclEntries: (d.aclEntries || []).map((e) => ({
        principalType: e.principalType,
        principalId: e.principalId,
        permission: e.permission || 'use',
      })),
    }
    const cfg = parseScenarioConfig(d.scenarioConfig)
    exportBranding.value = {
      exportCompanyName: String(cfg.exportCompanyName ?? ''),
      exportDocIdPrefix: String(cfg.exportDocIdPrefix ?? 'HT'),
      exportFooterNote: String(cfg.exportFooterNote ?? ''),
      pptPrimaryColor: String(cfg.pptPrimaryColor ?? '#315EFB'),
      pptAccentColor: String(cfg.pptAccentColor ?? '#F59E0B'),
    }
    const rest = { ...cfg }
    for (const k of EXPORT_KEYS) delete rest[k]
    scenarioParams.value = rest
  }
}

function addDeptAcl() {
  if (!selectedDeptId.value) return
  if (form.value.aclEntries.some((e) => e.principalType === 'department' && e.principalId === selectedDeptId.value)) {
    ElMessage.warning('该部门已添加')
    return
  }
  form.value.aclEntries.push({
    principalType: 'department',
    principalId: selectedDeptId.value,
    permission: 'use',
  })
  selectedDeptId.value = null
}

function removeAcl(i: number) {
  form.value.aclEntries.splice(i, 1)
}

function payload(): Partial<DigitalEmployeeDetail> & { scenarioConfig?: string } {
  const p = { ...form.value } as Partial<DigitalEmployeeDetail> & { scenarioConfig?: string }
  const merged: Record<string, string | number> = { ...scenarioParams.value }
  if (exportBranding.value.exportCompanyName.trim()) {
    merged.exportCompanyName = exportBranding.value.exportCompanyName.trim()
  }
  if (exportBranding.value.exportDocIdPrefix.trim()) {
    merged.exportDocIdPrefix = exportBranding.value.exportDocIdPrefix.trim()
  }
  if (exportBranding.value.exportFooterNote.trim()) {
    merged.exportFooterNote = exportBranding.value.exportFooterNote.trim()
  }
  if (exportBranding.value.pptPrimaryColor.trim()) {
    merged.pptPrimaryColor = exportBranding.value.pptPrimaryColor.trim()
  }
  if (exportBranding.value.pptAccentColor.trim()) {
    merged.pptAccentColor = exportBranding.value.pptAccentColor.trim()
  }
  p.scenarioConfig = Object.keys(merged).length ? JSON.stringify(merged) : ''
  return p
}

async function saveDraft(): Promise<number | null> {
  saving.value = true
  try {
    if (id.value) {
      await digitalEmployeeApi.update(id.value, payload())
      ElMessage.success('已保存')
      return id.value
    }
    const created = await digitalEmployeeApi.create(payload())
    ElMessage.success('已创建草稿')
    await router.replace({ name: 'DigitalEmployeeEdit', params: { id: created.id } })
    return created.id
  } finally {
    saving.value = false
  }
}

async function publish() {
  let eid = id.value ?? Number(route.params.id)
  if (!eid || Number.isNaN(eid)) {
    eid = (await saveDraft()) ?? 0
  }
  if (!eid) return
  saving.value = true
  try {
    await digitalEmployeeApi.update(eid, payload())
    await digitalEmployeeApi.publish(eid)
    ElMessage.success('已发布')
    router.push({ name: 'DigitalEmployeeAdmin' })
  } finally {
    saving.value = false
  }
}

async function optimize() {
  const eid = id.value
  if (!eid) {
    ElMessage.warning('请先保存草稿再优化')
    return
  }
  optimizing.value = true
  try {
    const text = await digitalEmployeeApi.optimizePrompt(eid)
    if (text) form.value.systemPrompt = text
    ElMessage.success('已优化设定')
  } finally {
    optimizing.value = false
  }
}

function close() {
  router.push({ name: 'DigitalEmployeeAdmin' })
}

onMounted(() => load().catch(() => ElMessage.error('加载失败')))
</script>

<template>
  <div class="editor">
    <div class="editor-scroll">
      <h1>{{ isEdit ? '编辑数字员工' : '创建数字员工' }}</h1>
      <p class="page-intro">选择工作场景即可开始。名称、简介和其他配置都可以留空，系统会自动补充默认值。</p>

      <div class="quick-create">
        <section class="col">
          <div class="section-title">
            <div><span>基础信息</span><h3>快速创建</h3></div>
            <span class="optional-badge">全部可选</span>
          </div>
          <el-form label-position="top">
            <el-form-item label="数字员工名称">
              <el-input v-model="form.name" placeholder="留空将根据场景自动生成，如：PPT 助手" />
            </el-form-item>
            <el-form-item label="主要用途">
              <el-input v-model="form.summary" type="textarea" :rows="3" placeholder="简单描述它要帮助用户完成什么，也可以留空" />
            </el-form-item>
          </el-form>
        </section>

        <section class="col">
          <div class="section-title">
            <div><span>工作类型</span><h3>选择一个场景</h3></div>
          </div>
          <el-select v-model="form.primaryScenario" style="width: 100%" size="large">
            <el-option v-for="s in scenarios" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <div v-if="activeScenario" class="scenario-summary">
            <strong>{{ activeScenario.name }}</strong>
            <span>{{ activeScenario.description }}</span>
            <small>系统自动加载对应话术、输出格式和推荐参数</small>
          </div>
        </section>
      </div>

      <div class="settings-toggle-row">
        <button type="button" class="settings-toggle" @click="advancedOpen = !advancedOpen">
          <span><strong>高级能力设置</strong><small>知识库、联网、详细规则、场景参数和企业品牌</small></span>
          <b>{{ advancedOpen ? '收起' : '展开' }}</b>
        </button>
        <button type="button" class="settings-toggle" @click="accessOpen = !accessOpen">
          <span><strong>发布范围</strong><small>默认企业内公开，也可以按部门限制使用</small></span>
          <b>{{ accessOpen ? '收起' : '展开' }}</b>
        </button>
      </div>

      <div v-if="advancedOpen || accessOpen" class="cols advanced-cols">
        <section v-if="advancedOpen" class="col">
          <h3>高级能力</h3>
          <el-form label-position="top">
            <el-form-item label="头像（emoji）">
              <el-input v-model="form.avatar" maxlength="8" />
            </el-form-item>
            <el-form-item label="智能体设定">
              <div class="prompt-head">
                <span>留空则使用场景默认设定</span>
                <el-button size="small" :loading="optimizing" @click="optimize">AI 优化</el-button>
              </div>
              <el-input v-model="form.systemPrompt" type="textarea" :rows="6" placeholder="只有需要特殊工作规则时才填写" />
            </el-form-item>
            <el-divider content-position="left">场景参数（可选）</el-divider>
            <template v-for="f in activeScenario?.configFields ?? []" :key="f.key">
              <el-form-item :label="f.label">
                <el-input-number v-if="f.type === 'number'" v-model="scenarioParams[f.key]" :min="1" :max="80" style="width: 100%" />
                <el-select v-else-if="f.type === 'select'" v-model="scenarioParams[f.key]" filterable allow-create default-first-option style="width: 100%">
                  <el-option v-for="opt in String(f.placeholder || f.defaultValue || '').split('/').filter(Boolean)" :key="opt" :label="opt" :value="opt" />
                </el-select>
                <el-input v-else v-model="scenarioParams[f.key]" :placeholder="f.placeholder" />
              </el-form-item>
            </template>
            <el-form-item label="展示用模型名">
              <el-input v-model="form.modelName" placeholder="仅用于管理台展示" />
            </el-form-item>
            <el-form-item label="联网搜索"><el-switch v-model="form.webSearch" /></el-form-item>
            <el-form-item label="长期记忆"><el-switch v-model="form.memoryEnabled" /></el-form-item>
            <el-form-item label="知识库">
              <el-select v-model="form.collectionIds" multiple filterable style="width: 100%">
                <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="只从知识库回复"><el-switch v-model="form.kbOnlyReply" /></el-form-item>
          </el-form>
        </section>

        <section v-if="advancedOpen" class="col">
          <h3>企业导出品牌（可选）</h3>
          <el-form label-position="top">
            <el-form-item label="公司 / 组织名称">
              <el-input v-model="exportBranding.exportCompanyName" placeholder="如：某某科技有限公司" />
            </el-form-item>
            <el-form-item label="文档编号前缀">
              <el-input v-model="exportBranding.exportDocIdPrefix" placeholder="HT / PPT / BID" maxlength="16" />
            </el-form-item>
            <el-form-item label="页脚说明">
              <el-input v-model="exportBranding.exportFooterNote" type="textarea" :rows="2" placeholder="留空使用系统默认审核提示" />
            </el-form-item>
            <template v-if="form.primaryScenario === 'ppt_authoring'">
              <el-form-item label="PPT 企业主色">
                <div class="color-setting">
                  <el-color-picker v-model="exportBranding.pptPrimaryColor" />
                  <el-input v-model="exportBranding.pptPrimaryColor" maxlength="7" />
                </div>
              </el-form-item>
              <el-form-item label="PPT 强调色">
                <div class="color-setting">
                  <el-color-picker v-model="exportBranding.pptAccentColor" />
                  <el-input v-model="exportBranding.pptAccentColor" maxlength="7" />
                </div>
              </el-form-item>
            </template>
          </el-form>
        </section>

        <section v-if="accessOpen" class="col">
          <h3>发布与授权</h3>
          <div class="readiness-card" :class="{ ok: readinessWarnings.length === 0 }">
            <div class="readiness-title">{{ readinessWarnings.length === 0 ? '可以直接发布' : '请检查当前设置' }}</div>
            <ul v-if="readinessWarnings.length"><li v-for="w in readinessWarnings" :key="w">{{ w }}</li></ul>
            <p v-else>发布后仍可随时修改名称、能力和授权范围。</p>
          </div>
          <el-form label-position="top">
            <el-form-item label="权限">
              <el-radio-group v-model="form.visibility">
                <el-radio value="public">企业内公开（默认）</el-radio>
                <el-radio value="restricted">受限（按部门授权）</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.visibility === 'restricted'" label="授权部门（可选）">
              <div class="acl-row">
                <el-select v-model="selectedDeptId" filterable placeholder="选择部门" style="flex: 1">
                  <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
                </el-select>
                <el-button @click="addDeptAcl">添加</el-button>
              </div>
              <p class="hint">不添加部门时，仅创建人和管理员可使用。</p>
              <ul class="acl-list">
                <li v-for="(a, i) in form.aclEntries" :key="i">
                  {{ a.principalType }} #{{ a.principalId }}
                  <el-button link type="danger" @click="removeAcl(i)">删除</el-button>
                </li>
              </ul>
            </el-form-item>
          </el-form>
        </section>
      </div>
    </div>

    <footer class="foot">
      <el-button @click="close">关闭</el-button>
      <el-button :loading="saving" @click="saveDraft">保存</el-button>
      <el-button type="primary" :loading="saving" @click="publish">{{ isEdit ? '保存并发布' : '创建并发布' }}</el-button>
    </footer>
  </div>
</template>

<style scoped>
/* 主布局 page-body 为 overflow:hidden，本页需占满高度并在中间区域滚动 */
.editor {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  max-width: 1400px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
}
.editor-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 24px 24px 8px;
  -webkit-overflow-scrolling: touch;
}
.editor-scroll h1 {
  margin: 0;
  font-size: 22px;
}
.page-intro {
  margin: 8px 0 20px;
  color: #667085;
  font-size: 14px;
}
.quick-create {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.section-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}
.section-title span {
  color: #667085;
  font-size: 12px;
}
.section-title h3 {
  margin: 4px 0 0;
  color: #101828;
  font-size: 18px;
}
.optional-badge {
  padding: 5px 9px;
  color: #4056e8 !important;
  border-radius: 20px;
  background: #eef2ff;
}
.scenario-summary {
  display: flex;
  flex-direction: column;
  margin-top: 14px;
  padding: 16px;
  border: 1px solid #e2e7f5;
  border-radius: 12px;
  background: #f8faff;
}
.scenario-summary strong {
  color: #1d2939;
}
.scenario-summary span {
  margin-top: 6px;
  color: #475467;
  font-size: 13px;
}
.scenario-summary small {
  margin-top: 10px;
  color: #7c8aa5;
}
.settings-toggle-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.settings-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  text-align: left;
  border: 1px solid #e4e7ec;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
}
.settings-toggle:hover {
  border-color: #9aa8ff;
  box-shadow: 0 8px 24px rgba(48, 66, 140, 0.06);
}
.settings-toggle span {
  display: flex;
  flex-direction: column;
}
.settings-toggle strong {
  color: #1d2939;
  font-size: 14px;
}
.settings-toggle small {
  margin-top: 5px;
  color: #667085;
}
.settings-toggle b {
  color: #4056e8;
  font-size: 12px;
}
.cols {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-top: 16px;
}
.advanced-cols {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.col {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px;
}
.col h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.prompt-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 13px;
  color: #6b7280;
}
.acl-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.acl-list {
  margin: 8px 0 0;
  padding-left: 0;
  list-style: none;
  font-size: 13px;
}
.readiness-card {
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid #fed7aa;
  background: #fff7ed;
  border-radius: 10px;
  color: #9a3412;
}
.readiness-card.ok {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #166534;
}
.readiness-title {
  font-weight: 700;
  margin-bottom: 6px;
}
.readiness-card ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.6;
  font-size: 12px;
}
.readiness-card p {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #6b7280;
}
.color-setting {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.color-setting .el-input {
  flex: 1;
}
.foot {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  gap: 12px;
  padding: 16px 24px 24px;
  background: var(--bg-page, #f5f7fa);
  border-top: 1px solid #e5e7eb;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.04);
}
@media (max-width: 1100px) {
  .cols,
  .quick-create,
  .settings-toggle-row {
    grid-template-columns: 1fr;
  }
}
</style>
