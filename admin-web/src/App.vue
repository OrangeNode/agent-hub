<template>
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN" :theme-overrides="themeOverrides">
    <n-message-provider>
      <router-view />
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { dateZhCN, NConfigProvider, NMessageProvider, zhCN, type GlobalThemeOverrides } from 'naive-ui'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2563eb',
    primaryColorHover: '#1d4ed8',
    primaryColorPressed: '#1e40af',
    borderRadius: '8px'
  },
  Layout: {
    color: '#f5f7fb',
    siderColor: '#ffffff',
    headerColor: '#ffffff'
  }
}
</script>

<style>
:root {
  color: #1f2937;
  background: #f5f7fb;
  font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
}

* {
  box-sizing: border-box;
}

body {
  min-height: 100%;
  margin: 0;
  background: #f5f7fb;
}

html,
#app {
  min-height: 100%;
}

#app {
  min-height: 100vh;
}
</style>
