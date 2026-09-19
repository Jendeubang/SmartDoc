<template>
  <div class="reset-page">
    <el-card class="reset-card">
      <h2>设置新密码</h2>
      <p>密码至少 10 位，并包含大小写字母、数字和特殊字符。</p>
      <el-input v-model="password" type="password" show-password placeholder="新密码" size="large" />
      <el-input v-model="confirmPassword" type="password" show-password placeholder="确认新密码" size="large" />
      <el-button type="primary" size="large" :loading="loading" @click="submit">重置密码</el-button>
      <router-link to="/login">返回登录</router-link>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../../api/user'

const route = useRoute()
const router = useRouter()
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)

const submit = async () => {
  if (!route.query.token) return ElMessage.error('重置链接缺少令牌')
  if (password.value !== confirmPassword.value) return ElMessage.warning('两次输入的密码不一致')
  loading.value = true
  try {
    await userApi.resetPassword(String(route.query.token), password.value)
    ElMessage.success('密码已重置，请重新登录')
    router.replace('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reset-page { min-height: 100vh; display: grid; place-items: center; background: #edf4e2; }
.reset-card { width: min(440px, calc(100vw - 32px)); border-radius: 18px; }
.reset-card :deep(.el-card__body) { display: grid; gap: 18px; padding: 34px; }
h2 { margin: 0; color: #554d68; } p { margin: 0; color: #7c7485; line-height: 1.7; }
a { text-align: center; color: #8879a4; text-decoration: none; }
</style>
