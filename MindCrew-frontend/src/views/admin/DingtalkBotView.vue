<template>
  <div class="dt-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">钉钉机器人</h2>
          <span class="title-tag">知识库问答</span>
        </div>
        <p class="page-desc">
          Stream 模式：填机器人的 AppKey + AppSecret，服务器主动连钉钉收消息--
          <strong>无需公网回调地址、无需 SSL、无需验签</strong>。钉钉机器人「消息接收模式」选 Stream 模式即可。
          配任意多个，各绑各库，增删改全在此页、即时生效。
        </p>
      </div>
      <div style="display:flex; gap:10px; align-items:center;">
        <el-button :icon="ChatLineSquare" @click="openLogs(null)">聊天记录</el-button>
        <el-button type="primary" :icon="Plus" @click="openDialog(null)">新建机器人</el-button>
      </div>
    </header>

    <el-table :data="rows" v-loading="loading" stripe size="small" class="dt-table">
      <el-table-column label="名称" min-width="140">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="bot-name">{{ row.name }}</span>
            <span v-if="row.description" class="bot-desc">{{ row.description }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="绑定知识库" min-width="140">
        <template #default="{ row }">
          <el-tag v-if="collName(row.collectionId)" size="small" type="info" effect="plain">{{ collName(row.collectionId) }}</el-tag>
          <span v-else class="muted">未绑定（全部可访问库）</span>
        </template>
      </el-table-column>
      <el-table-column label="AppKey" min-width="200">
        <template #default="{ row }">
          <code v-if="row.appKey" class="cb-url">{{ row.appKey }}</code>
          <span v-else class="warn-dot" title="未配 AppKey，无法连接">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="密钥" width="80" align="center">
        <template #default="{ row }">
          <span :class="row.hasSecret ? 'on' : 'off'">{{ row.hasSecret ? '已配' : '未配' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" @change="(v: any) => toggle(row, !!v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <button class="op-btn" @click="openLogs(row)">记录</button>
          <button class="op-btn" @click="openDialog(row)">编辑</button>
          <button class="op-btn danger" @click="del(row)">删除</button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && !rows.length" class="empty">还没有钉钉机器人，点右上角「新建机器人」开始。</div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑机器人' : '新建机器人'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：技术库客服机器人" maxlength="60" />
        </el-form-item>
        <el-form-item label="绑定知识库" required>
          <el-select v-model="form.collectionId" filterable placeholder="选一个知识库" style="width:100%">
            <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="AppKey" required>
          <el-input v-model="form.appKey" placeholder="钉钉「凭证」页的 Client ID（原 AppKey）" />
          <div class="form-hint">钉钉开放平台 → 凭证与基础信息 → Client ID（原 AppKey）。</div>
        </el-form-item>
        <el-form-item label="AppSecret" :required="!form.id">
          <el-input
            v-model="form.appSecret"
            type="password"
            show-password
            :placeholder="form.id ? '留空＝不修改原密钥' : '钉钉「凭证」页的 Client Secret'"
          />
          <div class="form-hint">钉钉开放平台 → 凭证与基础信息 → Client Secret（原 AppSecret），加密存储。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建成功 -->
    <el-dialog v-model="createdVisible" title="机器人已创建" width="520px">
      <el-alert type="success" :closable="false" show-icon>
        已保存并自动连接钉钉（Stream 模式）。请确保钉钉机器人「消息接收模式」选的是
        <strong>Stream 模式</strong> 并已发布，然后把机器人加到群 @它提问即可。
      </el-alert>
      <template #footer>
        <el-button type="primary" @click="createdVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- 聊天记录抽屉 -->
    <el-drawer v-model="logsVisible" :title="logsTitle" size="62%" direction="rtl">
      <div class="logs-toolbar">
        <el-select v-model="logFilter.botId" placeholder="全部机器人" clearable size="default"
                   style="width:200px" @change="loadLogs(1)">
          <el-option v-for="b in rows" :key="b.id" :label="b.name" :value="b.id" />
        </el-select>
        <el-input v-model="logFilter.keyword" placeholder="搜提问 / 回答 / 提问人" clearable
                  size="default" style="max-width:280px" @keyup.enter="loadLogs(1)" @clear="loadLogs(1)" />
        <el-button :icon="Search" @click="loadLogs(1)">搜索</el-button>
        <span class="logs-count">共 {{ logsTotal }} 条</span>
      </div>

      <div v-loading="logsLoading" class="logs-list">
        <div v-for="l in logs" :key="l.id" class="log-item">
          <div class="log-meta">
            <span class="log-sender">{{ l.senderNick || '匿名' }}</span>
            <span v-if="l.conversationTitle" class="log-conv">@ {{ l.conversationTitle }}</span>
            <span v-if="!logFilter.botId && l.botName" class="log-bot">{{ l.botName }}</span>
            <span class="log-time">{{ l.createTime }}</span>
            <span v-if="l.answerMs != null" class="log-ms">{{ (l.answerMs / 1000).toFixed(1) }}s</span>
          </div>
          <div class="log-q">问：{{ l.question }}</div>
          <div class="log-a">答：{{ l.answer }}</div>
        </div>
        <div v-if="!logsLoading && !logs.length" class="empty">暂无聊天记录</div>
      </div>

      <div v-if="logsTotal > logFilter.size" class="logs-pager">
        <el-pagination
          layout="prev, pager, next"
          :total="logsTotal"
          :page-size="logFilter.size"
          :current-page="logFilter.current"
          @current-change="loadLogs"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, ChatLineSquare } from '@element-plus/icons-vue'
import { dingtalkBotApi, type DingtalkBot, type DingtalkChatLog } from '@/api/dingtalkBot'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'

const unwrap = (r: any) => r?.data ?? r

const loading = ref(false)
const rows = ref<DingtalkBot[]>([])
const collections = ref<KnowledgeCollection[]>([])
const collMap = ref<Record<number, string>>({})

const collName = (id?: number | null) => (id != null ? collMap.value[id] : undefined)

const load = async () => {
  loading.value = true
  try {
    rows.value = unwrap(await dingtalkBotApi.list()) || []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { loading.value = false }
}
const loadCollections = async () => {
  try {
    const list: KnowledgeCollection[] = unwrap(await collectionApi.list()) || []
    collections.value = list
    collMap.value = Object.fromEntries(list.map(c => [c.id, c.name]))
  } catch { /* 静默 */ }
}
onMounted(() => { load(); loadCollections() })

// 聊天记录
const logsVisible = ref(false)
const logsLoading = ref(false)
const logs = ref<DingtalkChatLog[]>([])
const logsTotal = ref(0)
const logFilter = reactive<{ current: number; size: number; botId: number | null; keyword: string }>(
  { current: 1, size: 20, botId: null, keyword: '' }
)
const logsTitle = computed(() => {
  const b = logFilter.botId != null ? rows.value.find(r => r.id === logFilter.botId) : null
  return b ? `聊天记录 · ${b.name}` : '聊天记录 · 全部机器人'
})

const openLogs = (bot: DingtalkBot | null) => {
  logFilter.botId = bot ? bot.id : null
  logFilter.keyword = ''
  logsVisible.value = true
  loadLogs(1)
}

const loadLogs = async (page = 1) => {
  logFilter.current = page
  logsLoading.value = true
  try {
    const res: any = unwrap(await dingtalkBotApi.logs({
      current: logFilter.current,
      size: logFilter.size,
      botId: logFilter.botId,
      keyword: logFilter.keyword || undefined,
    }))
    logs.value = res?.records ?? res ?? []
    logsTotal.value = res?.total ?? logs.value.length
  } catch (e: any) {
    ElMessage.error('加载记录失败：' + (e?.message || ''))
  } finally { logsLoading.value = false }
}

// 新建 / 编辑
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<{ id: number | null; name: string; appKey: string; collectionId: number | null; appSecret: string; description: string }>({
  id: null, name: '', appKey: '', collectionId: null, appSecret: '', description: '',
})

const openDialog = (row: DingtalkBot | null) => {
  if (row) {
    form.id = row.id; form.name = row.name; form.appKey = row.appKey || ''
    form.collectionId = row.collectionId ?? null
    form.appSecret = ''; form.description = row.description || ''
  } else {
    form.id = null; form.name = ''; form.appKey = ''; form.collectionId = null
    form.appSecret = ''; form.description = ''
  }
  dialogVisible.value = true
}

const createdVisible = ref(false)

const save = async () => {
  if (!form.name.trim()) return ElMessage.warning('请填名称')
  if (form.collectionId == null) return ElMessage.warning('请选择绑定的知识库')
  if (!form.appKey.trim()) return ElMessage.warning('请填 AppKey')
  if (!form.id && !form.appSecret.trim()) return ElMessage.warning('请填 AppSecret')
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      appKey: form.appKey.trim(),
      collectionId: form.collectionId,
      appSecret: form.appSecret || undefined,
      description: form.description || undefined,
    }
    if (form.id) {
      await dingtalkBotApi.update(form.id, payload)
      ElMessage.success('已保存')
      dialogVisible.value = false
    } else {
      await dingtalkBotApi.create(payload)
      dialogVisible.value = false
      createdVisible.value = true
    }
    await load()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

const toggle = async (row: DingtalkBot, v: boolean) => {
  try {
    await dingtalkBotApi.setEnabled(row.id, v)
    row.enabled = v ? 1 : 0
  } catch (e: any) {
    ElMessage.error('操作失败：' + (e?.message || ''))
  }
}

const del = async (row: DingtalkBot) => {
  await ElMessageBox.confirm(`确认删除机器人「${row.name}」？删除后其回调地址立即失效。`, '删除', {
    type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger',
  })
  try {
    await dingtalkBotApi.delete(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

</script>

<style scoped>
.dt-page { padding: 24px 28px 40px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { margin-bottom: 20px; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.page-title { font-size: 22px; font-weight: 700; color: var(--ink-1); }
.title-tag { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px; background: linear-gradient(135deg, #dbeafe, #bfdbfe); color: #1d4ed8; }
.page-desc { font-size: 13px; color: var(--ink-3); max-width: 760px; line-height: 1.55; }

.dt-table { background: var(--bg-surface); border-radius: 10px; }
.name-cell { display: flex; flex-direction: column; gap: 2px; }
.bot-name { font-weight: 600; color: var(--ink-1); font-size: 13px; }
.bot-desc { font-size: 11px; color: var(--ink-4); }
.muted { color: var(--ink-4); font-size: 12px; }

.cb-cell { display: flex; align-items: center; gap: 6px; }
.cb-url { font-family: 'JetBrains Mono', monospace; font-size: 11.5px; color: var(--ink-2); background: var(--bg-elevated); border: 1px solid var(--line); border-radius: 4px; padding: 2px 6px; word-break: break-all; }
.on { color: #047857; font-weight: 600; }
.off { color: var(--ink-4); }
.warn-dot { color: #d97706; font-weight: 700; margin-left: 4px; }

.op-btn { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 12.5px; margin-right: 10px; }
.op-btn:hover { text-decoration: underline; }
.op-btn.danger { color: #dc2626; }

.empty { padding: 60px 20px; text-align: center; color: var(--ink-4); font-size: 13px; }
.form-hint { font-size: 12px; color: var(--ink-4); margin-top: 4px; line-height: 1.5; }
.cb-box { display: flex; align-items: center; gap: 10px; background: var(--bg-elevated); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
.cb-box .cb-url { flex: 1; }

/* 聊天记录抽屉 */
.logs-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.logs-toolbar .logs-count { margin-left: auto; font-size: 12px; color: var(--ink-4); white-space: nowrap; }
.logs-list { display: flex; flex-direction: column; gap: 12px; }
.log-item { border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; background: var(--bg-surface); }
.log-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; color: var(--ink-4); margin-bottom: 8px; }
.log-meta .log-sender { font-weight: 600; color: var(--ink-2); }
.log-meta .log-bot { background: var(--bg-elevated); border-radius: 4px; padding: 1px 6px; }
.log-meta .log-time { margin-left: auto; }
.log-meta .log-ms { color: var(--primary); }
.log-q { font-size: 13px; color: var(--ink-1); margin-bottom: 6px; white-space: pre-wrap; word-break: break-word; }
.log-a { font-size: 13px; color: var(--ink-2); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.logs-pager { display: flex; justify-content: center; margin-top: 16px; }
</style>
