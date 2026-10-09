"use strict";

if (typeof Symbol.ChromeXt == "undefined") {
  const initKey = ChromeXtUnlockKeyForInit;
  // Used to lock and unlock ChromeXt;

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible")
      ChromeXt.dispatch("focus", { requestFocus: false }, initKey);
  });
  // update current active tab

  const props = {
    Array: Object.getOwnPropertyNames(Array.prototype),
    ChromeXt: ["cspRules", "filters"],
    EventTarget: ["addEventListener", "dispatchEvent", "removeEventListener"],
    global: Object.keys(window),
    // Drop user-defined props in the global context
  };

  props.global.push(...props.EventTarget);

  const backup = {
    // Store some variables to avoid being hooked
    Error: Error,
    Event: CustomEvent,
    stringify: JSON.stringify.bind(JSON),
    setTimeout: setTimeout.bind(window),
  };

  const SyncMethods = ["fill", "pop", "push", "splice"];
  class SyncArray extends Array {
    #freeze;
    #ChromeXt = null; // Used to validate the sync method
    #name;
    #sync;

    constructor(name, sync = true, freeze = false) {
      super();
      this.#name = name;
      this.#sync = sync;
      this.#freeze = freeze;

      const toVerify = ["fill", "push"];
      const hook = (args, method) => {
        if (toVerify.includes(method) && this.#freeze) {
          let n = 0;
          if (method == "push") {
            n = args.length;
          } else if (method == "fill") {
            n = 1;
          }
          for (let i = 0; i < n; i++) {
            if (!Object.isFrozen(args[i]))
              throw new backup.Error(`Element ${args[i]} is not frozen`);
          }
        }
        const result = super[method](...args);
        ChromeXt.isLocked() && this.sync();
        return result;
      };

      this.proxy = (target, prop) => {
        // Getter for methods of super
        const value = target[prop];
        if (SyncMethods.includes(prop) && typeof value == "function") {
          return (...args) => hook(args, prop);
        } else {
          return value;
        }
      };
    }

    sync(data = this) {
      const LocalChromeXt = this.#ChromeXt || ChromeXt;
      LocalChromeXt.isLocked(true);
      this.#ChromeXt = null; // Must be re-validate each time
      if (this.#sync && typeof this.#name == "string") {
        const payload = {
          origin: window.location.origin,
          name: this.#name,
        };
        if (typeof data == "object" && Array.isArray(data)) {
          if (this.#freeze) data = data.filter((it) => Object.isFrozen(it));
          data = [...new Set(data)];
          if (data.length > 0) payload.data = data;
        }
        LocalChromeXt.dispatch("syncData", payload);
      }
    }

    /** @param {ChromeXtTarget} target */
    set ChromeXt(target) {
      this.#ChromeXt = target;
      backup.setTimeout(() => (this.#ChromeXt = null));
    }
  }

  let secure = Symbol("secure"); // Secure states of ChromeXt context

  class ChromeXtTarget {
    #debug;
    #locked; // Whether ChromeXt is available
    #security; // State of ChromeXt context
    #target;

    #store = {}; // SyncArrays with names in props.ChromeXt

    constructor(security, debug, target) {
      if (typeof debug == "function" && target instanceof EventTarget) {
        this.#debug = debug;
        this.#target = target;
      } else {
        this.#target = new EventTarget();
        this.#debug = console.debug.bind(console);
      }

      this.#check(security);

      props.EventTarget.forEach((m) => {
        const method = this.#target[m].bind(this.#target);
        this[m] = (...args) => {
          if (!this.isLocked(true)) return method(...args);
        };
      });

      if (secure.description == "secure" && this.#security == secure) {
        props.ChromeXt.forEach((p) => {
          const v = new SyncArray(p);
          this.#store[p] = v;
          this[p] = new Proxy(v, { get: v.proxy });
          delete v.proxy;
        });
      }
    }

    get globalKeys() {
      return props.global;
    }

    set security(value) {
      if (value !== secure) throw new backup.Error("Invalid bridge security state");
      this.#security = value;
    }

    #check(security) {
      // Block access to the bridge constructor from outside its closure.
      this.security = security;
    }

    dispatch(action, payload, key) {
      this.isLocked(key != initKey);
      if (this.#security !== secure)
        throw new backup.Error("Invalid bridge security state");
      // Kotlin anchor
      this.#debug(backup.stringify({ action, payload, key: initKey }));
    }
    isLocked(throwError = false) {
      const locked = this.#locked === true && secure.description == "verified";
      if (throwError && locked) throw new backup.Error("ChromeXt locked");
      return locked;
    }
    lock(token, name) {
      if (secure.description == "verified")
        throw backup.Error("ChromeXt was already locked once before");
      if (!this.isLocked() && name.length > 16 && token === initKey) {
        this.#locked = true;
        secure = Symbol("verified"); // Context is verified by the provided token
        delete Symbol.ChromeXt;
        Symbol = new Proxy(Symbol, {
          get(_target, prop) {
            if (prop == name) {
              return ChromeXt;
            } else {
              return Reflect.get(...arguments);
            }
          },
        });
        if (typeof userDefinedChromeXt != "undefined") {
          Symbol.ChromeXt = userDefinedChromeXt;
        }
        this.security = secure;
      }
    }
    post(event, detail) {
      if (!this.isLocked(true))
        this.dispatchEvent(new backup.Event(event, { detail }));
    }
    unlock(token, apiOnly = true) {
      if (!this.isLocked()) {
        if (!["content://", "file://"].includes(location.origin))
          throw backup.Error("ChromeXt is not locked");
        return this;
      }
      if (token == initKey) {
        const UnLocked = new ChromeXtTarget(
          this.#security,
          this.#debug,
          this.#target
        );
        if (!apiOnly) {
          // Allow to use SyncMethods
          props.ChromeXt.forEach((k) => {
            const array = this.#store[k];
            UnLocked[k] = new Proxy(this[k], {
              get(target, prop) {
                let value = target[prop];
                if (prop == "sync" || SyncMethods.includes(prop)) {
                  array.ChromeXt = UnLocked; // Unlock sync methods of SyncArray
                  if (prop == "sync") value = value.bind(array);
                }
                return value;
              },
            });
          });
        }
        return UnLocked;
      } else {
        throw new backup.Error("Failed to unlock ChromeXtTarget");
      }
    }
  }

  Object.freeze(ChromeXtTarget.prototype);
  Object.freeze(SyncArray.prototype);
  const ChromeXt = new ChromeXtTarget(secure);
  const userDefinedChromeXt = Symbol.ChromeXt;
  Object.freeze(ChromeXt);
  Symbol.ChromeXt = ChromeXt;
} else {
  throw Error("ChromeXt is already defined, cancel initialization");
}
// Kotlin separator

try {
  if (eruda._isInit) {
    eruda.hide();
    eruda.destroy();
  } else {
    eruda.init();
    eruda.show();
  }
} catch (e) {
  if (typeof define == "function") define.amd = false;
  Symbol.ChromeXt.unlock(ChromeXtUnlockKeyForEruda).dispatch("loadEruda");
}
// Kotlin separator

if (Symbol.ChromeXt.cspRules.length > 0) {
  Symbol.ChromeXt.cspRules.forEach((rule) => {
    if (rule.length == 0) return;
    // Skip empty cspRules
    const meta = document.createElement("meta");
    meta.setAttribute("http-equiv", "Content-Security-Policy");
    meta.setAttribute("content", rule);
    try {
      document.head.append(meta);
    } catch {
      setTimeout(() => {
        document.head.append(meta);
      }, 0);
    }
  });
}
// Kotlin separator

if (Symbol.ChromeXt.filters.length > 0) {
  const filter = Symbol.ChromeXt.filters.join(", ");
  let addFilterStyle = (css) => {
    const style = document.createElement("style");
    style.textContent = css;
    if (document.head) {
      document.head.appendChild(style);
    } else {
      setTimeout(() => document.head.appendChild(style));
    }
  };
  addFilterStyle(filter + " {display: none !important;}");
  window.addEventListener("load", () => {
    document.querySelectorAll(filter).forEach((node) => {
      node.hidden = true;
      node.style.display = "none";
    });
  });
}
