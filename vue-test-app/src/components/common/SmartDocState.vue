<template>
  <section class="smartdoc-state" :class="`is-${type}`" role="status" :aria-live="type === 'error' ? 'assertive' : 'polite'">
    <div class="state-icon" aria-hidden="true">
      <el-icon :size="24"><component :is="iconName" /></el-icon>
    </div>
    <div class="state-copy">
      <strong>{{ title }}</strong>
      <p>{{ description }}</p>
    </div>
    <el-button v-if="actionText" :type="type === 'error' ? 'primary' : undefined" plain @click="$emit('action')">
      {{ actionText }}
    </el-button>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  type: { type: String, default: 'empty' },
  title: { type: String, default: '暂无内容' },
  description: { type: String, default: '这里还没有可显示的数据。' },
  actionText: { type: String, default: '' }
})

defineEmits(['action'])

const iconName = computed(() => ({
  error: 'WarningFilled',
  loading: 'Loading',
  success: 'SuccessFilled',
  empty: 'FolderOpened'
}[props.type] || 'InfoFilled'))
</script>

<style scoped>
.smartdoc-state{min-height:132px;padding:24px;display:flex;align-items:center;justify-content:center;gap:14px;border:1px dashed var(--smartdoc-border-strong);border-radius:var(--smartdoc-radius-md);background:var(--smartdoc-surface-muted);color:var(--smartdoc-text)}
.state-icon{width:44px;height:44px;display:grid;place-items:center;flex:none;border-radius:14px;background:var(--smartdoc-primary-soft);color:var(--smartdoc-primary-active)}
.state-copy{max-width:520px;flex:1}.state-copy strong{display:block;margin-bottom:5px;font-size:14px}.state-copy p{margin:0;color:var(--smartdoc-text-secondary);font-size:12px;line-height:1.65}
.is-error .state-icon{background:#f7eaea;color:var(--smartdoc-danger)}.is-loading .state-icon{animation:spin 1.1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:640px){.smartdoc-state{align-items:flex-start;flex-wrap:wrap}.state-copy{min-width:calc(100% - 60px)}}
</style>
