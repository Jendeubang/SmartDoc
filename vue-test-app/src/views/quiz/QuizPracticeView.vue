<template>
  <div class="quiz-page" v-loading="loading">
    <header class="quiz-header">
      <div>
        <button class="back-link" @click="router.push('/toolbox')"><el-icon><ArrowLeft /></el-icon> 返回工具箱</button>
        <div class="title-row"><span class="title-mark"><el-icon><Reading /></el-icon></span><div><h1>SmartDoc 智能练题</h1><p>整合多份题库，按题型练习；题库会保存到云端，下次可直接继续。</p></div></div>
      </div>
      <el-button plain @click="startNewSet"><el-icon><Plus /></el-icon> 新建练题集</el-button>
    </header>

    <div class="quiz-layout">
      <aside class="set-sidebar">
        <div class="side-heading"><div><span>我的练题集</span><small>{{ savedSets.length }} 份已保存</small></div><el-button text @click="loadDocuments"><el-icon><Refresh /></el-icon></el-button></div>
        <button v-for="set in savedSets" :key="set.id" class="saved-set" :class="{ active: activeSet?.id === set.id }" @click="openSet(set.id)"><span class="set-icon"><el-icon><Collection /></el-icon></span><span><strong>{{ set.title }}</strong><small>{{ formatDate(set.updateTime || set.createTime) }}</small></span><el-icon><ArrowRight /></el-icon></button>
        <div v-if="!savedSets.length" class="empty-sets">还没有练题集<br />从右侧选择文档开始创建。</div>
      </aside>

      <main class="quiz-main">
        <section v-if="!activeSet" class="builder-card">
          <div class="builder-heading"><div><span class="eyebrow">CREATE QUIZ SET</span><h2>从多份文档构建练题集</h2><p>选择题库或学习资料后，DeepSeek 会识别题目、答案和题型，并统一成可练习的题集。</p></div></div>
          <div class="builder-toolbar"><el-input v-model="setTitle" placeholder="练题集名称，例如：Java 期末复习" /><input ref="uploadInput" class="hidden-file-input" type="file" multiple accept=".docx,.pdf,.txt,.md,.markdown,.html" @change="uploadSources" /><el-button plain :loading="uploading" @click="uploadInput?.click()"><el-icon><UploadFilled /></el-icon> 上传文档</el-button></div>
          <div class="source-tip">可一次上传多份文档，或勾选已有文档。扫描 PDF 请先用 OCR 识别后再加入。</div>
          <el-checkbox-group v-model="selectedSourceIds" class="source-list">
            <el-checkbox v-for="doc in sourceDocuments" :key="doc.id" :label="String(doc.id)" class="source-item"><span class="source-name">{{ doc.title || '未命名文档' }}</span><small>{{ doc.category === 'quiz-source' ? '已上传题库' : '云端文档' }}</small></el-checkbox>
          </el-checkbox-group>
          <div class="builder-footer"><span>已选 {{ selectedSourceIds.length }} 份文档</span><el-button type="primary" :loading="building" :disabled="!selectedSourceIds.length" @click="createQuizSet"><el-icon><MagicStick /></el-icon> 提取并创建练题集</el-button></div>
        </section>

        <section v-else class="practice-card">
          <div class="practice-top"><div><button class="plain-back" @click="leaveSet"><el-icon><ArrowLeft /></el-icon> 练题集</button><h2>{{ activeSet.title }}</h2><p>{{ activeSet.sourceNames?.join('、') || '已保存练题集' }}</p></div><div class="score-chip">正确 {{ correctCount }} / {{ activeSet.questions.length }}</div></div>
          <div class="type-summary"><button v-for="item in typeSummary" :key="item.type" :class="{ active: activeFilter === item.type }" @click="setTypeFilter(item.type)"><span>{{ item.label }}</span><strong>{{ item.count }}</strong></button></div>
          <div class="question-nav"><button v-for="(question, index) in visibleQuestions" :key="question.id" :class="{ active: activeQuestion?.id === question.id, done: feedbackById[question.id] }" @click="activeIndex = index">{{ index + 1 }}</button></div>
          <article v-if="activeQuestion" class="question-card">
            <div class="question-meta"><span class="type-badge">{{ typeName(activeQuestion.type) }}</span><span>第 {{ activeIndex + 1 }} / {{ visibleQuestions.length }} 题</span></div>
            <h3>{{ activeQuestion.stem }}</h3>

            <el-radio-group v-if="['single', 'judge'].includes(activeQuestion.type)" v-model="answerSheet[activeQuestion.id]" class="option-list" :disabled="Boolean(feedbackById[activeQuestion.id])"><el-radio v-for="option in normalizedOptions(activeQuestion)" :key="option.key" :label="option.key" border><strong>{{ option.key }}.</strong> {{ option.label }}</el-radio></el-radio-group>
            <el-checkbox-group v-else-if="activeQuestion.type === 'multiple'" v-model="answerSheet[activeQuestion.id]" class="option-list" :disabled="Boolean(feedbackById[activeQuestion.id])"><el-checkbox v-for="option in normalizedOptions(activeQuestion)" :key="option.key" :label="option.key" border><strong>{{ option.key }}.</strong> {{ option.label }}</el-checkbox></el-checkbox-group>
            <el-input v-else-if="activeQuestion.type === 'fill'" v-model="answerSheet[activeQuestion.id]" :disabled="Boolean(feedbackById[activeQuestion.id])" placeholder="填写答案" @keyup.enter="submitAnswer" />
            <el-input v-else v-model="answerSheet[activeQuestion.id]" type="textarea" :rows="5" :disabled="Boolean(feedbackById[activeQuestion.id])" placeholder="请输入简答内容，提交后由 AI 判断与参考答案的相似度" />

            <div v-if="feedbackById[activeQuestion.id]" class="answer-result" :class="{ correct: feedbackById[activeQuestion.id].correct }"><strong>{{ feedbackById[activeQuestion.id].correct ? '回答正确' : '回答有误' }}</strong><span v-if="feedbackById[activeQuestion.id].score !== undefined">匹配度 {{ feedbackById[activeQuestion.id].score }}%</span><p>{{ feedbackById[activeQuestion.id].message }}</p><p v-if="!feedbackById[activeQuestion.id].correct"><b>正确答案：</b>{{ displayAnswer(activeQuestion) }}</p></div>
            <div class="question-actions"><span>{{ activeQuestion.type === 'short' ? '简答题将由 DeepSeek 进行语义判定' : '提交后立即显示判定结果' }}</span><div><el-button v-if="feedbackById[activeQuestion.id] && activeIndex > 0" @click="activeIndex -= 1">上一题</el-button><el-button v-if="feedbackById[activeQuestion.id] && activeIndex < visibleQuestions.length - 1" type="primary" @click="activeIndex += 1">下一题</el-button><el-button v-else-if="!feedbackById[activeQuestion.id]" type="primary" :loading="judging" @click="submitAnswer">提交答案</el-button></div></div>
          </article>
          <div v-else class="no-questions">当前题型暂无题目</div>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { docApi } from '../../api/document'
