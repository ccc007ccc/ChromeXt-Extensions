self.addEventListener('message', event => {
  event.ports[0]?.postMessage({hasDOM: typeof document !== 'undefined'});
});
