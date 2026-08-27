<template>
  <div class="office-3d-page">
    <iframe
      v-if="officeAvailable"
      src="/3d-office/"
      class="office-iframe"
      frameborder="0"
      allow="fullscreen; accelerometer; gyroscope"
      allowfullscreen
      title="数字员工 3D 办公室"
    />
    <section v-else class="office-state" aria-live="polite">
      <div class="state-mark" aria-hidden="true">3D</div>
      <p class="state-kicker">DIGITAL EMPLOYEE SPACE</p>
      <h1>{{ checking ? '正在连接 3D 办公室' : '3D 办公室尚未部署' }}</h1>
      <p>{{ checking ? '正在确认可视化资源是否可用。' : '员工列表与对话功能仍可正常使用；部署 3D 资源后，这里会自动恢复。' }}</p>
      <button v-if="!checking" type="button" @click="router.replace({ name: 'DigitalEmployeeList' })">返回数字员工</button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

// 3D 办公室可视化 · 嵌入 React + Three.js 应用
// 源项目：nexus-3d-office（独立 React + Vite 构建，部署在 /3d-office/）
const router = useRouter()
const checking = ref(true)
const officeAvailable = ref(false)

onMounted(async () => {
  try {
    const response = await fetch('/3d-office/index.html', { cache: 'no-store' })
    const html = await response.text()
    // Vite 会把缺失路径回退到主应用（#app）。只有独立 React 入口才视为可用。
    officeAvailable.value = response.ok && /id=["']root["']/.test(html) && !/id=["']app["']/.test(html)
  } catch {
    officeAvailable.value = false
  } finally {
    checking.value = false
  }
})
</script>

<style scoped>
.office-3d-page {
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #f5f5f7;
}
.office-iframe {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
}
.office-state {
  display: grid;
  width: min(620px, calc(100% - 40px));
  min-height: 420px;
  margin: 56px auto;
  padding: 52px;
  place-items: center;
  align-content: center;
  border: 1px solid var(--nx-line, #dfe3ea);
  border-radius: 16px;
  background: #fff;
  color: var(--nx-ink, #161b26);
  text-align: center;
}
.state-mark {
  display: grid;
  width: 52px;
  height: 52px;
  margin-bottom: 22px;
  place-items: center;
  border-radius: 12px;
  background: var(--nx-dark, #19202d);
  color: #fff;
  font-size: 14px;
  font-weight: 750;
  letter-spacing: .08em;
}
.state-kicker { margin: 0 0 10px; color: var(--nx-accent, #347ed2); font-size: 11px; font-weight: 750; letter-spacing: .12em; }
.office-state h1 { margin: 0; font-size: clamp(25px, 4vw, 38px); letter-spacing: -.035em; }
.office-state p:not(.state-kicker) { max-width: 440px; margin: 14px 0 0; color: var(--ink-3, #657084); line-height: 1.7; }
.office-state button {
  min-height: 42px;
  margin-top: 26px;
  padding: 0 18px;
  border: 1px solid var(--nx-dark, #19202d);
  border-radius: 9px;
  background: var(--nx-dark, #19202d);
  color: #fff;
  cursor: pointer;
}
.office-state button:hover { background: var(--nx-accent, #347ed2); border-color: var(--nx-accent, #347ed2); }
</style>
