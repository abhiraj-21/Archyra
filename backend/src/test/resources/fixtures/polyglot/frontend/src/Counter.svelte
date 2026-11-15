<script>
  import { count, reset } from './store';

  function increment() {
    count.value += 1;
    reset();
  }
</script>

<button on:click={increment}>+</button>
