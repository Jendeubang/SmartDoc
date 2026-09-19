<template>
  <section class="ai-context" :class="{ collapsed: !expanded }" aria-label="AI 回答范围">
    <button type="button" class="context-head" :aria-expanded="expanded" @click="expanded = !expanded">
      <span class="context-title"><el-icon><FolderOpened /></el-icon>回答范围</span>
      <span class="context-status">
        <el-tag size="small" effect="plain" :type="selectedCount ? 'success' : 'info'">{{ scopeLabel }}</el-tag>
        <el-icon class="context-chevron"><ArrowDown /></el-icon>
      </span>
    </button>
    <div v-show="expanded" class="context-control">
      <el-select :model-value="modelValue" multiple collapse-tags collapse-tags-tooltip clearable filterable :loading="loading" placeholder="不选择时检索全部知识库" @update:model-value="$emit('update:modelValue', $event)">
        <el-option v-for="doc in documents" :key="doc.id" :label="doc.title" :value="doc.id">
          <div class="context-option"><span>{{ doc.title }}</span><small>{{ doc.organizationName || (doc.organizationId ? '企业文档' : '个人文档') }} · {{ doc.indexed === false ? '待索引' : '可检索' }}</small></div>
        </el-option>
      </el-select>
      <el-button circle plain :loading="uploading" aria-label="上传文档并加入回答范围" title="上传文档并加入回答范围" @click="$emit('upload')"><el-icon><Plus /></el-icon></el-button>
    </div>
    <template v-if="expanded">
      <p v-if="error" class="context-error"><el-icon><WarningFilled /></el-icon>{{ error }}</p>
      <p v-else class="context-hint">{{ hint }}</p>
    </template>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  documents: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  ragEnabled: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
defineEmits(['update:modelValue', 'upload'])

const expanded = ref(false)
const selectedCount = computed(() => props.modelValue.length)
const selectedTitles = computed(() => props.documents.filter(doc => props.modelValue.includes(doc.id)).map(doc => doc.title))
const scopeLabel = computed(() => selectedCount.value ? `指定文档 ${selectedCount.value} 份` : (props.ragEnabled ? '全部知识库' : '普通对话'))
const hint = computed(() => selectedCount.value
  ? `AI 将优先依据：${selectedTitles.value.slice(0, 3).join('、')}${selectedCount.value > 3 ? ` 等 ${selectedCount.value} 份文档` : ''}`
  : props.ragEnabled ? '未指定文档，AI 会按你的权限检索全部可访问知识库，并在回答中显示来源。' : '当前不会主动检索文档；开启“基于文档回答”可使用知识库。')
</script>

<style scoped>
.ai-context{width:100%;max-width:860px;margin:0 auto 10px;padding:12px 14px;border:1px solid var(--smartdoc-border);border-radius:14px;background:rgba(255,253,249,.94);box-shadow:0 7px 20px rgba(67,82,69,.045);transition:padding .18s ease}.ai-context.collapsed{padding-top:9px;padding-bottom:9px}.context-head,.context-control{display:flex;align-items:center;gap:10px}.context-head{width:100%;justify-content:space-between;margin:0 0 9px;padding:0;border:0;background:transparent;color:inherit;font:inherit;cursor:pointer}.collapsed .context-head{margin-bottom:0}.context-title{display:inline-flex;align-items:center;gap:6px;color:var(--smartdoc-primary-active);font-size:12px;font-weight:700}.context-status{display:inline-flex;align-items:center;gap:8px}.context-chevron{color:var(--smartdoc-text-secondary);transition:transform .18s ease}.context-head[aria-expanded="true"] .context-chevron{transform:rotate(180deg)}.context-head:hover .context-chevron{color:var(--smartdoc-primary-active)}.context-control .el-select{min-width:0;flex:1}.context-option{display:flex;align-items:center;justify-content:space-between;gap:15px}.context-option small{color:var(--smartdoc-text-muted);font-size:10px}.context-hint,.context-error{margin:8px 0 0;font-size:11px;line-height:1.55}.context-hint{color:var(--smartdoc-text-secondary)}.context-error{display:flex;align-items:center;gap:5px;color:var(--smartdoc-danger)}
</style>
