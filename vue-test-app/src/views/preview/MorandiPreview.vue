<template>
  <div class="preview-shell">
    <aside class="preview-aside">
      <div>
        <div class="preview-brand">
          <div class="preview-logo"><el-icon><Cpu /></el-icon></div>
          <span>SmartDoc</span>
        </div>

        <p class="preview-label">WORKSPACE</p>
        <button v-for="item in navItems" :key="item.key" class="preview-nav" :class="{ active: activeNav === item.key }" @click="activeNav = item.key">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </div>

      <div class="preview-user">
        <el-avatar :size="34">J</el-avatar>
        <div><strong>Jendeubang</strong><span>个人工作区</span></div>
        <el-icon><MoreFilled /></el-icon>
      </div>
    </aside>

    <main class="preview-main">
      <header class="preview-header">
        <div><span class="preview-crumb">SmartDoc / {{ activeNavLabel }}</span><h1>{{ activeNav === 'library' ? '云端文档库' : '我的工作台' }}</h1></div>
        <div class="preview-header-actions"><el-button text circle><el-icon><Bell /></el-icon></el-button><el-button round plain>帮助中心</el-button></div>
      </header>

      <section v-if="activeNav !== 'library'" class="preview-hero">
        <p class="eyebrow">SMART DOCUMENT WORKSPACE</p>
        <h2>今天想让 SmartDoc<br />帮你完成什么？</h2>
        <p class="hero-copy">上传文档、提取重点，或让 AI 为你规划下一步工作。</p>
        <div class="task-box">
          <el-icon class="task-plus"><Plus /></el-icon>
          <input v-model="task" placeholder="例如：总结这份项目周报，并提取风险事项" @keyup.enter="submitTask" />
          <el-button class="task-send" circle @click="submitTask"><el-icon><Top /></el-icon></el-button>
        </div>
        <div class="prompt-row">
          <button v-for="prompt in prompts" :key="prompt.label" @click="task = prompt.value"><el-icon><component :is="prompt.icon" /></el-icon>{{ prompt.label }}</button>
        </div>
      </section>

      <section v-if="activeNav === 'library'" class="library-intro">
        <div><p class="eyebrow">DOCUMENT LIBRARY</p><h2>让每份文档都更有价值</h2><p>统一管理、智能提取，并随时回到你需要的内容。</p></div>
        <el-button type="primary" round><el-icon><Upload /></el-icon>上传文档</el-button>
      </section>

      <section class="stats-grid">
        <article v-for="stat in stats" :key="stat.label" class="stat-item" :class="stat.tone">
          <el-icon><component :is="stat.icon" /></el-icon>
          <div><strong>{{ stat.value }}</strong><span>{{ stat.label }}</span></div>
        </article>
      </section>

      <section class="content-grid">
        <article class="surface docs-surface">
          <div class="surface-title"><div><p class="eyebrow">RECENT DOCUMENTS</p><h3>最近文档</h3></div><el-button text>查看全部 <el-icon><ArrowRight /></el-icon></el-button></div>
          <button v-for="doc in documents" :key="doc.name" class="doc-row">
            <span class="doc-icon" :class="doc.color"><el-icon><Document /></el-icon></span>
            <span class="doc-name"><strong>{{ doc.name }}</strong><small>{{ doc.meta }}</small></span>
            <el-tag size="small" round :type="doc.status === '已分析' ? 'success' : 'info'">{{ doc.status }}</el-tag>
            <el-icon class="row-arrow"><ArrowRight /></el-icon>
          </button>
        </article>

        <article class="surface activity-surface">
          <div class="surface-title"><div><p class="eyebrow">AI ACTIVITY</p><h3>AI 正在处理</h3></div><span class="live-dot">实时</span></div>
          <div class="activity-item"><span class="activity-mark lavender"><el-icon><MagicStick /></el-icon></span><div><strong>{{ latestTask || '会议纪要提炼' }}</strong><small>正在生成结构化摘要</small><el-progress :percentage="68" :show-text="false" :stroke-width="6" /></div></div>
          <div class="activity-item"><span class="activity-mark pink"><el-icon><Connection /></el-icon></span><div><strong>产品知识库</strong><small>已完成 24 / 26 份文档索引</small><el-progress :percentage="92" :show-text="false" :stroke-width="6" status="success" /></div></div>
          <button class="timeline-link" @click="activeNav = 'agent'">查看 Agent 执行时间线 <el-icon><ArrowRight /></el-icon></button>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

