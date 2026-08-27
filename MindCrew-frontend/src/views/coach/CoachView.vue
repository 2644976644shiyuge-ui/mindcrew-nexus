<template>
  <div class="coach-page">
    <!-- 顶部：标题 + 入口（品牌渐变 hero） -->
    <header class="page-header">
      <div class="hero-left">
        <div class="title-row">
          <div class="hero-badge">
            <el-icon :size="18" color="#0071E3"><MagicStick /></el-icon>
          </div>
          <div>
            <h2 class="page-title">教练模式</h2>
            <span class="title-tag">AI 主动出题 · 检验学习效果</span>
          </div>
        </div>
        <p class="page-desc">
          知识库会基于你导入的资料给你出考核题。答完会给评分和讲解，错题会推荐复习章节。
          越练越精准，AI 也会越来越懂你薄弱在哪里。
        </p>
      </div>
      <div class="header-actions">
        <el-button :icon="DataAnalysis" plain @click="showStats = true">我的学习</el-button>
        <el-button type="primary" :icon="List" @click="loadHistory">历史会话</el-button>
      </div>
    </header>

    <!-- ═════════ idle 视图：仪表盘式开始页 ═════════ -->
    <template v-if="view === 'idle'">
      <!-- 顶部统计卡（仅显示后端有的真实数据 · 不 mock） -->
      <div v-if="idleStats.length" class="stat-strip-v2">
        <div v-for="s in idleStats" :key="s.label" class="stat-card-v2" :style="statCardStyle(s)">
          <div class="stat-glow"></div>
          <div class="stat-left">
            <div class="stat-label">{{ s.label }}</div>
            <div class="stat-value">{{ s.value }}</div>
            <div class="stat-sub">{{ s.sub }}</div>
          </div>
          <div class="stat-icon-wrap">
            <el-icon :size="24"><component :is="s.icon" /></el-icon>
          </div>
        </div>
      </div>

      <!-- 主体 grid · 左大 开始新练习 + 右 最近练习记录 -->
      <div class="idle-grid">
        <!-- 左：开始新练习 -->
        <section class="start-card-v2">
          <header class="start-head">
            <div class="start-icon-v2">
              <el-icon size="22" color="#0071E3"><MagicStick /></el-icon>
            </div>
            <div class="start-head-text">
              <h3>开始新练习</h3>
              <p class="start-sub">AI 为你量身定制练习题，精准提升学习效果</p>
            </div>
            <div class="start-step-pill">3 步</div>
          </header>

          <!-- 3 步进度指示 -->
          <div class="step-bar">
            <div class="step-item" :class="{ active: stepIndex >= 1, done: stepIndex > 1 }">
              <span class="step-num">1</span>
              <span class="step-name">选范围</span>
            </div>
            <div class="step-line" :class="{ filled: stepIndex > 1 }"></div>
            <div class="step-item" :class="{ active: stepIndex >= 2, done: stepIndex > 2 }">
              <span class="step-num">2</span>
              <span class="step-name">设难度</span>
            </div>
            <div class="step-line" :class="{ filled: stepIndex > 2 }"></div>
            <div class="step-item" :class="{ active: stepIndex >= 3 }">
              <span class="step-num">3</span>
              <span class="step-name">定题数</span>
            </div>
          </div>

          <div class="form-row-v2">
            <label class="form-label">
              <span class="form-label-text">
                <span class="step-tag">1</span>
                知识库范围
              </span>
              <span class="label-tip">不选 = 全量可访问</span>
            </label>
            <el-select
              v-model="startForm.collectionIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="全部知识库"
              style="width: 100%"
              :loading="kbLoading"
              size="large"
            >
              <el-option
                v-for="c in collList"
                :key="c.id"
                :label="c.name"
                :value="c.id"
              />
            </el-select>
          </div>

          <div class="form-row-v2">
            <label class="form-label">
              <span class="form-label-text">
                <span class="step-tag">2</span>
                难度等级
              </span>
              <span class="label-tip">影响题型与深度</span>
            </label>
            <div class="difficulty-row">
              <button
                v-for="d in (['easy','medium','hard'] as const)"
                :key="d"
                class="diff-btn"
                :class="['diff-' + d, { active: startForm.difficulty === d }]"
                @click="startForm.difficulty = d"
              >
                <span class="diff-icon">
                  <el-icon :size="14"><component :is="d === 'easy' ? 'Sunny' : d === 'medium' ? 'PartlyCloudy' : 'Lightning'" /></el-icon>
                </span>
                {{ d === 'easy' ? '简单' : d === 'medium' ? '中等' : '困难' }}
              </button>
            </div>
            <p class="diff-hint">{{
              startForm.difficulty === 'easy' ? '基础题，适合入门巩固' :
              startForm.difficulty === 'medium' ? '中等难度，适合巩固提升' :
              '高难度，挑战深度理解'
            }}</p>
          </div>

          <div class="form-row-v2">
            <label class="form-label">
              <span class="form-label-text">
                <span class="step-tag">3</span>
                题目数量
              </span>
              <span class="label-tip">5-30 题</span>
            </label>
            <div class="qcount-row">
              <el-input-number
                v-model="startForm.questionTotal"
                :min="3" :max="30" :step="1"
                size="large"
                controls-position="right"
              />
              <span class="qcount-unit">题</span>
              <div class="qcount-quick">
                <button v-for="n in [5, 10, 15, 20]" :key="n" class="quick-chip" :class="{ active: startForm.questionTotal === n }" @click="startForm.questionTotal = n">{{ n }}</button>
              </div>
              <span class="qcount-hint">建议 5-20 题，效果更佳</span>
            </div>
          </div>

          <button
            class="start-cta"
            :disabled="starting"
            @click="onStart"
          >
            <el-icon :size="18" v-if="!starting"><Promotion /></el-icon>
            <span>{{ starting ? `正在出题…（约 ${expectedSeconds} 秒）` : '启动练习' }}</span>
          </button>
          <p class="start-footer">
            <el-icon :size="11"><Clock /></el-icon>
            预计用时 {{ Math.max(5, Math.round(startForm.questionTotal * 1.5)) }}-{{ Math.round(startForm.questionTotal * 2.5) }} 分钟
            · AI 智能生成 · 即时反馈分析
          </p>
        </section>

        <!-- 右：最近练习记录（只有真实历史才显示，没有则隐藏） -->
        <section v-if="recentSessions.length" class="recent-card">
          <header class="recent-head">
            <div>
              <span class="recent-title">最近练习记录</span>
              <span class="recent-sub">点击查看完整成绩单</span>
            </div>
            <button class="recent-link" @click="loadHistory">
              查看全部 <el-icon :size="10"><ArrowRight /></el-icon>
            </button>
          </header>
          <div class="recent-list">
            <div
              v-for="s in recentSessions"
              :key="s.id"
              class="recent-item"
              :class="['acc-' + sessionAccTier(s)]"
              @click="openHistoryDetail(s)"
            >
              <div class="recent-strip"></div>
              <div class="recent-body">
                <div class="recent-row1">
                  <span class="recent-time">{{ formatRecentTime(s.startAt || s.createTime) }}</span>
                  <span class="recent-diff" :class="['diff-pill', 'diff-' + s.difficulty]">
                    {{ difficultyLabel(s.difficulty) }}
                  </span>
                </div>
                <div class="recent-row2">
                  <span class="recent-q">已答 {{ s.questionDone || 0 }} / {{ s.questionTotal }} 题</span>
                </div>
                <div class="recent-row3">
                  <div class="recent-bar">
                    <div class="recent-bar-fill" :style="{ width: (s.questionDone ? sessionAccuracyPct(s) : 0) + '%' }"></div>
                  </div>
                  <span class="recent-score" :style="{ color: sessionAccuracyColor(sessionAccuracyPct(s)) }">
                    {{ s.questionDone ? `${sessionAccuracyPct(s)}%` : (s.status === 'active' ? '进行中' : '未开始') }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 没有历史时给个空态提示，引导首次练习 -->
        <section v-else class="recent-card empty">
          <div class="empty-icon">
            <el-icon :size="40"><Aim /></el-icon>
          </div>
          <div class="empty-title">还没有练习记录</div>
          <div class="empty-sub">完成第一次练习后，这里会展示你的进度</div>
        </section>
      </div>
    </template>

    <!-- 练习中 -->
    <div v-if="view === 'practice'" class="practice-area">
      <!-- session 顶部 banner -->
      <div class="session-banner">
        <div>
          <div class="banner-title">
            <el-tag size="small" type="warning">{{ difficultyLabel(session?.difficulty) }}</el-tag>
            <span>第 {{ (session?.questionDone || 0) + 1 }} / {{ session?.questionTotal }} 题</span>
            <span class="banner-scope" v-if="session?.kbScopeLabel">· {{ session.kbScopeLabel }}</span>
          </div>
          <el-progress
            :percentage="((session?.questionDone || 0) / (session?.questionTotal || 1)) * 100"
            :stroke-width="6"
            :show-text="false"
            color="#0a0a0a"
          />
        </div>
        <el-button :icon="CircleClose" text @click="onAbort">退出</el-button>
      </div>

      <!-- 题目卡 -->
      <article class="q-card" v-loading="loadingQuestion" element-loading-text="出题中...">
        <header class="q-head" v-if="currentQuestion">
          <span class="seq">第 {{ currentQuestion.seq }} 题</span>
          <span class="q-type">{{ qTypeLabel(currentQuestion.questionType) }}</span>
          <span class="q-source" v-if="currentQuestion.sourceKbName">来源：{{ currentQuestion.sourceKbName }}</span>
        </header>

        <div v-if="currentQuestion" class="q-body">
          <div class="q-stem">{{ currentQuestion.question }}</div>

          <!-- 单选题 -->
          <div v-if="currentQuestion.questionType === 'single_choice' && questionOptions.length" class="q-options">
            <el-radio-group v-model="userAnswer" :disabled="!!lastAnswer">
              <el-radio
                v-for="opt in questionOptions"
                :key="opt"
                :label="extractChoiceLetter(opt)"
                :value="extractChoiceLetter(opt)"
                class="opt"
              >{{ opt }}</el-radio>
            </el-radio-group>
          </div>

          <!-- 多选题 -->
          <div v-else-if="currentQuestion.questionType === 'multiple_choice' && questionOptions.length" class="q-options">
            <el-checkbox-group v-model="userAnswer" :disabled="!!lastAnswer">
              <el-checkbox
                v-for="opt in questionOptions"
                :key="opt"
                :label="extractChoiceLetter(opt)"
                :value="extractChoiceLetter(opt)"
                class="opt"
              >{{ opt }}</el-checkbox>
            </el-checkbox-group>
          </div>

          <!-- 判断题 -->
          <div v-else-if="currentQuestion.questionType === 'true_false'" class="q-options tf">
            <el-radio-group v-model="userAnswer" :disabled="!!lastAnswer">
              <el-radio-button value="对">对</el-radio-button>
              <el-radio-button value="错">错</el-radio-button>
            </el-radio-group>
          </div>

          <!-- 短答题 -->
          <div v-else class="q-options">
            <el-input
              v-model="userAnswer"
              type="textarea"
              :rows="4"
              :disabled="!!lastAnswer"
              placeholder="请输入你的答案..."
              maxlength="500"
              show-word-limit
            />
          </div>

          <!-- 评分反馈 -->
          <div v-if="lastAnswer" class="feedback" :class="judgeClass(lastAnswer.judgment)">
            <div class="fb-head">
              <span class="fb-score">{{ lastAnswer.score }} 分</span>
              <span class="fb-judge">{{ judgeLabel(lastAnswer.judgment) }}</span>
            </div>
            <div class="fb-text">{{ lastAnswer.feedback }}</div>
            <div class="fb-expected" v-if="currentQuestion.expectedAnswer">
              <span class="lbl">标准答案</span>
              {{ currentQuestion.expectedAnswer }}
            </div>
            <div class="fb-expected" v-if="currentQuestion.sourceQuote">
              <span class="lbl">原文出处</span>
              <span style="font-style: italic;">「{{ currentQuestion.sourceQuote }}」</span>
            </div>
            <div class="fb-explain" v-if="currentQuestion.explanation">
              <span class="lbl">解析</span>
              {{ currentQuestion.explanation }}
            </div>
          </div>

          <div class="q-actions">
            <el-button
              v-if="!lastAnswer"
              type="primary"
              :loading="submitting"
              :disabled="submitDisabled"
              @click="onSubmit"
            >提交答案</el-button>
            <el-button
              v-else
              type="primary"
              :loading="loadingQuestion"
              @click="loadNext"
            >
              {{ isLastQuestion ? '查看总结' : '下一题 →' }}
            </el-button>
          </div>
        </div>
      </article>
    </div>

    <!-- 总结报告 -->
    <div v-if="view === 'finished'" class="summary-card">
      <div class="summary-medal">
        <el-icon size="56" :color="medalColor"><Trophy /></el-icon>
      </div>
      <h3>本次练习完成</h3>
      <div class="summary-stats">
        <div class="stat">
          <span class="num">{{ session?.questionDone }}</span>
          <span class="lbl">已答题</span>
        </div>
        <div class="stat">
          <span class="num">{{ session?.correctCount }}</span>
          <span class="lbl">正确</span>
        </div>
        <div class="stat">
          <span class="num">{{ avgScore }}</span>
          <span class="lbl">平均分</span>
        </div>
      </div>
      <p class="summary-msg">{{ summaryMessage }}</p>
      <div class="summary-actions">
        <el-button @click="resetToIdle">再练一次</el-button>
        <el-button type="primary" @click="showDetail = true">查看详情</el-button>
      </div>
    </div>

    <!-- 历史会话抽屉 -->
    <el-drawer v-model="historyVisible" title="我的练习历史" size="560px">
      <div class="history-list" v-loading="historyLoading">
        <article
          v-for="s in historyList"
          :key="s.id"
          class="history-item"
          @click="openHistoryDetail(s)"
        >
          <div class="row1">
            <el-tag size="small" :type="statusTagType(s.status)">{{ statusLabel(s.status) }}</el-tag>
            <span class="diff">{{ difficultyLabel(s.difficulty) }}</span>
            <span class="time">{{ formatTime(s.startAt) }}</span>
          </div>
          <div class="row2">
            <span class="scope">{{ s.kbScopeLabel || '全量知识库' }}</span>
          </div>
          <div class="row3">
            <span>{{ s.questionDone }}/{{ s.questionTotal }} 题</span>
            <span class="dot">·</span>
            <span>对 {{ s.correctCount }}</span>
            <span class="dot">·</span>
            <span>平均 {{ s.questionDone ? Math.round(s.totalScore / s.questionDone) : 0 }} 分</span>
          </div>
        </article>
        <div v-if="!historyLoading && historyList.length === 0" class="empty">
          <p>还没有练习记录</p>
        </div>
      </div>
    </el-drawer>

    <!-- 我的统计抽屉 -->
    <el-drawer v-model="showStats" title="我的学习" size="520px">
      <div v-loading="statsLoading" class="stats-panel">
        <div class="stat-grid">
          <div class="stat-card">
            <div class="card-label">练习次数</div>
            <div class="card-num">{{ stats?.sessionCount ?? 0 }}</div>
            <div class="card-sub">完成 {{ stats?.finishedSessionCount ?? 0 }}</div>
          </div>
          <div class="stat-card">
            <div class="card-label">作答题数</div>
            <div class="card-num">{{ stats?.answeredQuestionCount ?? 0 }}</div>
            <div class="card-sub">对 {{ stats?.correctCount ?? 0 }}</div>
          </div>
          <div class="stat-card">
            <div class="card-label">平均分</div>
            <div class="card-num">{{ stats?.avgScore ?? 0 }}</div>
            <div class="card-sub">满分 100</div>
          </div>
          <div class="stat-card">
            <div class="card-label">正确率</div>
            <div class="card-num">{{ accuracyPct }}</div>
            <div class="card-sub">≥80 分判为正确</div>
          </div>
        </div>

        <h4 class="stats-sub">薄弱知识库 Top 5</h4>
        <div v-if="!stats?.weakKbs || stats.weakKbs.length === 0" class="empty">
          <p>暂无足够数据</p>
        </div>
        <div v-else class="weak-list">
          <div v-for="w in stats.weakKbs" :key="w.kbId" class="weak-item">
            <div class="weak-name">{{ w.kbName }}</div>
            <div class="weak-bar">
              <div class="weak-fill" :style="{ width: w.avgScore + '%' }"></div>
            </div>
            <div class="weak-score">{{ w.avgScore }} 分 · {{ w.answered }} 题</div>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 详情对话框（完成后展示题目+答案） -->
    <el-dialog v-model="showDetail" title="练习详情" width="780px" top="5vh">
      <div v-loading="detailLoading" class="detail-list">
        <article v-for="q in detailQuestions" :key="q.id" class="detail-item">
          <div class="d-head">
            <span class="d-seq">Q{{ q.seq }}</span>
            <span class="d-type">{{ qTypeLabel(q.questionType) }}</span>
            <span class="d-source">{{ q.sourceKbName }}</span>
            <span v-if="detailAnswers[q.id]" class="d-score" :class="judgeClass(detailAnswers[q.id]?.judgment)">
              {{ detailAnswers[q.id]?.score }} 分
            </span>
          </div>
          <div class="d-q">{{ q.question }}</div>
          <div class="d-ua" v-if="detailAnswers[q.id]">
            <span class="lbl">你的答案</span>
            {{ detailAnswers[q.id]?.userAnswer || '（未作答）' }}
          </div>
          <div class="d-ea" v-if="q.expectedAnswer">
            <span class="lbl">标准答案</span>
            {{ q.expectedAnswer }}
          </div>
          <div class="d-fb" v-if="detailAnswers[q.id]">
            <span class="lbl">反馈</span>
            {{ detailAnswers[q.id]?.feedback }}
          </div>
        </article>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  MagicStick, List, DataAnalysis, CircleClose, Trophy,
  PieChart, FolderOpened, CircleCheckFilled, ArrowRight, Promotion,
  Sunny, PartlyCloudy, Lightning, Aim, Clock,
} from '@element-plus/icons-vue'
import { coachApi, type CoachSession, type CoachQuestion, type CoachAnswer, type UserStats } from '@/api/coach'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'

