<template>
  <section v-if="visible" class="tool-task-status" :class="`is-${status}`" role="status" :aria-live="status === 'failed' ? 'assertive' : 'polite'">
    <div class="task-heading">
      <span><el-icon><component :is="statusIcon" /></el-icon>{{ statusLabel }}</span>
      <strong>{{ normalizedProgress }}%</strong>
    </div>
    <el-progress :percentage="normalizedProgress" :status="progressStatus" :stroke-width="8" />
    <p>{{ message || defaultMessage }}</p>
    <div v-if="status === 'failed'" class="task-actions">
      <el-button size="small" type="primary" plain @click="$emit('retry')">重新执行</el-button>
      <el-button size="small" text @click="$emit('dismiss')">关闭</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: Boolean,
  status: { type: String, default: 'running' },
  progress: { type: Number, default: 0 },
  message: { type: String, default: '' }
})
defineEmits(['retry', 'dismiss'])

const normalizedProgress = computed(() => Math.max(0, Math.min(100, Number(props.progress) || 0)))
const statusLabel = computed(() => ({ running: '正在处理', success: '处理完成', failed: '处理失败' }[props.status] || '等待处理'))
const statusIcon = computed(() => ({ running: 'Loading', success: 'SuccessFilled', failed: 'WarningFilled' }[props.status] || 'Clock'))
const progressStatus = computed(() => props.status === 'success' ? 'success' : props.status === 'failed' ? 'exception' : undefined)
const defaultMessage = computed(() => props.status === 'running' ? '请保持页面开启，任务会在后台持续更新。' : props.status === 'success' ? '结果已经生成，可以预览或下载。' : '任务没有完成，请查看失败原因后重试。')
</script>

<style scoped>
.tool-task-status{margin-top:14px;padding:13px;border:1px solid var(--smartdoc-border);border-radius:13px;background:var(--smartdoc-primary-subtle)}.task-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:9px;color:var(--smartdoc-text);font-size:12px}.task-heading span{display:flex;align-items:center;gap:6px;font-weight:700}.task-heading strong{color:var(--smartdoc-primary-active)}.task-heading .is-loading{animation:spin 1s linear infinite}.tool-task-status p{margin:9px 0 0;color:var(--smartdoc-text-secondary);font-size:11px;line-height:1.6}.is-failed{background:#fcf4f3;border-color:#edd5d2}.is-failed .task-heading{color:var(--smartdoc-danger)}.task-actions{display:flex;gap:6px;margin-top:10px}@keyframes spin{to{transform:rotate(360deg)}}
</style>
