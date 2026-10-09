import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

const assetUrl = new URL("../app/src/main/assets/scripts.js", import.meta.url);
const [bridgeAsset, openErudaAsset] = readFileSync(assetUrl, "utf8").split(
  "// Kotlin separator\n\n"
);
const token = 0.125;
const hiddenName = "bridgeTestPrivateNamespace";
const bridgeRef = `Symbol.${hiddenName}`;

// Local.kt supplies these placeholders. Manager.kt locks ordinary documents after
// loading the bridge; this harness models that native boundary and executes the
// shipped JavaScript, without copying its bridge implementation.
const initScript = bridgeAsset
  .replace("Symbol.ChromeXt", bridgeRef)
  .replace("ChromeXtUnlockKeyForInit", String(token));
const openErudaScript = openErudaAsset
  .replace("Symbol.ChromeXt", bridgeRef)
  .replace("ChromeXtUnlockKeyForEruda", String(token));

function openDocument(t, href = "https://example.test/page") {
  const messages = [];
  const timers = new Set();
  const document = new EventTarget();
  document.visibilityState = "hidden";
  const windowEvents = new EventTarget();
  const context = vm.createContext({
    document,
    location: new URL(href),
    EventTarget,
    CustomEvent,
    console: {
      // The native console hook receives this serialized action and payload.
      debug(message) {
        messages.push(JSON.parse(message));
      },
    },
    addEventListener: windowEvents.addEventListener.bind(windowEvents),
    removeEventListener: windowEvents.removeEventListener.bind(windowEvents),
    dispatchEvent: windowEvents.dispatchEvent.bind(windowEvents),
    setTimeout(callback, delay) {
      const timer = setTimeout(callback, delay);
      timers.add(timer);
      return timer;
    },
  });
  t.after(() => timers.forEach(clearTimeout));
  const run = (source) => vm.runInContext(source, context);
  run("window = globalThis;");
  run(`${initScript}\nSymbol.ChromeXt.lock(${token}, '${hiddenName}');`);
  return { context, document, messages, run };
}

const ordinaryUrls = [
  "https://example.test/page",
  "http://example.test/page",
  "https://example.test/sample.user.js",
  "https://raw.githubusercontent.com/example/scripts/main/sample.user.js",
  "https://jingmatrix.github.io/ChromeXt/",
  "https://jianyu-ma.onrender.com/ChromeXt/",
  "https://jianyu-ma.netlify.app/ChromeXt/",
  "file:///tmp/sample.js",
  "content://documents/sample.js",
  "about:blank",
];

for (const href of ordinaryUrls) {
  test(`ordinary document has no GM or installer bridge: ${href}`, (t) => {
    const { messages, run } = openDocument(t, href);

    assert.equal(run("typeof ChromeXt"), "undefined");
    assert.equal(run("typeof Symbol.ChromeXt"), "undefined");
    assert.equal(run("typeof Symbol.installScript"), "undefined");
    for (const name of ["GM", "GM_info", "GM_getValue", "GM_setValue", "installScript"]) {
      assert.equal(run(`typeof ${name}`), "undefined", name);
    }
    assert.equal(run(`${bridgeRef}.isLocked()`), true);
    assert.equal(run(`typeof ${bridgeRef}.scripts`), "undefined");
    assert.equal(run(`typeof ${bridgeRef}.commands`), "undefined");
    assert.deepEqual(messages, []);
  });
}

test("locked bridge rejects dispatch and incorrect unlock tokens", (t) => {
  const { messages, run } = openDocument(t);

  assert.throws(() => run(`${bridgeRef}.dispatch('loadEruda')`), /ChromeXt locked/);
  assert.throws(() => run(`${bridgeRef}.unlock(0.75)`), /Failed to unlock/);
  assert.throws(
    () => run(`${bridgeRef}.dispatch('loadEruda', {}, 0.75)`),
    /ChromeXt locked/
  );
  assert.throws(() => run(`${bridgeRef}.post('response', {})`), /ChromeXt locked/);
  assert.equal(run(`${bridgeRef}.isLocked()`), true);
  assert.deepEqual(messages, []);
});

test("Eruda bootstrap requests a native load through the locked document bridge", (t) => {
  const { messages, run } = openDocument(t);

  run(openErudaScript);

  assert.deepEqual(messages, [{ action: "loadEruda", key: token }]);
  assert.equal(run(`${bridgeRef}.isLocked()`), true);
  assert.equal(run("typeof Symbol.ChromeXt"), "undefined");
});

test("Eruda bootstrap toggles an already downloaded console without native reloads", (t) => {
  const { context, messages, run } = openDocument(t);
  const calls = [];
  context.eruda = {
    _isInit: false,
    init() {
      calls.push("init");
      this._isInit = true;
    },
    show() {
      calls.push("show");
    },
    hide() {
      calls.push("hide");
    },
    destroy() {
      calls.push("destroy");
      this._isInit = false;
    },
  };

  run(openErudaScript);
  run(openErudaScript);

  assert.deepEqual(calls, ["init", "show", "hide", "destroy"]);
  assert.deepEqual(messages, []);
  assert.equal(run(`${bridgeRef}.isLocked()`), true);
});

test("Eruda page customization sends filter and CSP changes to native storage", (t) => {
  const { messages, run } = openDocument(t);

  run(`${bridgeRef}.unlock(${token}, false).filters.push('.advertisement')`);
  run(`${bridgeRef}.unlock(${token}, false).cspRules.sync(["img-src 'self'"])`);

  assert.deepEqual(messages, [
    {
      action: "syncData",
      payload: {
        origin: "https://example.test",
        name: "filters",
        data: [".advertisement"],
      },
      key: token,
    },
    {
      action: "syncData",
      payload: {
        origin: "https://example.test",
        name: "cspRules",
        data: ["img-src 'self'"],
      },
      key: token,
    },
  ]);
  assert.equal(run(`${bridgeRef}.isLocked()`), true);
});

test("native responses reach listeners sharing an authorized bridge", (t) => {
  const { messages, run } = openDocument(t);
  run(`
    globalThis.responses = [];
    ${bridgeRef}.unlock(${token}).addEventListener('response', event => {
      responses.push(event.detail);
    });
    ${bridgeRef}.unlock(${token}).post('response', { ok: true });
  `);

  assert.deepEqual(JSON.parse(run("JSON.stringify(responses)")), [{ ok: true }]);
  assert.deepEqual(messages, []);
  assert.equal(run(`${bridgeRef}.isLocked()`), true);
});

test("visible documents still notify the native tab tracker while the bridge is locked", (t) => {
  const { document, messages, run } = openDocument(t);

  document.dispatchEvent(new Event("visibilitychange"));
  assert.deepEqual(messages, []);
  document.visibilityState = "visible";
  document.dispatchEvent(new Event("visibilitychange"));

  assert.deepEqual(messages, [
    { action: "focus", payload: { requestFocus: false }, key: token },
  ]);
  assert.equal(run(`${bridgeRef}.isLocked()`), true);
});
