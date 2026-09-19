<template>
  <div class="profile-page">
    <main class="profile-shell">
      <button class="back-button" type="button" @click="router.push('/dashboard')">
        <el-icon><ArrowLeft /></el-icon> 返回工作台
      </button>

      <section class="profile-card" v-loading="loading">
        <header class="profile-header">
          <p class="eyebrow">ACCOUNT SETTINGS</p>
          <div class="header-title-row"><h1>个人信息</h1><el-button plain @click="router.push('/ai-settings')">模型配置</el-button></div>
          <p>管理头像和个性签名。注册时设置的昵称将作为你的登录名称，无法修改。</p>
        </header>

        <div class="profile-content">
          <aside class="avatar-panel">
            <el-avatar :size="108" :src="avatarPreview || undefined" class="profile-avatar">
              {{ profileForm.username.charAt(0).toUpperCase() }}
            </el-avatar>
            <el-button plain class="avatar-button" :loading="uploading" @click="avatarInputRef?.click()">更换头像</el-button>
            <input ref="avatarInputRef" type="file" accept="image/jpeg,image/png,image/webp,image/gif" hidden @change="handleAvatarChange" />
            <p>支持 JPG、PNG、WEBP、GIF<br />大小不超过 2MB</p>
          </aside>

          <el-form class="profile-form" label-position="top" @submit.prevent>
            <el-form-item label="登录昵称">
              <el-input :model-value="profileForm.username" disabled />
              <div class="field-hint">此昵称在注册时确定，可用于登录，暂不支持修改。</div>
            </el-form-item>
            <el-form-item label="个性签名">
              <el-input v-model="profileForm.signature" type="textarea" :rows="4" maxlength="160" show-word-limit placeholder="介绍一下自己吧" />
            </el-form-item>
            <el-form-item label="用户 ID">
              <el-input :model-value="String(currentUserId || '')" disabled />
            </el-form-item>
            <div class="profile-actions">
              <el-button @click="router.push('/dashboard')">取消</el-button>
              <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
            </div>
          </el-form>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { userApi } from '../../api/user'
import { fileApi } from '../../api/file'
import { STORAGE_KEYS } from '../../constants'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const avatarInputRef = ref(null)
const avatarPreview = ref('')
let blobPreviewUrl = ''

const currentUserId = computed(() => {
  try {
    const cachedUser = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER_INFO) || '{}')
    return cachedUser.id || cachedUser.userId || localStorage.getItem('userId') || ''
  } catch {
    return localStorage.getItem('userId') || ''
  }
})
const profileForm = ref({ username: '', signature: '', avatarFileId: '' })
const PROFILE_UPDATED_KEY = 'smartdoc_profile_updated_at'

const notifyProfileUpdated = (profile = {}) => {
  const detail = {
    type: 'profile-updated',
    avatarFileId: profile.avatarFileId || '',
    username: profile.username || '',
    updatedAt: Date.now()
  }
  localStorage.setItem(PROFILE_UPDATED_KEY, JSON.stringify(detail))
  window.dispatchEvent(new CustomEvent('smartdoc-profile-updated', { detail }))
  if (typeof BroadcastChannel !== 'undefined') {
    const channel = new BroadcastChannel('smartdoc-profile-sync')
    channel.postMessage(detail)
    channel.close()
  }
}

const disposeBlobPreview = () => {
  if (blobPreviewUrl) {
    URL.revokeObjectURL(blobPreviewUrl)
    blobPreviewUrl = ''
  }
}

const fetchAvatar = async (fileId) => {
  if (!fileId) {
    disposeBlobPreview()
    avatarPreview.value = ''
    return
  }
  try {
    // 头像是附加资源：短暂限流或文件缺失时不应影响个人信息页本身。
    const blob = await fileApi.download(fileId, { suppressGlobalError: true, timeout: 15000 })
    disposeBlobPreview()
    blobPreviewUrl = URL.createObjectURL(blob)
    avatarPreview.value = blobPreviewUrl
  } catch {
    avatarPreview.value = ''
  }
}

const loadProfile = async () => {
  if (!currentUserId.value) {
    ElMessage.error('未读取到登录用户，请重新登录')
    router.replace('/login')
    return
  }
  loading.value = true
  try {
    const response = await userApi.getUserInfo(currentUserId.value)
    const user = response?.data || {}
    profileForm.value = {
      username: user.username || '',
      signature: user.signature || '',
      avatarFileId: user.avatarFileId || ''
    }
    await fetchAvatar(user.avatarFileId)
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '加载个人信息失败')
  } finally {
    loading.value = false
  }
}

