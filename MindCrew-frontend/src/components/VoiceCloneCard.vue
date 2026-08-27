<template>
  <div class="voice-clone-card">
    <header class="vc-header">
      <div>
        <div class="vc-title">
          <el-icon size="16" color="#0a0a0a"><Mic /></el-icon>
          <span>音色管理</span>
          <span class="vc-tag">{{ isAdmin ? '系统预设音色' : '原始音色 + 自定义' }}</span>
        </div>
        <p class="vc-desc" v-if="isAdmin">
          管理员新建的音色会作为<strong>系统预设</strong>，对全体用户可见、可在电话/朗读中选用。
          录一段或上传 10-30 秒清晰人声即可 AI 复刻。
        </p>
        <p class="vc-desc" v-else>
          原始音色为系统预置（可控制是否在电话列表显示）；自定义音色由你录一段或上传 10-30 秒清晰人声 AI 复刻。
          语音通话 / TTS 朗读都能用。自定义音色数量不限。
        </p>
      </div>
      <el-button
        type="primary"
        :icon="Microphone"
        @click="openClone"
        :disabled="loading"
      >新建音色</el-button>
    </header>

    <!-- 原始（系统预置）音色 · 打电话界面可选的官方音色 -->
    <div class="preset-section" v-loading="loading">
      <div class="section-head">
        <span class="section-title">{{ isAdmin ? '系统预设音色' : '原始音色 · 系统预置' }}</span>
        <span class="section-hint">打电话 / 朗读可选的官方音色。关掉开关后不在电话列表显示。</span>
      </div>
      <div v-if="!loading && !presetVoices.length" class="vc-empty small">
        <div class="empty-sub">暂无预置音色</div>
      </div>
      <div
        v-for="v in presetVoices"
        :key="v.id"
        class="voice-item preset"
        :class="{ disabled: v.enabled === 0 }"
      >
        <div class="vi-left">
          <div class="vi-avatar" :style="{ background: avatarColor(v) }">
            {{ (v.name || '?').charAt(0).toUpperCase() }}
          </div>
          <div class="vi-meta">
            <div class="vi-name">{{ v.name }}</div>
            <div class="vi-sub">
              <span class="status-tag preset-tag">原始</span>
              <span v-if="v.isDefault === 1" class="status-tag default-tag">默认</span>
              <span class="vi-gender">{{ genderLabel(v.gender) }}</span>
              <span v-if="v.description" class="vi-time">· {{ v.description }}</span>
            </div>
          </div>
        </div>
        <div class="vi-actions">
          <div class="show-toggle">
            <span class="toggle-label">电话列表显示</span>
            <el-switch
              :model-value="v.enabled === 1"
              :loading="togglingId === v.id"
              :disabled="!isAdmin"
              @change="(val: any) => toggleShow(v, !!val)"
            />
          </div>
          <el-button
            :icon="VideoPlay"
            size="small"
            :loading="playingId === v.id"
            @click="testPlay(v)"
          >试听</el-button>
          <el-button
            v-if="isAdmin"
            :icon="Delete"
            size="small"
            type="danger"
            plain
            @click="onDelete(v)"
          >删除</el-button>
        </div>
      </div>
    </div>

    <!-- 普通用户：我的自定义音色（原状）；管理员：仅当有早前遗留的个人音色时显示，可一键迁移为系统预设 -->
    <template v-if="!isAdmin || myVoices.length">
    <div class="section-head custom-head">
      <span class="section-title">{{ isAdmin ? '待迁移的个人音色' : '我的自定义音色' }}</span>
      <span v-if="isAdmin" class="section-hint">你早前创建的个人音色，点「转为预设」即可迁移为系统预设供全员使用。</span>
    </div>

    <!-- 音色卡片网格 -->
    <div v-loading="loading" class="vc-list">
      <div v-if="!isAdmin && !loading && !myVoices.length" class="vc-empty">
        <div class="empty-emoji">🎤</div>
        <div class="empty-title">还没有自定义音色</div>
        <div class="empty-sub">点「新建音色」录一段你自己的声音</div>
      </div>

      <div
        v-for="v in myVoices"
        :key="v.id"
        class="voice-item"
        :class="`status-${v.status || 'ready'}`"
      >
        <div class="vi-left">
          <div class="vi-avatar" :style="{ background: avatarColor(v) }">
            {{ (v.name || '?').charAt(0).toUpperCase() }}
          </div>
          <div class="vi-meta">
            <div class="vi-name">{{ v.name }}</div>
            <div class="vi-sub">
              <span v-if="v.status === 'cloning'" class="status-tag cloning">复刻中…</span>
              <span v-else-if="v.status === 'failed'" class="status-tag failed">复刻失败</span>
              <span v-else class="status-tag ready">已就绪</span>
              <span class="vi-gender">{{ genderLabel(v.gender) }}</span>
              <span v-if="v.createTime" class="vi-time">· {{ formatDate(v.createTime) }}</span>
            </div>
            <div v-if="v.errorMessage && v.status === 'failed'" class="vi-error">
              {{ v.errorMessage }}
            </div>
          </div>
        </div>
        <div class="vi-actions">
          <el-button
            v-if="v.status === 'ready'"
            :icon="VideoPlay"
            size="small"
            :loading="playingId === v.id"
            @click="testPlay(v)"
          >试听</el-button>
          <el-button
            v-if="isAdmin && v.status === 'ready'"
            type="primary"
            size="small"
            :loading="promotingId === v.id"
            @click="promote(v)"
          >转为预设</el-button>
          <el-button
            :icon="Delete"
            size="small"
            type="danger"
            plain
            @click="onDelete(v)"
          >删除</el-button>
        </div>
      </div>
    </div>
    </template>

    <!-- 录音对话框 -->
    <el-dialog
      v-model="cloneVisible"
      title="录制 / 上传声音样本"
      width="560px"
      :close-on-click-modal="false"
      :before-close="onCloneClose"
    >
      <div class="clone-form">
        <!-- 样本来源切换 -->
        <div class="form-block">
          <label class="form-label">声音样本来源</label>
          <el-radio-group v-model="inputMode" size="small" @change="onModeChange">
            <el-radio-button value="record">🎙 现场录音</el-radio-button>
            <el-radio-button value="upload">📁 上传文件</el-radio-button>
          </el-radio-group>
        </div>

        <!-- 朗读文本（仅录音模式） -->
        <div v-if="inputMode === 'record'" class="form-block">
          <label class="form-label">请清晰朗读下面这段话（10-30 秒）</label>
          <div class="prompt-text">{{ readingPrompt }}</div>
          <div class="form-hint">建议在安静环境录制，吐字清晰；过短/过长都会影响复刻效果</div>
        </div>

        <!-- 录音控件（录音模式） -->
        <div v-if="inputMode === 'record'" class="form-block">
          <label class="form-label">录音</label>
          <div class="record-row">
            <button
              v-if="!recording && !sampleBlob"
              class="rec-btn start"
              @click="startRecord"
            >
              <el-icon :size="18"><Microphone /></el-icon>
              开始录音
            </button>
            <button
              v-else-if="recording"
              class="rec-btn stop"
              @click="stopRecord"
            >
              <el-icon :size="18"><VideoPause /></el-icon>
              停止 · {{ recordSeconds }}s
            </button>
            <template v-else>
              <audio :src="samplePreviewUrl!" controls class="rec-preview" />
              <button class="rec-btn redo" @click="resetSample">
                <el-icon :size="14"><RefreshLeft /></el-icon>
                重录
              </button>
            </template>
          </div>
          <div v-if="recording" class="meter-bar-wrap">
            <div class="meter-bar" :style="{ width: micLevel + '%' }"></div>
          </div>
        </div>

        <!-- 上传控件（上传模式） -->
        <div v-else class="form-block">
          <label class="form-label">上传音频文件</label>
          <input
            ref="fileInputRef"
            type="file"
            accept="audio/*,video/*,.mp3,.wav,.m4a,.aac,.ogg,.webm,.flac,.mp4,.mov,.mkv,.avi,.flv,.m4v"
            style="display:none"
            @change="onFileChange"
          />
          <div class="record-row">
            <button v-if="!sampleBlob" class="rec-btn start" @click="triggerFilePick">
              <el-icon :size="16"><UploadFilled /></el-icon>
              选择音频文件
            </button>
            <template v-else>
              <audio :src="samplePreviewUrl!" controls class="rec-preview" />
              <button class="rec-btn redo" @click="resetSample">
                <el-icon :size="14"><RefreshLeft /></el-icon>
                重选
              </button>
            </template>
          </div>
          <div class="form-hint">支持音频(mp3/wav/m4a/aac)或视频(mp4/mov 等)；视频会自动抽取音轨。建议含 5-60 秒清晰人声 · 音频 ≤10MB / 视频 ≤100MB</div>
        </div>

        <!-- 音色名 + 性别 -->
        <div class="form-block-row">
          <div class="form-block">
            <label class="form-label">音色名称</label>
            <el-input v-model="form.name" placeholder="如：老板的声音" maxlength="20" />
          </div>
          <div class="form-block">
            <label class="form-label">性别</label>
            <el-radio-group v-model="form.gender">
              <el-radio value="male">男</el-radio>
              <el-radio value="female">女</el-radio>
              <el-radio value="neutral">中性</el-radio>
            </el-radio-group>
          </div>
        </div>

        <!-- 合规勾选 -->
        <div class="form-block">
          <el-checkbox v-model="form.agreed">
            我授权 {{ brandStore.systemName }} 使用我的声音用于 AI 合成，仅本人账号可用
          </el-checkbox>
        </div>
      </div>

      <template #footer>
        <el-button @click="onCloneClose">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!sampleBlob || !form.name || !form.agreed"
          @click="submitClone"
        >
          {{ submitting ? '复刻中… (约 5-30 秒)' : '提交复刻' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Mic, Microphone, VideoPlay, VideoPause, RefreshLeft, Delete, UploadFilled,
} from '@element-plus/icons-vue'
import { voiceApi, type VoicePersona } from '@/api/voice'
import { useUserStore } from '@/stores/user'
import { useBrandStore } from '@/stores/brand'

const userStore = useUserStore()
const brandStore = useBrandStore()
const isAdmin = computed(() => userStore.isAdmin)

// ─── 列表 ───
const myVoices = ref<VoicePersona[]>([])
const presetVoices = ref<VoicePersona[]>([])
const loading = ref(false)

async function loadMyVoices() {
  loading.value = true
  try {
    // 一次拉「原始预置 + 自己的自定义」，按归属拆分
    const res: any = await voiceApi.manageVoices()
    const all: VoicePersona[] = res?.data ?? res ?? []
    presetVoices.value = all.filter(v => v.ownerUserId == null)
    myVoices.value = all.filter(v => v.ownerUserId != null)
  } finally { loading.value = false }
}

// ─── 电话列表显示开关（仅管理员可改预置） ───
const togglingId = ref<number | null>(null)

async function toggleShow(v: VoicePersona, enabled: boolean) {
  togglingId.value = v.id
  try {
    await voiceApi.setVoiceEnabled(v.id, enabled)
    v.enabled = enabled ? 1 : 0
    ElMessage.success(enabled ? '已在电话列表显示' : '已从电话列表隐藏')
  } catch (e: any) {
    ElMessage.error('修改失败：' + (e?.response?.data?.message || e?.message || ''))
  } finally { togglingId.value = null }
}

// ─── 试听 ───
const playingId = ref<number | null>(null)
let currentAudio: HTMLAudioElement | null = null

async function testPlay(v: VoicePersona) {
  if (playingId.value === v.id) {
    currentAudio?.pause()
    playingId.value = null
    return
  }
  playingId.value = v.id
  try {
    const url = await voiceApi.synth('你好，这是我的声音试听', v.id)
    currentAudio = new Audio(url)
    currentAudio.onended = () => { playingId.value = null }
    currentAudio.onerror = () => { playingId.value = null }
    await currentAudio.play()
  } catch (e: any) {
    ElMessage.error('试听失败：' + (e?.message || ''))
    playingId.value = null
  }
}

// ─── 转为系统预设（管理员迁移早前的个人音色） ───
const promotingId = ref<number | null>(null)
async function promote(v: VoicePersona) {
  try {
    await ElMessageBox.confirm(
      `把音色「${v.name}」转为系统预设？转后将对全体用户可见，且不再归属个人。`,
      '转为系统预设', { type: 'warning' })
    promotingId.value = v.id
    await voiceApi.promoteVoice(v.id)
    ElMessage.success('已转为系统预设')
    await loadMyVoices()
  } catch (e: any) {
    if (e === 'cancel' || (e?.message || '').includes('cancel')) return
    ElMessage.error('转换失败：' + (e?.response?.data?.message || e?.message || ''))
  } finally { promotingId.value = null }
}

// ─── 删除 ───
async function onDelete(v: VoicePersona) {
  try {
    await ElMessageBox.confirm(`确认删除音色「${v.name}」？此操作不可撤销。`, '提示', { type: 'warning' })
    await voiceApi.deleteMyVoice(v.id)
    ElMessage.success('已删除')
    await loadMyVoices()
  } catch (e: any) {
    if (e === 'cancel' || (e?.message || '').includes('cancel')) return
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

// ─── 样本来源（录音 / 上传） ───
const inputMode = ref<'record' | 'upload'>('record')
const fileInputRef = ref<HTMLInputElement | null>(null)

function onModeChange() {
  // 切换来源时清空已有样本，避免录音/上传混淆
  resetSample()
}

function triggerFilePick() {
  fileInputRef.value?.click()
}

/** 读取音频时长（秒）· 拿不到则 resolve 0 由后端兜底 */
function getAudioDuration(url: string): Promise<number> {
  return new Promise((resolve) => {
    const a = new Audio()
    a.preload = 'metadata'
    a.onloadedmetadata = () => resolve(a.duration || 0)
    a.onerror = () => resolve(0)
    a.src = url
  })
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''            // 允许重复选同一文件
  if (!file) return

  // 格式校验（音频或视频，视频后端会抽音轨）
  const isVideo = /^video\//.test(file.type) || /\.(mp4|mov|mkv|avi|flv|m4v)$/i.test(file.name)
  const okType = /^(audio|video)\//.test(file.type)
    || /\.(mp3|wav|m4a|aac|ogg|webm|flac|mp4|mov|mkv|avi|flv|m4v)$/i.test(file.name)
  if (!okType) {
    ElMessage.warning('请选择音频或视频文件')
    return
  }
  // 大小校验：音频 ≤10MB，视频 ≤100MB
  const maxBytes = isVideo ? 100 * 1024 * 1024 : 10 * 1024 * 1024
  if (file.size > maxBytes) {
    ElMessage.warning(`文件过大，请控制在 ${maxBytes / 1024 / 1024}MB 以内`)
    return
  }
  const url = URL.createObjectURL(file)
  // 时长校验仅对音频做（视频时长由后端抽轨后处理，前端不强校验）
  if (!isVideo) {
    const dur = await getAudioDuration(url)
    if (dur && dur < 5) {
      ElMessage.warning('音频过短，至少 5 秒')
      URL.revokeObjectURL(url)
      return
    }
    if (dur && dur > 60) {
      ElMessage.warning('音频过长，请控制在 60 秒以内')
      URL.revokeObjectURL(url)
      return
    }
  }
  sampleBlob.value = file
  samplePreviewUrl.value = url
}

// ─── 录音 ───
const cloneVisible = ref(false)
const recording = ref(false)
const recordSeconds = ref(0)
const micLevel = ref(0)
const sampleBlob = ref<Blob | null>(null)
const samplePreviewUrl = ref<string | null>(null)
const submitting = ref(false)

const form = reactive({
  name: '',
  gender: 'neutral',
  agreed: false,
})

const readingPrompt = computed(() => {
  // 包含日期和复杂词以提升声纹独特性 + 防止用预录音冒名
  const today = new Date()
  const dateStr = `${today.getFullYear()}年${today.getMonth() + 1}月${today.getDate()}日`
  return `今天是${dateStr}，我现在正在为 ${brandStore.systemName} 智能知识库录制我的专属音色。` +
         `从青藏高原到东海之滨，从人工智能到自然语言处理，我希望系统能用我自己的声音回答问题。`
})

let mediaRecorder: MediaRecorder | null = null
let mediaStream: MediaStream | null = null
let recordedChunks: BlobPart[] = []
let recordTimer: any = null
let levelTimer: any = null
let audioCtx: AudioContext | null = null
let analyser: AnalyserNode | null = null

function openClone() {
  cloneVisible.value = true
  inputMode.value = 'record'
  resetSample()
  form.name = ''
  form.gender = 'neutral'
  form.agreed = false
}

async function startRecord() {
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    })
  } catch (e: any) {
    ElMessage.error('麦克风权限被拒绝：' + (e?.message || ''))
    return
  }
  recordedChunks = []
  const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
    ? 'audio/webm;codecs=opus'
    : (MediaRecorder.isTypeSupported('audio/mp4') ? 'audio/mp4' : '')
  mediaRecorder = new MediaRecorder(mediaStream, mimeType ? { mimeType } : undefined)
  mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) recordedChunks.push(e.data) }
  mediaRecorder.onstop = () => {
    const blob = new Blob(recordedChunks, { type: mimeType || 'audio/webm' })
    sampleBlob.value = blob
    samplePreviewUrl.value = URL.createObjectURL(blob)
  }
  mediaRecorder.start()
  recording.value = true
  recordSeconds.value = 0
  recordTimer = setInterval(() => {
    recordSeconds.value++
    // 最长 60 秒自动停（DashScope 上限）
    if (recordSeconds.value >= 60) stopRecord()
  }, 1000)

  // 音量条
  audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)()
  const src = audioCtx.createMediaStreamSource(mediaStream)
  analyser = audioCtx.createAnalyser()
  analyser.fftSize = 256
  src.connect(analyser)
  const dataArr = new Uint8Array(analyser.frequencyBinCount)
  levelTimer = setInterval(() => {
    if (!analyser) return
    analyser.getByteFrequencyData(dataArr)
    let sum = 0
    for (let i = 0; i < dataArr.length; i++) sum += dataArr[i]!
    micLevel.value = Math.min(100, Math.round((sum / dataArr.length) * 1.5))
  }, 80)
}