// ─── 知识库（collection）列表 ───
const collList = ref<KnowledgeCollection[]>([])
const kbLoading = ref(false)

async function loadKbs() {
  kbLoading.value = true
  try {
    const res: any = await collectionApi.list()
    collList.value = res?.data ?? res ?? []
  } finally { kbLoading.value = false }
}

// ─── view 切换 ───
type View = 'idle' | 'practice' | 'finished'
const view = ref<View>('idle')

const startForm = reactive({
  collectionIds: [] as number[],
  difficulty: 'medium' as 'easy' | 'medium' | 'hard',
  questionTotal: 10,
})

const session = ref<CoachSession | null>(null)
const currentQuestion = ref<CoachQuestion | null>(null)
const lastAnswer = ref<CoachAnswer | null>(null)
// 单选/判断/简答是字符串，多选是 string[]（提交时 join 成字母串）。
// 用 any 兼容 el-radio-group(string) 与 el-checkbox-group(array) 两种 v-model 类型。
const userAnswer = ref<any>('')

const starting = ref(false)
const loadingQuestion = ref(false)
const submitting = ref(false)

// 单题约 1.2 秒、加最少 4 秒底盘
const expectedSeconds = computed(() => Math.max(4, Math.round(startForm.questionTotal * 1.2)))

