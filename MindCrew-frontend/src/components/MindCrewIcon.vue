<template>
  <!--
    ZYCOO Nexus 品牌图标 v3 · CSS 绘制紫色渐变方块 + 白色字母 C
    保留 size / color / accentColor props 以兼容历史调用方
  -->
  <div
    class="brand-mark"
    :style="markStyle"
    :aria-label="`ZYCOO Nexus`"
    role="img"
  >
    <span class="mark-letter" :style="letterStyle">C</span>
    <span class="mark-spark" :style="sparkStyle"></span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  size?: number | string
  /** 兼容老接口，新版图标自带配色，不再使用 */
  color?: string
  accentColor?: string
}>(), {
  size: 32,
  color: '#ffffff',
  accentColor: '#38bdf8',
})

const BASE = 38
const s = computed(() => Number(props.size) || 32)
const r = computed(() => s.value / BASE)

const markStyle = computed(() => ({
  width: `${s.value}px`,
  height: `${s.value}px`,
  borderRadius: `${Math.max(3, Math.round(11 * r.value))}px`,
  boxShadow: [
    `0 ${Math.round(10 * r.value)}px ${Math.round(26 * r.value)}px rgba(110, 90, 230, 0.45)`,
    `inset 0 ${Math.max(1, Math.round(r.value))}px 0 rgba(255, 255, 255, 0.25)`,
  ].join(', '),
}))

const letterStyle = computed(() => ({
  fontSize: `${Math.max(7, Math.round(19 * r.value))}px`,
}))

const sparkStyle = computed(() => {
  const sparkPx = Math.max(2, Math.round(6 * r.value))
  const offsetPx = Math.round(6 * r.value)
  return {
    width: `${sparkPx}px`,
    height: `${sparkPx}px`,
    top: `${offsetPx}px`,
    right: `${offsetPx}px`,
  }
})
</script>

<style scoped>
.brand-mark {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #6E5AE6 0%, #4F47C4 55%, #2E1B9A 100%);
  flex-shrink: 0;
  user-select: none;
}

.mark-letter {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.02em;
  line-height: 1;
}

.mark-spark {
  position: absolute;
  border-radius: 50%;
  background: #C7C0FF;
  box-shadow: 0 0 8px #a1a1aa, 0 0 14px #a1a1aa;
}
</style>
