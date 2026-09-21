<template>
  <section class="workbench-overview smartdoc-surface" aria-labelledby="workbench-overview-title">
    <div class="overview-heading">
      <div><h2 id="workbench-overview-title">今天的文档工作</h2><p>从最近使用、收藏和待处理内容继续。</p></div>
      <el-button text type="primary" @click="$emit('open-library')">查看全部文档</el-button>
    </div>
    <div class="overview-grid">
      <button type="button" class="metric recent" @click="$emit('open-document', recentDocuments[0])">
        <span class="metric-icon"><el-icon><Clock /></el-icon></span><span><strong>{{ recentDocuments.length }}</strong><small>最近更新</small></span>
      </button>
      <button type="button" class="metric" @click="$emit('filter', 'favorite')">
        <span class="metric-icon"><el-icon><StarFilled /></el-icon></span><span><strong>{{ favoriteCount }}</strong><small>收藏文档</small></span>
      </button>
      <button type="button" class="metric" @click="$emit('filter', 'pending')">
        <span class="metric-icon"><el-icon><DataAnalysis /></el-icon></span><span><strong>{{ pendingCount }}</strong><small>待处理</small></span>
      </button>
    </div>
    <div v-if="recentDocuments.length" class="recent-strip" aria-label="最近文档">
      <button v-for="doc in recentDocuments" :key="doc.id" type="button" @click="$emit('open-document', doc)">
        <el-icon><Document /></el-icon><span>{{ doc.name }}</span><small>{{ doc.time }}</small>
      </button>
    </div>
    <p v-else class="overview-empty">上传或新建第一份文档后，最近使用记录会显示在这里。</p>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ documents: { type: Array, default: () => [] } })
defineEmits(['open-library', 'open-document', 'filter'])

const recentDocuments = computed(() => [...props.documents].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 3))
const favoriteCount = computed(() => props.documents.filter(doc => doc.favorite).length)
const pendingCount = computed(() => props.documents.filter(doc => !doc.analyzed || doc.parseStatus === 'parsing').length)
</script>

<style scoped>
.workbench-overview{margin:0 auto 22px;padding:20px 22px;max-width:1100px}.overview-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.overview-heading h2{margin:0;color:var(--smartdoc-text);font-size:17px}.overview-heading p{margin:5px 0 0;color:var(--smartdoc-text-secondary);font-size:12px}.overview-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:16px}.metric{min-height:72px;padding:13px 15px;border:1px solid var(--smartdoc-border);border-radius:14px;display:flex;align-items:center;gap:12px;background:var(--smartdoc-surface-muted);color:var(--smartdoc-text);text-align:left;cursor:pointer}.metric:hover{border-color:var(--smartdoc-primary);background:var(--smartdoc-primary-subtle)}.metric-icon{width:38px;height:38px;display:grid;place-items:center;border-radius:12px;background:var(--smartdoc-primary-soft);color:var(--smartdoc-primary-active);font-size:18px}.metric strong,.metric small{display:block}.metric strong{font-size:20px}.metric small{margin-top:3px;color:var(--smartdoc-text-secondary);font-size:11px}.recent-strip{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:12px}.recent-strip button{min-width:0;padding:10px;border:0;border-radius:10px;display:grid;grid-template-columns:20px minmax(0,1fr) auto;align-items:center;gap:7px;background:transparent;color:var(--smartdoc-text-secondary);text-align:left;cursor:pointer}.recent-strip button:hover{background:var(--smartdoc-primary-subtle);color:var(--smartdoc-primary-active)}.recent-strip span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.recent-strip small{font-size:10px}.overview-empty{margin:14px 0 0;color:var(--smartdoc-text-muted);font-size:12px}@media(max-width:760px){.overview-grid,.recent-strip{grid-template-columns:1fr}.overview-heading{align-items:center}}
</style>
