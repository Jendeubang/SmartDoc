/**
 * Agent 进度订阅 composable
 * 连接 WebSocket 并跟踪 Agent 任务执行进度
 */
import { ref, onUnmounted } from 'vue'
import { connectWebSocket, disconnectWebSocket, subscribeToTopic, unsubscribeFromTopic } from '../utils/websocket'

export function useAgentProgress() {
  const currentStep = ref('')
  const currentStatus = ref('')
  const progressMessage = ref('')
  const isProgressActive = ref(false)
  let unsubscribe = null

  // 开始追踪指定 traceId 的进度
  function startTracking(traceId, userId) {
    isProgressActive.value = true
    currentStep.value = '准备中...'
    currentStatus.value = 'running'

    connectWebSocket()

    // 订阅用户主题（接收该用户所有 Agent 任务进度）
    const userTopic = `/topic/agent/users/${userId}`
    unsubscribeFromTopic(userTopic)
    subscribeToTopic(userTopic, (data) => {
      if (data) {
        if (data.step) currentStep.value = data.step
        if (data.status) currentStatus.value = data.status
        if (data.message) progressMessage.value = data.message

        // 终态停止追踪
        if (data.status === 'success' || data.status === 'error' || data.status === 'cancelled') {
          isProgressActive.value = false
        }
      }
    })

    // 如果指定了 traceId，订阅具体任务主题
    if (traceId) {
      const taskTopic = `/topic/agent/tasks/${traceId}`
      unsubscribeFromTopic(taskTopic)
      subscribeToTopic(taskTopic, (data) => {
        if (data) {
          if (data.step) currentStep.value = data.step
          if (data.status) currentStatus.value = data.status
          if (data.message) progressMessage.value = data.message

          if (data.status === 'success' || data.status === 'error' || data.status === 'cancelled') {
            isProgressActive.value = false
          }
        }
      })
    }

    // 5 分钟超时保护
    setTimeout(() => {
      if (isProgressActive.value) {
        isProgressActive.value = false
        progressMessage.value = '任务执行超时'
      }
    }, 300000)
  }

  // 停止追踪
  function stopTracking() {
    isProgressActive.value = false
    currentStep.value = ''
    currentStatus.value = ''
    progressMessage.value = ''
  }

  // 组件卸载时清理
  onUnmounted(() => {
    stopTracking()
  })

  return {
    currentStep,
    currentStatus,
    progressMessage,
    isProgressActive,
    startTracking,
    stopTracking
  }
}
