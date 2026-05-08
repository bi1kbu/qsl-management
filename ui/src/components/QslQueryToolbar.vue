<template>
  <div class="qsl-filter-panel">
    <div class="qsl-query-toolbar">
      <div class="qsl-query-toolbar__left">
        <slot name="left" />
      </div>
      <div class="qsl-query-toolbar__right">
        <slot name="right" />
      </div>
    </div>
    <slot />
  </div>
</template>
