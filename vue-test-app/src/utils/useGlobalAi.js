import { ref } from 'vue'

const HISTORY_PREFIX = 'smartdoc_global_ai_history_v1_'
const CONVERSATION_PREFIX = 'smartdoc_global_ai_conversation_v1_'
const LEGACY_CONVERSATION_KEY = 'global_conv_id'
const MAX_HISTORY_ITEMS = 120
const MAX_STORAGE_LENGTH = 2_500_000

const isAiPanelVisible = ref(false)
const chatHistory = ref([])
const isAiThinking = ref(false)
const currentConvId = ref(null)
let activeUserId = null

const getUserId = (userId) => String(userId || localStorage.getItem('userId') || 'guest')
const historyKey = (userId) => `${HISTORY_PREFIX}${getUserId(userId)}`
const conversationKey = (userId) => `${CONVERSATION_PREFIX}${getUserId(userId)}`

function saveHistory(userId = activeUserId) {
  const key = historyKey(userId)
  const messages = chatHistory.value.slice(-MAX_HISTORY_ITEMS).map(({ role, text, actionResult }) => ({ role, text, actionResult }))
  while (messages.length && JSON.stringify(messages).length > MAX_STORAGE_LENGTH) messages.shift()
  try { localStorage.setItem(key, JSON.stringify(messages)) } catch { /* 浏览器存储不可用时仍保留当前会话内历史 */ }
}

function initializeConversation(userId) {
  const normalizedUserId = getUserId(userId)
  if (activeUserId === normalizedUserId) return
  activeUserId = normalizedUserId
  try {
    const savedHistory = JSON.parse(localStorage.getItem(historyKey(normalizedUserId)) || '[]')
    chatHistory.value = Array.isArray(savedHistory) ? savedHistory.filter(item => item && item.role && typeof item.text === 'string').slice(-MAX_HISTORY_ITEMS) : []
  } catch { chatHistory.value = [] }
  currentConvId.value = localStorage.getItem(conversationKey(normalizedUserId)) || null
  if (!currentConvId.value) {
    const legacyConversationId = localStorage.getItem(LEGACY_CONVERSATION_KEY)
    if (legacyConversationId) {
      currentConvId.value = legacyConversationId
      localStorage.setItem(conversationKey(normalizedUserId), legacyConversationId)
      localStorage.removeItem(LEGACY_CONVERSATION_KEY)
    }
  }
}

export function useGlobalAi() {
  const toggleAiPanel = () => { isAiPanelVisible.value = !isAiPanelVisible.value }

  const resetConversation = () => {
    const userId = getUserId(activeUserId)
    chatHistory.value = []
    currentConvId.value = null
    localStorage.removeItem(historyKey(userId))
    localStorage.removeItem(conversationKey(userId))
  }

  const saveConvId = (convId) => {
    currentConvId.value = convId
    localStorage.setItem(conversationKey(activeUserId), convId)
  }

  const appendMessage = (message) => {
    chatHistory.value.push(message)
    saveHistory()
  }

  const clearChat = () => {
    chatHistory.value = []
    saveHistory()
  }

  return { isAiPanelVisible, chatHistory, isAiThinking, currentConvId, toggleAiPanel, appendMessage, clearChat, resetConversation, saveConvId, initializeConversation }
}