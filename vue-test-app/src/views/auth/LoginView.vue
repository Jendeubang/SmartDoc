<template>
  <div class="auth-bg">
    <el-card class="auth-card" shadow="hover">
      <div class="logo">SmartDoc 文档智能处理平台</div>
      <h2 class="title">欢迎回来</h2>
      <el-form :model="form" label-position="top">
        <el-form-item label="昵称">
          <el-input v-model="form.username" placeholder="请输入注册时设置的昵称" size="large" prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" size="large" prefix-icon="Lock" @keyup.enter="handleLogin" />
        </el-form-item>
        <div class="forgot-row"><button type="button" class="text-button" @click="forgotVisible = true">忘记密码？</button></div>
        <el-button type="primary" class="submit-btn" size="large" @click="handleLogin" :loading="loading">登录</el-button>
        <div class="footer-text"><router-link to="/register">没有账号？免费注册</router-link></div>
      </el-form>
    </el-card>

    <el-dialog v-model="forgotVisible" title="找回密码" width="min(420px, calc(100vw - 32px))">
      <p class="dialog-tip">输入注册昵称或邮箱，重置链接会发送到注册邮箱。</p>
      <el-input v-model="forgotAccount" placeholder="昵称或邮箱" @keyup.enter="sendResetMail" />
      <template #footer>
        <el-button @click="forgotVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="sendResetMail">发送重置邮件</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../../api/user'
import { STORAGE_KEYS } from '../../constants'

const router = useRouter()
const loading = ref(false)
const sending = ref(false)
const forgotVisible = ref(false)
const forgotAccount = ref('')
const form = ref({ username: '', password: '' })

const handleLogin = async () => {
  if (!form.value.username || !form.value.password) return ElMessage.warning('请填写完整的昵称和密码')
  loading.value = true
  try {
    const res = await userApi.login(form.value)
    const loginData = res.data || {}
    const accessToken = loginData.accessToken || loginData.token
    const user = loginData.user || {}
    if (!accessToken) throw new Error('登录成功但后端未返回 accessToken')
    localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
    if (loginData.refreshToken) localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, loginData.refreshToken)
    if (user.id) localStorage.setItem('userId', user.id)
    localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(user))
    if (user.username) localStorage.setItem('userName', user.username)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}

const sendResetMail = async () => {
  if (!forgotAccount.value.trim()) return ElMessage.warning('请输入昵称或邮箱')
  sending.value = true
  try {
    await userApi.forgotPassword(forgotAccount.value.trim())
    forgotVisible.value = false
    ElMessage.success('如果账号存在，重置邮件已发送到注册邮箱')
  } finally {
    sending.value = false
  }
}
</script>

<style scoped>
.auth-bg { height: 100vh; display: flex; justify-content: center; align-items: center; background: #f0f2f5; }
.auth-card { width: min(460px, calc(100vw - 32px)); padding: 20px 30px; border-radius: 12px; }
.logo { text-align: center; white-space: nowrap; font-size: 24px; font-weight: 700; color: #8879a4; margin-bottom: 10px; }
.title { text-align: center; font-size: 18px; color: #333; margin-bottom: 25px; }
.forgot-row { display: flex; justify-content: flex-end; margin-top: -8px; }
.text-button { border: 0; background: transparent; color: #8879a4; cursor: pointer; padding: 0; }
.submit-btn { width: 100%; margin-top: 14px; font-weight: 700; }
.footer-text { text-align: center; margin-top: 15px; font-size: 14px; }
.footer-text a { color: #8879a4; text-decoration: none; }
.dialog-tip { color: #7c7485; margin-top: 0; }
@media (max-width: 440px) { .logo { white-space: normal; font-size: 21px; } }
</style>
