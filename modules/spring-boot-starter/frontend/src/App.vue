<script setup lang="ts">
import ChatContainer from './components/ChatContainer.vue'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <div class="header-content">
        <h1 class="header-title">Atmosphere AI Console</h1>
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

.header-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
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
