<template>
  <div class="fb-page">
    <header class="page-header">
      <div class="page-header-l">
        <div class="title-row">
          <h2 class="page-title">反馈审核</h2>
          <span class="title-tag">校正反哺闭环</span>
        </div>
        <p class="page-desc">
          用户对 AI 答复的点赞/踩 + 校正内容会沉淀到这里。审核员认可后会写入 Golden Pair 库，
          相似问题再问时 AI 直接返回标准答案，**实现"AI 越用越准"**。
        </p>
      </div>
      <div class="stat-strip">
        <div class="stat-card" v-for="s in statsList" :key="s.label">
          <div class="stat-num" :style="{ color: s.color }">{{ s.value }}</div>
          <div class="stat-lbl">{{ s.label }}</div>
        </div>
      </div>
    </header>

    <main class="fb-main" v-loading="loading">
      <div class="filter-bar">
        <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:140px" @change="loadList">
          <el-option label="待审核"  value="pending" />
          <el-option label="已收录"  value="approved" />
          <el-option label="已驳回"  value="rejected" />
        </el-select>
        <el-select v-model="filterRating" placeholder="全部评分" clearable style="width:140px" @change="loadList">
          <el-option label="👍 赞"  value="up" />
          <el-option label="👎 踩"  value="down" />
        </el-select>
        <el-button :icon="Refresh" @click="loadList" />
      </div>

      <div v-if="!loading && list.length === 0" class="empty">
        <div class="empty-ring"></div>
        <p>暂无反馈</p>
      </div>

      <article
        v-for="item in list"
        :key="item.feedback.id"
        class="fb-card"
        :class="`status-${item.feedback.status}`"
      >
        <header class="fb-head">
          <span class="rating-tag" :class="item.feedback.rating">
            {{ item.feedback.rating === 'up' ? '👍 赞' : '👎 踩' }}
          </span>
          <span class="status-tag" :class="`s-${item.feedback.status}`">
            {{ statusLabel(item.feedback.status) }}
          </span>
          <span class="fb-submitter">
            <el-icon size="11"><User /></el-icon>
            {{ item.submitterName || ('#' + item.feedback.userId) }}
          </span>
          <span class="fb-time">{{ formatTime(item.feedback.createTime) }}</span>
        </header>

        <!-- 对话上下文：用户问题 → AI 答复 -->
        <div class="qa-thread">
          <div v-if="item.userQuestion" class="qa-row user">
            <span class="qa-role">问</span>
            <div class="qa-bubble">{{ item.userQuestion }}</div>
          </div>
          <div v-else class="qa-row user missing">
            <span class="qa-role">问</span>
            <div class="qa-bubble missing-text">（原问题已删除或不可追溯）</div>
          </div>
          <div v-if="item.aiAnswer" class="qa-row ai">
            <span class="qa-role">答</span>
            <div class="qa-bubble" :class="{ 'truncate-bubble': !expanded[item.feedback.id] && item.aiAnswer.length > 360 }">
              {{ item.aiAnswer }}
            </div>
          </div>
          <div v-else class="qa-row ai missing">
            <span class="qa-role">答</span>
            <div class="qa-bubble missing-text">（AI 答复已删除）</div>
          </div>
          <button
            v-if="item.aiAnswer && item.aiAnswer.length > 360"
            class="expand-btn"
            @click="expanded[item.feedback.id] = !expanded[item.feedback.id]"
          >
            {{ expanded[item.feedback.id] ? '收起' : '展开完整答复' }}
          </button>
        </div>

        <!-- 反馈附加信息 -->
        <div v-if="item.feedback.comment || item.feedback.correctionText || item.feedback.reviewerNote" class="fb-body">
          <div v-if="item.feedback.comment" class="block">
            <span class="block-label">用户评论</span>
            <div class="block-text">{{ item.feedback.comment }}</div>
          </div>
          <div v-if="item.feedback.correctionText" class="block correction">
            <span class="block-label">用户提供的正确答案</span>
            <div class="block-text correction-text">{{ item.feedback.correctionText }}</div>
          </div>
          <div v-if="item.feedback.reviewerNote" class="block">
            <span class="block-label">
              审核备注 {{ item.reviewerName ? '· ' + item.reviewerName : '' }}
            </span>
            <div class="block-text">{{ item.feedback.reviewerNote }}</div>
          </div>
        </div>
        <div v-else-if="item.feedback.status === 'pending'" class="no-extra-note">
          <el-icon size="12"><InfoFilled /></el-icon>
          用户只点了赞/踩 · 未提供评论或纠正答案
        </div>

        <footer v-if="item.feedback.status === 'pending'" class="fb-actions">
          <el-button size="small" type="primary" :icon="CircleCheck" @click="openApprove(item)">
            认可并收录为 Golden Pair
          </el-button>
          <el-button size="small" type="danger" :icon="Close" plain @click="openReject(item)">
            驳回
          </el-button>
        </footer>
        <footer v-else-if="item.feedback.goldenPairId" class="fb-link">
          ✓ 已生成 Golden Pair #{{ item.feedback.goldenPairId }}
        </footer>
      </article>

      <div v-if="total > pageSize" class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          background
          layout="prev, pager, next"
          @current-change="loadList"
        />
      </div>
    </main>

    <!-- 收录对话框 -->
    <el-dialog v-model="approveVisible" title="收录为 Golden Pair" width="640px" :close-on-click-modal="false">
      <div class="approve-hint">
        审核员可在用户答案基础上微调；提交后写入 Golden Pair 库，相似问题命中后直接返回此答案。
      </div>
      <el-input
        v-model="finalAnswer"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 18 }"
        placeholder="标准答案（可空 · 默认用用户提供的纠正文本）"
        maxlength="5000"
        show-word-limit
      />
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmApprove">收录</el-button>
      </template>
    </el-dialog>

    <!-- 驳回对话框 -->
    <el-dialog v-model="rejectVisible" title="驳回反馈" width="480px">
      <el-input v-model="rejectNote" type="textarea" :rows="4" placeholder="驳回原因（可空）" maxlength="500" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="confirmReject">驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, CircleCheck, Close, User, InfoFilled } from '@element-plus/icons-vue'