const questionOptions = computed<string[]>(() => {
  if (!currentQuestion.value?.options) return []
  try {
    const arr = JSON.parse(currentQuestion.value.options)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
})

const isLastQuestion = computed(() => {
  if (!session.value) return false
  return (session.value.questionDone || 0) >= (session.value.questionTotal || 0)
})

// 提交按钮可用性：多选看是否选了项，其它看是否填了内容
const submitDisabled = computed(() => {
  const v = userAnswer.value
  if (Array.isArray(v)) return v.length === 0
  return !v || !String(v).trim()
})

// ─── 启动 ───
async function onStart() {
  starting.value = true
  try {
    // 选中的知识库 → 展开成文档 ID（后端按文档 ID 出题）
    let kbIds: number[] | undefined = undefined
    if (startForm.collectionIds.length) {
      const lists = await Promise.all(
        startForm.collectionIds.map(cid => collectionApi.listDocs(cid))
      )
      const ids = lists.flatMap((r: any) => (r?.data ?? r ?? []).map((d: any) => d.id))
      kbIds = Array.from(new Set(ids))
      if (!kbIds.length) {
        ElMessage.warning('所选知识库下暂无文档，无法出题')
        starting.value = false
        return
      }
    }
    const res: any = await coachApi.startSession({
      kbIds,
      collectionIds: startForm.collectionIds,   // 传知识库 id，后端据此解析"本知识库出题规则"
      difficulty: startForm.difficulty,
      questionTotal: startForm.questionTotal,
    })
    session.value = res?.data ?? res
    view.value = 'practice'
    await loadNext()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '启动失败')
  } finally { starting.value = false }
}

