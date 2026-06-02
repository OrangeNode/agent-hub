<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <router-view />
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { NConfigProvider, NMessageProvider } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2563eb',
    primaryColorHover: '#1d4ed8',
    primaryColorPressed: '#1e40af',
    primaryColorSuppl: '#2563eb',
    borderRadius: '12px',
    fontFamily:
      '"Aptos", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
  },
  Button: {
    borderRadiusMedium: '10px',
    borderRadiusLarge: '12px',
    fontWeight: '600'
  },
  Input: {
    borderRadius: '14px',
    borderColorFocus: '#2563eb',
    boxShadowFocus: '0 0 0 2px rgba(37, 99, 235, 0.16)'
  }
}
</script>

<style>
html,
body,
#app {
  min-height: 100%;
  margin: 0;
}

body {
  font-family: "Aptos", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  background: #ffffff;
  text-rendering: geometricPrecision;
}

* {
  box-sizing: border-box;
}
</style>