import { feedbackApi, type FeedbackDetailVO } from '@/api/feedback'
import { goldenPairApi } from '@/api/goldenPair'

const loading = ref(false)
const list = ref<FeedbackDetailVO[]>([])
const expanded = reactive<Record<number, boolean>>({})
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterStatus = ref('pending')
const filterRating = ref('')

const stats = ref({ pending: 0, approved: 0, rejected: 0 })
const goldenStats = ref({ total: 0, totalHits: 0 })

const statsList = computed(() => [
  { label: '待审核', value: stats.value.pending,  color: '#f59e0b' },
  { label: '已收录', value: stats.value.approved, color: '#34d399' },
  { label: '已驳回', value: stats.value.rejected, color: '#94a3b8' },
  { label: 'Golden Pair', value: goldenStats.value.total, color: '#38bdf8' },
  { label: '命中累计', value: goldenStats.value.totalHits, color: '#a1a1aa' },
])

async function loadList() {
  loading.value = true
  try {
    const res: any = await feedbackApi.page({
      current: currentPage.value,
      size: pageSize.value,
      status: filterStatus.value || undefined,
      rating: filterRating.value || undefined,
    })
    const data = res?.data ?? res
    list.value = data?.records || []
    total.value = data?.total || 0
  } finally { loading.value = false }
}

async function loadStats() {
  try {
    const a: any = await feedbackApi.count()
    stats.value = a?.data ?? a
  } catch {}
  try {
    const g: any = await goldenPairApi.stats()
    goldenStats.value = g?.data ?? g
  } catch {}
}

onMounted(() => { loadList(); loadStats() })

// ── 收录 ──
const approveVisible = ref(false)
const finalAnswer = ref('')
const submitting = ref(false)
const currentTarget = ref<FeedbackDetailVO | null>(null)

function openApprove(item: FeedbackDetailVO) {
  currentTarget.value = item
  // 默认值优先：用户提供的纠正 > AI 原答复（管理员可在此基础上微调）
  finalAnswer.value = item.feedback.correctionText || item.aiAnswer || ''
  approveVisible.value = true
}

async function confirmApprove() {
  if (!currentTarget.value) return
  submitting.value = true
  try {
    await goldenPairApi.fromFeedback(currentTarget.value.feedback.id, finalAnswer.value.trim() || undefined)
    ElMessage.success('已收录为 Golden Pair · AI 下次遇到相似问题会直接命中')
    approveVisible.value = false
    await Promise.all([loadList(), loadStats()])
  } catch (e: any) {
    ElMessage.error('收录失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

// ── 驳回 ──
const rejectVisible = ref(false)
const rejectNote = ref('')

function openReject(item: FeedbackDetailVO) {
  currentTarget.value = item
  rejectNote.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!currentTarget.value) return
  submitting.value = true
  try {
    await feedbackApi.reject(currentTarget.value.feedback.id, rejectNote.value)
    ElMessage.success('已驳回')
    rejectVisible.value = false
    await Promise.all([loadList(), loadStats()])
  } catch (e: any) {
    ElMessage.error('驳回失败：' + (e?.message || ''))
  } finally { submitting.value = false }
}

function statusLabel(s: string) {
  return ({ pending: '待审核', approved: '已收录', rejected: '已驳回' } as any)[s] || s
}

function formatTime(t?: string) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.fb-page { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 28px; flex-wrap: wrap; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); letter-spacing: -0.02em; }
.title-tag { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #d1fae5, #a7f3d0); color: #047857; }
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 720px; line-height: 1.65; }
.stat-strip { display: flex; gap: 10px; flex-wrap: wrap; }
.stat-card { padding: 10px 16px; background: var(--bg-surface); border: 1px solid var(--line);
  border-radius: 10px; min-width: 92px; text-align: center; }
.stat-num { font-size: 22px; font-weight: 700; font-family: 'Manrope', sans-serif; }
.stat-lbl { font-size: 11px; color: var(--ink-3); margin-top: 2px; }

.filter-bar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; }

