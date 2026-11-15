<template>
  <UserCard @select="onSelect" />
</template>

<script setup lang="ts">
import UserCard from './components/UserCard.vue';
import { formatName } from './utils';

function onSelect(name: string) {
  formatName(name);
}
</script>

<style scoped>
.app { color: red; }
</style>
