<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="title">
        <span class="logo"><svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M13.5 2 4 13.5h6.5L9 22l10-12h-6.6L13.5 2z"/></svg></span>
        <div>
          <h1>Storm 商城后台</h1>
          <p class="muted">管理后台</p>
        </div>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />
      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password autocomplete="current-password" size="large" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="full" @click="submit">登录</el-button>
      </el-form>
      <p class="muted foot"><a href="/">返回商城</a></p>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../auth'

const route = useRoute()
const router = useRouter()
const username = ref('admin')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await login(username.value, password.value)
    router.replace(route.query.redirect || '/')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px;
  background: radial-gradient(circle at 20% 10%, rgba(6,182,212,.15), transparent 40%), radial-gradient(circle at 80% 90%, rgba(139,92,246,.15), transparent 40%), var(--page-bg); }
.login-card { width: 100%; max-width: 400px; }
.title { display: flex; gap: 14px; align-items: center; margin-bottom: 22px; }
.title h1 { font-size: 20px; margin: 0; }
.title p { margin: 2px 0 0; }
.logo { width: 44px; height: 44px; border-radius: 12px; background: var(--grad); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 22px; }
.mb { margin-bottom: 16px; }
.full { width: 100%; }
.foot { text-align: center; font-size: 13px; margin: 18px 0 0; }
</style>
