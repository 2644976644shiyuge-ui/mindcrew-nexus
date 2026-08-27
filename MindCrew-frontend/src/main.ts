import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
// Light theme only - dark css-vars intentionally removed
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import CountUp from 'vue-countup-v3'
import App from './App.vue'
import router from './router'
import { useBrandStore } from '@/stores/brand'
import './assets/base.css'
import './assets/main.css'
import './assets/mobile.css'   // 全局移动端适配 · 仅 @media (max-width: 768px) 生效，Web 端不受影响
import './assets/nexus-experience.css'
import './assets/product-system.css'

const app = createApp(App)
const pinia = createPinia()
app.component('CountUp', CountUp)

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
useBrandStore().fetchBrand().catch(() => {})

/**
 * 移动端可视高度兜底
 * 部分内嵌浏览器 / WebView 对 CSS 100dvh 支持不一致，会退化成 100vh（含被底部工具栏遮挡的区域），
 * 导致聊天输入框等底部内容被工具栏盖住、或底部多出一块空白。
 * visualViewport.height 是"真正可见区域"的高度（自动排除浏览器上下工具栏、软键盘），最准；
 * 不支持时退回 window.innerHeight。写进 --app-height 供布局根容器作为高度基准。
 */
const setAppHeight = () => {
  const h = window.visualViewport?.height ?? window.innerHeight
  document.documentElement.style.setProperty('--app-height', `${Math.round(h)}px`)
}
setAppHeight()
window.addEventListener('resize', setAppHeight)
window.addEventListener('orientationchange', setAppHeight)
// 工具栏伸缩 / 软键盘弹起都会触发 visualViewport.resize -- 比 window.resize 更灵敏
window.visualViewport?.addEventListener('resize', setAppHeight)

app.mount('#app')