async function loadNext() {
  if (!session.value) return
  if (isLastQuestion.value) {
    await refreshSession()
    view.value = 'finished'
    return
  }
  loadingQuestion.value = true
  userAnswer.value = ''
  lastAnswer.value = null
  try {
    const res: any = await coachApi.nextQuestion(session.value.id)
    currentQuestion.value = res?.data ?? res
    // 多选题答案用数组，其它用字符串
    userAnswer.value = currentQuestion.value?.questionType === 'multiple_choice' ? [] : ''
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '出题失败')
  } finally { loadingQuestion.value = false }
}

async function onSubmit() {
  if (!currentQuestion.value) return
  const raw = userAnswer.value
  // 多选 → 选中的字母拼成 "AC"；其它 → 去空白的字符串
  const ans = Array.isArray(raw) ? raw.join('') : (typeof raw === 'string' ? raw : String(raw || '')).trim()
  if (!ans) {
    ElMessage.warning('请先填写答案')
    return
  }
  submitting.value = true
  try {
    const res: any = await coachApi.submit(currentQuestion.value.id, ans)
    lastAnswer.value = res?.data ?? res
    // 拉一次最新 session，更新进度
    await refreshSession()
    // 评分完后服务端会把 expected_answer 加入新的 detail 查询；但 currentQuestion 是 next 时拿的，没有 expected
    // 这里通过详情接口补一次（仅本题）
    await fillExpected()
    if (session.value && session.value.status === 'finished') {
      // 自动跳总结
      // 但让用户先看完反馈，按"查看总结"再切
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '提交失败')
  } finally { submitting.value = false }
}

async function fillExpected() {
  if (!session.value || !currentQuestion.value) return
  try {
    const res: any = await coachApi.sessionDetail(session.value.id)
    const data = res?.data ?? res
    const qs: CoachQuestion[] = data?.questions || []
    const matched = qs.find(q => q.id === currentQuestion.value!.id)
    if (matched) {
      currentQuestion.value.expectedAnswer = matched.expectedAnswer
      currentQuestion.value.explanation = matched.explanation
    }
  } catch {}
}

async function refreshSession() {
  if (!session.value) return
  try {
    const res: any = await coachApi.getSession(session.value.id)
    session.value = res?.data ?? res
  } catch {}
}

async function onAbort() {
  if (!session.value) return
  try {
    await ElMessageBox.confirm('退出后本次练习不可继续，确认？', '提示', { type: 'warning' })
    await coachApi.endSession(session.value.id)
    resetToIdle()
  } catch {}
}

function resetToIdle() {
  session.value = null
  currentQuestion.value = null
  lastAnswer.value = null
  userAnswer.value = ''
  view.value = 'idle'
}

// ─── 总结 ───
const avgScore = computed(() => {
  if (!session.value || !session.value.questionDone) return 0
  return Math.round((session.value.totalScore || 0) / session.value.questionDone)
})
const medalColor = computed(() => {
  if (avgScore.value >= 85) return '#f59e0b'
  if (avgScore.value >= 60) return '#38bdf8'
  return '#94a3b8'
})
const summaryMessage = computed(() => {
  if (avgScore.value >= 90) return '非常出色！知识掌握扎实。'
  if (avgScore.value >= 75) return '不错的水平，仍有提升空间。'
  if (avgScore.value >= 60) return '基础已建立，建议复习薄弱章节再练。'
  return '建议先回到资料里把关键点过一遍，再来挑战。'
})

// ─── 历史 ───
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<CoachSession[]>([])
async function loadHistory() {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const res: any = await coachApi.mySessions({ current: 1, size: 30 })
    const data = res?.data ?? res
    historyList.value = data?.records || []
  } finally { historyLoading.value = false }
}

async function openHistoryDetail(s: CoachSession) {
  session.value = s
  await openSessionDetail(s.id)
  historyVisible.value = false
  showDetail.value = true
}

// ─── 我的统计 ───
const showStats = ref(false)
const statsLoading = ref(false)
const stats = ref<UserStats | null>(null)
async function loadStats() {
  statsLoading.value = true
  try {
    const res: any = await coachApi.myStats()
    stats.value = res?.data ?? res
  } finally { statsLoading.value = false }
}
const accuracyPct = computed(() => {
  if (!stats.value || !stats.value.accuracy) return '0%'
  return Math.round(stats.value.accuracy * 100) + '%'
})

import { watch } from 'vue'
watch(showStats, v => { if (v) loadStats() })

// ─── 详情 ───
const showDetail = ref(false)
const detailLoading = ref(false)
const detailQuestions = ref<CoachQuestion[]>([])
const detailAnswers = ref<Record<number, CoachAnswer>>({})

async function openSessionDetail(sessionId: number) {
  detailLoading.value = true
  try {
    const res: any = await coachApi.sessionDetail(sessionId)
    const data = res?.data ?? res
    detailQuestions.value = data?.questions || []
    detailAnswers.value = data?.answers || {}
  } finally { detailLoading.value = false }
}

watch(showDetail, async v => {
  if (v && session.value) {
    await openSessionDetail(session.value.id)
  }
})

// ─── helpers ───
function extractChoiceLetter(opt: string): string {
  // "A. xxx" → "A"
  if (!opt) return ''
  const m = opt.match(/^\s*([A-D])[\.、\s]/i)
  if (m && m[1]) return m[1].toUpperCase()
  return opt.charAt(0).toUpperCase()
}

