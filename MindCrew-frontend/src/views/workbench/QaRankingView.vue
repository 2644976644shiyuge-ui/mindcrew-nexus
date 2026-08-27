<template>
  <div class="rank-page">
    <header class="rank-header">
      <div>
        <h2 class="title">问答排行 <span class="title-tag">热点沉淀经验</span></h2>
        <p class="desc">按问题归一化聚合、频次倒序。可对高频问答点赞、纠正、收录进 Golden Pair 经验库。</p>
      </div>
      <el-radio-group v-model="range" @change="loadAll">
        <el-radio-button value="today">今日</el-radio-button>
        <el-radio-button value="7d">近7天</el-radio-button>
        <el-radio-button value="30d">近30天</el-radio-button>
      </el-radio-group>
    </header>

    <el-tabs v-model="tab" class="rank-tabs">
      <!-- 全系统 -->
      <el-tab-pane label="全系统排行" name="system">
        <el-table :data="systemRows" v-loading="loading" stripe>
          <el-table-column type="index" label="#" width="60" align="center" />
          <el-table-column label="问题" min-width="380">
            <template #default="{ row }"><span class="q-text">{{ row.question }}</span></template>
          </el-table-column>
          <el-table-column label="频次" width="120" align="right" sortable :sort-method="(a:any,b:any)=>a.count-b.count">
            <template #default="{ row }"><b class="cnt">{{ row.count }}</b></template>
          </el-table-column>
          <el-table-column label="最近提问" width="170">
            <template #default="{ row }">{{ fmt(row.lastAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" class="op-btn" title="点赞 / 纠正 / 收录到经验库" @click="openExperience(row)">
                <el-icon class="op-ic"><MagicStick /></el-icon>收录经验
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!loading && !systemRows.length" class="empty">该时间段暂无问答</div>
      </el-tab-pane>

      <!-- 按文档（按命中文档归类） -->
      <el-tab-pane label="按文档" name="kb" lazy>
        <div v-loading="loading">
          <el-collapse v-model="kbActive">
            <el-collapse-item v-for="g in kbGroups" :key="String(g.kbId)" :name="String(g.kbId)">
              <template #title>
                <span class="grp-name">{{ g.kbName }}</span>
                <el-tag size="small" type="info" effect="plain" class="grp-cnt">{{ g.count }} 次提问</el-tag>
              </template>
              <el-table v-if="kbActive.includes(String(g.kbId))" :data="g.top" stripe size="small">
                <el-table-column type="index" label="#" width="50" align="center" />
                <el-table-column label="问题" min-width="360">
                  <template #default="{ row }"><span class="q-text">{{ row.question }}</span></template>
                </el-table-column>
                <el-table-column label="频次" width="90" align="right">
                  <template #default="{ row }"><b class="cnt">{{ row.count }}</b></template>
                </el-table-column>
                <el-table-column label="操作" width="160" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" class="op-btn" title="点赞 / 纠正 / 收录到经验库" @click="openExperience(row)">
                <el-icon class="op-ic"><MagicStick /></el-icon>收录经验
              </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
          <div v-if="!loading && !kbGroups.length" class="empty">该时间段暂无问答</div>
        </div>
      </el-tab-pane>

      <!-- 按用户 -->
      <el-tab-pane label="按用户" name="user" lazy>
        <div v-loading="loading">
          <el-collapse v-model="userActive">
            <el-collapse-item v-for="g in userGroups" :key="String(g.userId)" :name="String(g.userId)">
              <template #title>
                <span class="grp-name">{{ g.userName }}</span>
                <el-tag size="small" type="info" effect="plain" class="grp-cnt">{{ g.count }} 次提问</el-tag>
              </template>
              <el-table v-if="userActive.includes(String(g.userId))" :data="g.top" stripe size="small">
                <el-table-column type="index" label="#" width="50" align="center" />
                <el-table-column label="问题" min-width="360">
                  <template #default="{ row }"><span class="q-text">{{ row.question }}</span></template>
                </el-table-column>
                <el-table-column label="频次" width="90" align="right">
                  <template #default="{ row }"><b class="cnt">{{ row.count }}</b></template>
                </el-table-column>
                <el-table-column label="操作" width="160" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" class="op-btn" title="点赞 / 纠正 / 收录到经验库" @click="openExperience(row)">
                <el-icon class="op-ic"><MagicStick /></el-icon>收录经验
              </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
          <div v-if="!loading && !userGroups.length" class="empty">该时间段暂无问答</div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 点赞/纠正/收录 弹窗 -->
    <el-dialog v-model="expVisible" title="点赞 / 纠正 / 收录到经验库" width="680px" top="6vh">
      <div v-loading="expLoading">
        <div class="exp-field">
          <label>问题</label>
          <el-input v-model="exp.question" type="textarea" :rows="2" />
        </div>
        <div class="exp-field">
          <label>标准答案 <span class="hint">（直接修改即为「纠正」，不改即原样「收录」）</span></label>
          <el-input v-model="exp.answer" type="textarea" :rows="9" placeholder="该问题暂无答案记录，可手动补充" />
        </div>
      </div>
      <template #footer>
        <el-button :icon="Star" :disabled="!exp.assistantMsgId || liked" @click="doLike">
          {{ liked ? '已点赞' : '点赞' }}
        </el-button>
        <el-button @click="expVisible = false">取消</el-button>
        <el-button type="primary" :icon="Collection" :loading="saving" @click="doCollect">收录进经验库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Star, Collection, MagicStick } from '@element-plus/icons-vue'
