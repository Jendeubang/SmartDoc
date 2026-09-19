<template>
  <div class="ai-message-actions" aria-label="AI 回答操作">
    <el-button link size="small" icon="CopyDocument" @click="$emit('copy')">复制</el-button>
    <el-button link size="small" icon="RefreshRight" @click="$emit('retry')">重新生成</el-button>
    <el-button link size="small" icon="Top" :type="feedback === 'up' ? 'primary' : undefined" aria-label="回答有帮助" @click="$emit('feedback', 'up')" />
    <el-button link size="small" icon="Bottom" :type="feedback === 'down' ? 'danger' : undefined" aria-label="回答需要改进" @click="$emit('feedback', 'down')" />
  </div>
</template>

<script setup>
defineProps({ feedback: { type: String, default: '' } })
defineEmits(['copy', 'retry', 'feedback'])
</script>

<style scoped>
.ai-message-actions{display:flex;align-items:center;gap:1px;margin-top:11px;padding-top:8px;border-top:1px solid var(--smartdoc-border)}
.ai-message-actions .el-button+.el-button{margin-left:0}
</style>
