<script setup lang="ts">
import { ref, onMounted } from 'vue'
import ChatContainer from './components/ChatContainer.vue'
import logoUrl from './assets/logo.svg'

const subtitle = ref('')

onMounted(async () => {
  try {
    const res = await fetch('/api/console/info')
    if (res.ok) {
      const data = await res.json()
      if (data.subtitle) subtitle.value = data.subtitle
    }
  } catch {
    // Console info not available — no subtitle
  }
})
</script>

<template>
  <div class="app">
    <header class="app-header">
      <div class="header-content">
        <img :src="logoUrl" alt="Atmosphere" class="header-logo" />
        <div class="header-titles">
          <h1 class="header-title">Atmosphere AI Console</h1>
          <span v-if="subtitle" class="header-subtitle">{{ subtitle }}</span>
        </div>
        <span class="header-badge">v4</span>
      </div>
    </header>
    <main class="app-main">
      <ChatContainer />
    </main>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
}

.app-header {
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-surface);
  padding: 0 1.5rem;
  height: 56px;
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.header-logo {
  width: 28px;
  height: 28px;
}

.header-titles {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.header-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  line-height: 1.2;
}

.header-subtitle {
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-weight: 400;
}

.header-badge {
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--accent-color);
  background: var(--accent-bg);
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  letter-spacing: 0.025em;
}

.app-main {
  flex: 1;
  overflow: hidden;
}
</style>
