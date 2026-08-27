<script setup lang="ts">
/**
 * 知识库详情页 · 任务 15
 * 显示库内文档列表 + 上传到此库 + 移出文档
 */
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus, Delete, Document, Refresh, Link, DocumentCopy, Lock, Hide, View, OfficeBuilding, User, Close, CircleCheckFilled, EditPen, Search, MagicStick, Trophy, Microphone, Setting, Share } from '@element-plus/icons-vue'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { voiceApi, type VoicePersona } from '@/api/voice'
import { personaApi } from '@/api/persona'
import { skillPackApi } from '@/api/skillPack'
import { coachRuleApi, type CoachRule } from '@/api/coachRule'
import { knowledgeApi } from '@/api/knowledge'
import { useUploadStore } from '@/stores/upload'
import { apiKeyApi, type ApiKey } from '@/api/apiKey'
import { kbAclApi, positionApi, departmentApi, type Position, type Department } from '@/api/orgAcl'

const route = useRoute()
const router = useRouter()
const uploadStore = useUploadStore()

const collId = computed(() => Number(route.params.id))
const collection = ref<KnowledgeCollection | null>(null)
const docs = ref<any[]>([])
const loading = ref(false)

// 文档检索 · 文档多时按名称/类型/分类过滤 + 状态筛选（纯前端，全量已加载）
const docSearch = ref('')
const docStatusFilter = ref('')
// 文档分类功能暂时下线（置 true 即可恢复）
const SHOW_CATEGORY = false
const filteredDocs = computed(() => {
  const kw = docSearch.value.trim().toLowerCase()
  return docs.value.filter(d => {
    if (docStatusFilter.value && d.status !== docStatusFilter.value) return false
    if (!kw) return true
    return [d.name, d.fileType, d.category]
      .some(v => String(v ?? '').toLowerCase().includes(kw))
  })
})

// 前端分页：文档几千条时只渲染当前页，避免 el-table 一次渲染上千行卡死
const docPage = ref(1)
const docPageSize = 50
const pagedDocs = computed(() => {
  const start = (docPage.value - 1) * docPageSize
  return filteredDocs.value.slice(start, start + docPageSize)
})
// 搜索/筛选变化时回到第 1 页
watch([docSearch, docStatusFilter], () => { docPage.value = 1 })