.fb-card { background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px;
  padding: 16px 20px; margin-bottom: 14px; transition: box-shadow 0.15s; }
.fb-card:hover { box-shadow: var(--shadow-md); }
.fb-card.status-pending { border-color: #f59e0b; }
.fb-card.status-approved { border-color: #34d399; }
.fb-card.status-rejected { border-color: #94a3b8; opacity: 0.75; }

.fb-head {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 12px; flex-wrap: wrap;
}
.fb-submitter {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11.5px; color: var(--ink-3);
  padding: 2px 8px; background: var(--bg-hover); border-radius: 999px;
}
.rating-tag { font-size: 11.5px; font-weight: 600; padding: 3px 10px; border-radius: 999px; }
.rating-tag.up { background: rgba(52,211,153,0.15); color: #047857; }
.rating-tag.down { background: rgba(248,113,113,0.15); color: #b91c1c; }
.status-tag { font-size: 10.5px; font-weight: 600; padding: 2px 8px; border-radius: 999px;
  background: var(--bg-subtle); color: var(--ink-3); }
.status-tag.s-pending { background: rgba(245,158,11,0.15); color: #b45309; }
.status-tag.s-approved { background: rgba(52,211,153,0.15); color: #047857; }
.fb-time { font-size: 11.5px; color: var(--ink-4); margin-left: auto; }

/* ── 对话轨 · 用户问题 + AI 答复 ── */
.qa-thread {
  display: flex; flex-direction: column; gap: 8px;
  margin-bottom: 12px;
}
.qa-row {
  display: flex; align-items: flex-start; gap: 10px;
}
.qa-role {
  flex-shrink: 0;
  width: 28px; height: 28px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11.5px; font-weight: 700;
}
.qa-row.user .qa-role { background: rgba(56, 189, 248, 0.18); color: #0284c7; }
.qa-row.ai   .qa-role { background: rgba(0, 0, 0, 0.18); color: #0a0a0a; }
.qa-bubble {
  flex: 1;
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--ink-1);
  background: var(--bg-hover);
  padding: 10px 14px;
  border-radius: 10px;
  white-space: pre-wrap;
  word-break: break-word;
}
.qa-row.ai .qa-bubble { background: rgba(0, 0, 0, 0.06); }
.qa-bubble.truncate-bubble {
  max-height: 96px;
  overflow: hidden;
  position: relative;
  mask-image: linear-gradient(to bottom, #17181c 60%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, #17181c 60%, transparent 100%);
}
.qa-bubble.missing-text { color: var(--ink-4); font-style: italic; }
.expand-btn {
  align-self: flex-start;
  margin-left: 38px;
  font-size: 11.5px;
  color: var(--brand);
  background: none; border: none;
  cursor: pointer;
  padding: 2px 0;
}
.expand-btn:hover { text-decoration: underline; }

.no-extra-note {
  display: inline-flex; align-items: center; gap: 4px;
  margin-bottom: 12px;
  font-size: 12px; color: var(--ink-4);
  padding: 6px 12px;
  background: var(--bg-hover);
  border-radius: 6px;
}

.fb-body { display: flex; flex-direction: column; gap: 10px; }
.block { display: flex; flex-direction: column; gap: 4px; }
.block-label { font-size: 10.5px; font-weight: 700; text-transform: uppercase; color: var(--ink-4); letter-spacing: 0.04em; }
.block-text { font-size: 13.5px; color: var(--ink-1); line-height: 1.6; }
.block.correction { background: rgba(56,189,248,0.05); border: 1px solid rgba(56,189,248,0.18); padding: 10px 12px; border-radius: 8px; }
.correction-text { white-space: pre-wrap; }

.fb-actions { display: flex; gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--line); }
.fb-link { margin-top: 10px; font-size: 12px; color: var(--ink-3); }

.approve-hint { padding: 10px 14px; margin-bottom: 14px; background: rgba(52,211,153,0.08);
  border: 1px solid rgba(52,211,153,0.25); border-radius: 8px; font-size: 12.5px; color: var(--ink-2); line-height: 1.55; }

.empty { padding: 80px 0; text-align: center; color: var(--ink-3); }
.empty-ring { width: 48px; height: 48px; border: 3px solid var(--line); border-top-color: var(--brand);
  border-radius: 50%; margin: 0 auto 14px; }

.pagination { margin-top: 20px; text-align: center; }
</style>
