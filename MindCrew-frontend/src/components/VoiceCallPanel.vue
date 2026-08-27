<template>
  <div class="voice-call-panel">
    <!-- 状态环 -->
    <div class="orb" :class="orbClass">
      <div class="orb-inner">
        <el-icon size="48" color="#fff">
          <PhoneFilled v-if="callState === 'idle' || callState === 'ready'" />
          <Microphone v-else-if="callState === 'listening'" />
          <Loading    v-else-if="callState === 'thinking' || callState === 'connecting'" />
          <Bell       v-else-if="callState === 'speaking'" />
        </el-icon>
      </div>
      <div v-if="callState === 'listening' || callState === 'speaking'" class="orb-wave"></div>
    </div>

    <div class="state-text">{{ stateLabel }}</div>
    <div v-if="currentVoice" class="voice-text">音色：{{ currentVoice.name }}</div>
    <div v-if="callState !== 'idle'" class="kb-scope">
      <el-icon size="11"><FolderOpened /></el-icon> {{ scopeLabel }}
    </div>

    <!-- 音色 + 知识库选择 + 电平条 -->
    <div v-if="callState === 'idle'" class="voice-picker">
      <el-select
        v-model="selectedVoiceId"
        placeholder="选择音色"
        size="default"
        style="width: 280px"
      >
        <el-option
          v-for="v in displayVoices"
          :key="v.id"
          :label="v.name"
          :value="v.id"
        />
      </el-select>
      <el-select
        v-model="selectedCollectionIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        filterable
        clearable
        placeholder="知识库范围 · 默认全部"
        size="default"
        style="width: 280px"
      >
        <el-option
          v-for="c in collections"
          :key="c.id"
          :label="c.name"
          :value="c.id"
        />
      </el-select>
      <div class="kb-pick-hint">
        {{ selectedCollectionIds.length ? `在选中的 ${selectedCollectionIds.length} 个知识库内回答` : '在全部知识库内回答' }}
      </div>
    </div>
    <div v-if="callState !== 'idle' && callState !== 'connecting'" class="mic-meter">
      <div class="meter-bar" :style="{ width: micLevelPct + '%', background: micLevelColor }"></div>
    </div>

    <!-- 联网开关：开启后本轮 AI 会联网检索最新信息（通话中切换下一轮生效） -->
    <div class="web-toggle-row">
      <button
        type="button"
        class="web-toggle-btn"
        :class="{ 'is-active': webSearchEnabled }"
        :title="webSearchEnabled ? '联网已开启 · AI 可检索最新网络信息（点击关闭）' : '联网已关闭 · 仅用知识库与通用知识（点击开启）'"
        @click="webSearchEnabled = !webSearchEnabled"
      >
        <el-icon size="14"><Connection /></el-icon>
        <span>联网{{ webSearchEnabled ? '已开启' : '' }}</span>
      </button>
    </div>

    <!-- 控制按钮 -->
    <div class="ctrl-bar">
      <el-button
        v-if="callState === 'idle'"
        type="primary"
        round
        size="large"
        :icon="PhoneFilled"
        @click="startCall"
      >
        开始通话
      </el-button>
      <template v-else>
        <el-button
          v-if="callState === 'speaking'"
          :icon="VideoPause"
          round
          size="default"
          @click="interruptAi"
        >打断</el-button>
        <el-button
          :icon="CloseBold"
          round
          size="default"
          type="danger"
          @click="endCall"
        >挂断</el-button>
      </template>
    </div>

    <div v-if="callState === 'ready' || callState === 'listening'" class="hint">
      直接说话 → 静默 1 秒视为说完
    </div>
    <div v-else-if="callState === 'speaking'" class="hint">
      AI 说话中 · 你开口会自动打断
    </div>

    <!-- 字幕轨 -->
    <div v-if="transcripts.length" class="transcripts" ref="transcriptsRef">
      <div
        v-for="(t, i) in transcripts"
        :key="i"
        class="bubble"
        :class="t.role"
      >
        <div class="bubble-role">{{ t.role === 'user' ? '你' : 'AI' }}</div>
        <div class="bubble-text">{{ t.text }}{{ t.partial ? '…' : '' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  PhoneFilled, Microphone, Loading, Bell, VideoPause, CloseBold, FolderOpened, Connection,
} from '@element-plus/icons-vue'
import { voiceApi, type VoicePersona } from '@/api/voice'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { useUserStore } from '@/stores/user'

type CallState = 'idle' | 'connecting' | 'ready' | 'listening' | 'thinking' | 'speaking'

const props = defineProps<{
  kbIds?: number[]
  collectionIds?: number[]   // 初始选中的知识库（来自聊天侧栏），可在弹窗内改
  kbScopeLabel?: string
  autoStart?: boolean
}>()

const emit = defineEmits<{
  (e: 'state-change', s: CallState): void
  (e: 'close'): void
}>()

const userStore = useUserStore()

// ─── 音色 ───
const voices = ref<VoicePersona[]>([])
const selectedVoiceId = ref<number | undefined>(undefined)
const currentVoice = computed(() => voices.value.find(v => v.id === selectedVoiceId.value))

async function loadVoices() {
  try {
    const res: any = await voiceApi.voices()
    voices.value = res?.data ?? res ?? []
    const def = voices.value.find(v => v.isDefault === 1)
    selectedVoiceId.value = def?.id ?? voices.value[0]?.id
  } catch (e: any) {
    ElMessage.error('加载音色失败：' + (e?.message || ''))
  }
}

// ─── 知识库（collection）选择 ───
const collections = ref<KnowledgeCollection[]>([])
const selectedCollectionIds = ref<number[]>(props.collectionIds ? [...props.collectionIds] : [])

async function loadCollections() {
  try {
    const res: any = await collectionApi.list()
    collections.value = res?.data ?? res ?? []
  } catch { /* 静默：拿不到就只能走全库 */ }
}

/** 解析某知识库配置的音色 id 列表（voiceIds 逗号分隔） */
function parseVoiceIds(s?: string | null): number[] {
  return !s ? [] : s.split(',').map(x => Number(x.trim())).filter(n => !Number.isNaN(n))
}

/**
 * 实际下拉显示的音色：仅当「恰好选中 1 个」知识库且其配置了音色时，限定为这些音色；
 * 否则（多选 / 未选 / 未配置）显示全部音色。
 */
const displayVoices = computed<VoicePersona[]>(() => {
  if (selectedCollectionIds.value.length !== 1) return voices.value
  const c = collections.value.find(x => x.id === selectedCollectionIds.value[0])
  const ids = parseVoiceIds(c?.voiceIds)
  if (!ids.length) return voices.value
  const filtered = voices.value.filter(v => ids.includes(v.id))
  return filtered.length ? filtered : voices.value
})

// 知识库选择 / 音色列表变化 → 若当前选中音色不在可选范围内，自动切到第一个可选音色
watch(displayVoices, (list) => {
  if (!list.length) return
  if (!list.some(v => v.id === selectedVoiceId.value)) {
    selectedVoiceId.value = list.find(v => v.isDefault === 1)?.id ?? list[0]!.id
  }
})

// 通话中展示的范围标签：按"实际选中的知识库"算（不再用父级传的旧 kbScopeLabel）
const scopeLabel = computed(() => {
  if (!selectedCollectionIds.value.length) return '全部知识库'
  const names = selectedCollectionIds.value
    .map(id => collections.value.find(c => c.id === id)?.name)
    .filter(Boolean) as string[]
  if (!names.length) return `已选 ${selectedCollectionIds.value.length} 个知识库`
  return names.length <= 2 ? names.join('、') : `${names.slice(0, 2).join('、')} 等 ${names.length} 个`
})

/** 把选中的知识库解析成文档 id 列表（语音后端按文档 knowledge_base_id 过滤）；不选=全库=[] */
async function resolveKbIds(): Promise<number[]> {
  if (!selectedCollectionIds.value.length) return []
  try {
    const lists = await Promise.all(
      selectedCollectionIds.value.map(id => collectionApi.listDocs(id))
    )
    const docIds = new Set<number>()
    for (const l of lists) {
      const arr: any[] = (l as any)?.data ?? l ?? []
      for (const d of arr) if (d?.id != null) docIds.add(Number(d.id))
    }
    return [...docIds]
  } catch {
    return []   // 解析失败退回全库，别让通话起不来
  }
}

// ─── 联网开关 · 默认关，用户可在通话面板开启；持久化，通话中切换下一轮生效 ───
const webSearchEnabled = ref(localStorage.getItem('voice_web_search') === '1')
watch(webSearchEnabled, (v) => {
  localStorage.setItem('voice_web_search', v ? '1' : '0')
  // 通话进行中切换 → 通知后端，下一轮回答生效
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'update_web', webSearch: v }))
  }
})

