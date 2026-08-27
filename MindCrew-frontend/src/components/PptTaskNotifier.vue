<template></template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { ElNotification } from 'element-plus'
import { useRouter } from 'vue-router'
import { pptApi } from '@/api/ppt'
import { digitalEmployeeApi } from '@/api/digitalEmployee'

const router = useRouter()
const STORAGE_KEY = 'mindcrew-ppt-notified-tasks'
let timer: number | undefined
let checking = false

const notifiedIds = () => {
  try {
    return new Set<number>(JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'))
  } catch {
    return new Set<number>()
  }
}

const remember = (ids: Set<number>) => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify([...ids].slice(-80)))
}

const checkTasks = async () => {
  if (checking) return
  checking = true
  try {
    const tasks = await pptApi.list(10)
    const notified = notifiedIds()
    const recentThreshold = Date.now() - 10 * 60 * 1000
    for (const task of tasks) {
      if (
        task.status === 'completed'
        && task.completedAt
        && new Date(task.completedAt).getTime() >= recentThreshold
        && !notified.has(task.id)
      ) {
        notified.add(task.id)
        ElNotification({
          title: '演示文稿已生成',
          message: `${task.title}，点击前往下载`,
          type: 'success',
          duration: 8000,
          onClick: async () => {
            if (task.employeeId && task.conversationId) {
              await router.push({
                name: 'DigitalEmployeeChat',
                params: { id: task.employeeId },
                query: { conversationId: task.conversationId },
              })
              return
            }
            try {
              const employees = await digitalEmployeeApi.listMine()
              const pptEmployee = employees.find(item => item.primaryScenario === 'ppt_authoring')
              if (pptEmployee) {
                await router.push({
                  name: 'DigitalEmployeeChat',
                  params: { id: pptEmployee.id },
                })
                return
              }
            } catch {
              // 数字员工列表加载失败时仍回到统一入口。
            }
            await router.push({
              name: 'DigitalEmployeeList',
              query: { capability: 'ppt' },
            })
          },
        })
      }
    }
    remember(notified)
  } catch {
    // 全局轮询静默失败；页面内会展示明确错误。
  } finally {
    checking = false
  }
}

onMounted(() => {
  checkTasks()
  timer = window.setInterval(checkTasks, 7000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>