function qTypeLabel(t?: string) {
  return {
    short_answer: '简答题',
    single_choice: '单选题',
    multiple_choice: '多选题',
    true_false: '判断题',
  }[t || ''] || '简答题'
}

function difficultyLabel(d?: string) {
  return { easy: '简单', medium: '中等', hard: '困难' }[d || ''] || '中等'
}

function judgeLabel(j?: string) {
  return { correct: '正确', partial: '部分正确', wrong: '错误' }[j || ''] || ''
}

function judgeClass(j?: string) {
  return {
    'fb-correct': j === 'correct',
    'fb-partial': j === 'partial',
    'fb-wrong': j === 'wrong',
  }
}

function statusLabel(s?: string) {
  return { active: '进行中', finished: '已完成', abandoned: '已退出' }[s || ''] || s
}

function statusTagType(s?: string): any {
  return { active: 'primary', finished: 'success', abandoned: 'info' }[s || ''] || ''
}

function formatTime(t?: string) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

// 用于 idle 视图右侧"最近练习记录"展示
const recentSessions = ref<CoachSession[]>([])
async function loadRecentSessions() {
  try {
    const res: any = await coachApi.mySessions({ current: 1, size: 5 })
    const data = res?.data ?? res
    recentSessions.value = data?.records || []
  } catch {}
}

function formatRecentTime(t?: string) {
  if (!t) return ''
  const d = new Date(t)
  const pad = (n: number) => n < 10 ? '0' + n : '' + n
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function sessionAccuracyPct(s: CoachSession): number {
  const done = s.questionDone || 0
  if (done === 0) return 0
  return Math.round(((s.correctCount || 0) / done) * 100)
}

function sessionAccuracyColor(pct: number): string {
  if (pct >= 80) return '#10b981'
  if (pct >= 60) return '#f59e0b'
  return '#ef4444'
}

// idle 视图顶部 4 个统计卡 · 只显示后端真实数据，没有的不展示（不 mock）
// 参考主页柔和低饱和度 pastel 配色：浅色文字渐变 + 淡色背景块
const STAT_PALETTE = [
  { gradient: 'linear-gradient(135deg, #1D1D1F, #6E6E73)',   glow: '#0071E3', iconBg: '#EBF3FF', iconColor: '#0071E3' }, // 蓝灰
  { gradient: 'linear-gradient(135deg, #1D1D1F, #34C759)',   glow: '#34C759', iconBg: '#E8F8ED', iconColor: '#34C759' }, // 绿
  { gradient: 'linear-gradient(135deg, #1D1D1F, #FF9F0A)',   glow: '#FF9F0A', iconBg: '#FFF1E0', iconColor: '#FF9F0A' }, // 橙
  { gradient: 'linear-gradient(135deg, #1D1D1F, #FF2D55)',   glow: '#FF2D55', iconBg: '#FFE4EC', iconColor: '#FF2D55' }, // 粉
]
const idleStats = computed(() => {
  if (!stats.value) return []
  const items: Array<{ label: string; value: string | number; sub: string; icon: string; palette: typeof STAT_PALETTE[number] }> = []
  if (typeof stats.value.answeredQuestionCount === 'number') {
    items.push({
      label: '累计题目', value: stats.value.answeredQuestionCount, sub: '完成练习',
      icon: 'FolderOpened', palette: STAT_PALETTE[0]!,
    })
  }
  if (typeof stats.value.correctCount === 'number') {
    items.push({
      label: '答对题数', value: stats.value.correctCount, sub: '历史累计',
      icon: 'CircleCheckFilled', palette: STAT_PALETTE[1]!,
    })
  }
  if (typeof stats.value.accuracy === 'number') {
    items.push({
      label: '正确率', value: Math.round(stats.value.accuracy * 100) + '%', sub: '历史平均',
      icon: 'PieChart', palette: STAT_PALETTE[2]!,
    })
  }
  if (typeof stats.value.sessionCount === 'number') {
    items.push({
      label: '练习场次', value: stats.value.sessionCount, sub: '历史累计',
      icon: 'Trophy', palette: STAT_PALETTE[3]!,
    })
  }
  return items
})
/** 注入统计卡的渐变 CSS 变量 */
function statCardStyle(s: { palette: typeof STAT_PALETTE[number] }) {
  return {
    '--value-gradient': s.palette.gradient,
    '--icon-bg': s.palette.iconBg,
    '--icon-color': '#fff',
    '--icon-shadow': s.palette.glow + '40',
  } as Record<string, string>
}

// ═══ 步骤进度指示（按当前填写状态高亮）═══
const stepIndex = computed(() => {
  const s = startForm
  if (s.questionTotal && s.difficulty) return 3
  if (s.difficulty) return 2
  if (s.collectionIds && s.collectionIds.length) return 1
  return 0
})

/** 记录正确率分级 · 给历史卡片色条 + 大字 */
function sessionAccTier(s: { questionDone?: number; status?: string }): 'high' | 'mid' | 'low' | 'idle' {
  if (!s.questionDone) return 'idle'
  const acc = sessionAccuracyPct(s as any)
  if (acc >= 80) return 'high'
  if (acc >= 50) return 'mid'
  return 'low'
}

onMounted(() => {
  loadKbs()
  loadStats()
  loadRecentSessions()
})
</script>

<style scoped>
.coach-page {
  padding: 28px 32px 48px;
  height: 100%;
  overflow-y: auto;
  background: var(--bg-page);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 32px;
  flex-wrap: wrap;
}
.hero-left { flex: 1; min-width: 280px; }
.title-row { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.hero-badge {
  width: 44px; height: 44px;
  border-radius: 13px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #EBF3FF, #DBEAFE);
  color: #0071E3;
  box-shadow: 0 4px 12px rgba(0, 113, 227, .12);
}
.page-title { font-size: 26px; font-weight: 700; color: var(--ink-1); letter-spacing: -.01em; margin: 0 0 4px; line-height: 1.1; }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 4px 10px; border-radius: 999px;
  background: #F5F5F7; color: #6E6E73;
  letter-spacing: .02em;
}
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 680px; line-height: 1.7; margin: 0; }
.header-actions { display: flex; gap: 8px; align-items: center; }

/* ════════════════════════════════════════════════════
 * idle 视图 v2 · 仪表盘式开始页
 * ════════════════════════════════════════════════════ */

