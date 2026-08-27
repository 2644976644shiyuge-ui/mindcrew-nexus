/**
 * 闲置自动下线（前端层）
 *
 * 监听用户交互事件，超过 timeoutMs 无任何操作即触发 onTimeout（通常是退出登录 + 跳登录页）。
 * 这是即时 UX 层：服务端 Redis 滑动会话才是权威强制层，两者超时时长保持一致即可。
 *
 * 用法：
 *   const stop = startIdleLogout(60 * 60 * 1000, () => { ... })
 *   // 组件卸载时：stop()
 */
const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'click'] as const

export function startIdleLogout(timeoutMs: number, onTimeout: () => void): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  let lastReset = 0

  const fire = () => {
    cleanup()
    onTimeout()
  }

  const reset = () => {
    const now = Date.now()
    // 节流：1s 内的连续活动不重复重置定时器，避免高频 mousemove 造成开销
    if (now - lastReset < 1000) return
    lastReset = now
    if (timer) clearTimeout(timer)
    timer = setTimeout(fire, timeoutMs)
  }

  // 切回前台时立即检查（后台标签 setTimeout 可能被节流）
  const onVisible = () => {
    if (document.visibilityState === 'visible') reset()
  }

  ACTIVITY_EVENTS.forEach(ev => window.addEventListener(ev, reset, { passive: true }))
  document.addEventListener('visibilitychange', onVisible)

  reset() // 启动初始计时

  function cleanup() {
    if (timer) clearTimeout(timer)
    timer = null
    ACTIVITY_EVENTS.forEach(ev => window.removeEventListener(ev, reset))
    document.removeEventListener('visibilitychange', onVisible)
  }

  return cleanup
}