// ─── 通话状态 ───
const callState = ref<CallState>('idle')
const transcripts = ref<{ role: 'user' | 'ai'; text: string; partial: boolean }[]>([])
const transcriptsRef = ref<HTMLElement | null>(null)
const micLevelPct = ref(0)

watch(callState, (s) => emit('state-change', s))

const stateLabel = computed(() => ({
  idle: '空闲',
  connecting: '正在连接...',
  ready: '请直接说话',
  listening: '正在聆听...',
  thinking: 'AI 正在思考...',
  speaking: 'AI 正在回答',
}[callState.value]))

const orbClass = computed(() => 'orb-' + callState.value)
const micLevelColor = computed(() => {
  if (micLevelPct.value > 60) return '#10b981'
  if (micLevelPct.value > 20) return '#38bdf8'
  return '#94a3b8'
})

// ─── 音频通路 ───
let ws: WebSocket | null = null
let audioCtx: AudioContext | null = null
let micStream: MediaStream | null = null
let micSrc: MediaStreamAudioSourceNode | null = null
let micNode: AudioWorkletNode | null = null

let playSampleRate = 22050
let playStartTime = 0
let playingChunks = 0
let scheduledNodes: AudioBufferSourceNode[] = []
// 播放经 MediaStreamDestination + <audio>，让浏览器回声消除(AEC)把 AI 声音纳入参考信号，
// 避免 AI 外放漏进麦克风、和用户插话混在一起（也是「AI 自己打断自己」的根因修复）。
let playDest: MediaStreamAudioDestinationNode | null = null
let playAudioEl: HTMLAudioElement | null = null
// 常驻静音保活源：防止 MediaStreamDestination 在「无音频输入」时 underrun，
// 浏览器会重复播放最后一个 render quantum 的残帧 → 打断后出现停不下来的「咯咯咯」杂音
// （自定义复刻音色结尾残帧有波形，故尤其明显；预置音色结尾多为静音不易察觉）。
let silentKeepAlive: OscillatorNode | null = null