function stopRecord() {
  if (recordSeconds.value < 5) {
    ElMessage.warning('录音过短，至少 5 秒')
    cleanupRecorder()
    return
  }
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  cleanupRecorder()
}

function cleanupRecorder() {
  if (recordTimer) { clearInterval(recordTimer); recordTimer = null }
  if (levelTimer) { clearInterval(levelTimer); levelTimer = null }
  if (mediaStream) { mediaStream.getTracks().forEach(t => t.stop()); mediaStream = null }
  if (audioCtx && audioCtx.state !== 'closed') { audioCtx.close().catch(() => {}); audioCtx = null }
  analyser = null
  recording.value = false
  micLevel.value = 0
}

function resetSample() {
  cleanupRecorder()
  if (samplePreviewUrl.value) URL.revokeObjectURL(samplePreviewUrl.value)
  sampleBlob.value = null
  samplePreviewUrl.value = null
  recordSeconds.value = 0
}

async function submitClone() {
  if (!sampleBlob.value) { ElMessage.warning('请先录音或上传音频文件'); return }
  if (!form.name.trim()) { ElMessage.warning('请填音色名称'); return }
  if (!form.agreed) { ElMessage.warning('请勾选合规授权'); return }

  submitting.value = true
  try {
    await voiceApi.cloneVoice(sampleBlob.value, form.name.trim(), form.gender, form.agreed)
    ElMessage.success('复刻成功！现在可以在语音通话/朗读里用这个音色了')
    cloneVisible.value = false
    resetSample()
    await loadMyVoices()
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '复刻失败'
    ElMessage.error(msg)
  } finally { submitting.value = false }
}