import { fileApi } from '../../api/file'
import { aiApi } from '../../api/ai'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const uploading = ref(false)
const building = ref(false)
const judging = ref(false)
const documents = ref([])
const selectedSourceIds = ref([])
const setTitle = ref('')
const uploadInput = ref(null)
const activeSet = ref(null)
const activeIndex = ref(0)
const activeFilter = ref('all')
const answerSheet = ref({})
const feedbackById = ref({})

const typeConfig = { single: '选择题', multiple: '多选题', fill: '填空题', judge: '判断题', short: '简答题' }
const savedSets = computed(() => documents.value.filter(doc => doc.category === 'quiz-set'))
const sourceDocuments = computed(() => documents.value.filter(doc => doc.category !== 'quiz-set'))
const typeSummary = computed(() => {
  const all = activeSet.value?.questions || []
  const items = [{ type: 'all', label: '全部', count: all.length }]
  Object.entries(typeConfig).forEach(([type, label]) => { const count = all.filter(question => question.type === type).length; if (count) items.push({ type, label, count }) })
  return items
})
const visibleQuestions = computed(() => {
  const questions = activeSet.value?.questions || []
  return activeFilter.value === 'all' ? questions : questions.filter(question => question.type === activeFilter.value)
})
const activeQuestion = computed(() => visibleQuestions.value[activeIndex.value])
const correctCount = computed(() => Object.values(feedbackById.value).filter(item => item.correct).length)