import { qaRankingApi, type RankItem, type RankGroup } from '@/api/qaRanking'
import { goldenPairApi } from '@/api/goldenPair'

const unwrap = (r: any) => r?.data ?? r

const range = ref('7d')
const tab = ref('system')
const loading = ref(false)

const systemRows = ref<RankItem[]>([])
const kbGroups = ref<RankGroup[]>([])
const userGroups = ref<RankGroup[]>([])
const kbActive = ref<string[]>([])
const userActive = ref<string[]>([])

const fmt = (s?: string) => (s ? s.replace('T', ' ').slice(0, 16) : '-')

const loadAll = async () => {
  loading.value = true
  try {
    const d: any = unwrap(await qaRankingApi.all(range.value)) || {}
    systemRows.value = d.system || []
    kbGroups.value = d.byKb || []
    userGroups.value = d.byUser || []
    // 默认展开第一个分组
    const firstKb = kbGroups.value[0]
    const firstUser = userGroups.value[0]
    kbActive.value = firstKb ? [String(firstKb.kbId)] : []
    userActive.value = firstUser ? [String(firstUser.userId)] : []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { loading.value = false }
}

onMounted(loadAll)

// ── 经验弹窗 ──
const expVisible = ref(false)
const expLoading = ref(false)
const saving = ref(false)
const liked = ref(false)
const exp = reactive<{ question: string; answer: string; sourcesJson?: string; assistantMsgId?: number | null }>(
  { question: '', answer: '', sourcesJson: undefined, assistantMsgId: null }
)

const openExperience = async (row: RankItem) => {
  exp.question = row.question
  exp.answer = ''
  exp.sourcesJson = undefined
  exp.assistantMsgId = null
  liked.value = false
  expVisible.value = true
  if (!row.sampleMsgId) return
  expLoading.value = true
  try {
    const d: any = unwrap(await qaRankingApi.answer(row.sampleMsgId))
    exp.question = d?.question || row.question
    exp.answer = d?.answer || ''
    exp.sourcesJson = d?.sourcesJson || undefined
    exp.assistantMsgId = d?.assistantMsgId || null
  } catch { /* 静默 */ } finally { expLoading.value = false }
}

const doLike = async () => {
  if (!exp.assistantMsgId) return
  try {
    await qaRankingApi.like(exp.assistantMsgId)
    liked.value = true
    ElMessage.success('已点赞')
  } catch (e: any) {
    ElMessage.error('点赞失败：' + (e?.message || ''))
  }
}

const doCollect = async () => {
  if (!exp.question.trim() || !exp.answer.trim()) {
    ElMessage.warning('问题和答案都不能为空')
    return
  }
  saving.value = true
  try {
    await goldenPairApi.create({
      question: exp.question.trim(),
      answer: exp.answer.trim(),
      sourcesJson: exp.sourcesJson,
    })
    ElMessage.success('已收录进经验库')
    expVisible.value = false
  } catch (e: any) {
    ElMessage.error('收录失败：' + (e?.message || ''))
  } finally { saving.value = false }
}
</script>

<style scoped>
.rank-page { padding: 24px 28px; height: 100%; overflow-y: auto; }
.rank-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 8px; gap: 16px; }
.title { font-size: 20px; font-weight: 700; color: var(--ink-1); display: flex; align-items: center; gap: 10px; }
.title-tag { font-size: 12px; font-weight: 500; color: var(--brand); background: var(--brand-soft); padding: 2px 10px; border-radius: 999px; }
.desc { font-size: 13px; color: var(--ink-4); margin: 6px 0 0; }
.rank-tabs { margin-top: 8px; }
.q-text { color: var(--ink-1); }
.cnt { color: var(--brand); font-size: 14px; }
.grp-name { font-weight: 600; color: var(--ink-1); margin-right: 10px; }
.grp-cnt { margin-left: 4px; }
.empty { text-align: center; color: var(--ink-4); font-size: 13px; padding: 50px 0; }
.exp-field { margin-bottom: 14px; }
.exp-field label { display: block; font-size: 13px; color: var(--ink-2); margin-bottom: 6px; }
.exp-field .hint { color: var(--ink-4); font-weight: 400; font-size: 12px; }
/* 操作列：收录经验 */
.op-btn { font-weight: 500; gap: 5px; padding: 2px; }
.op-btn .op-ic { font-size: 15px; transition: transform .2s ease; }
.op-btn:hover .op-ic { transform: rotate(-15deg) scale(1.08); }
</style>