const VOICE_RMS_THRESH = 0.04    // 人声门限（仅用于本地 VAD 状态/打断判定，不影响送 ASR）
const SPEAK_DETECT_MS = 350       // 需持续这么久才判定「开始说话」，滤掉短促杂音
const INTERRUPT_DETECT_MS = 450   // 打断需更明确的持续人声，避免杂音误打断

let voiceActiveSince = 0
let userHangup = false

// ─── 启动 ───
let effectiveKbIds: number[] = []

async function startCall() {
  if (!selectedVoiceId.value) { ElMessage.warning('请选择音色'); return }
  callState.value = 'connecting'
  userHangup = false
  try {
    // 把选中的知识库解析成文档 id（不选=全库）；解析后再连 WS
    effectiveKbIds = await resolveKbIds()
    await ensureAudioContext()
    await openWs()
    await startMicCapture()
  } catch (e: any) {
    ElMessage.error('启动失败：' + (e?.message || ''))
    await endCall()
  }
}

async function ensureAudioContext() {
  if (audioCtx) return
  const Ctor = window.AudioContext || (window as any).webkitAudioContext
  if (!Ctor) throw new Error('浏览器不支持音频，请用 Chrome/Edge/Firefox')
  audioCtx = new Ctor({ latencyHint: 'interactive' })
  if (!(audioCtx as any).audioWorklet) {
    throw new Error('语音通话需要 HTTPS 或 localhost 环境（浏览器安全策略要求）')
  }
  await (audioCtx as any).audioWorklet.addModule('/voice/mic-worklet.js')

  // 经 MediaStreamDestination + <audio> 播放 → Chrome AEC 把这路输出纳入回声参考。
  // 不支持时回退到 audioCtx.destination（功能不变，仅回声抑制变弱）。
  try {
    playDest = audioCtx.createMediaStreamDestination()
    playAudioEl = new Audio()
    playAudioEl.srcObject = playDest.stream
    playAudioEl.autoplay = true
    await playAudioEl.play().catch(() => {})
    // 常驻静音保活：oscillator → gain(0) → playDest，保证这条 MediaStream 永不 underrun，
    // destination 始终输出真静音，杜绝打断后残帧循环的「咯咯咯」。
    silentKeepAlive = audioCtx.createOscillator()
    const muteGain = audioCtx.createGain()
    muteGain.gain.value = 0
    silentKeepAlive.connect(muteGain).connect(playDest)
    silentKeepAlive.start()
  } catch {
    playDest = null
    silentKeepAlive = null
  }
}