function data(response) { return response?.data ?? response ?? {} }
function userId() { return localStorage.getItem('userId') }
function formatDate(value) { return value ? String(value).replace('T', ' ').slice(0, 10) : '刚刚创建' }
function typeName(type) { return typeConfig[type] || '简答题' }
function normalize(value) { return String(value ?? '').toLowerCase().replace(/[\s\p{P}\p{S}]/gu, '') }
function startNewSet() { activeSet.value = null; activeIndex.value = 0; activeFilter.value = 'all'; answerSheet.value = {}; feedbackById.value = {}; router.push('/quiz') }
function leaveSet() { activeSet.value = null; router.push('/quiz') }
function setTypeFilter(type) { activeFilter.value = type; activeIndex.value = 0 }
function normalizedOptions(question) {
  const options = Array.isArray(question.options) ? question.options : []
  if (question.type === 'judge' && !options.length) return [{ key: 'true', label: '正确' }, { key: 'false', label: '错误' }]
  return options.map((option, index) => {
    if (typeof option === 'object') return { key: String(option.key || option.value || String.fromCharCode(65 + index)), label: String(option.label || option.text || option.key) }
    const match = String(option).match(/^\s*([A-Z])\s*[\.、:：]\s*(.*)$/i)
    return match ? { key: match[1].toUpperCase(), label: match[2] } : { key: String.fromCharCode(65 + index), label: String(option) }
  })
}
function displayAnswer(question) {
  if (Array.isArray(question.answer)) return question.answer.join('、')
  if (question.type === 'judge') return ['true', '正确', '对'].includes(String(question.answer).toLowerCase()) ? '正确' : '错误'
  return String(question.answer || '')
}
function normalizeQuestion(raw, index) {
  const rawType = String(raw.type || raw.questionType || '').toLowerCase()
  const typeMap = { choice: 'single', singlechoice: 'single', multiplechoice: 'multiple', blank: 'fill', truefalse: 'judge', essay: 'short', shortanswer: 'short', 单选题: 'single', 多选题: 'multiple', 填空题: 'fill', 判断题: 'judge', 简答题: 'short' }
  let type = typeMap[rawType] || rawType
  if (!typeConfig[type]) type = Array.isArray(raw.options) && raw.options.length > 1 ? 'single' : 'short'
  const options = Array.isArray(raw.options) ? raw.options : []
  if (type === 'judge' && !options.length) raw.options = [{ key: 'true', label: '正确' }, { key: 'false', label: '错误' }]
  if ((type === 'single' || type === 'multiple') && options.length < 2) type = 'short'
  const answer = raw.answer ?? raw.referenceAnswer ?? raw.correctAnswer ?? ''
  return { id: String(raw.id || `q-${index + 1}`), type, stem: String(raw.stem || raw.question || raw.title || '').trim(), options: raw.options || options, answer: Array.isArray(answer) ? answer.map(String) : String(answer), analysis: String(raw.analysis || raw.explanation || '请结合参考答案复习对应知识点。') }
}
function parseModelQuestions(result) {
  const text = typeof result === 'string' ? result : (result?.finalAnswer || result?.answer || result?.content || JSON.stringify(result || ''))
  const clean = text.replace(/```(?:json)?/gi, '').replace(/```/g, '').trim()
  const start = clean.indexOf('['); const end = clean.lastIndexOf(']')
  if (start < 0 || end <= start) return []
  try { const parsed = JSON.parse(clean.slice(start, end + 1)); return Array.isArray(parsed) ? parsed.map(normalizeQuestion).filter(question => question.stem && question.answer) : [] } catch { return [] }
}
function detectQuestionTypeHeading(line, fallback) {
  if (/多项选择|多选题|多选/.test(line)) return 'multiple'
  if (/单项选择|单选题|选择题|单选/.test(line)) return 'single'
  if (/判断题|辨析题|判断/.test(line)) return 'judge'
  if (/填空题|填空/.test(line)) return 'fill'
  if (/简答题|问答题|论述题|简答/.test(line)) return 'short'
  return fallback
}
function extractQuestionAnswerPairs(content, sourceName, offset = 0) {
  const fullText = String(content || '').replace(/\r\n/g, '\n')
  const lines = fullText.split('\n')
  const answerKey = {}
  for (const item of fullText.matchAll(/(?:^|[\n；;，,、\s])(\d+)\s*[.、．:：]\s*([A-H]|正确|错误|对|错)(?=\s*(?:[；;，,、]|\d+\s*[.、．:：]|$))/gim)) answerKey[item[1]] = item[2]
  const blocks = []
  let sectionType = 'short'
  let current = null
  const pushCurrent = () => { if (current?.lines.length) blocks.push(current); current = null }
  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line) { if (current) current.lines.push(''); continue }
    const nextType = detectQuestionTypeHeading(line, sectionType)
    if (nextType !== sectionType && /(选择题|单选|多选|判断|填空|简答|问答题|论述题)/.test(line)) { pushCurrent(); sectionType = nextType; continue }
    const start = line.match(/^(?:[（(]\s*(\d+)\s*[)）]|(\d+)\s*[.、．])\s*(.+)$/)
    const number = start?.[1] || start?.[2]
    const rest = start?.[3] || ''
    const explicitQuestion = /^(?:问|题目|问题)\s*[：:]/.test(rest)
    const questionLike = /[？?]$/.test(rest) || /^(?:单选|多选|判断|填空|简答)题/.test(rest)
    if (start && (explicitQuestion || sectionType !== 'short' || questionLike)) { pushCurrent(); current = { sectionType, number, lines: [rest] }; continue }
    if (current) current.lines.push(line)
  }
  pushCurrent()

  return blocks.map((block, index) => {
    const body = block.lines.join('\n').trim()
    const answerMatch = body.match(/(?:^|\n)\s*(?:参考)?(?:正确)?(?:答案|答)\s*[：:]\s*([\s\S]*)$/)
    if (!answerMatch && !answerKey[block.number]) return null
    const beforeAnswer = answerMatch ? body.slice(0, answerMatch.index).trim() : body
    const options = []
    const stemLines = []
    for (const line of beforeAnswer.split('\n')) {
      const option = line.trim().match(/^[（(]?([A-H])[)）]?\s*(?:[.、．:：]|\s+)\s*(.+)$/i)
      if (option) options.push({ key: option[1].toUpperCase(), label: option[2].trim() })
      else stemLines.push(line)
    }
    const stem = stemLines.join('\n').replace(/^(?:问|题目|问题)\s*[：:]\s*/, '').trim()
    if (!stem) return null
    const answerText = (answerMatch?.[1] || answerKey[block.number] || '').trim().replace(/\n[一二三四五六七八九十]+、[\s\S]*$/, '').trim()
    let type = block.sectionType
    const keys = (answerText.match(/\b[A-H]\b/gi) || []).map(key => key.toUpperCase())
    if (options.length >= 2 && type === 'short') type = keys.length > 1 ? 'multiple' : 'single'
    if (type === 'judge') return normalizeQuestion({ id: `source-${offset + index + 1}`, type, stem, options: [{ key: 'true', label: '正确' }, { key: 'false', label: '错误' }], answer: /^(?:正确|对|是|true)$/i.test(answerText) ? 'true' : 'false', analysis: `答案提取自《${sourceName}》。` }, offset + index)
    if ((type === 'single' || type === 'multiple') && options.length >= 2) return normalizeQuestion({ id: `source-${offset + index + 1}`, type, stem, options, answer: type === 'multiple' ? keys : (keys[0] || answerText), analysis: `答案提取自《${sourceName}》。` }, offset + index)
    if (type === 'fill') return normalizeQuestion({ id: `source-${offset + index + 1}`, type, stem, answer: answerText, analysis: `答案提取自《${sourceName}》。` }, offset + index)
    return normalizeQuestion({ id: `source-${offset + index + 1}`, type: 'short', stem, answer: answerText, analysis: `答案提取自《${sourceName}》。` }, offset + index)
  }).filter(Boolean)
}
async function loadDocuments() {
  if (!userId()) { router.push('/login'); return }
  loading.value = true
  try { documents.value = data(await docApi.getUserDocs(userId())) || [] } catch { ElMessage.error('读取文档库失败') } finally { loading.value = false }
}
async function openSet(id) {
  loading.value = true
  try {
    const detail = data(await docApi.getDocDetail(id))
    const saved = JSON.parse(detail.content || '{}')
    if (!Array.isArray(saved.questions) || !saved.questions.length) throw new Error('该练题集内容无效')
    activeSet.value = { ...saved, id: String(id), title: saved.title || detail.title, questions: saved.questions.map(normalizeQuestion) }
    activeIndex.value = 0; activeFilter.value = 'all'; answerSheet.value = {}; feedbackById.value = {}
    if (route.params.id !== String(id)) router.push(`/quiz/${id}`)
  } catch (error) { ElMessage.error(error?.message || '打开练题集失败') } finally { loading.value = false }
}
async function uploadSources(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) return
  uploading.value = true
  try {
    const newIds = []
    for (const file of files) {
      const uploaded = data(await fileApi.upload(file)); const fileId = uploaded.fileId || uploaded.id
      if (!fileId) throw new Error(`${file.name} 上传失败`)
      const created = data(await docApi.createDoc({ title: file.name, fileId, category: 'quiz-source' }))
      if (!created.id) throw new Error(`${file.name} 文档解析失败`)
      newIds.push(String(created.id))
    }
    await loadDocuments(); selectedSourceIds.value = [...new Set([...selectedSourceIds.value, ...newIds])]
    ElMessage.success(`已上传 ${newIds.length} 份题库文档`)
  } catch (error) { ElMessage.error(error?.message || '题库上传失败') } finally { event.target.value = ''; uploading.value = false }
}
async function createQuizSet() {
  building.value = true
  try {
    const selected = sourceDocuments.value.filter(doc => selectedSourceIds.value.includes(String(doc.id)))
    const sources = []
    for (const doc of selected) {
      const detail = data(await docApi.getDocDetail(doc.id))
      if (detail.content?.trim()) sources.push({ id: String(doc.id), name: doc.title, content: detail.content })
    }
    if (!sources.length) throw new Error('所选文档没有可读取正文；扫描件请先 OCR')

    let questions = []
    let offset = 0
    for (const source of sources) {
      const extracted = extractQuestionAnswerPairs(source.content, source.name, offset)
      questions.push(...extracted)
      offset += extracted.length
    }
    questions = questions.slice(0, 30)

    if (!questions.length) {
      const merged = sources.map((source, index) => `【文档 ${index + 1}：${source.name}】\n${source.content.slice(0, 6000)}`).join('\n\n').slice(0, 12000)
      const prompt = `请从以下资料中制作最多 12 道练习题。题型只能是 single（单选）、multiple（多选）、fill（填空）、judge（判断）、short（简答）。选择题必须提供 options，options 为 [{"key":"A","label":"选项内容"}]；判断题 options 固定为正确/错误。答案为选择题的 key、判断题 true 或 false、填空/简答为标准答案。只输出 JSON 数组，不要 Markdown。每一项格式：{"type":"single","stem":"题干","options":[{"key":"A","label":"..."}],"answer":"A","analysis":"解析"}。题目和答案必须依据资料，不要编造。\n\n资料：\n${merged}`
      const generated = data(await aiApi.executeAgent({ task: prompt, context: { source: 'quiz-set-builder', model: 'deepseek-chat' } }))
      questions = parseModelQuestions(generated)
    }
    if (!questions.length) throw new Error('未能提取到题目和答案；请确认文档中包含“问：…答：…”或可读正文')

    const title = setTitle.value.trim() || `练题集 ${new Date().toLocaleDateString('zh-CN')}`
    const created = data(await docApi.createDoc({ title, category: 'quiz-set' }))
    if (!created.id) throw new Error('练题集保存失败')
    const payload = { version: 1, title, sourceDocumentIds: sources.map(item => item.id), sourceNames: sources.map(item => item.name), questions, createdAt: new Date().toISOString() }
    await docApi.updateDoc(created.id, { title, content: JSON.stringify(payload), category: 'quiz-set', changeLog: '创建智能练题集' })
    await loadDocuments()
    ElMessage.success(`练题集已保存，共 ${questions.length} 道题${questions[0]?.id?.startsWith('source-') ? '（已直接提取题库问答）' : ''}`)
    await openSet(created.id)
  } catch (error) { ElMessage.error(error?.message || '创建练题集失败') } finally { building.value = false }
}
function objectiveCorrect(question, answer) {
  const expected = Array.isArray(question.answer) ? question.answer.map(normalize).sort() : normalize(question.answer)
  if (question.type === 'multiple') return Array.isArray(answer) && JSON.stringify(answer.map(normalize).sort()) === JSON.stringify(expected)
  if (question.type === 'judge') {
    const actual = ['true', '正确', '对', '是'].includes(String(answer).toLowerCase()) ? 'true' : 'false'
    const wanted = ['true', '正确', '对', '是'].includes(String(question.answer).toLowerCase()) ? 'true' : 'false'
    return actual === wanted
  }
  const actual = normalize(answer)
  return actual === expected || (question.type === 'fill' && (expected.includes(actual) || actual.includes(expected)))
}
async function submitAnswer() {
  const question = activeQuestion.value
  if (!question) return
  const answer = answerSheet.value[question.id]
  if (!answer || (Array.isArray(answer) && !answer.length)) return ElMessage.warning('请先作答')
  judging.value = true
  try {
    if (question.type !== 'short') {
      const correct = objectiveCorrect(question, answer)
      feedbackById.value = { ...feedbackById.value, [question.id]: { correct, message: correct ? '回答正确，继续下一题。' : question.analysis } }
      return
    }
    const prompt = `判断学生的简答是否覆盖标准答案的核心要点。只输出 JSON：{"correct":true或false,"score":0-100,"message":"一句简洁评价"}。正确阈值为 70 分。\n题目：${question.stem}\n标准答案：${question.answer}\n学生答案：${answer}`
    const judged = data(await aiApi.executeAgent({ task: prompt, context: { source: 'quiz-short-answer-judge', model: 'deepseek-chat' } }))
    const raw = typeof judged === 'string' ? judged : (judged.finalAnswer || judged.answer || judged.content || '')
    const match = String(raw).replace(/```json|```/g, '').match(/\{[\s\S]*\}/)
    if (!match) throw new Error('AI 未返回判定结果')
    const result = JSON.parse(match[0])
    feedbackById.value = { ...feedbackById.value, [question.id]: { correct: Boolean(result.correct), score: Number(result.score || 0), message: result.message || question.analysis } }
  } catch (error) { ElMessage.error(error?.message || '答案判定失败') } finally { judging.value = false }
}
watch(() => route.params.id, async id => { if (id && (!activeSet.value || activeSet.value.id !== String(id))) await openSet(String(id)); if (!id && activeSet.value) activeSet.value = null })
onMounted(async () => { await loadDocuments(); if (route.params.id) await openSet(String(route.params.id)) })
</script>

