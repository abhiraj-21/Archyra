<template><div @click="greet">card</div></template>

<script>
import helper from '../helper';

export default {
  name: 'UserCard',
  methods: {
    greet() {
      helper();
    },
  },
};
</script>