async function openWs(): Promise<void> {
  return new Promise((resolve, reject) => {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${proto}://${window.location.host}/api/voice-call/ws?token=${encodeURIComponent(userStore.token)}`
    ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'

    let resolved = false

    ws.onopen = () => {
      ws!.send(JSON.stringify({
        type: 'config',
        voiceId: selectedVoiceId.value,
        kbIds: effectiveKbIds,
        webSearch: webSearchEnabled.value,
      }))
    }
    ws.onmessage = (ev) => handleWsMessage(ev)
    ws.onerror = () => { if (!resolved) { resolved = true; reject(new Error('ws error')) } }
    ws.onclose = () => {
      if (!userHangup && callState.value !== 'idle') {
        ElMessage.warning('通话连接已断开')
      }
      callState.value = 'idle'
    }

    const onceReady = (ev: MessageEvent) => {
      if (typeof ev.data !== 'string') return
      try {
        const m = JSON.parse(ev.data)
        if (m.type === 'ready') {
          ws!.removeEventListener('message', onceReady)
          if (!resolved) { resolved = true; callState.value = 'ready'; resolve() }
        } else if (m.type === 'error') {
          ws!.removeEventListener('message', onceReady)
          if (!resolved) { resolved = true; reject(new Error(m.message)) }
        }
      } catch {}
    }
    ws.addEventListener('message', onceReady)
  })
}

function handleWsMessage(ev: MessageEvent) {
  if (typeof ev.data === 'string') {
    try { onJsonMessage(JSON.parse(ev.data)) } catch {}
  } else if (ev.data instanceof ArrayBuffer) {
    onPcmFrame(ev.data)
  }
}

function onJsonMessage(m: any) {
  switch (m.type) {
    case 'asr_partial':
      callState.value = 'listening'
      upsertTranscript('user', m.text, true)
      scrollLater()
      break
    case 'asr_final':
      upsertTranscript('user', m.text, false)
      scrollLater()
      break
    case 'thinking':
      callState.value = 'thinking'
      break
    case 'llm_answer':
      upsertTranscript('ai', m.text, false)
      scrollLater()
      break
    case 'tts_start':
      // 新一轮回答开始：先硬清空上一轮残留的已排程音频，杜绝「上一个回答 + 本次回答」叠播
      for (const n of scheduledNodes) { try { n.stop() } catch {} }
      scheduledNodes = []
      playingChunks = 0
      playSampleRate = m.sampleRate || 22050
      playStartTime = audioCtx!.currentTime
      callState.value = 'speaking'
      break
    case 'barge_in':
      // 后端 ASR 识别到用户在 AI 说话期间插话 → 已取消 TTS。前端立即停播并清空残帧。
      for (const n of scheduledNodes) { try { n.stop() } catch {} }
      scheduledNodes = []
      playingChunks = 0
      playStartTime = audioCtx?.currentTime ?? 0
      callState.value = 'listening'
      break
    case 'turn_end':
      waitForPlaybackThen(() => {
        if (callState.value !== 'listening') callState.value = 'ready'
      })
      break
    case 'error':
      ElMessage.error(m.message || '通话出错')
      if (ws && ws.readyState === WebSocket.OPEN) callState.value = 'ready'
      else callState.value = 'idle'
      break
  }
}

function upsertTranscript(role: 'user' | 'ai', text: string, partial: boolean) {
  if (!text) return
  const last = transcripts.value[transcripts.value.length - 1]
  if (last && last.role === role && last.partial) {
    last.text = text; last.partial = partial
  } else {
    transcripts.value.push({ role, text, partial })
  }
}

