// Test transport only. Production extension execution remains disabled.
import {EventEmitter} from 'node:events';

export class CdpProbeClient extends EventEmitter {
  constructor(socket) {
    super();
    this.socket = socket;
    this.nextId = 0;
    this.pending = new Map();
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id !== undefined) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        clearTimeout(pending.timer);
        if (message.sessionId !== pending.sessionId) {
          pending.reject(new Error('Response session does not match the request'));
        } else if (message.error) {
          pending.reject(Object.assign(new Error(message.error.message), {code: message.error.code}));
        } else pending.resolve(message.result);
      } else this.emit('event', message);
    });
    socket.addEventListener('close', () => {
      for (const request of this.pending.values()) {
        clearTimeout(request.timer);
        request.reject(new Error('CDP connection closed'));
      }
      this.pending.clear();
    });
  }

  static async connect(url) {
    const socket = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { socket.close(); reject(new Error('CDP connect timeout')); }, 5000);
      socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, {once: true});
      socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('CDP connection failed')); }, {once: true});
    });
    return new CdpProbeClient(socket);
  }

  call(method, params = {}, sessionId, timeout = 5000) {
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out`));
      }, timeout);
      this.pending.set(id, {resolve, reject, timer, sessionId});
      this.socket.send(JSON.stringify({id, method, params, sessionId}));
    });
  }

  close() { this.socket.close(); }
}

export async function eventually(operation, timeout = 8000) {
  const deadline = Date.now() + timeout;
  let error;
  while (Date.now() < deadline) {
    try {
      const value = await operation();
      if (value) return value;
    } catch (caught) { error = caught; }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw error ?? new Error('Expected probe state did not arrive');
}