function onCloneClose(done?: () => void) {
  if (submitting.value) {
    ElMessage.warning('正在复刻中，请等待…')
    return
  }
  cleanupRecorder()
  resetSample()
  cloneVisible.value = false
  done?.()
}

// ─── 工具 ───
function avatarColor(v: VoicePersona): string {
  const colors = ['#0a0a0a', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444']
  return colors[(v.id || 0) % colors.length]!
}
function genderLabel(g?: string) {
  return ({ male: '男声', female: '女声', neutral: '中性' } as any)[g || ''] || '未指定'
}
function formatDate(t?: string) {
  if (!t) return ''
  const d = new Date(t)
  const pad = (n: number) => n < 10 ? '0' + n : '' + n
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

onMounted(loadMyVoices)
onBeforeUnmount(() => {
  cleanupRecorder()
  currentAudio?.pause()
  if (samplePreviewUrl.value) URL.revokeObjectURL(samplePreviewUrl.value)
})

defineExpose({ loadMyVoices })
</script>

<style scoped>
.voice-clone-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 22px 24px;
}

.vc-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line-soft);
}
.vc-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 15px; font-weight: 700; color: var(--ink-1);
  margin-bottom: 6px;
}
.vc-tag {
  font-size: 10px; font-weight: 600;
  padding: 2px 8px; border-radius: 999px;
  background: rgba(0,0,0,.12); color: #0a0a0a;
}
.vc-desc { font-size: 12.5px; color: var(--ink-3); line-height: 1.55; max-width: 520px; }

