<template>
  <el-tooltip :content="recording ? '松开停止 · 取消按 Esc' : '按住说话（自动转文字）'" placement="top">
    <button
      class="voice-input-btn"
      :class="{ recording, error: errored }"
      @mousedown.prevent="startRecord"
      @mouseup.prevent="stopRecord"
      @mouseleave="onLeave"
      @touchstart.prevent="startRecord"
      @touchend.prevent="stopRecord"
      @contextmenu.prevent
    >
      <span class="ico-wrap">
        <el-icon :size="16">
          <Microphone v-if="!recording" />
          <Loading v-else />
        </el-icon>
      </span>
      <span v-if="recording" class="rec-anim">
        <span class="bar" v-for="i in 4" :key="i" :style="{ animationDelay: i * 80 + 'ms' }"></span>
      </span>
    </button>
  </el-tooltip>
</template>

<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Microphone, Loading } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const emit = defineEmits<{
  /** 实时识别（partial）和最终（final）文本，父组件可同步填到输入框 */
  (e: 'partial', text: string): void
  (e: 'final', text: string): void
}>()

const userStore = useUserStore()

const recording = ref(false)
const errored = ref(false)

let ws: WebSocket | null = null
let audioCtx: AudioContext | null = null
let micStream: MediaStream | null = null
let micSrc: MediaStreamAudioSourceNode | null = null
let micNode: AudioWorkletNode | null = null

let lastFinalText = ''

async function startRecord() {
  if (recording.value) return
  recording.value = true
  errored.value = false
  lastFinalText = ''
  try {
    await openAudio()
    await openWs()
    sendToWs('START')
  } catch (e: any) {
    errored.value = true
    recording.value = false
    ElMessage.error('启动语音输入失败：' + (e?.message || ''))
    await teardown()
  }
}

async function stopRecord() {
  if (!recording.value) return
  recording.value = false
  sendToWs('STOP')
  // 给后端一点时间处理最后一段
  setTimeout(() => { teardown() }, 800)
}

function onLeave(e: MouseEvent) {
  // 拖出按钮 = 取消（不发送最终文本）
  if (recording.value && (e.buttons & 1)) {
    recording.value = false
    sendToWs('STOP')
    setTimeout(() => { teardown() }, 200)
  }
}

async function openAudio() {
  audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)({ latencyHint: 'interactive' })
  await audioCtx.audioWorklet.addModule('/voice/mic-worklet.js')
  micStream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  })
  micSrc = audioCtx.createMediaStreamSource(micStream)
  micNode = new AudioWorkletNode(audioCtx, 'mic-worklet')
  micNode.port.onmessage = (ev) => {
    const d = ev.data
    if (d.type === 'pcm16' && ws && ws.readyState === WebSocket.OPEN) {
      ws.send(d.buffer)
    }
  }
  micSrc.connect(micNode)
}

async function openWs(): Promise<void> {
  return new Promise((resolve, reject) => {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${proto}://${window.location.host}/api/speech/ws?token=${encodeURIComponent(userStore.token)}`
    ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'

    const timeout = setTimeout(() => { reject(new Error('连接超时')) }, 5000)

    ws.onopen = () => { clearTimeout(timeout); resolve() }
    ws.onerror = () => { clearTimeout(timeout); reject(new Error('ws error')) }
    ws.onclose = () => {}
    ws.onmessage = (ev) => {
      if (typeof ev.data !== 'string') return
      try {
        const m = JSON.parse(ev.data)
        if (m.type === 'transcript') {
          if (m.isFinal) {
            lastFinalText = m.text || ''
            emit('partial', lastFinalText)
          } else {
            emit('partial', m.text || '')
          }
        } else if (m.type === 'finished') {
          const t = (m.text || lastFinalText || '').trim()
          if (t) emit('final', t)
        } else if (m.type === 'error') {
          ElMessage.error(m.message || '识别失败')
        }
      } catch {}
    }
  })
}

function sendToWs(text: string) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try { ws.send(text) } catch {}
  }
}

async function teardown() {
  if (micNode) { try { micNode.disconnect() } catch {} micNode = null }
  if (micSrc)  { try { micSrc.disconnect() } catch {} micSrc = null }
  if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null }
  if (audioCtx && audioCtx.state !== 'closed') { try { await audioCtx.close() } catch {} }
  audioCtx = null
  if (ws) { try { ws.close() } catch {} ws = null }
}

onBeforeUnmount(teardown)
</script>

<style scoped>
.voice-input-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: auto;
  min-width: 36px;
  height: 36px;
  padding: 0 10px;
  border-radius: 10px;
  background: var(--bg-hover);
  color: var(--ink-2);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
  user-select: none;
}
.voice-input-btn:hover {
  background: rgba(56, 189, 248, 0.12);
  color: #0284c7;
  border-color: rgba(56, 189, 248, 0.35);
}
.voice-input-btn.recording {
  background: rgba(239, 68, 68, 0.12);
  color: #dc2626;
  border-color: rgba(239, 68, 68, 0.45);
}
.voice-input-btn.error {
  background: rgba(239, 68, 68, 0.18);
  color: #991b1b;
}
.ico-wrap { display: inline-flex; align-items: center; }

.rec-anim { display: inline-flex; gap: 2px; align-items: center; height: 14px; }
.rec-anim .bar {
  width: 2px;
  height: 100%;
  background: currentColor;
  border-radius: 1px;
  animation: bar-pulse 600ms ease-in-out infinite;
}
@keyframes bar-pulse {
  0%, 100% { transform: scaleY(0.3); }
  50%      { transform: scaleY(1.0); }
}
</style>
