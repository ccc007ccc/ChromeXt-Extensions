(() => {
  globalThis.__probeSecretB = 'private-b';
  document.addEventListener('chromext-probe-first-script', () => {
    document.documentElement.setAttribute('data-probe-b', 'before-first-script');
  });
})();