.preset-section { display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px; }
.section-head {
  display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap;
  padding-bottom: 4px;
}
.section-head.custom-head {
  margin-top: 2px; padding-top: 14px;
  border-top: 1px solid var(--line-soft);
}
.section-title { font-size: 13px; font-weight: 700; color: var(--ink-1); }
.section-hint { font-size: 11.5px; color: var(--ink-4); }

.voice-item.preset.disabled { opacity: .62; }
.status-tag.preset-tag { background: rgba(14,165,233,.15); color: #0369a1; }
.status-tag.default-tag { background: rgba(0,0,0,.15); color: #0a0a0a; }

.show-toggle { display: flex; flex-direction: column; align-items: center; gap: 2px; margin-right: 4px; }
.toggle-label { font-size: 10.5px; color: var(--ink-4); white-space: nowrap; }

.vc-empty.small { padding: 14px 0; }

.vc-list { display: flex; flex-direction: column; gap: 10px; }
.vc-empty {
  text-align: center; padding: 36px 0;
  color: var(--ink-4);
}
.empty-emoji { font-size: 34px; margin-bottom: 8px; }
.empty-title { font-size: 14px; font-weight: 600; color: var(--ink-2); margin-bottom: 4px; }
.empty-sub { font-size: 12px; color: var(--ink-4); }

.voice-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 16px;
  background: var(--bg-hover);
  border: 1px solid transparent;
  border-radius: 10px;
  transition: all .15s;
}
.voice-item:hover { border-color: var(--line); }
.voice-item.status-failed { background: rgba(239,68,68,.05); border-color: rgba(239,68,68,.2); }
.voice-item.status-cloning { background: rgba(245,158,11,.05); border-color: rgba(245,158,11,.2); }

.vi-left { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
.vi-avatar {
  width: 40px; height: 40px; border-radius: 50%;
  color: #fff; font-weight: 700; font-size: 16px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.vi-meta { flex: 1; min-width: 0; }
.vi-name { font-size: 14px; font-weight: 600; color: var(--ink-1); margin-bottom: 4px; }
.vi-sub { display: flex; gap: 8px; align-items: center; font-size: 11.5px; color: var(--ink-3); }
.status-tag { padding: 1px 8px; border-radius: 4px; font-weight: 600; font-size: 10.5px; }
.status-tag.ready { background: rgba(16,185,129,.15); color: #047857; }
.status-tag.cloning { background: rgba(245,158,11,.15); color: #b45309; }
.status-tag.failed { background: rgba(239,68,68,.15); color: #b91c1c; }
.vi-gender { color: var(--ink-3); }
.vi-time { color: var(--ink-4); }
.vi-error {
  margin-top: 4px;
  font-size: 11.5px; color: #b91c1c;
  background: rgba(239,68,68,.08);
  padding: 4px 8px; border-radius: 4px;
}
.vi-actions { display: flex; gap: 6px; }

/* ── 录音对话框 ── */
.clone-form { display: flex; flex-direction: column; gap: 18px; }
.form-block { display: flex; flex-direction: column; gap: 8px; }
.form-block-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-label { font-size: 12.5px; font-weight: 600; color: var(--ink-2); }
.form-hint { font-size: 11.5px; color: var(--ink-4); }
.prompt-text {
  padding: 12px 14px;
  background: rgba(0,0,0,.06);
  border: 1px solid rgba(0,0,0,.18);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-1);
}

.record-row { display: flex; align-items: center; gap: 12px; }
.rec-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 10px 18px;
  border-radius: 8px;
  border: 1.5px solid;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all .15s;
  background: var(--bg-surface);
}
.rec-btn.start { color: #0a0a0a; border-color: #0a0a0a; }
.rec-btn.start:hover { background: rgba(0,0,0,.08); }
.rec-btn.stop { color: #dc2626; border-color: #dc2626; background: rgba(239,68,68,.08); animation: pulse 1.4s infinite; }
.rec-btn.redo { color: var(--ink-2); border-color: var(--line); }
.rec-btn.redo:hover { color: #0a0a0a; border-color: #0a0a0a; }
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(239,68,68,.4); }
  50% { box-shadow: 0 0 0 8px rgba(239,68,68,0); }
}

.rec-preview { flex: 1; height: 36px; }

.meter-bar-wrap {
  margin-top: 8px;
  height: 4px;
  background: var(--bg-hover);
  border-radius: 2px;
  overflow: hidden;
}
.meter-bar {
  height: 100%;
  background: linear-gradient(90deg, #38bdf8, #0a0a0a);
}
</style>
