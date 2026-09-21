/**
 * @description Axios 全局请求封装工具
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { STORAGE_KEYS, RES_CODE } from '../constants'
import { requestErrorMessage as normalizedRequestErrorMessage } from './requestError'

const service = axios.create({
    baseURL: '/api',
    timeout: 300000
})

const AI_PATH_PREFIX = '/ai/'
const AIOPS_PATH_PREFIX = '/ai/aiops/'
let refreshPromise = null
let lastErrorNotice = { message: '', time: 0 }

function requestErrorMessage(error) {
  const status = error?.response?.status
  const serverMessage = error?.response?.data?.message || error?.response?.data?.msg
  if (serverMessage) return serverMessage
  if (!error?.response) return '网络连接失败，请检查网络或服务是否已启动'
  if (status === 400) return '请求内容有误，请检查填写信息'
  if (status === 403) return '没有权限执行此操作'
  if (status === 404) return '请求的内容不存在或已被移除'
  if (status === 409) return '当前数据已被其他成员更新，请刷新后重试'
  if (status === 413) return '文件过大，请压缩后重新上传'
  if (status === 429) return '操作过于频繁，请稍后再试'
  if (status >= 500) return '服务暂时不可用，请稍后重试'
  if (error?.code === 'ECONNABORTED') return '请求超时，请稍后重试'
  return '操作未完成，请稍后重试'
}

function showErrorOnce(message) {
  const now = Date.now()
  if (lastErrorNotice.message === message && now - lastErrorNotice.time < 2500) return
  lastErrorNotice = { message, time: now }
  ElMessage.error(message)
}

function getAccessToken() {
  return localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN)
}

function buildAuthHeaders() {
  const token = getAccessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function clearSession() {
  localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
}

async function refreshAccessToken() {
  const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
  if (!refreshToken) throw new Error('Missing refresh token')
  if (!refreshPromise) {
    refreshPromise = axios.post('/api/users/refresh', { refreshToken }, { timeout: 15000 })
      .then(response => {
        const payload = response.data?.data || response.data || {}
        if (!payload.accessToken || !payload.refreshToken) throw new Error('Invalid refresh response')
        localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, payload.accessToken)
        localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, payload.refreshToken)
        return payload.accessToken
      })
      .finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

function isAiRequest(url) {
  if (!url || !url.startsWith(AI_PATH_PREFIX) || url.startsWith(AIOPS_PATH_PREFIX)) return false

  // 状态查询与配置读取不是模型调用；轮询时继续为其上报两条指标会放大请求量，
  // 最终挤占真实 AI 任务和正常文件打开请求的限流配额。
  const telemetryExcludedPaths = [
    '/ai/async/jobs',
    '/ai/models',
    '/ai/provider-credentials',
    '/ai/agent/conversations'
  ]
  return !telemetryExcludedPaths.some(path => url.startsWith(path))
}

function reportAiMetrics(config, hasError) {
  if (!config || !config.url) return
  const url = config.url
  if (!isAiRequest(url)) return

  const name = url.replace(/^\/ai\//, '').replace(/\//g, '.')
  try {
    // 后台静默上报，使用未拦截的 fetch 避免死循环
    const base = axios.defaults.baseURL || '/api'
    fetch(base + '/ai/aiops/metrics/counter?name=' + encodeURIComponent('ai.' + name) + '&delta=1', {
      method: 'POST', headers: { 'Authorization': config.headers?.Authorization || '' }
    }).catch(() => {})

    if (config._startTime && !hasError) {
      const duration = Date.now() - config._startTime
      fetch(base + '/ai/aiops/metrics/timer?name=' + encodeURIComponent('ai.' + name) + '&duration=' + duration, {
        method: 'POST', headers: { 'Authorization': config.headers?.Authorization || '' }
      }).catch(() => {})
    }
  } catch (e) { /* 静默 */ }
}

service.interceptors.request.use(
    config => {
        config._startTime = Date.now()
        const token = getAccessToken()
        if (token) {
            config.headers['Authorization'] = 'Bearer ' + token
        }
        return config
    },
    error => Promise.reject(error)
)

service.interceptors.response.use(
    response => {
        if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
            reportAiMetrics(response.config, false)
            return response.data
        }

        const res = response.data

        // 如果后端返回的是字符串（比如 HTML 源码），而不是 JSON 对象，直接放行！
        if (typeof res === 'string') {
            return res
        }

        // 兼容后端直接返回数组而没有 code 的情况（如 getFileList）
        if (res && res.code === undefined) {
            reportAiMetrics(response.config, false)
            return { data: res, code: 200, message: 'success' }
        }

        if (res.code === 200 || res.code === 0) {
            reportAiMetrics(response.config, false)
            return res
        } else if (res.code === RES_CODE.UNAUTHORIZED || res.code === 604 || res.code === 605) {
            reportAiMetrics(response.config, true)
            showErrorOnce('登录已过期，请重新登录')
            clearSession()
            router.push('/login')
            return Promise.reject(new Error(res.message || 'Unauthorized'))
        } else {
            reportAiMetrics(response.config, true)
            if (!response.config?.suppressGlobalError) showErrorOnce(res.message || '操作失败')
            return Promise.reject(new Error(res.message || 'Error'))
        }
    },
    error => {
        // 路由切换、页面卸载或组件销毁引起的主动取消不是服务器故障。
        if (axios.isCancel(error) || error.code === 'ERR_CANCELED') {
            return Promise.reject(error)
        }
        if (error.config) {
            reportAiMetrics(error.config, true)
        }
        if (error.response && error.response.status === 401 && !error.config?.skipAuthRefresh && !error.config?._retried) {
            const original = error.config
            original._retried = true
            return refreshAccessToken().then(token => {
                original.headers = original.headers || {}
                original.headers.Authorization = `Bearer ${token}`
                return service(original)
            }).catch(() => {
                clearSession()
                router.push('/login')
                return Promise.reject(error)
            })
        } else if (error.response && error.response.status === 401) {
            showErrorOnce('登录已过期，请重新登录')
            clearSession()
            router.push('/login')
        } else {
            if (!error.config?.suppressGlobalError) showErrorOnce(normalizedRequestErrorMessage(error))
        }
        error.userMessage = normalizedRequestErrorMessage(error)
        return Promise.reject(error)
    }
)

export default service
