(() => {
  globalThis.__probeSecretA = 'private-a';
  document.addEventListener('chromext-probe-first-script', () => {
    document.documentElement.setAttribute('data-probe-a', 'before-first-script');
  });
})();