const activeNav = ref('workbench')
const task = ref('')
const latestTask = ref('')

const navItems = [
  { key: 'workbench', label: '我的工作台', icon: 'Monitor' },
  { key: 'library', label: '云端文档库', icon: 'FolderOpened' },
  { key: 'agent', label: 'AI Agent', icon: 'Cpu' },
  { key: 'knowledge', label: '知识库', icon: 'Collection' },
  { key: 'aiops', label: '运行监控', icon: 'DataLine' }
]

const prompts = [
  { label: '上传文档', value: '上传一份文档并提取重点', icon: 'Upload' },
  { label: '生成 PPT', value: '根据文档生成一份项目汇报 PPT', icon: 'Monitor' },
  { label: '开始对话', value: '基于我的知识库回答问题', icon: 'ChatLineSquare' }
]

const stats = [
  { label: '云端文档', value: '128', icon: 'Document', tone: 'lavender' },
  { label: 'AI 已分析', value: '86', icon: 'MagicStick', tone: 'green' },
  { label: '待处理任务', value: '07', icon: 'Clock', tone: 'pink' },
  { label: '知识库', value: '12', icon: 'Collection', tone: 'sand' }
]

const documents = [
  { name: 'SmartDoc 项目需求说明书', meta: '今天 10:24 · 1.8 MB', status: '已分析', color: 'lavender' },
  { name: 'RAG 知识库方案评审纪要', meta: '昨天 16:40 · 860 KB', status: '已分析', color: 'pink' },
  { name: '七月运营周报', meta: '2026-07-29 · 420 KB', status: '待处理', color: 'green' }
]

const activeNavLabel = computed(() => navItems.find(item => item.key === activeNav.value)?.label || '我的工作台')

function submitTask() {
  if (!task.value.trim()) return
  latestTask.value = task.value.trim()
  task.value = ''
  ElMessage.success('已创建 AI 任务（预览模式）')
}
</script>