const loadAll = async () => {
  loading.value = true
  try {
    const [c, ds]: any = await Promise.all([
      collectionApi.get(collId.value),
      collectionApi.listDocs(collId.value),
    ])
    collection.value = c?.data ?? c
    docs.value = ds?.data ?? ds ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { loading.value = false }
}

const removeFromCollection = async (doc: any) => {
  await ElMessageBox.confirm(
    `把「${doc.name}」从本知识库移出？\n文档本身不会被删除，会变成「散文档」可重新归类。`,
    '提示',
    { type: 'warning' }
  )
  try {
    await collectionApi.remove(collId.value, [doc.id])
    ElMessage.success('已移出')
    await loadAll()
  } catch (e: any) {
    ElMessage.error('操作失败：' + (e?.message || ''))
  }
}

const deleteDoc = async (doc: any) => {
  await ElMessageBox.confirm(
    `永久删除文档「${doc.name}」？此操作不可恢复（含 Milvus 向量）`,
    '危险操作',
    { type: 'error', confirmButtonText: '确认删除' }
  )
  try {
    await knowledgeApi.delete(doc.id)
    ElMessage.success('已删除')
    await loadAll()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

const openUploadDialog = () => {
  // 复用全局上传 dock · 上传时把当前 collectionId 带上
  uploadStore.targetCollectionId = collId.value
  uploadStore.dialogVisible = true
}

// ─── API 接入调试样例（钉钉 HTTP 模式 + curl） ───────────
const apiOrigin = window.location.origin
const curlExample = computed(() =>
  `curl -X POST ${apiOrigin}/api/v3/chat \\\n` +
  `  -H "Authorization: Bearer mk_xxx" \\\n` +
  `  -H "Content-Type: application/json" \\\n` +
  `  -d '{"question":"你好，介绍一下","collectionId":${collId.value}}'`
)
const dingtalkCallbackUrl = computed(() => `${apiOrigin}/api/dingtalk/callback`)
const copyText = async (t: string) => {
  try { await navigator.clipboard.writeText(t); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败，请手动选择复制') }
}

// ─── 专属人格（#2 · 在详情页可编辑，不再只能创建时选） ───────────
const personas = ref<any[]>([])
const loadPersonas = async () => {
  try {
    const data: any = await personaApi.list()
    personas.value = (data?.data ?? data ?? []).filter((p: any) => p.enabled !== 0)
  } catch { /* 人格加载失败不阻断主页面 */ }
}
const currentPersonaName = computed(() => {
  const pid = collection.value?.personaId
  if (!pid) return '全局默认人格'
  const p = personas.value.find((x: any) => x.id === pid)
  return p ? p.name : `人格 #${pid}`
})
const personaForm = ref<{ personaId: number | null }>({ personaId: null })
// ─── 统一「知识库管理」弹窗：人格/技能/音色 + 教练/权限 双栏一屏平铺 ───
const manageDialogVisible = ref(false)
const pendingSection = ref('persona')   // 打开时高亮的小节（统计卡片快捷点选用）

// 打开统一弹窗；一次性把各小节的表单与数据准备好
const openManageDialog = (section = 'persona') => {
  personaForm.value.personaId = collection.value?.personaId || null
  skillForm.value.skillPackId = collection.value?.skillPackId || null
  voiceForm.value.voiceIds = parseVoiceIds(collection.value?.voiceIds)
  prepCoachForm()
  loadAclData()
  pendingSection.value = section
  manageDialogVisible.value = true
}

// 双栏一屏，无需滚动；从统计卡片点进来时，把对应小节闪烁高亮一下做定位提示
const flashSection = ref('')
const scrollToPendingSection = () => {
  if (!pendingSection.value) return
  flashSection.value = pendingSection.value
  setTimeout(() => { flashSection.value = '' }, 1400)
}

// 统计卡片快捷点选 → 打开统一弹窗并高亮对应小节
const openPersonaDialog = () => openManageDialog('persona')
const openSkillDialog = () => openManageDialog('skill')
const openVoiceDialog = () => openManageDialog('voice')
const openCoachDialog = () => openManageDialog('coach')
const openAclDialog = () => openManageDialog('acl')

// 统一保存：人格/技能/音色/教练 一次提交（权限为即时生效，不在此列）
const savingAll = ref(false)
const saveAll = async () => {
  if (!collection.value) return
  if (!personaForm.value.personaId) { ElMessage.warning('请先选择专属人格（不能为空）'); return }
  if (!coachForm.value.coachRuleId) { ElMessage.warning('请先选择教练规则（不能为空）'); return }
  savingAll.value = true
  try {
    await collectionApi.update(collId.value, {
      personaId: personaForm.value.personaId,
      skillPackId: skillForm.value.skillPackId || 0,   // 0 = 清除技能绑定
      voiceIds: voiceForm.value.voiceIds.join(','),     // 空串 = 不限制音色
      coachRuleId: coachForm.value.coachRuleId,
    } as any)
    ElMessage.success('已保存知识库配置')
    manageDialogVisible.value = false
    await loadAll()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { savingAll.value = false }
}

// ─── 专属技能（知识库级 · 单选此库对话时套用该技能指令） ───────────
const skillPacks = ref<any[]>([])
const loadSkillPacks = async () => {
  try {
    const data: any = await skillPackApi.usable()
    skillPacks.value = (data?.data ?? data ?? []).filter((s: any) => s.enabled !== 0)
  } catch { /* 技能加载失败不阻断主页面 */ }
}
const currentSkillName = computed(() => {
  const sid = collection.value?.skillPackId
  if (!sid) return '未设置（不套用）'
  const s = skillPacks.value.find((x: any) => x.id === sid)
  return s ? s.name : `技能 #${sid}`
})
const skillForm = ref<{ skillPackId: number | null }>({ skillPackId: null })

// ─── 音色配置（知识库级 · 单选此库打电话时限定可选音色，可多选） ───────────
const voices = ref<VoicePersona[]>([])
const loadVoices = async () => {
  try {
    const data: any = await voiceApi.voices()
    voices.value = (data?.data ?? data ?? []).filter((v: any) => v.enabled !== 0)
  } catch { /* 音色加载失败不阻断主页面 */ }
}
/** collection.voiceIds 是逗号分隔字符串 → number[] */
const parseVoiceIds = (s?: string | null): number[] =>
  !s ? [] : s.split(',').map(x => Number(x.trim())).filter(n => !Number.isNaN(n))
const currentVoiceLabel = computed(() => {
  const ids = parseVoiceIds(collection.value?.voiceIds)
  if (!ids.length) return '未配置（全部音色）'
  const names = ids.map(id => voices.value.find(v => v.id === id)?.name || `#${id}`)
  return names.length <= 2 ? names.join('、') : `${names.length} 个音色`
})
const voiceForm = ref<{ voiceIds: number[] }>({ voiceIds: [] })

// ─── 教练规则（知识库级 · 从「教练规则」管理页下拉选一个） ───────────
const coachRules = ref<CoachRule[]>([])
const loadCoachRules = async () => {
  try {
    const data: any = await coachRuleApi.usable()
    coachRules.value = (data?.data ?? data ?? []).filter((c: any) => c.enabled !== 0)
  } catch { /* 加载失败不阻断主页面 */ }
}
const currentCoachLabel = computed(() => {
  const cid = collection.value?.coachRuleId
  if (!cid) return '未设置（默认出题）'
  const c = coachRules.value.find((x: any) => x.id === cid)
  return c ? c.name : `规则 #${cid}`
})
const coachForm = ref<{ coachRuleId: number | null }>({ coachRuleId: null })
const prepCoachForm = () => {
  coachForm.value.coachRuleId = collection.value?.coachRuleId || null
}

const STATUS_LABEL: Record<string, { txt: string; type: string }> = {
  uploading:  { txt: '上传中', type: 'info' },
  processing: { txt: '处理中', type: 'warning' },
  rebuild_queued: { txt: '等待重建', type: 'warning' },
  rebuilding: { txt: '重建中', type: 'warning' },
  ready:      { txt: '就绪',   type: 'success' },
  failed:     { txt: '失败',   type: 'danger' },
  rebuild_failed: { txt: '重建失败', type: 'danger' },
}

const formatSize = (bytes?: number) => {
  if (!bytes) return '-'
  const kb = bytes / 1024
  if (kb < 1024) return kb.toFixed(1) + ' KB'
  return (kb / 1024).toFixed(2) + ' MB'
}
const formatDate = (s?: string) => {
  if (!s) return '-'
  return new Date(s).toLocaleString('zh-CN').replace(/\//g, '-')
}

// ─── 权限管理（任务 15.2 · 库级 ACL + 可见性） ───────────
const aclLoading = ref(false)
const currentVisibility = ref<'public' | 'scoped' | 'private'>('public')

interface AclEntry { positionId?: number | null; departmentId?: number | null; permission: 'read' | 'write' | 'admin' }
const aclEntries = ref<AclEntry[]>([])
const allPositions = ref<Position[]>([])
const allDepartments = ref<Department[]>([])

const addType = ref<'position' | 'department'>('position')
const newPositionId = ref<number | null>(null)
const newDepartmentId = ref<number | null>(null)
const newPermission = ref<'read' | 'write' | 'admin'>('read')

const VIS_OPTIONS = [
  { value: 'public', icon: 'View', title: '公开', desc: '所有登录用户可读' },
  { value: 'scoped', icon: 'Lock', title: '部门可见', desc: '按下方授权规则' },
  { value: 'private', icon: 'Hide', title: '仅自己', desc: '只有创建者可见' },
] as const

const loadAclData = async () => {
  currentVisibility.value = (collection.value?.visibility ?? 'public') as any
  aclLoading.value = true
  // 分别 try · 这样能确切知道是哪个 API 挂了
  try {
    const aclRes: any = await kbAclApi.listCollectionAcl(collId.value)
    const acls = aclRes?.data ?? aclRes ?? []
    aclEntries.value = acls.map((a: any) => ({
      positionId: a.positionId, departmentId: a.departmentId, permission: a.permission,
    }))
  } catch (e: any) {
    console.error('[ACL] listCollectionAcl 失败', e)
    ElMessage.error('加载授权列表失败：' + (e?.response?.data?.message || e?.message || ''))
    aclEntries.value = []
  }
  if (allPositions.value.length === 0) {
    try {
      const pRes: any = await positionApi.list()
      allPositions.value = pRes?.data ?? pRes ?? []
    } catch (e: any) {
      console.error('[ACL] 加载职位失败', e)
      ElMessage.warning('职位字典加载失败：' + (e?.response?.data?.message || e?.message || ''))
    }
  }
  if (allDepartments.value.length === 0) {
    try {
      const dRes: any = await departmentApi.list()
      allDepartments.value = dRes?.data ?? dRes ?? []
    } catch (e: any) {
      console.error('[ACL] 加载部门失败', e)
      ElMessage.warning('部门字典加载失败：' + (e?.response?.data?.message || e?.message || ''))
    }
  }
  aclLoading.value = false
}

const changeVisibility = async (v: 'public' | 'scoped' | 'private') => {
  if (currentVisibility.value === v) return
  try {
    await collectionApi.update(collId.value, { visibility: v })
    currentVisibility.value = v
    if (collection.value) collection.value.visibility = v
    ElMessage.success('已切换可见性')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.response?.data?.message || e?.message || ''))
  }
}

const positionLabel = (id: number | null | undefined) => {
  if (!id) return ''
  const p = allPositions.value.find(x => x.id === id)
  return p ? `${p.name} (${p.code})` : `#${id}`
}
const departmentLabel = (id: number | null | undefined) => {
  if (!id) return ''
  return allDepartments.value.find(x => x.id === id)?.name || `#${id}`
}
const aclSubjectLabel = (e: AclEntry) =>
  e.departmentId ? departmentLabel(e.departmentId) : positionLabel(e.positionId)

const addAcl = () => {
  const subjectExists = (e: AclEntry) =>
    (addType.value === 'position' && e.positionId === newPositionId.value)
    || (addType.value === 'department' && e.departmentId === newDepartmentId.value)
  if (aclEntries.value.some(subjectExists)) {
    ElMessage.warning('该对象已授权')
    return
  }
  if (addType.value === 'position' && newPositionId.value) {
    aclEntries.value.push({ positionId: newPositionId.value, departmentId: null, permission: newPermission.value })
    newPositionId.value = null
  } else if (addType.value === 'department' && newDepartmentId.value) {
    aclEntries.value.push({ positionId: null, departmentId: newDepartmentId.value, permission: newPermission.value })
    newDepartmentId.value = null
  }
  saveAcls()
}
const removeAclAt = (idx: number) => {
  aclEntries.value.splice(idx, 1)
  saveAcls()
}
const saveAcls = async () => {
  try {
    await kbAclApi.replaceCollectionAcl(collId.value, aclEntries.value as any)
  } catch (e: any) {
    ElMessage.error('保存权限失败：' + (e?.message || ''))
  }
}

// ─── API Key 管理（任务 15·按知识库授权） ───────────
const apiKeyDialogVisible = ref(false)
const apiKeysLoading = ref(false)
const apiKeys = ref<ApiKey[]>([])

const issueFormVisible = ref(false)
const issuing = ref(false)
const issueForm = ref<{ name: string; monthlyQuota: number; expireAt?: string; description?: string }>({
  name: '', monthlyQuota: 10000, expireAt: undefined, description: '',
})

const issuedResultVisible = ref(false)
const issuedRawKey = ref('')

const openApiKeyDialog = async () => {
  apiKeyDialogVisible.value = true
  await loadKeys()
}
const loadKeys = async () => {
  apiKeysLoading.value = true
  try {
    const res: any = await apiKeyApi.byCollection(collId.value)
    apiKeys.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { apiKeysLoading.value = false }
}
const revokeKey = async (k: ApiKey) => {
  await ElMessageBox.confirm(`确认吊销「${k.name}」？立即失效不可恢复。`, '警告', { type: 'warning' })
  try {
    await apiKeyApi.revoke(k.id)
    ElMessage.success('已吊销')
    await loadKeys()
  } catch (e: any) {
    ElMessage.error('吊销失败：' + (e?.message || ''))
  }
}
const openIssueDialog = () => {
  issueForm.value = { name: '', monthlyQuota: 10000, expireAt: undefined, description: '' }
  issueFormVisible.value = true
}
const doIssue = async () => {
  if (!issueForm.value.name.trim()) { ElMessage.warning('请填 Key 名称'); return }
  issuing.value = true
  try {
    const res: any = await apiKeyApi.issue({
      name: issueForm.value.name.trim(),
      allowedCollectionIds: [collId.value],   // 任务 15：按知识库授权
      monthlyQuota: issueForm.value.monthlyQuota,
      expireAt: issueForm.value.expireAt,
      description: issueForm.value.description,
    })
    const data = res?.data ?? res
    issuedRawKey.value = data?.rawKey || ''
    issuedResultVisible.value = true
    issueFormVisible.value = false
    await loadKeys()
  } catch (e: any) {
    ElMessage.error('生成失败：' + (e?.response?.data?.message || e?.message || ''))
  } finally { issuing.value = false }
}
const copyRawKey = async () => {
  await navigator.clipboard.writeText(issuedRawKey.value)
  ElMessage.success('已复制到剪贴板')
}
const ackIssuedResult = () => {
  issuedRawKey.value = ''
  issuedResultVisible.value = false
}

onMounted(() => {
  loadAll()
  loadPersonas()
  loadSkillPacks()
  loadVoices()
  loadCoachRules()
  // 上传成功后自动刷新列表 + 计数器
  unregisterUploadCb = uploadStore.onItemDone(() => loadAll())
})

let unregisterUploadCb: (() => void) | null = null
onBeforeUnmount(() => {
  unregisterUploadCb?.()
})
</script>

<template>
  <div class="coll-detail" v-loading="loading">
    <header class="header">
      <button class="back-btn" @click="router.push('/collections')">
        <el-icon size="14"><ArrowLeft /></el-icon> 返回
      </button>
      <div class="title-block" v-if="collection">
        <div
          class="title-icon"
          :style="{ background: (collection.color || '#0a0a0a') + '18', color: collection.color }"
        >
          <el-icon size="18"><component :is="collection.icon || 'FolderOpened'" /></el-icon>
        </div>
        <div>
          <h2 class="title">{{ collection.name }}</h2>
          <p class="sub">{{ collection.description || '暂无描述' }}</p>
        </div>
      </div>
      <div class="header-right">
        <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
        <!-- 五项配置合并进同一弹窗（tab 切换），点开即用，无需先选 -->
        <el-button :icon="Setting" @click="openManageDialog()">知识库管理</el-button>
        <el-button :icon="Link" @click="openApiKeyDialog">API 接入</el-button>
        <el-button :icon="Share" @click="router.push({ path: '/knowledge-graph', query: { collectionId: collId } })">知识图谱</el-button>
        <el-button type="primary" :icon="Plus" @click="openUploadDialog">上传到此库</el-button>
      </div>
    </header>

    <!-- 知识库管理：双栏左右分栏，一屏完整展示，底部统一保存/关闭 -->
    <el-dialog
      v-model="manageDialogVisible"
      :title="`知识库管理 · ${collection?.name || ''}`"
      width="940px"
      class="manage-dialog"
      :close-on-click-modal="false"
      @opened="scrollToPendingSection"
    >
      <div class="manage-grid">
        <!-- 左栏：人格 / 技能 / 音色 / 教练 -->
        <div class="manage-col">
          <section id="manage-sec-persona" class="manage-section" :class="{ 'sec-flash': flashSection === 'persona' }">
            <div class="manage-section-head"><el-icon><User /></el-icon><span>人格设置</span></div>
            <div class="field-label">专属人格</div>
            <el-select
              v-model="personaForm.personaId"
              clearable
              placeholder="（默认）跟随全局默认人格"
              style="width: 100%"
            >
              <el-option
                v-for="p in personas"
                :key="p.id"
                :label="p.name + (p.isDefault ? '（全局默认）' : '')"
                :value="p.id"
              />
            </el-select>
          </section>

          <section id="manage-sec-skill" class="manage-section" :class="{ 'sec-flash': flashSection === 'skill' }">
            <div class="manage-section-head"><el-icon><MagicStick /></el-icon><span>技能设置</span></div>
            <div class="field-label">专属技能</div>
            <el-select
              v-model="skillForm.skillPackId"
              clearable
              placeholder="（默认）不套用技能"
              style="width: 100%"
            >
              <el-option
                v-for="s in skillPacks"
                :key="s.id"
                :label="s.name"
                :value="s.id"
              />
            </el-select>
          </section>

          <section id="manage-sec-voice" class="manage-section" :class="{ 'sec-flash': flashSection === 'voice' }">
            <div class="manage-section-head"><el-icon><Microphone /></el-icon><span>音色配置</span></div>
            <div class="field-label">可用音色（可多选）</div>
            <el-select
              v-model="voiceForm.voiceIds"
              multiple
              clearable
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="（默认）不限制 · 全部音色可选"
              style="width: 100%"
            >
              <el-option
                v-for="v in voices"
                :key="v.id"
                :label="v.name + (v.isDefault === 1 ? '（默认）' : '')"
                :value="v.id"
              />
            </el-select>
          </section>

          <section id="manage-sec-coach" class="manage-section" :class="{ 'sec-flash': flashSection === 'coach' }">
            <div class="manage-section-head"><el-icon><Trophy /></el-icon><span>教练规则</span></div>
            <div class="field-label">教练规则</div>
            <el-select
              v-model="coachForm.coachRuleId"
              clearable
              placeholder="（默认）按四种题型均衡出题"
              style="width: 100%"
            >
              <el-option
                v-for="c in coachRules"
                :key="c.id"
                :label="c.name"
                :value="c.id"
              >
                <span>{{ c.name }}</span>
                <span v-if="c.description" style="margin-left:8px;font-size:12px;color:var(--ink-3)">{{ c.description }}</span>
              </el-option>
            </el-select>
          </section>
        </div>

        <!-- 右栏：权限管理（即时生效） -->
        <div class="manage-col">
          <section id="manage-sec-acl" class="manage-section manage-section-acl" :class="{ 'sec-flash': flashSection === 'acl' }">
            <div class="manage-section-head">
              <el-icon><Lock /></el-icon><span>权限管理</span>
              <span class="head-tip">即时生效，无需保存</span>
            </div>
      <!-- 可见性切换 -->
      <div class="acl-section">
        <div class="section-title">可见性</div>
        <div class="vis-cards">
          <button
            v-for="v in VIS_OPTIONS"
            :key="v.value"
            class="vis-card"
            :class="{ active: currentVisibility === v.value }"
            @click="changeVisibility(v.value)"
          >
            <el-icon size="18" class="vis-icon"><component :is="v.icon" /></el-icon>
            <div class="vis-card-body">
              <div class="vis-card-title">{{ v.title }}</div>
              <div class="vis-card-desc">{{ v.desc }}</div>
            </div>
            <el-icon v-if="currentVisibility === v.value" size="14" class="vis-check"><CircleCheckFilled /></el-icon>
          </button>
        </div>
      </div>

      <!-- 授权对象（仅 scoped 时显示）-->
      <div v-if="currentVisibility === 'scoped'" class="acl-section">
        <div class="section-title">授权对象</div>
        <div v-loading="aclLoading">
          <div class="acl-entries">
          <div v-for="(e, idx) in aclEntries" :key="idx" class="acl-row">
            <span class="acl-subject-tag" :class="e.departmentId ? 'dept' : 'pos'">
              <el-icon size="11"><component :is="e.departmentId ? 'OfficeBuilding' : 'User'" /></el-icon>
              {{ e.departmentId ? '部门' : '职位' }}
            </span>
            <span class="acl-name">{{ aclSubjectLabel(e) }}</span>
            <el-select v-model="e.permission" size="small" style="width:90px" @change="saveAcls">
              <el-option label="read" value="read" />
              <el-option label="write" value="write" />
              <el-option label="admin" value="admin" />
            </el-select>
            <button class="acl-del" @click="removeAclAt(idx)" title="移除">
              <el-icon size="12"><Close /></el-icon>
            </button>
          </div>
          </div>

          <div class="acl-add-block">
            <el-radio-group v-model="addType" size="small">
              <el-radio-button value="position">按职位</el-radio-button>
              <el-radio-button value="department">按部门</el-radio-button>
            </el-radio-group>
            <div class="acl-add-row">
              <el-select v-if="addType === 'position'" v-model="newPositionId" placeholder="选择职位" size="small" style="flex:1" clearable filterable>
                <el-option v-for="p in allPositions" :key="p.id" :label="`${p.name} (${p.code})`" :value="p.id" />
              </el-select>
              <el-select v-else v-model="newDepartmentId" placeholder="选择部门" size="small" style="flex:1" clearable filterable>
                <el-option v-for="d in allDepartments" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
              <el-select v-model="newPermission" size="small" style="width:90px">
                <el-option label="read" value="read" />
                <el-option label="write" value="write" />
                <el-option label="admin" value="admin" />
              </el-select>
              <el-button size="small" type="primary" @click="addAcl"
                         :disabled="addType === 'position' ? !newPositionId : !newDepartmentId">添加</el-button>
            </div>
          </div>
          <div class="acl-tip">
            <strong>职位级</strong>·精确到单个角色 · <strong>部门级</strong>·覆盖该部门 + 所有子部门<br />
            read 仅可问答检索 · write 可上传修改 · admin 可删库及再授权
          </div>
        </div>
      </div>

          </section>
        </div>
      </div>
      <template #footer>
        <el-button @click="manageDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="savingAll" @click="saveAll">保存配置</el-button>
      </template>
    </el-dialog>

    <!-- API Key 管理弹窗 · 任务 15：按知识库授权 -->
    <el-dialog
      v-model="apiKeyDialogVisible"
      :title="`API 接入 · ${collection?.name || ''}`"
      width="640px"
      :close-on-click-modal="false"
    >
      <p class="hint">
        为这个【知识库】颁发<strong>独立的 API Key</strong> 给第三方系统。
        Key 只能访问本库的全部文档，<strong>关闭弹窗后无法再看到完整 key</strong>。
      </p>

      <div v-loading="apiKeysLoading" class="api-keys-list">
        <article v-for="k in apiKeys" :key="k.id" class="api-key-card" :class="`status-${k.status}`">
          <header>
            <div>
              <span class="api-key-name">{{ k.name }}</span>
              <el-tag size="small" :type="k.status === 'active' ? 'success' : k.status === 'revoked' ? 'danger' : 'info'" effect="light" style="margin-left:6px">
                {{ k.status === 'active' ? '生效中' : k.status === 'revoked' ? '已吊销' : '已过期' }}
              </el-tag>
            </div>
            <el-button v-if="k.status === 'active'" link size="small" type="danger" @click="revokeKey(k)">吊销</el-button>
          </header>
          <div class="api-key-meta">
            <code>{{ k.keyPrefix }}…</code>
            <span>月配额 {{ k.monthUsed }} / {{ k.monthlyQuota }}</span>
            <span>累计 {{ k.totalCalls }} 次</span>
            <span v-if="k.expireAt">过期 {{ k.expireAt.slice(0, 10) }}</span>
          </div>
        </article>
        <div v-if="!apiKeysLoading && !apiKeys.length" class="empty">
          这个知识库还没有 API Key
        </div>
      </div>

      <!-- 调试样例 / 接入说明 -->
      <div class="api-debug">
        <div class="api-debug-title">调试样例（把 <code>mk_xxx</code> 换成上面生成的 Key）</div>

        <div class="api-debug-block">
          <div class="adb-head">
            <span>① 对外问答 · POST /api/v3/chat</span>
            <el-button text size="small" @click="copyText(curlExample)">复制</el-button>
          </div>
          <pre>{{ curlExample }}</pre>
        </div>

        <div class="api-debug-block">
          <div class="adb-head">
            <span>② 钉钉机器人回调地址（HTTP 模式）</span>
            <el-button text size="small" @click="copyText(dingtalkCallbackUrl)">复制</el-button>
          </div>
          <pre>{{ dingtalkCallbackUrl }}</pre>
          <div class="adb-note">
            在钉钉开放平台机器人「消息接收地址」填此 URL；服务端 <code>.env</code> 配置：
            <code>DINGTALK_ENABLED=true</code>、<code>DINGTALK_APP_SECRET=机器人加签密钥</code>、
            <code>DINGTALK_API_KEY=上面生成的Key</code>、<code>DINGTALK_COLLECTION_ID={{ collId }}</code>，
            重启后端生效。
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="apiKeyDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Plus" @click="openIssueDialog">生成新 API Key</el-button>
      </template>
    </el-dialog>

    <!-- 生成 API Key 表单 -->
    <el-dialog v-model="issueFormVisible" :title="`为「${collection?.name}」生成 API Key`" width="500px" :close-on-click-modal="false">
      <el-form :model="issueForm" label-width="100px">
        <el-form-item label="Key 名称" required>
          <el-input v-model="issueForm.name" placeholder="如 客户 X 接入" maxlength="40" show-word-limit />
        </el-form-item>
        <el-form-item label="月配额">
          <el-input-number v-model="issueForm.monthlyQuota" :min="100" :max="1000000" :step="1000" />
          <span style="font-size:11px;color:#94a3b8;margin-left:6px">次/月</span>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="issueForm.expireAt" type="date" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="留空 = 永不过期" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="issueForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" @click="doIssue">生成</el-button>
      </template>
    </el-dialog>

    <!-- 生成结果（仅一次显示完整 key） -->
    <el-dialog v-model="issuedResultVisible" title="⚠ 完整 API Key（仅显示一次）" width="560px" :show-close="false" :close-on-click-modal="false">
      <p class="warn-text">请立即复制保存。关闭后将无法再看到完整 key，仅能看前缀。</p>
      <div class="raw-key-box">
        <code>{{ issuedRawKey }}</code>
        <el-button :icon="DocumentCopy" @click="copyRawKey">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="ackIssuedResult">我已保存</el-button>
      </template>
    </el-dialog>
    <!-- /API Key 相关弹窗 -->

    <!-- 库统计 -->
    <div v-if="collection" class="stats-row">
      <div class="stat-pill">
        <span class="stat-label">文档</span>
        <span class="stat-val">{{ collection.docCount }}</span>
      </div>
      <div class="stat-pill">
        <span class="stat-label">切片</span>
        <span class="stat-val">{{ collection.totalChunks }}</span>
      </div>
      <div class="stat-pill">
        <span class="stat-label">可见性</span>
        <span class="stat-val">
          {{ collection.visibility === 'public' ? '公开' : collection.visibility === 'scoped' ? '部门可见' : '仅自己' }}
        </span>
      </div>
      <div class="stat-pill stat-pill-clickable" title="点击编辑专属人格" @click="openPersonaDialog">
        <span class="stat-label">专属人格</span>
        <span class="stat-val">{{ currentPersonaName }}</span>
        <el-icon size="12" class="stat-edit"><EditPen /></el-icon>
      </div>
      <div class="stat-pill stat-pill-clickable" title="点击编辑专属技能" @click="openSkillDialog">
        <span class="stat-label">专属技能</span>
        <span class="stat-val">{{ currentSkillName }}</span>
        <el-icon size="12" class="stat-edit"><EditPen /></el-icon>
      </div>
      <div class="stat-pill stat-pill-clickable" title="点击配置可用音色" @click="openVoiceDialog">
        <span class="stat-label">音色配置</span>
        <span class="stat-val">{{ currentVoiceLabel }}</span>
        <el-icon size="12" class="stat-edit"><EditPen /></el-icon>
      </div>
      <div class="stat-pill stat-pill-clickable" title="点击编辑教练出题规则" @click="openCoachDialog">
        <span class="stat-label">教练规则</span>
        <span class="stat-val">{{ currentCoachLabel }}</span>
        <el-icon size="12" class="stat-edit"><EditPen /></el-icon>
      </div>
    </div>

    <!-- 文档检索 -->
    <div v-if="docs.length" class="doc-toolbar">
      <el-input
        v-model="docSearch"
        placeholder="搜索文档名 / 类型…"
        clearable
        size="default"
        style="max-width:340px"
        :prefix-icon="Search"
      />
      <el-select v-model="docStatusFilter" placeholder="全部状态" clearable size="default" style="width:130px">
        <el-option label="就绪"   value="ready" />
        <el-option label="处理中" value="processing" />
        <el-option label="等待重建" value="rebuild_queued" />
        <el-option label="重建中" value="rebuilding" />
        <el-option label="失败"   value="failed" />
        <el-option label="重建失败" value="rebuild_failed" />
      </el-select>
      <span class="doc-count">{{ filteredDocs.length }} / {{ docs.length }} 个文档</span>
    </div>

    <!-- 文档列表 -->
    <el-table :data="pagedDocs" stripe>
      <el-table-column label="文档" min-width="280">
        <template #default="{ row }">
          <div class="doc-cell">
            <el-icon size="16" color="#0a0a0a"><Document /></el-icon>
            <div>
              <div class="doc-name">{{ row.name }}</div>
              <div class="doc-sub">{{ row.fileType?.toUpperCase() }} · {{ formatSize(row.fileSize) }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="SHOW_CATEGORY" label="分类" prop="category" width="120">
        <template #default="{ row }">{{ row.category || '-' }}</template>
      </el-table-column>
      <el-table-column label="切片" prop="chunkCount" width="80" align="right" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_LABEL[row.status]?.type as any" size="small">
            {{ STATUS_LABEL[row.status]?.txt || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">{{ formatDate(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <div class="row-ops">
            <el-button link size="small" class="op-move" @click="removeFromCollection(row)">移出</el-button>
            <span class="op-sep" />
            <el-button link size="small" type="danger" class="op-del" @click="deleteDoc(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="filteredDocs.length > docPageSize" class="doc-pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="filteredDocs.length"
        :page-size="docPageSize"
        :current-page="docPage"
        @current-change="(p: number) => docPage = p"
      />
    </div>
    <div v-if="!loading && !docs.length" class="empty">
      <el-icon size="32" color="#94a3b8"><Document /></el-icon>
      <p>这个知识库还没有文档</p>
      <el-button type="primary" size="small" :icon="Plus" @click="openUploadDialog">上传第一个文档</el-button>
    </div>
    <div v-else-if="!loading && docs.length && !filteredDocs.length" class="empty">
      <el-icon size="32" color="#94a3b8"><Search /></el-icon>
      <p>没有匹配的文档</p>
    </div>
  </div>
</template>

<style scoped>
.coll-detail { padding: 28px 32px; height: 100%; overflow-y: auto; background: transparent; }
.header { display: flex; align-items: flex-start; gap: 14px; margin-bottom: 18px; flex-wrap: wrap; }
.back-btn {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 6px 12px; background: var(--bg-surface);
  border: 1px solid var(--line); border-radius: 6px;
  font-size: 12.5px; color: var(--ink-2); cursor: pointer;
}
.back-btn:hover { background: var(--bg-hover); color: var(--ink-1); }
.title-block { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
.title-icon { width: 44px; height: 44px; display: inline-flex; align-items: center; justify-content: center; border-radius: 10px; flex-shrink: 0; }
.title { font-size: 20px; font-weight: 700; color: var(--ink-1); }
.sub { font-size: 12px; color: var(--ink-3); margin-top: 2px; }
.header-right { display: flex; gap: 10px; }

.stats-row { display: flex; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.stat-pill {
  display: flex; gap: 6px; align-items: baseline;
  padding: 6px 14px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 12px;
}
.stat-label { color: var(--ink-3); }
.stat-val { color: var(--ink-1); font-weight: 700; }
.stat-pill-clickable { cursor: pointer; transition: border-color .15s, background .15s; }
.stat-pill-clickable:hover { border-color: var(--primary); background: var(--primary-dim); }
.stat-edit { color: var(--ink-3); align-self: center; }
.persona-hint { font-size: 11.5px; color: var(--ink-3); margin-top: 6px; line-height: 1.5; }

/* 「知识库管理」统一弹窗 · 双栏左右分栏，一屏无滚动 */
:deep(.manage-dialog .el-dialog__body) { padding-top: 6px; overflow: visible; }
.manage-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: start;
}
.manage-col { min-width: 0; }
.manage-col:first-child { padding-right: 28px; }
.manage-col + .manage-col { border-left: 1px solid var(--line, #e5e7eb); padding-left: 28px; }
.manage-section { padding: 12px 0 14px; border-radius: 8px; transition: background .3s; }
.manage-section + .manage-section { border-top: 1px solid var(--line, #e5e7eb); }
.manage-section-acl { padding-top: 12px; }
.manage-section-head {
  display: flex; align-items: center; gap: 7px;
  font-size: 15px; font-weight: 700; color: var(--ink-1);
  margin-bottom: 12px;
}
.manage-section-head .el-icon { color: var(--primary, #6366f1); }
.manage-section-head .head-tip { margin-left: auto; font-size: 11px; font-weight: 400; color: var(--ink-3); }
.field-label { font-size: 13px; font-weight: 600; color: var(--ink-2); margin-bottom: 6px; }

/* ACL 授权对象列表：超出内部滚动，弹窗整体不出现纵向滚动条 */
.acl-entries { max-height: 156px; overflow-y: auto; }

/* 统计卡片点进来时，对应小节短暂高亮做定位提示 */
.sec-flash { animation: secFlash 1.4s ease; }
@keyframes secFlash {
  0%, 60% { background: var(--primary-dim, rgba(0,0,0,0.10)); }
  100% { background: transparent; }
}

.doc-cell { display: flex; align-items: center; gap: 10px; }
.doc-name { font-size: 13px; color: var(--ink-1); font-weight: 600; }
.doc-sub { font-size: 11px; color: var(--ink-3); }

.doc-pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.empty { text-align: center; padding: 60px 20px; color: var(--ink-3); }
.empty p { margin: 10px 0; font-size: 13px; }
.doc-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
/* 行内操作：移出 / 删除 */
.row-ops { display: inline-flex; align-items: center; }
.row-ops .op-sep { width: 1px; height: 12px; margin: 0 8px; background: var(--line-strong); }
.row-ops :deep(.el-button) {
  padding: 3px 8px; border-radius: var(--radius-sm); transition: background .15s ease, color .15s ease;
}
.row-ops .op-move:hover { color: var(--brand) !important; background: var(--brand-soft); }
.row-ops .op-del:hover { color: var(--danger) !important; background: var(--danger-soft); }
.doc-toolbar .doc-count { margin-left: auto; font-size: 12px; color: var(--ink-3); white-space: nowrap; }

/* ── API Key 弹窗 ───────────────── */
.hint { font-size: 12.5px; color: var(--ink-2); margin-bottom: 14px; line-height: 1.6; padding: 10px 12px; background: rgba(0,0,0,0.06); border-radius: 8px; }
.api-keys-list { display: flex; flex-direction: column; gap: 10px; max-height: 360px; overflow-y: auto; }
.api-key-card {
  background: var(--bg-surface); border: 1px solid var(--line);
  border-radius: 10px; padding: 12px 14px;
}
.api-key-card.status-revoked, .api-key-card.status-expired { opacity: 0.6; }
.api-key-card header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.api-key-name { font-weight: 600; color: var(--ink-1); font-size: 13.5px; }
.api-key-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 11.5px; color: var(--ink-3); }
.api-key-meta code { background: rgba(0,0,0,0.08); color: #0a0a0a; padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', monospace; }
.warn-text { color: #b45309; font-size: 13px; margin-bottom: 12px; padding: 8px 12px; background: rgba(245,158,11,0.1); border-radius: 6px; }
.raw-key-box {
  display: flex; align-items: center; gap: 8px;
  background: #0f172a; color: #38bdf8;
  padding: 14px; border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
}
.raw-key-box code { flex: 1; word-break: break-all; font-size: 13px; }

/* ── ACL / 可见性 ─────────────────────── */
.acl-section { margin-bottom: 20px; }
.acl-section:last-child { margin-bottom: 0; }
.section-title { font-size: 13px; font-weight: 700; color: var(--ink-1); margin-bottom: 10px; }

.vis-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.vis-card {
  position: relative; display: flex; align-items: flex-start; gap: 8px;
  padding: 12px 14px; background: var(--bg-surface);
  border: 1.5px solid var(--line); border-radius: 8px; cursor: pointer;
  text-align: left; transition: all 0.15s; appearance: none; font-family: inherit;
}
.vis-card:hover { border-color: #0a0a0a; background: rgba(0,0,0,0.04); }
.vis-card.active { border-color: #0a0a0a; background: rgba(0,0,0,0.08); }
.vis-card .vis-icon { color: #0a0a0a; flex-shrink: 0; margin-top: 2px; }
.vis-card-title { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.vis-card-desc { font-size: 11px; color: var(--ink-3); margin-top: 2px; }
.vis-card .vis-check { position: absolute; top: 8px; right: 8px; color: #0a0a0a; }

.acl-row {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; margin-bottom: 6px;
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 8px;
}
.acl-subject-tag {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 8px; border-radius: 999px;
  font-size: 10.5px; font-weight: 600;
}
.acl-subject-tag.pos { background: rgba(14,165,233,0.12); color: #0369a1; }
.acl-subject-tag.dept { background: rgba(0,0,0,0.12); color: #0a0a0a; }
.acl-name { flex: 1; font-size: 13px; color: var(--ink-1); }
.acl-del {
  background: transparent; border: none; cursor: pointer; color: var(--ink-3);
  width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
}
.acl-del:hover { background: rgba(239,68,68,0.1); color: #ef4444; }

.acl-add-block { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--line); }
.acl-add-row { display: flex; gap: 8px; margin-top: 8px; align-items: center; }
.acl-tip { font-size: 11.5px; color: var(--ink-3); margin-top: 10px; line-height: 1.6; padding: 8px 10px; background: rgba(0,0,0,0.04); border-radius: 6px; }

/* API 接入调试样例 */
.api-debug { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 14px; }
.api-debug-title { font-size: 13px; font-weight: 600; color: var(--ink-1); margin-bottom: 10px; }
.api-debug-block { margin-bottom: 12px; }
.adb-head { display: flex; align-items: center; justify-content: space-between; font-size: 12.5px; color: var(--ink-2); font-weight: 600; margin-bottom: 4px; }
.api-debug pre {
  margin: 0; background: #0f172a; color: #e2e8f0; padding: 10px 12px; border-radius: 8px;
  font-family: 'JetBrains Mono', monospace; font-size: 12px; line-height: 1.55;
  white-space: pre-wrap; word-break: break-all;
}
.adb-note { font-size: 12px; color: var(--ink-3); line-height: 1.6; margin-top: 6px; }
.adb-note code { background: var(--bg-elevated); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; font-size: 11.5px; }
</style>