/* 顶部 4 卡 · 渐变 + 数字滚动 + 微光晕 */
.stat-strip-v2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
  margin-bottom: 22px;
}
.stat-card-v2 {
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 22px 24px;
  transition: all .25s cubic-bezier(.22, 1, .36, 1);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .04);
}
.stat-glow {
  position: absolute;
  top: -40px; right: -40px;
  width: 120px; height: 120px;
  border-radius: 50%;
  opacity: .18;
  pointer-events: none;
  filter: blur(8px);
  transition: opacity .3s;
}
.stat-card-v2:hover {
  border-color: var(--line-strong);
  transform: translateY(-2px);
  box-shadow: 0 12px 28px rgba(15, 23, 42, .08);
}
.stat-card-v2:hover .stat-glow { opacity: .28; }
.stat-left { display: flex; flex-direction: column; gap: 6px; position: relative; z-index: 1; }
.stat-label { font-size: 12.5px; color: var(--ink-3); font-weight: 500; letter-spacing: .02em; }
.stat-value {
  font-size: 34px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1;
  font-family: 'Manrope', -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--ink-1);
  font-variant-numeric: tabular-nums;
}
.stat-sub { font-size: 11.5px; color: var(--ink-4); font-weight: 500; }
.stat-icon-wrap {
  width: 50px; height: 50px;
  border-radius: 14px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--icon-bg, var(--ink-4));
  color: var(--icon-color, #fff);
  flex-shrink: 0;
  position: relative; z-index: 1;
  box-shadow: 0 6px 16px var(--icon-shadow, rgba(0, 0, 0, .12));
  transition: transform .25s ease;
}
.stat-card-v2:hover .stat-icon-wrap { transform: scale(1.08) rotate(-6deg); }

/* 主体 grid */
.idle-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
@media (max-width: 1100px) {
  .idle-grid { grid-template-columns: 1fr; }
}

/* 左 · 开始新练习 */
.start-card-v2 {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 28px 32px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}
.start-head {
  display: flex; gap: 14px; align-items: center;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line-soft);
  margin-bottom: 22px;
}
.start-icon-v2 {
  flex-shrink: 0;
  width: 48px; height: 48px;
  border-radius: 13px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #EBF3FF, #DBEAFE);
  color: #0071E3;
  box-shadow: 0 4px 12px rgba(0, 113, 227, .10);
  position: relative;
}
.start-icon-v2::after {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: 16px;
  background: linear-gradient(135deg, #EBF3FF, #DBEAFE);
  opacity: .4;
  z-index: -1;
  filter: blur(10px);
}
.start-head-text { flex: 1; }
.start-card-v2 h3 { font-size: 19px; font-weight: 700; color: var(--ink-1); margin: 0 0 4px; letter-spacing: -.01em; }
.start-sub { font-size: 12.5px; color: var(--ink-3); margin: 0; }
.start-step-pill {
  font-size: 10.5px; font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  background: #F5F5F7;
  color: #1D1D1F;
  letter-spacing: .05em;
}

/* ═══ 3 步进度条 ═══ */
.step-bar {
  display: flex;
  align-items: center;
  gap: 0;
  margin-bottom: 22px;
  padding: 14px 16px;
  background: var(--bg-soft, #F8FAFC);
  border-radius: 12px;
  border: 1px solid var(--line-soft);
}
.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-4);
  font-size: 12.5px;
  font-weight: 500;
  transition: color .2s;
}
.step-item.active { color: var(--ink-1); font-weight: 600; }
.step-item.done { color: #10B981; }
.step-num {
  width: 22px; height: 22px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--bg-surface);
  border: 1.5px solid var(--line);
  font-size: 11px; font-weight: 700;
  color: var(--ink-4);
  transition: all .2s;
}
.step-item.active .step-num {
  background: #0071E3;
  border-color: transparent;
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 113, 227, .20);
}
.step-item.done .step-num {
  background: #34C759;
  border-color: transparent;
  color: #fff;
}
.step-line {
  flex: 1;
  height: 2px;
  background: var(--line);
  margin: 0 10px;
  border-radius: 1px;
  transition: background .25s;
}
.step-line.filled { background: rgba(0, 113, 227, .35); }

