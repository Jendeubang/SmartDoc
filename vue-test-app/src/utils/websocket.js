/**
 * WebSocket 实时进度连接模块
 * 封装 SockJS + STOMP 客户端，自动重连
 */
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { STORAGE_KEYS } from '../constants'

let stompClient = null
let connected = false
const subscriptions = new Map()
const reconnectTimer = { id: null }
const WS_ENDPOINT = '/ws/agent'

function createClient() {
  stompClient = new Client({
    webSocketFactory: () => new SockJS(WS_ENDPOINT),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    connectHeaders: {
      Authorization: `Bearer ${localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN) || ''}`
    },
    onConnect: () => {
      connected = true
      console.log('[WS] 已连接')
    },
    onDisconnect: () => {
      connected = false
      console.log('[WS] 已断开')
    },
    onStompError: (frame) => {
      console.error('[WS] STOMP 错误', frame.headers?.message)
    }
  })
  stompClient.activate()
}

export function connectWebSocket() {
  if (stompClient && connected) return
  if (stompClient) {
    stompClient.deactivate()
    stompClient = null
  }
  createClient()
}

export function disconnectWebSocket() {
  if (reconnectTimer.id) {
    clearTimeout(reconnectTimer.id)
    reconnectTimer.id = null
  }
  subscriptions.clear()
  if (stompClient) {
    try { stompClient.deactivate() } catch (e) { /* ignore */ }
    stompClient = null
  }
  connected = false
}

export function subscribeToTopic(topic, callback) {
  if (!stompClient || !connected) {
    console.warn('[WS] 未连接，等待连接后订阅', topic)
    const retry = setTimeout(() => {
      subscribeToTopic(topic, callback)
    }, 1000)
    subscriptions.set(topic, { callback, retry })
    return () => clearTimeout(retry)
  }

  // 取消之前的订阅
  if (subscriptions.has(topic)) {
    const existing = subscriptions.get(topic)
    if (existing.subscription) {
      try { existing.subscription.unsubscribe() } catch (e) { /* ignore */ }
    }
    if (existing.retry) {
      clearTimeout(existing.retry)
    }
  }

  const subscription = stompClient.subscribe(topic, (message) => {
    try {
      const data = JSON.parse(message.body)
      callback(data)
    } catch (e) {
      callback(message.body)
    }
  })

  subscriptions.set(topic, { callback, subscription })
  console.log('[WS] 已订阅', topic)

  return () => {
    try { subscription.unsubscribe() } catch (e) { /* ignore */ }
    subscriptions.delete(topic)
  }
}

export function unsubscribeFromTopic(topic) {
  if (subscriptions.has(topic)) {
    const entry = subscriptions.get(topic)
    if (entry.subscription) {
      try { entry.subscription.unsubscribe() } catch (e) { /* ignore */ }
    }
    if (entry.retry) {
      clearTimeout(entry.retry)
    }
    subscriptions.delete(topic)
    console.log('[WS] 已取消订阅', topic)
  }
}

export function isWebSocketConnected() {
  return connected
}