<style scoped>
.quiz-page{min-height:100vh;padding:30px 34px 42px;background:#fbfaf8;color:#423c4b;font-family:"Microsoft YaHei",sans-serif}.quiz-header{max-width:1420px;margin:0 auto 22px;display:flex;justify-content:space-between;align-items:center;gap:20px}.back-link,.plain-back{border:0;background:transparent;padding:0;color:#817794;cursor:pointer;font-size:13px;display:inline-flex;align-items:center;gap:5px}.title-row{display:flex;align-items:center;gap:13px;margin-top:11px}.title-mark{width:46px;height:46px;border-radius:15px;display:grid;place-items:center;background:#a16f87;color:#fff;font-size:21px;box-shadow:0 8px 20px #a16f8755}.title-row h1{margin:0 0 5px;font-size:25px}.title-row p{margin:0;color:#948da0;font-size:13px}.quiz-layout{max-width:1420px;margin:auto;display:grid;grid-template-columns:260px minmax(0,1fr);gap:18px}.set-sidebar,.builder-card,.practice-card{background:#fff;border:1px solid #ebe5ea;border-radius:20px;box-shadow:0 8px 28px #7a6d8a0b}.set-sidebar{padding:15px;height:max-content}.side-heading{display:flex;justify-content:space-between;align-items:center;padding:3px 4px 13px}.side-heading span,.side-heading small{display:block}.side-heading span{font-size:14px;font-weight:700}.side-heading small{font-size:11px;color:#9e96a5;margin-top:3px}.saved-set{width:100%;display:flex;gap:9px;align-items:center;border:0;border-radius:12px;background:transparent;padding:10px 8px;text-align:left;cursor:pointer;color:#756d7d}.saved-set:hover,.saved-set.active{background:#f8f4f8}.saved-set>span:nth-child(2){min-width:0;flex:1}.saved-set strong,.saved-set small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.saved-set strong{font-size:12px;color:#574f61}.saved-set small{font-size:10px;color:#a198a7;margin-top:3px}.set-icon{display:grid;place-items:center;width:28px;height:28px;border-radius:9px;background:#f2e9ee;color:#a16f87}.empty-sets{padding:25px 8px;text-align:center;color:#a69eaa;font-size:11px;line-height:1.7}.builder-card,.practice-card{padding:28px}.eyebrow{font-size:10px;letter-spacing:1.5px;color:#a16f87;font-weight:700}.builder-heading h2,.practice-top h2{margin:7px 0;color:#51495b;font-size:23px}.builder-heading p,.practice-top p{font-size:12px;color:#8e8695;line-height:1.7;margin:0}.builder-toolbar{display:flex;gap:10px;margin-top:25px}.builder-toolbar .el-input{flex:1}.hidden-file-input{display:none}.source-tip{font-size:11px;color:#9b92a0;margin:12px 0}.source-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;max-height:380px;overflow:auto;padding:2px}.source-item{height:auto!important;margin:0!important;padding:12px;border:1px solid #eee8ef;border-radius:12px;display:flex!important;align-items:center}.source-name{display:block;max-width:230px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#5b5261;font-size:12px}.source-item small{display:block;color:#aaa2af;font-size:10px;margin-top:3px}.builder-footer{margin-top:18px;padding-top:15px;border-top:1px dashed #e7e0e7;display:flex;justify-content:space-between;align-items:center;font-size:12px;color:#847b8b}.practice-top{display:flex;align-items:flex-start;justify-content:space-between;gap:15px}.score-chip{padding:8px 12px;border-radius:99px;background:#edf4e2;color:#66805f;font-size:12px;white-space:nowrap}.type-summary{display:flex;gap:8px;flex-wrap:wrap;margin:22px 0 14px}.type-summary button{border:1px solid #ebe4ec;border-radius:10px;background:#faf9fa;color:#807787;padding:8px 10px;cursor:pointer;font-size:11px}.type-summary button strong{margin-left:7px}.type-summary button.active{background:#f3edf6;border-color:#aca0ce;color:#706287}.question-nav{display:flex;gap:7px;overflow:auto;padding-bottom:6px}.question-nav button{flex:none;width:30px;height:30px;border:1px solid #e9e2eb;border-radius:8px;background:#fff;color:#827889;font-size:11px;cursor:pointer}.question-nav button.active{background:#84799f;border-color:#84799f;color:#fff}.question-nav button.done:not(.active){background:#ebf4ed;border-color:#d3e6d7;color:#64836c}.question-card{margin-top:17px;padding:22px;border-radius:16px;background:#fcfbfc;border:1px solid #eee8ef}.question-meta{display:flex;justify-content:space-between;color:#988fa0;font-size:11px}.type-badge{background:#f1e9f1;color:#a16f87;border-radius:99px;padding:3px 8px}.question-card h3{font-size:16px;line-height:1.8;color:#4e4656;margin:16px 0}.option-list{display:flex;flex-direction:column;gap:9px;width:100%}.option-list :deep(.el-radio),.option-list :deep(.el-checkbox){height:auto!important;margin:0!important;padding:10px 12px;white-space:normal;line-height:1.6}.answer-result{margin-top:14px;padding:12px;border-radius:11px;background:#fbefec;color:#9d6059;font-size:12px;line-height:1.65}.answer-result.correct{background:#eaf4ec;color:#577a60}.answer-result strong{margin-right:9px}.answer-result p{margin:5px 0 0}.question-actions{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:16px;font-size:11px;color:#928998}.no-questions{padding:80px 0;text-align:center;color:#a39aa7;font-size:13px}@media(max-width:850px){.quiz-page{padding:20px 14px}.quiz-header{align-items:flex-start}.quiz-layout{grid-template-columns:1fr}.set-sidebar{order:2}.source-list{grid-template-columns:1fr}.builder-toolbar,.practice-top{flex-direction:column}.builder-toolbar .el-button{width:100%}.question-actions{align-items:flex-start;flex-direction:column}}
.option-list :deep(.el-radio),.option-list :deep(.el-checkbox){justify-content:flex-start!important;text-align:left!important}.option-list :deep(.el-radio__label),.option-list :deep(.el-checkbox__label){flex:1;text-align:left!important;white-space:normal}</style>