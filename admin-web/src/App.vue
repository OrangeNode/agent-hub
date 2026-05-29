<template>
  <n-config-provider>
    <n-message-provider>
      <router-view />
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { NConfigProvider, NMessageProvider } from 'naive-ui'
</script>

<style>
:root {
  color: #1f2937;
  background: #f3f6fb;
  font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: #f3f6fb;
}

#app {
  min-height: 100vh;
}
</style>