<style scoped>
.preview-shell { min-height: 100vh; display: grid; grid-template-columns: 238px minmax(0, 1fr); background: var(--sd-bg); color: var(--sd-text); }
.preview-aside { min-height: 100vh; display: flex; flex-direction: column; justify-content: space-between; padding: 28px 16px 18px; background: rgba(255, 253, 249, .92); border-right: 1px solid var(--sd-border); }
.preview-brand { display: flex; align-items: center; gap: 11px; padding: 0 10px 28px; color: var(--sd-lavender-deep); font-size: 19px; font-weight: 750; }
.preview-logo { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 12px; background: var(--sd-lavender); color: #fffdf9; box-shadow: 0 8px 18px rgba(172,160,206,.28); }
.preview-label { margin: 0 10px 10px; color: #a39bac; font-size: 10px; font-weight: 700; letter-spacing: .14em; }
.preview-nav { width: 100%; display: flex; align-items: center; gap: 11px; margin: 4px 0; padding: 11px 12px; border: 0; border-radius: 12px; background: transparent; color: var(--sd-text-muted); font: inherit; font-size: 14px; text-align: left; cursor: pointer; transition: .18s ease; }
.preview-nav:hover { background: #f3edf1; color: var(--sd-lavender-deep); }
.preview-nav.active { background: #eee8f5; color: var(--sd-lavender-deep); font-weight: 700; }
.preview-user { display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 14px; background: #f7f3ed; }
.preview-user .el-avatar { background: var(--sd-pink); color: var(--sd-lavender-deep); font-weight: 700; }.preview-user div { flex: 1; min-width: 0; }.preview-user strong,.preview-user span { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.preview-user strong { font-size:13px; }.preview-user span { margin-top:3px; color:var(--sd-text-muted); font-size:11px; }
.preview-main { padding: 30px clamp(24px, 5vw, 72px) 50px; overflow: auto; }.preview-header { display:flex; align-items:center; justify-content:space-between; margin:0 auto 26px; max-width:1280px; }.preview-header h1 { margin:5px 0 0; font-size:22px; line-height:1.2; }.preview-crumb,.eyebrow { color:var(--sd-text-muted); font-size:11px; font-weight:700; letter-spacing:.12em; }.preview-header-actions { display:flex; align-items:center; gap:6px; }
.preview-hero,.library-intro { max-width:1280px; margin:0 auto; padding:40px; border:1px solid var(--sd-border); border-radius:24px; background:#fffdf9; box-shadow:var(--sd-shadow); }.preview-hero h2,.library-intro h2 { margin:9px 0 10px; color:var(--sd-text); font-size:clamp(28px,4vw,42px); line-height:1.18; letter-spacing:-.03em; }.hero-copy,.library-intro p:not(.eyebrow) { margin:0; color:var(--sd-text-muted); font-size:15px; }.task-box { display:flex; align-items:center; gap:12px; max-width:780px; height:62px; margin-top:30px; padding:0 10px 0 18px; border:1px solid var(--sd-border); border-radius:18px; background:#fff; transition:.18s ease; }.task-box:focus-within { border-color:var(--sd-lavender); box-shadow:0 0 0 4px rgba(172,160,206,.16); }.task-box input { min-width:0; flex:1; border:0; outline:0; background:transparent; color:var(--sd-text); font:inherit; font-size:15px; }.task-box input::placeholder { color:#aaa3b1; }.task-plus { color:var(--sd-lavender-deep); font-size:19px; }.task-send { --el-button-bg-color:var(--sd-lavender-deep); --el-button-border-color:var(--sd-lavender-deep); --el-button-hover-bg-color:#71678b; --el-button-hover-border-color:#71678b; }.prompt-row { display:flex; flex-wrap:wrap; gap:9px; margin-top:16px; }.prompt-row button { display:flex; gap:7px; align-items:center; padding:8px 12px; border:1px solid var(--sd-border); border-radius:999px; background:#fffdf9; color:var(--sd-text-muted); font:inherit; font-size:13px; cursor:pointer; }.prompt-row button:hover { background:#eee8f5; color:var(--sd-lavender-deep); }
.library-intro { display:flex; align-items:center; justify-content:space-between; }.stats-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; max-width:1280px; margin:22px auto; }.stat-item { display:flex; align-items:center; gap:14px; padding:18px; border:1px solid var(--sd-border); border-radius:16px; background:rgba(255,253,249,.82); }.stat-item>.el-icon { display:grid; place-items:center; width:39px; height:39px; border-radius:12px; font-size:19px; }.stat-item strong,.stat-item span { display:block; }.stat-item strong { font-size:23px; line-height:1; }.stat-item span { margin-top:5px; color:var(--sd-text-muted); font-size:12px; }.stat-item.lavender>.el-icon { background:#eee8f5; color:#84789f; }.stat-item.green>.el-icon { background:#e5f0e3; color:#638570; }.stat-item.pink>.el-icon { background:#f2e7eb; color:#a36e7b; }.stat-item.sand>.el-icon { background:#f2eadc; color:#a58252; }
.content-grid { display:grid; grid-template-columns:minmax(0,1.25fr) minmax(320px,.75fr); gap:18px; max-width:1280px; margin:0 auto; }.surface { padding:24px; border:1px solid var(--sd-border); border-radius:20px; background:#fffdf9; }.surface-title { display:flex; align-items:flex-start; justify-content:space-between; margin-bottom:14px; }.surface-title h3 { margin:5px 0 0; font-size:18px; }.surface-title .el-button { color:var(--sd-lavender-deep); }.doc-row { width:100%; display:grid; grid-template-columns:38px minmax(0,1fr) auto 20px; align-items:center; gap:11px; padding:12px 0; border:0; border-top:1px solid #f0eaed; background:transparent; color:inherit; text-align:left; cursor:pointer; }.doc-row:first-of-type { border-top:0; }.doc-icon { display:grid; place-items:center; width:36px; height:36px; border-radius:10px; }.doc-icon.lavender { background:#eee8f5; color:#84789f; }.doc-icon.pink { background:#f2e7eb; color:#a36e7b; }.doc-icon.green { background:#e5f0e3; color:#638570; }.doc-name { min-width:0; }.doc-name strong,.doc-name small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.doc-name strong { font-size:13px; }.doc-name small { margin-top:4px; color:var(--sd-text-muted); font-size:11px; }.row-arrow { color:#b5adbb; }.activity-surface { background:#f7f3ed; }.live-dot { display:flex; align-items:center; gap:6px; color:#638570; font-size:12px; }.live-dot::before { content:''; width:7px; height:7px; border-radius:50%; background:#84a896; }.activity-item { display:flex; gap:11px; padding:14px 0; border-top:1px solid #ebe3e4; }.activity-mark { display:grid; flex:0 0 auto; place-items:center; width:34px; height:34px; border-radius:10px; }.activity-mark.lavender { background:#e8e0f1; color:#84789f; }.activity-mark.pink { background:#f0dfe5; color:#a36e7b; }.activity-item div { flex:1; }.activity-item strong,.activity-item small { display:block; }.activity-item strong { font-size:13px; }.activity-item small { margin:4px 0 8px; color:var(--sd-text-muted); font-size:11px; }.timeline-link { display:flex; align-items:center; gap:6px; margin-top:4px; padding:0; border:0; background:transparent; color:var(--sd-lavender-deep); font:inherit; font-size:13px; cursor:pointer; }
@media (max-width:960px) { .preview-shell { grid-template-columns:76px minmax(0,1fr); }.preview-brand span,.preview-label,.preview-nav span,.preview-user div,.preview-user>.el-icon { display:none; }.preview-brand { justify-content:center; padding:0 0 28px; }.preview-nav { justify-content:center; padding:13px; }.preview-user { justify-content:center; padding:8px; }.stats-grid { grid-template-columns:repeat(2,1fr); }.content-grid { grid-template-columns:1fr; } }
@media (max-width:640px) { .preview-shell { grid-template-columns:1fr; }.preview-aside { display:none; }.preview-main { padding:22px 16px 34px; }.preview-header { align-items:flex-start; }.preview-header-actions .el-button:last-child { display:none; }.preview-hero,.library-intro { padding:26px 20px; }.preview-hero h2,.library-intro h2 { font-size:29px; }.library-intro { align-items:flex-start; gap:16px; flex-direction:column; }.stats-grid { gap:10px; }.stat-item { padding:14px; }.surface { padding:18px; }.doc-row { grid-template-columns:34px minmax(0,1fr) 20px; }.doc-row .el-tag { display:none; } }

.task-send {
  width: 42px !important;
  min-width: 42px !important;
  height: 42px !important;
  padding: 0 !important;
  border: 1px solid #74698e !important;
  background: #74698e !important;
  color: #fff !important;
  box-shadow: 0 6px 14px rgba(116, 105, 142, 0.3) !important;
}
.task-send:hover,
.task-send:focus-visible {
  border-color: #615778 !important;
  background: #615778 !important;
  color: #fff !important;
  box-shadow: 0 0 0 4px rgba(172, 160, 206, 0.28), 0 7px 16px rgba(116, 105, 142, 0.32) !important;
}
.task-send :deep(.el-icon),
.task-send :deep(.el-icon svg) {
  color: #fff !important;
  fill: currentColor;
}</style>