.form-row-v2 { margin-bottom: 20px; }
.form-label {
  display: flex; justify-content: space-between; align-items: baseline;
  font-size: 13px; font-weight: 600; color: var(--ink-2);
  margin-bottom: 10px;
}
.form-label-text { display: inline-flex; align-items: center; gap: 8px; }
.step-tag {
  width: 18px; height: 18px;
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  background: #EBF3FF;
  color: #0071E3;
  font-size: 10px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.label-tip { font-size: 11.5px; color: var(--ink-4); font-weight: 400; }

/* ═══ 难度三按钮（颜色语义）═══ */
.difficulty-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.diff-btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 12px 0;
  font-size: 13.5px; font-weight: 500;
  background: var(--bg-surface);
  border: 1.5px solid var(--line);
  border-radius: 11px;
  color: var(--ink-2);
  cursor: pointer;
  transition: all .2s cubic-bezier(.22, 1, .36, 1);
}
.diff-btn .diff-icon { display: inline-flex; opacity: .55; transition: opacity .2s; }
.diff-btn:hover { border-color: var(--line-strong); transform: translateY(-1px); }
.diff-btn:hover .diff-icon { opacity: 1; }
.diff-btn.diff-easy.active {
  background: #E8F8ED;
  border-color: #34C759;
  color: #1D1D1F;
  box-shadow: 0 2px 8px rgba(52, 199, 89, .12);
}
.diff-btn.diff-easy.active .diff-icon { color: #34C759; opacity: 1; }
.diff-btn.diff-medium.active {
  background: #FFF1E0;
  border-color: #FF9F0A;
  color: #1D1D1F;
  box-shadow: 0 2px 8px rgba(255, 159, 10, .12);
}
.diff-btn.diff-medium.active .diff-icon { color: #FF9F0A; opacity: 1; }
.diff-btn.diff-hard.active {
  background: #FFE4EC;
  border-color: #FF2D55;
  color: #1D1D1F;
  box-shadow: 0 2px 8px rgba(255, 45, 85, .12);
}
.diff-btn.diff-hard.active .diff-icon { color: #FF2D55; opacity: 1; }
.diff-hint { margin-top: 8px; font-size: 11.5px; color: var(--ink-4); }

/* ═══ 题目数量 + 快速选择 chips ═══ */
.qcount-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.qcount-unit { font-size: 13px; color: var(--ink-2); font-weight: 500; }
.qcount-quick { display: inline-flex; gap: 4px; margin-left: 4px; }
.quick-chip {
  padding: 4px 11px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: var(--bg-surface);
  color: var(--ink-2);
  border-radius: 999px;
  cursor: pointer;
  transition: all .18s;
  font-variant-numeric: tabular-nums;
}
.quick-chip:hover { border-color: var(--line-strong); color: var(--ink-1); }
.quick-chip.active {
  background: #1D1D1F;
  border-color: transparent;
  color: #fff;
  box-shadow: 0 2px 8px rgba(29, 29, 31, .15);
}
.qcount-hint { margin-left: auto; font-size: 11.5px; color: var(--ink-4); }

/* ═══ CTA 启动按钮 · 黑色实底（参考主页"实时思考过程"badge 风格）═══ */
.start-cta {
  display: flex; align-items: center; justify-content: center; gap: 10px;
  width: 100%;
  padding: 15px;
  background: #1D1D1F;
  color: #fff;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: .02em;
  cursor: pointer;
  transition: all .25s cubic-bezier(.22, 1, .36, 1);
  box-shadow: 0 6px 18px rgba(29, 29, 31, .18);
  position: relative;
  overflow: hidden;
}
.start-cta::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, transparent 30%, rgba(255,255,255,.10) 50%, transparent 70%);
  transform: translateX(-100%);
  transition: transform .6s;
}
.start-cta:hover:not(:disabled) {
  transform: translateY(-1px);
  background: #17181c;
  box-shadow: 0 10px 24px rgba(29, 29, 31, .25);
}
.start-cta:hover:not(:disabled)::before { transform: translateX(100%); }
.start-cta:disabled {
  opacity: .55;
  cursor: not-allowed;
  background: #86868B;
  box-shadow: none;
}
.start-footer {
  margin-top: 12px;
  font-size: 11.5px;
  color: var(--ink-4);
  text-align: center;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  justify-content: center;
  width: 100%;
}
.start-footer .el-icon { color: var(--ink-3); }

/* 右 · 最近练习记录 */
.recent-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 24px 26px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}
.recent-head {
  display: flex; justify-content: space-between; align-items: flex-start;
  margin-bottom: 16px;
  gap: 8px;
}
.recent-title { font-size: 15px; font-weight: 700; color: var(--ink-1); display: block; }
.recent-sub { font-size: 11px; color: var(--ink-4); display: block; margin-top: 2px; }
.recent-link {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 12px; color: #1D1D1F; background: none; border: none; cursor: pointer;
  font-weight: 600;
  padding: 6px 10px;
  border-radius: 8px;
  transition: background .15s;
}
.recent-link:hover { background: #F5F5F7; }

.recent-list { display: flex; flex-direction: column; gap: 10px; }
.recent-item {
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 14px;
  padding: 14px 16px 14px 18px;
  border-radius: 12px;
  cursor: pointer;
  background: var(--bg-soft, #F8FAFC);
  border: 1px solid var(--line-soft);
  transition: all .2s ease;
  overflow: hidden;
}
.recent-item:hover {
  background: var(--bg-surface);
  border-color: var(--line);
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(15, 23, 42, .06);
}
/* 左色条按正确率着色 */
.recent-strip {
  position: absolute;
  left: 0; top: 0; bottom: 0;
  width: 4px;
  border-radius: 12px 0 0 12px;
}
.recent-item.acc-high .recent-strip { background: #34C759; }
.recent-item.acc-mid  .recent-strip { background: #FF9F0A; }
.recent-item.acc-low  .recent-strip { background: #FF3B30; }
.recent-item.acc-idle .recent-strip { background: #86868B; }

.recent-body { flex: 1; min-width: 0; }
.recent-row1 { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
.recent-row2 { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.recent-row3 { display: flex; align-items: center; gap: 10px; }

.recent-time { font-size: 12.5px; color: var(--ink-2); font-weight: 600; font-variant-numeric: tabular-nums; }
.recent-meta { font-size: 12px; color: var(--ink-3); }
.recent-diff { display: none; }  /* 旧的已不用 */
.recent-q { font-size: 11.5px; color: var(--ink-3); }

/* 难度 pill（带颜色） */
.diff-pill {
  display: inline-block;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}
.diff-pill.diff-easy   { background: #DCFCE7; color: #047857; }
.diff-pill.diff-medium { background: #FEF3C7; color: #B45309; }
.diff-pill.diff-hard   { background: #FEE2E2; color: #B91C1C; }

/* 正确率小进度条 + 数字 */
.recent-bar {
  flex: 1;
  height: 4px;
  background: var(--line);
  border-radius: 4px;
  overflow: hidden;
}
.recent-bar-fill {
  height: 100%;
  background: #34C759;
  border-radius: 4px;
}
.recent-item.acc-mid  .recent-bar-fill { background: #FF9F0A; }
.recent-item.acc-low  .recent-bar-fill { background: #FF3B30; }
.recent-item.acc-idle .recent-bar-fill { background: #86868B; }
.recent-score {
  font-size: 13px;
  font-weight: 700;
  font-family: 'Manrope', -apple-system, sans-serif;
  font-variant-numeric: tabular-nums;
  min-width: 48px;
  text-align: right;
}

/* 空态 */
.recent-card.empty {
  text-align: center;
  padding: 56px 24px;
  background: linear-gradient(180deg, var(--bg-surface), var(--bg-soft, #F8FAFC));
}
.empty-icon {
  display: inline-flex;
  align-items: center; justify-content: center;
  width: 64px; height: 64px;
  border-radius: 18px;
  background: linear-gradient(135deg, #F5F5F7, #E8E8ED);
  color: #86868B;
  margin-bottom: 16px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, .04);
}
.empty-title { font-size: 14px; font-weight: 600; color: var(--ink-1); margin-bottom: 6px; }
.empty-sub { font-size: 12px; color: var(--ink-3); }

/* ── start card (legacy · 已不再渲染，保留避免覆盖问题) ── */
.start-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 36px 40px;
  max-width: 640px;
  margin: 24px auto;
  box-shadow: var(--shadow-sm);
}
.start-icon {
  width: 78px; height: 78px; margin: 0 auto 14px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #ede9fe, #e4e4e7);
  border-radius: 50%;
}
.start-card h3 { text-align: center; font-size: 20px; font-weight: 700; margin-bottom: 6px; color: var(--ink-1); }
.start-card .hint { text-align: center; font-size: 13px; color: var(--ink-3); margin-bottom: 26px; }
.start-card .prepare-hint {
  text-align: center; font-size: 12px; color: var(--ink-4);
  margin-top: 12px; line-height: 1.6;
}
.form-row { margin-bottom: 18px; }
.form-row label { display: block; font-size: 12.5px; color: var(--ink-2); font-weight: 600; margin-bottom: 8px; }

/* ── practice ── */
.practice-area { max-width: 820px; margin: 0 auto; }
.session-banner {
  display: flex; justify-content: space-between; align-items: flex-end; gap: 16px;
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px;
  padding: 14px 20px; margin-bottom: 16px;
}
.session-banner > div:first-child { flex: 1; }
.banner-title {
  display: flex; align-items: center; gap: 10px;
  font-size: 13.5px; color: var(--ink-2); font-weight: 600; margin-bottom: 8px;
}
.banner-scope { color: var(--ink-4); font-weight: 400; }

.q-card {
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 14px;
  padding: 24px 28px; min-height: 280px;
}
.q-head {
  display: flex; gap: 10px; align-items: center;
  font-size: 11.5px; color: var(--ink-4); margin-bottom: 14px;
  font-family: 'JetBrains Mono', monospace;
}
.q-head .seq { font-weight: 700; color: var(--brand); }
.q-head .q-type {
  padding: 2px 8px; border-radius: 999px;
  background: rgba(0, 0, 0, 0.1); color: #0a0a0a; font-weight: 600;
}
.q-head .q-source { margin-left: auto; font-style: italic; }

.q-stem {
  font-size: 15.5px; font-weight: 600; color: var(--ink-1);
  margin-bottom: 18px; line-height: 1.7;
}
.q-options { margin-bottom: 22px; }
.q-options .opt { display: block; margin-bottom: 10px; font-size: 14px; }
.q-options.tf { display: flex; gap: 10px; }

/* feedback */
.feedback {
  border-radius: 10px; padding: 16px 18px; margin-bottom: 18px;
  border: 1px solid;
}
.feedback.fb-correct { background: rgba(52, 211, 153, 0.07); border-color: #34d399; }
.feedback.fb-partial { background: rgba(245, 158, 11, 0.07); border-color: #f59e0b; }
.feedback.fb-wrong   { background: rgba(239, 68, 68, 0.07);  border-color: #ef4444; }

.fb-head { display: flex; gap: 10px; align-items: baseline; margin-bottom: 8px; }
.fb-score { font-size: 22px; font-weight: 800; color: var(--ink-1); }
.fb-judge { font-size: 13px; font-weight: 600; color: var(--ink-2); }
.fb-text { font-size: 13.5px; color: var(--ink-2); line-height: 1.7; margin-bottom: 10px; }
.fb-expected, .fb-explain {
  font-size: 12.5px; color: var(--ink-3); line-height: 1.7;
  background: rgba(255,255,255,0.5); padding: 8px 10px; border-radius: 6px; margin-bottom: 6px;
}
.fb-expected .lbl, .fb-explain .lbl {
  display: inline-block; margin-right: 6px; padding: 1px 7px; border-radius: 4px;
  background: var(--bg-hover); color: var(--ink-2); font-weight: 600; font-size: 11px;
}

.q-actions { text-align: right; }

/* ── summary ── */
.summary-card {
  max-width: 540px; margin: 60px auto; text-align: center;
  background: var(--bg-surface); border: 1px solid var(--line); border-radius: 16px;
  padding: 50px 40px;
}
.summary-medal {
  width: 100px; height: 100px; margin: 0 auto 16px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #fef3c7, #fde68a); border-radius: 50%;
}
.summary-card h3 { font-size: 22px; font-weight: 700; color: var(--ink-1); margin-bottom: 28px; }
.summary-stats { display: flex; justify-content: center; gap: 50px; margin-bottom: 22px; }
.summary-stats .stat { display: flex; flex-direction: column; align-items: center; }
.summary-stats .num { font-size: 30px; font-weight: 800; color: var(--brand); }
.summary-stats .lbl { font-size: 12px; color: var(--ink-3); margin-top: 4px; }
.summary-msg { color: var(--ink-2); font-size: 14px; margin-bottom: 26px; }
.summary-actions { display: flex; gap: 10px; justify-content: center; }

/* ── history drawer ── */
.history-list { display: flex; flex-direction: column; gap: 12px; padding: 0 4px; }
.history-item {
  border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px;
  cursor: pointer; transition: all 0.15s;
}
.history-item:hover { border-color: var(--brand); background: var(--bg-hover); }
.history-item .row1 { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--ink-3); margin-bottom: 6px; }
.history-item .row1 .time { margin-left: auto; }
.history-item .row1 .diff { color: var(--ink-4); }
.history-item .row2 { font-size: 13px; color: var(--ink-2); font-weight: 600; margin-bottom: 4px; }
.history-item .row3 { font-size: 11.5px; color: var(--ink-4); display: flex; gap: 6px; }
.history-item .dot { color: var(--line); }

/* ── stats drawer ── */
.stats-panel { padding: 4px; }
.stat-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 26px;
}
.stat-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 16px 18px;
  background: var(--bg-surface);
}
.stat-card .card-label { font-size: 11.5px; color: var(--ink-4); font-weight: 600; }
.stat-card .card-num { font-size: 28px; font-weight: 800; color: var(--ink-1); margin: 6px 0 2px; }
.stat-card .card-sub { font-size: 11px; color: var(--ink-4); }

.stats-sub { font-size: 13px; font-weight: 700; color: var(--ink-2); margin: 14px 0 12px; }
.weak-list { display: flex; flex-direction: column; gap: 10px; }
.weak-item { font-size: 12.5px; color: var(--ink-2); }
.weak-name { margin-bottom: 4px; font-weight: 600; }
.weak-bar { height: 6px; background: var(--bg-hover); border-radius: 3px; overflow: hidden; }
.weak-fill { height: 100%; background: linear-gradient(90deg, #ef4444, #f59e0b 60%, #34d399); }
.weak-score { font-size: 11px; color: var(--ink-4); margin-top: 3px; }

/* ── detail ── */
.detail-list { max-height: 70vh; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.detail-item {
  border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px;
}
.d-head {
  display: flex; gap: 8px; align-items: center;
  font-size: 11.5px; color: var(--ink-4); margin-bottom: 8px;
}
.d-head .d-seq { font-weight: 700; color: var(--brand); }
.d-head .d-type {
  padding: 1px 7px; border-radius: 4px;
  background: rgba(0, 0, 0, 0.1); color: #0a0a0a; font-weight: 600;
}
.d-head .d-source { margin-left: auto; font-style: italic; }
.d-head .d-score { padding: 1px 8px; border-radius: 4px; font-weight: 700; }
.d-head .d-score.fb-correct { background: rgba(52, 211, 153, 0.15); color: #047857; }
.d-head .d-score.fb-partial { background: rgba(245, 158, 11, 0.15); color: #b45309; }
.d-head .d-score.fb-wrong { background: rgba(239, 68, 68, 0.15); color: #b91c1c; }

.d-q { font-size: 13.5px; font-weight: 600; color: var(--ink-1); margin-bottom: 8px; }
.d-ua, .d-ea, .d-fb { font-size: 12.5px; color: var(--ink-2); line-height: 1.7; margin-bottom: 4px; }
.d-ua .lbl, .d-ea .lbl, .d-fb .lbl {
  display: inline-block; margin-right: 6px; padding: 1px 7px; border-radius: 4px;
  background: var(--bg-hover); color: var(--ink-3); font-weight: 600; font-size: 11px;
}

.empty { padding: 40px 0; text-align: center; color: var(--ink-3); }
</style>