const handleAvatarChange = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像大小不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    const response = await fileApi.upload(file)
    const fileId = response?.data?.fileId || response?.data?.id
    if (!fileId) throw new Error('头像上传后未返回文件标识')
    profileForm.value.avatarFileId = fileId
    disposeBlobPreview()
    blobPreviewUrl = URL.createObjectURL(file)
    avatarPreview.value = blobPreviewUrl
    ElMessage.success('头像已上传，点击保存修改后生效')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '头像上传失败')
  } finally {
    uploading.value = false
  }
}

const saveProfile = async () => {
  saving.value = true
  try {
    const response = await userApi.updateProfile({
      signature: profileForm.value.signature.trim(),
      avatarFileId: profileForm.value.avatarFileId || ''
    })
    const updated = response?.data || {}
    let cached = {}
    try { cached = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER_INFO) || '{}') } catch { cached = {} }
    localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify({ ...cached, ...updated }))
    profileForm.value.signature = updated.signature || profileForm.value.signature.trim()
    profileForm.value.avatarFileId = updated.avatarFileId || profileForm.value.avatarFileId
    notifyProfileUpdated({
      ...updated,
      username: updated.username || profileForm.value.username,
      avatarFileId: profileForm.value.avatarFileId
    })
    ElMessage.success('个人信息已保存')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '保存个人信息失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadProfile)
onBeforeUnmount(disposeBlobPreview)
</script>

<style scoped>
.profile-page { min-height: 100vh; padding: 42px 24px; background: radial-gradient(circle at 90% 0%, #ece7f3 0, transparent 31%), #f8f7f5; color: #423c4a; }
.profile-shell { width: min(900px, 100%); margin: 0 auto; }
.back-button { display: inline-flex; align-items: center; gap: 7px; border: 0; padding: 8px 3px; color: #74698e; background: transparent; cursor: pointer; font-size: 14px; }
.back-button:hover { color: #5e5278; }
.profile-card { margin-top: 17px; overflow: hidden; border: 1px solid #e6e0e8; border-radius: 22px; background: rgba(255, 255, 255, .94); box-shadow: 0 18px 45px rgba(78, 67, 92, .10); }
.profile-header { padding: 38px 48px 30px; background: linear-gradient(118deg, #f1eef5, #fbfaf9 68%); border-bottom: 1px solid #ebe6ed; }
.eyebrow { margin: 0 0 10px; color: #9789ae; font-size: 11px; font-weight: 700; letter-spacing: .16em; }
.profile-header h1 { margin: 0; color: #50465a; font-size: 28px; letter-spacing: .02em; }
.header-title-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header-title-row :deep(.el-button) { border-color: #d8d0e0; color: #74698e; background: rgba(255,255,255,.72); }
.profile-header > p:last-child { margin: 10px 0 0; color: #8a8191; font-size: 14px; }
.profile-content { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 42px; padding: 38px 48px 44px; }
.avatar-panel { display: flex; flex-direction: column; align-items: center; padding-top: 7px; text-align: center; }
.profile-avatar { color: #fff; background: linear-gradient(135deg, #8775a4, #b5a9ca); box-shadow: 0 10px 25px rgba(111, 92, 138, .23); font-size: 36px; font-weight: 700; }
.avatar-button { margin-top: 18px; border-color: #d8d0e0; color: #74698e; background: #fcfbfc; }
.avatar-panel p { margin: 12px 0 0; color: #aaa2ae; font-size: 12px; line-height: 1.65; }
.profile-form :deep(.el-form-item) { margin-bottom: 22px; }
.profile-form :deep(.el-form-item__label) { color: #5d5466; font-weight: 650; line-height: 1; padding-bottom: 10px; }
.profile-form :deep(.el-input__wrapper), .profile-form :deep(.el-textarea__inner) { box-shadow: 0 0 0 1px #e5dfe8 inset; }
.profile-form :deep(.el-input__wrapper.is-focus), .profile-form :deep(.el-textarea__inner:focus) { box-shadow: 0 0 0 1px #9180aa inset; }
.field-hint { margin-top: 7px; color: #a198a7; font-size: 12px; }
.profile-actions { display: flex; justify-content: flex-end; gap: 12px; padding-top: 3px; }
.profile-actions :deep(.el-button--primary) { border-color: #7f6b9c; background: #7f6b9c; }
.profile-actions :deep(.el-button--primary:hover) { border-color: #6f5d8a; background: #6f5d8a; }
@media (max-width: 660px) { .profile-page { padding: 24px 14px; } .profile-header, .profile-content { padding-left: 24px; padding-right: 24px; } .profile-content { grid-template-columns: 1fr; gap: 25px; } .avatar-panel { padding-top: 0; } }
</style>
