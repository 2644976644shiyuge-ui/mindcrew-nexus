<template>
  <div class="apikey-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">API Key 管理</h2>
          <span class="title-tag">对外授权</span>
        </div>
        <p class="page-desc">为第三方系统签发 API Key，按知识库授权访问，控制月调用配额与 QPS，并追踪用量与调用日志。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openIssue">生成 API Key</el-button>
    </header>

    <!-- 过滤栏 -->
    <div class="filter-bar">
      <el-select v-model="filterStatus" placeholder="状态" clearable style="width:140px" @change="reload">
        <el-option label="生效中" value="active" />
        <el-option label="已吊销" value="revoked" />
        <el-option label="已过期" value="expired" />
      </el-select>
      <el-button :icon="Refresh" @click="reload" />
    </div>

    <!-- 表格 -->
    <el-table :data="rows" v-loading="loading" stripe size="small" class="key-table">
      <el-table-column label="名称 / Key" min-width="200">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="key-name">{{ row.name }}</span>
            <code class="key-prefix">{{ row.keyPrefix }}••••</code>
            <span v-if="row.description" class="key-desc">{{ row.description }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="授权知识库" min-width="180">
        <template #default="{ row }">
          <div class="kb-tags">
            <el-tag v-for="name in collectionNames(row)" :key="name" size="small" type="info" effect="plain">{{ name }}</el-tag>
            <span v-if="collectionNames(row).length === 0" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="月用量 / 配额" width="160">
        <template #default="{ row }">
          <div class="usage-cell">
            <el-progress
              :percentage="usagePercent(row)"
              :status="usagePercent(row) >= 100 ? 'exception' : undefined"
              :stroke-width="6"
              :show-text="false"
            />
            <span class="usage-text">{{ row.monthUsed }} / {{ row.monthlyQuota > 0 ? row.monthlyQuota : '∞' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="QPS" width="70" align="right">
        <template #default="{ row }">{{ row.rateLimitQps || '-' }}</template>
      </el-table-column>
      <el-table-column label="总调用" width="90" align="right">
        <template #default="{ row }">{{ row.totalCalls }}</template>
      </el-table-column>
      <el-table-column label="最后调用" width="150">
        <template #default="{ row }">{{ row.lastUsedAt ? formatTime(row.lastUsedAt) : '从未' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <span class="status-tag" :class="`s-${row.status}`">{{ statusLabel(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <div class="ops">
            <button class="op-btn" @click="openLogs(row)">日志</button>
            <button class="op-btn" @click="openQuota(row)" :disabled="row.status !== 'active'">配额</button>
            <button class="op-btn warn" @click="handleRevoke(row)" :disabled="row.status !== 'active'">吊销</button>
            <button class="op-btn danger" @click="handleDelete(row)">删除</button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="current"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        background
        layout="total, sizes, prev, pager, next"
        @change="reload"
      />
    </div>

    <!-- 生成 Key 弹窗 -->
    <el-dialog v-model="issueVisible" title="生成 API Key" width="560px" :close-on-click-modal="false">
      <el-form label-width="100px" class="issue-form">
        <el-form-item label="名称" required>
          <el-input v-model="issueForm.name" placeholder="如：客服小程序、官网问答" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="授权知识库">
          <el-select v-model="issueForm.allowedCollectionIds" multiple filterable placeholder="不选=授权全部可访问知识库" style="width:100%">
            <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="月配额">
          <el-input-number v-model="issueForm.monthlyQuota" :min="0" :step="100" controls-position="right" style="width:160px" />
          <span class="form-hint">每月最大调用次数，0 = 不限</span>
        </el-form-item>
        <el-form-item label="QPS 限流">
          <el-input-number v-model="issueForm.rateLimitQps" :min="0" :step="1" controls-position="right" style="width:160px" />
          <span class="form-hint">每秒最大请求数，0 = 不限</span>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="issueForm.expireAt" type="datetime" placeholder="留空=永不过期" value-format="YYYY-MM-DDTHH:mm:ss" style="width:220px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="issueForm.description" type="textarea" :rows="2" maxlength="200" placeholder="用途说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" @click="handleIssue">生成</el-button>
      </template>
    </el-dialog>

    <!-- 生成结果 · rawKey 仅此一次 -->
    <el-dialog v-model="rawKeyVisible" title="API Key 已生成" width="560px" :close-on-click-modal="false" :show-close="false">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:16px">
        请立即复制并妥善保存，关闭后将无法再查看完整 Key。
      </el-alert>
      <div class="raw-key-box">
        <code class="raw-key">{{ rawKeyResult?.rawKey }}</code>
        <el-button type="primary" size="small" :icon="CopyDocument" @click="copyRawKey">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="closeRawKey">我已保存</el-button>
      </template>
    </el-dialog>

    <!-- 改配额弹窗 -->
    <el-dialog v-model="quotaVisible" title="修改配额" width="420px">
      <el-form label-width="100px">
        <el-form-item label="月配额">
          <el-input-number v-model="quotaForm.monthlyQuota" :min="0" :step="100" controls-position="right" style="width:160px" />
        </el-form-item>
        <el-form-item label="QPS 限流">
          <el-input-number v-model="quotaForm.rateLimitQps" :min="0" :step="1" controls-position="right" style="width:160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" :loading="quotaSaving" @click="handleSaveQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- 调用日志抽屉 -->
    <el-drawer v-model="logsVisible" :title="`调用日志 · ${currentKey?.name || ''}`" size="62%" direction="rtl">
      <div class="logs-body" v-loading="logsLoading">
        <el-table :data="logRows" stripe size="small">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatTime(row.calledAt) }}</template>
          </el-table-column>
          <el-table-column prop="api" label="接口" width="120" />
          <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="70" align="center">
            <template #default="{ row }">
              <span class="status-tag" :class="row.statusCode < 400 ? 's-active' : 's-revoked'">{{ row.statusCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Tokens" width="110" align="right">
            <template #default="{ row }">{{ row.inputTokens }}+{{ row.outputTokens }}</template>
          </el-table-column>
          <el-table-column label="费用" width="90" align="right">
            <template #default="{ row }">¥{{ (row.costCny || 0).toFixed(4) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="80" align="right">
            <template #default="{ row }">{{ row.latencyMs }}ms</template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="120" />
        </el-table>
        <div class="pagination">
          <el-pagination
            v-model:current-page="logsCurrent"
            v-model:page-size="logsSize"
            :total="logsTotal"
            :page-sizes="[20, 50]"
            background
            small
            layout="total, prev, pager, next"
            @change="loadLogs"
          />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, CopyDocument } from '@element-plus/icons-vue'
import { apiKeyApi, type ApiKey, type ApiCallLog, type IssueResult } from '@/api/apiKey'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'

const unwrap = (res: any) => res?.data ?? res

// ── 列表 ──
const loading = ref(false)
const rows = ref<ApiKey[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(20)
const filterStatus = ref('')

const collections = ref<KnowledgeCollection[]>([])
const collectionMap = ref<Record<number, string>>({})

const reload = async () => {
  loading.value = true
  try {
    const d = unwrap(await apiKeyApi.page({
      current: current.value,
      size: size.value,
      status: filterStatus.value || undefined,
    }))
    rows.value = d?.records || []
    total.value = d?.total || 0
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally {
    loading.value = false
  }
}

const loadCollections = async () => {
  try {
    const list: KnowledgeCollection[] = unwrap(await collectionApi.list()) || []
    collections.value = list
    collectionMap.value = Object.fromEntries(list.map(c => [c.id, c.name]))
  } catch { /* 静默 · 不阻断主列表 */ }
}

onMounted(async () => {
  await Promise.all([reload(), loadCollections()])
})

// ── 工具 ──
const formatTime = (t?: string) => (t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '')

const statusLabel = (s: string) => ({ active: '生效中', revoked: '已吊销', expired: '已过期' }[s] || s)

const usagePercent = (row: ApiKey) => {
  if (!row.monthlyQuota || row.monthlyQuota <= 0) return 0
  return Math.min(100, Math.round((row.monthUsed / row.monthlyQuota) * 100))
}

const parseIds = (json?: string): number[] => {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

const collectionNames = (row: ApiKey): string[] =>
  parseIds(row.allowedCollectionIds).map(id => collectionMap.value[id] || `#${id}`)

// ── 生成 Key ──
const issueVisible = ref(false)
const issuing = ref(false)
const issueForm = ref({
  name: '',
  allowedCollectionIds: [] as number[],
  monthlyQuota: 0,
  rateLimitQps: 0,
  expireAt: '' as string | undefined,
  description: '',
})

const openIssue = () => {
  issueForm.value = { name: '', allowedCollectionIds: [], monthlyQuota: 0, rateLimitQps: 0, expireAt: '', description: '' }
  issueVisible.value = true
}

const rawKeyVisible = ref(false)
const rawKeyResult = ref<IssueResult | null>(null)

const handleIssue = async () => {
  if (!issueForm.value.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  issuing.value = true
  try {
    const res = unwrap(await apiKeyApi.issue({
      name: issueForm.value.name.trim(),
      allowedCollectionIds: issueForm.value.allowedCollectionIds.length ? issueForm.value.allowedCollectionIds : undefined,
      monthlyQuota: issueForm.value.monthlyQuota || undefined,
      rateLimitQps: issueForm.value.rateLimitQps || undefined,
      expireAt: issueForm.value.expireAt || undefined,
      description: issueForm.value.description || undefined,
    }))
    rawKeyResult.value = res
    issueVisible.value = false
    rawKeyVisible.value = true
    await reload()
  } catch (e: any) {
    ElMessage.error('生成失败：' + (e?.message || ''))
  } finally {
    issuing.value = false
  }
}

const copyRawKey = async () => {
  const key = rawKeyResult.value?.rawKey
  if (!key) return
  try {
    await navigator.clipboard.writeText(key)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

const closeRawKey = () => {
  rawKeyVisible.value = false
  rawKeyResult.value = null
}

// ── 改配额 ──
const quotaVisible = ref(false)
const quotaSaving = ref(false)
const currentKey = ref<ApiKey | null>(null)
const quotaForm = ref({ monthlyQuota: 0, rateLimitQps: 0 })

const openQuota = (row: ApiKey) => {
  currentKey.value = row
  quotaForm.value = { monthlyQuota: row.monthlyQuota || 0, rateLimitQps: row.rateLimitQps || 0 }
  quotaVisible.value = true
}

const handleSaveQuota = async () => {
  if (!currentKey.value) return
  quotaSaving.value = true
  try {
    await apiKeyApi.updateQuota(currentKey.value.id, {
      monthlyQuota: quotaForm.value.monthlyQuota,
      rateLimitQps: quotaForm.value.rateLimitQps,
    })
    ElMessage.success('已更新')
    quotaVisible.value = false
    await reload()
  } catch (e: any) {
    ElMessage.error('更新失败：' + (e?.message || ''))
  } finally {
    quotaSaving.value = false
  }
}

// ── 吊销 / 删除 ──
const handleRevoke = async (row: ApiKey) => {
  await ElMessageBox.confirm(`确认吊销「${row.name}」？吊销后该 Key 立即失效，但保留记录。`, '吊销 API Key', { type: 'warning' })
  try {
    await apiKeyApi.revoke(row.id)
    ElMessage.success('已吊销')
    await reload()
  } catch (e: any) {
    ElMessage.error('吊销失败：' + (e?.message || ''))
  }
}

const handleDelete = async (row: ApiKey) => {
  await ElMessageBox.confirm(`确认删除「${row.name}」？删除后记录不可恢复。`, '删除 API Key', {
    type: 'warning',
    confirmButtonText: '确认删除',
    confirmButtonClass: 'el-button--danger',
  })
  try {
    await apiKeyApi.delete(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

// ── 调用日志 ──
const logsVisible = ref(false)
const logsLoading = ref(false)
const logRows = ref<ApiCallLog[]>([])
const logsTotal = ref(0)
const logsCurrent = ref(1)
const logsSize = ref(20)

const openLogs = (row: ApiKey) => {
  currentKey.value = row
  logsCurrent.value = 1
  logsVisible.value = true
  loadLogs()
}

const loadLogs = async () => {
  if (!currentKey.value) return
  logsLoading.value = true
  try {
    const d = unwrap(await apiKeyApi.logs({
      current: logsCurrent.value,
      size: logsSize.value,
      keyId: currentKey.value.id,
    }))
    logRows.value = d?.records || []
    logsTotal.value = d?.total || 0
  } catch (e: any) {
    ElMessage.error('加载日志失败：' + (e?.message || ''))
  } finally {
    logsLoading.value = false
  }
}
</script>

<style scoped>
.apikey-page { padding: 24px 28px 40px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { margin-bottom: 20px; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.page-title { font-size: 22px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #dbeafe, #bfdbfe); color: #1d4ed8;
}
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 720px; line-height: 1.55; }

.filter-bar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }

.key-table { background: var(--bg-surface); border-radius: 10px; }

.name-cell { display: flex; flex-direction: column; gap: 2px; }
.key-name { font-weight: 600; color: var(--ink-1); font-size: 13px; }
.key-prefix { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--ink-3); }
.key-desc { font-size: 11px; color: var(--ink-4); }

.kb-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.muted { color: var(--ink-4); }

.usage-cell { display: flex; flex-direction: column; gap: 3px; }
.usage-text { font-size: 11px; color: var(--ink-3); font-family: 'JetBrains Mono', monospace; }

.status-tag { font-size: 10.5px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.status-tag.s-active { background: rgba(52,211,153,0.15); color: #047857; }
.status-tag.s-revoked { background: rgba(248,113,113,0.15); color: #b91c1c; }
.status-tag.s-expired { background: rgba(148,163,184,0.18); color: #475569; }

.ops { display: flex; gap: 8px; }
.op-btn { background: none; border: none; color: var(--brand); cursor: pointer; font-size: 12.5px; padding: 0; }
.op-btn:hover:not(:disabled) { text-decoration: underline; }
.op-btn:disabled { color: var(--ink-4); cursor: not-allowed; }
.op-btn.warn { color: #d97706; }
.op-btn.danger { color: #dc2626; }

.pagination { margin-top: 18px; display: flex; justify-content: flex-end; }

.issue-form .form-hint { margin-left: 10px; font-size: 12px; color: var(--ink-4); }

.raw-key-box {
  display: flex; align-items: center; gap: 10px;
  background: #0f172a; border-radius: 8px; padding: 12px 14px;
}
.raw-key { flex: 1; font-family: 'JetBrains Mono', monospace; font-size: 13px; color: #34d399; word-break: break-all; }

.logs-body { padding: 0 4px; }
</style>