function scrollLater() {
  nextTick(() => {
    const el = transcriptsRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onPcmFrame(buf: ArrayBuffer) {
  if (!audioCtx) return
  // 只在「正在播报」状态才播：打断后 / 轮次切换间到达的残留帧直接丢弃，
  // 杜绝「上一个回答的残留帧」与「新回答的帧」排进同一条时间线叠着播。
  if (callState.value !== 'speaking') return
  const i16 = new Int16Array(buf)
  const f32 = new Float32Array(i16.length)
  for (let i = 0; i < i16.length; i++) {
    const s = i16[i] ?? 0
    f32[i] = s / (s < 0 ? 0x8000 : 0x7fff)
  }
  const ab = audioCtx.createBuffer(1, f32.length, playSampleRate)
  ab.getChannelData(0).set(f32)
  const src = audioCtx.createBufferSource()
  src.buffer = ab
  src.connect(playDest ?? audioCtx.destination)
  const startAt = Math.max(audioCtx.currentTime + 0.02, playStartTime)
  src.start(startAt)
  playStartTime = startAt + ab.duration
  playingChunks++
  scheduledNodes.push(src)
  src.onended = () => {
    playingChunks--
    scheduledNodes = scheduledNodes.filter(n => n !== src)
  }
}

function waitForPlaybackThen(cb: () => void) {
  const tick = () => {
    if (playingChunks <= 0 || callState.value === 'idle') cb()
    else setTimeout(tick, 100)
  }
  tick()
}

async function startMicCapture() {
  if (!audioCtx || micNode) return
  micStream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  })
  micSrc = audioCtx.createMediaStreamSource(micStream)
  micNode = new AudioWorkletNode(audioCtx, 'mic-worklet')
  micNode.port.onmessage = (ev) => {
    const d = ev.data
    if (d.type === 'pcm16') {
      // 连续把音频送给 ASR：DashScope 靠「语音后的静音」来断句出 asr_final，
      // 绝不能在静音处掐断音频流，否则它判断不出「说完了」→ 不回答。
      // 杂音/语气词由后端 isMeaningfulSpeech 过滤，不在这里挡。
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(d.buffer)
      handleVad(d.rms)
    } else if (d.type === 'level') {
      handleVad(d.rms)
    }
  }
  micSrc.connect(micNode)
}

function stopMicCapture() {
  if (micNode) { try { micNode.disconnect() } catch {} micNode = null }
  if (micSrc)  { try { micSrc.disconnect() } catch {} micSrc = null }
  if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null }
  micLevelPct.value = 0
}

function handleVad(rms: number) {
  micLevelPct.value = Math.min(100, Math.round(rms * 700))
  const now = Date.now()
  const voicing = rms > VOICE_RMS_THRESH

  if (callState.value === 'speaking') {
    if (voicing) {
      if (!voiceActiveSince) voiceActiveSince = now
      else if (now - voiceActiveSince > INTERRUPT_DETECT_MS) {
        interruptAi()
        voiceActiveSince = 0
      }
    } else { voiceActiveSince = 0 }
    return
  }

  if (callState.value === 'ready' || callState.value === 'listening') {
    if (voicing) {
      voiceActiveSince ||= now
      if (callState.value === 'ready' && now - voiceActiveSince > SPEAK_DETECT_MS) {
        callState.value = 'listening'
      }
    } else {
      voiceActiveSince = 0
    }
  }
}

function interruptAi() {
  for (const n of scheduledNodes) { try { n.stop() } catch {} }
  scheduledNodes = []
  playingChunks = 0
  playStartTime = audioCtx?.currentTime ?? 0
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'cancel' }))
  }
  callState.value = 'ready'
}

async function endCall() {
  userHangup = true
  stopMicCapture()
  for (const n of scheduledNodes) { try { n.stop() } catch {} }
  scheduledNodes = []; playingChunks = 0
  if (silentKeepAlive) { try { silentKeepAlive.stop(); silentKeepAlive.disconnect() } catch {} silentKeepAlive = null }
  if (playAudioEl) { try { playAudioEl.pause(); playAudioEl.srcObject = null } catch {} playAudioEl = null }
  playDest = null
  if (ws) { try { ws.close() } catch {} ws = null }
  if (audioCtx && audioCtx.state !== 'closed') { try { await audioCtx.close() } catch {} audioCtx = null }
  callState.value = 'idle'
  voiceActiveSince = 0
  emit('close')
}

// 父组件切了 KB → 通话也同步
watch(() => props.kbIds, (next) => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'update_kb', kbIds: next || [] }))
  }
}, { deep: true })

