import { ref, nextTick } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

const USER_COLORS = [
  '#3370ff', '#f56c6c', '#67c23a', '#e6a23c', '#909399',
  '#b37feb', '#36cfc9', '#ff85c0', '#597ef7', '#ffc53d'
]

let colorIndex = 0
const userColorMap = {}

function getUserColor(userId) {
  if (!userColorMap[userId]) {
    userColorMap[userId] = USER_COLORS[colorIndex % USER_COLORS.length]
    colorIndex++
  }
  return userColorMap[userId]
}

export function useCollaboration(documentId, userId, userName) {
  const onlineUsers = ref([])
  const remoteCursors = ref([])
  const isConnected = ref(false)
  const isCollaborating = ref(false)
  const connectionError = ref('')
  const lastSyncTime = ref(0)

  let stompClient = null
  let subscriptions = []
  let paperElement = null
  let savedEventHandler = null

  function cleanupSubscriptions() {
    subscriptions.forEach(s => { try { s.unsubscribe() } catch (e) {} })
    subscriptions = []
  }

  // 通过 REST API 拉取最新文档内容
  async function fetchLatestContent() {
    try {
      const token = localStorage.getItem('accessToken') || ''
      const headers = { 'Authorization': token ? `Bearer ${token}` : '' }
      const res = await fetch(`/api/documents/${documentId}`, { headers })
      const data = await res.json()
      const docData = data.data || data
      return docData.content || ''
    } catch (e) {
      console.warn('[协作] 拉取内容失败', e)
      return null
    }
  }

  // 更新编辑器内容
  async function updateEditorContent() {
    if (!paperElement) return
    const content = await fetchLatestContent()
    if (content && content !== paperElement.innerHTML) {
      paperElement.innerHTML = content
      paperElement.dispatchEvent(new Event('input', { bubbles: true }))
      lastSyncTime.value = Date.now()
    }
  }

  // 处理收到的消息
  function handleMessage(message) {
    try {
      const event = JSON.parse(message.body)
      switch (event.type) {
        case 'EDIT':
          // 编辑通知 → 拉取最新全文
          updateEditorContent()
          break
        case 'JOIN':
          if (event.joinMessage && event.joinMessage.userId !== userId) {
            const u = event.joinMessage
            if (!onlineUsers.value.find(x => x.id === u.userId)) {
              onlineUsers.value.push({ id: u.userId, name: u.userName || u.userId, color: getUserColor(u.userId) })
            }
          }
          break
        case 'LEAVE':
          if (event.joinMessage) {
            onlineUsers.value = onlineUsers.value.filter(u => u.id !== event.joinMessage.userId)
            remoteCursors.value = remoteCursors.value.filter(c => c.userId !== event.joinMessage.userId)
          }
          break
        case 'CURSOR':
          if (event.cursorPosition && event.cursorPosition.userId !== userId) {
            const c = event.cursorPosition
            const idx = remoteCursors.value.findIndex(r => r.userId === c.userId)
            const cursor = { userId: c.userId, from: c.from, to: c.to, color: getUserColor(c.userId) }
            if (idx >= 0) remoteCursors.value[idx] = cursor
            else remoteCursors.value.push(cursor)
          }
          break
      }
    } catch (e) {
      console.warn('[协作] 消息解析失败', e)
    }
  }

  // 订阅文档主题
  function subscribeToDocument(docId) {
    const sub = stompClient.subscribe(`/topic/doc/${docId}`, handleMessage)
    subscriptions.push(sub)
  }

  // 发送 STOMP 消息
  function send(destination, body) {
    if (stompClient && stompClient.connected) {
      stompClient.publish({ destination, body: JSON.stringify(body) })
    }
  }

  function connect() {
    return new Promise((resolve, reject) => {
      stompClient = new Client({
        webSocketFactory: () => new SockJS('/ws/collaborate'),
        connectHeaders: {
          Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}`
        },
        reconnectDelay: 5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        onConnect: () => {
          isConnected.value = true
          connectionError.value = ''
          subscribeToDocument(documentId)
          send('/app/collaborate/join', { documentId, userId, userName })
          resolve()
        },
        onDisconnect: () => {
          isConnected.value = false
          isCollaborating.value = false
        },
        onStompError: (frame) => {
          connectionError.value = frame.headers?.message || 'STOMP 错误'
          reject(new Error(connectionError.value))
        }
      })
      stompClient.activate()
      setTimeout(() => { if (!isConnected.value) reject(new Error('连接超时')) }, 10000)
    })
  }

  function disconnect() {
    if (isCollaborating.value) {
      send('/app/collaborate/leave', { documentId, userId, userName })
    }
    cleanupSubscriptions()
    if (stompClient) {
      try { stompClient.deactivate() } catch (e) {}
      stompClient = null
    }
    isConnected.value = false
    isCollaborating.value = false
    onlineUsers.value = []
    remoteCursors.value = []
  }

  async function startCollaboration() {
    if (isCollaborating.value) return
    paperElement = document.querySelector('.paper')
    if (!paperElement) {
      connectionError.value = '未找到编辑器'
      return
    }
    try {
      await connect()
      await updateEditorContent() // 首次加入拉取最新内容
      savedEventHandler = event => {
        if (String(event.detail?.documentId) !== String(documentId)) return
        send('/app/collaborate/edit', {
          documentId,
          type: 'INSERT',
          from: 0,
          to: 0,
          text: ''
        })
      }
      window.addEventListener('smartdoc-document-saved', savedEventHandler)
      isCollaborating.value = true
      lastSyncTime.value = Date.now()
    } catch (e) {
      connectionError.value = e.message || '启动失败'
      isConnected.value = false
      throw e
    }
  }

  function stopCollaboration() {
    if (savedEventHandler) {
      window.removeEventListener('smartdoc-document-saved', savedEventHandler)
      savedEventHandler = null
    }
    disconnect()
  }

  return {
    onlineUsers,
    remoteCursors,
    isConnected,
    isCollaborating,
    connectionError,
    lastSyncTime,
    startCollaboration,
    stopCollaboration,
    getUserColor
  }
}