onMounted(async () => {
  await Promise.all([loadVoices(), loadCollections()])
  if (props.autoStart) startCall()
})
onBeforeUnmount(() => { endCall() })

defineExpose({ startCall, endCall })
</script>

<style scoped>
.voice-call-panel {
  display: flex; flex-direction: column; align-items: center;
  padding: 18px 12px 24px;
  gap: 12px;
}

.orb {
  position: relative;
  width: 128px; height: 128px;
  border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #94a3b8 0%, #64748b 100%);
  transition: background 0.3s ease;
}
.orb-inner {
  width: 106px; height: 106px;
  border-radius: 50%;
  background: rgba(255,255,255,0.10);
  border: 2px solid rgba(255,255,255,0.25);
  display: flex; align-items: center; justify-content: center;
}
.orb-idle       { background: linear-gradient(135deg, #94a3b8, #64748b); }
.orb-connecting { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.orb-ready      { background: linear-gradient(135deg, #60a5fa, #3b82f6); }
.orb-listening  { background: linear-gradient(135deg, #38bdf8, #0ea5e9); animation: pulse 1.4s infinite; }
.orb-thinking   { background: linear-gradient(135deg, #c084fc, #0a0a0a); animation: spin 2s linear infinite; }
.orb-speaking   { background: linear-gradient(135deg, #34d399, #10b981); animation: pulse 1s infinite; }

.orb-wave {
  position: absolute; inset: -14px;
  border-radius: 50%;
  border: 2px solid rgba(56, 189, 248, 0.45);
  animation: wave 1.4s infinite;
}
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(56, 189, 248, 0.4); }
  50% { box-shadow: 0 0 0 16px rgba(56, 189, 248, 0); }
}
@keyframes wave {
  0% { transform: scale(0.95); opacity: 0.8; }
  100% { transform: scale(1.25); opacity: 0; }
}
@keyframes spin {
  from { transform: rotate(0deg); } to { transform: rotate(360deg); }
}

.state-text { font-size: 15px; font-weight: 600; color: var(--ink-1); }
.voice-text, .kb-scope { font-size: 12px; color: var(--ink-4); display: flex; align-items: center; gap: 4px; }

.voice-picker { margin-top: 4px; display: flex; flex-direction: column; align-items: center; gap: 10px; }
.kb-pick-hint { font-size: 12px; color: var(--ink-4); }

.mic-meter {
  width: 100%; max-width: 280px; height: 5px;
  background: var(--bg-hover); border-radius: 3px; overflow: hidden;
}
.meter-bar { height: 100%; transition: background 200ms ease; }

.ctrl-bar { display: flex; justify-content: center; gap: 10px; flex-wrap: wrap; }
.hint { font-size: 11.5px; color: var(--ink-4); }

/* 联网开关按钮 · 开启时蓝色高亮 */
.web-toggle-row { display: flex; justify-content: center; }
.web-toggle-btn {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 14px; border-radius: 999px; cursor: pointer;
  font-size: 12.5px; line-height: 1;
  border: 1px solid var(--el-border-color, #dcdfe6);
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--ink-4, #909399);
  transition: all .18s ease;
}
.web-toggle-btn:hover { border-color: #38bdf8; color: #38bdf8; }
.web-toggle-btn.is-active {
  border-color: #38bdf8; color: #fff;
  background: linear-gradient(135deg, #38bdf8, #2563eb);
  box-shadow: 0 2px 8px rgba(56, 189, 248, .35);
}

.transcripts {
  width: 100%;
  display: flex; flex-direction: column; gap: 10px;
  background: var(--bg-hover);
  border-radius: 8px;
  padding: 12px 14px;
  max-height: 240px;
  overflow-y: auto;
  font-size: 12.5px;
}
.bubble { display: flex; gap: 10px; line-height: 1.6; }
.bubble-role {
  flex-shrink: 0;
  width: 26px; height: 26px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 10.5px; font-weight: 700;
}
.bubble.user .bubble-role { background: rgba(56, 189, 248, 0.18); color: #0284c7; }
.bubble.ai .bubble-role  { background: rgba(0, 0, 0, 0.18); color: #0a0a0a; }
.bubble-text { flex: 1; color: var(--ink-1); white-space: pre-wrap; }
</style>
