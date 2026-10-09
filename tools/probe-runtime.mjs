#!/usr/bin/env node
// Spec A/B capability experiments. This does not install or enable an extension runtime.
import {execFileSync} from 'node:child_process';
import {createServer} from 'node:http';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes, createHash} from 'node:crypto';
import {parseArgs} from 'node:util';
import {CdpProbeClient, eventually} from './cdp-probe-client.mjs';

const {values: args} = parseArgs({options: {
  serial: {type: 'string'}, adb: {type: 'string', default: 'adb'},
  output: {type: 'string', default: 'runtime-probe.json'},
}});
if (!args.serial) throw new Error('Usage: probe-runtime.mjs --serial DEVICE [--adb PATH] [--output FILE]');
const adb = (...command) => execFileSync(args.adb, ['-s', args.serial, ...command], {encoding: 'utf8', timeout: 15000}).trim();
const fixture = resolve(dirname(fileURLToPath(import.meta.url)), '../tests/runtime-fixtures');
const nonce = randomBytes(8).toString('hex');
const worldA = `chromext-experiment-a-${nonce}`, worldB = `chromext-experiment-b-${nonce}`;
const binding = `__chromext_probe_${nonce}`;
const report = {
  started: new Date().toISOString(), checks: [], observations: {},
  scope: 'Dedicated synthetic tab and child frames only; no production extension runtime',
  gates: {A: 'partial', B: 'unverified', arbitraryExtensionExecution: 'disabled'},
};
function record(name, passed, evidence) {
  report.checks.push({name, passed, evidence: structuredClone(evidence)});
  console.log(`${passed ? 'PASS' : 'FAIL'} ${name}`);
}
let client, targetId, session, forwardPort, fixturePort;
const contexts = new Map(), sessions = new Set(), bindings = [], events = [], childSetups = [];
const extensionBase = 'chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/';
const sourceA = await readFile(resolve(fixture, 'extension-a/content.js'), 'utf8');
const sourceB = await readFile(resolve(fixture, 'extension-b/content.js'), 'utf8');
report.fixtures = Object.fromEntries(await Promise.all(['extension-a/manifest.json', 'extension-a/content.js',
  'extension-b/manifest.json', 'extension-b/content.js'].map(async path => [path,
  createHash('sha256').update(await readFile(resolve(fixture, path))).digest('hex')])));

function html(top) {
  return `<!doctype html><meta charset="utf-8"><title>ChromeXt synthetic runtime probe</title>
  <script>
    document.dispatchEvent(new Event('chromext-probe-first-script'));
    globalThis.__first = {
      a: document.documentElement.getAttribute('data-probe-a'),
      b: document.documentElement.getAttribute('data-probe-b'),
      secretA: typeof globalThis.__probeSecretA, secretB: typeof globalThis.__probeSecretB,
      binding: typeof globalThis[${JSON.stringify(binding)}]
    };
    globalThis.__probeMain = 'main-world';
    addEventListener('load', () => { globalThis.__loaded = true; });
    addEventListener('pageshow', e => { globalThis.__fromBFCache = e.persisted; });
  </script><p>Only this test document is inspected.</p>
  ${top ? `<iframe id="same" src="/${nonce}/frame?same"></iframe>
    <iframe id="cross" src="http://localhost:${fixturePort}/${nonce}/frame?cross"></iframe>` : ''}`;
}
const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  if (!url.pathname.startsWith(`/${nonce}/`)) { response.writeHead(404); response.end(); return; }
  if (url.pathname.endsWith('/redirect')) {
    response.writeHead(302, {Location: `/${nonce}/top?redirected`}); response.end(); return;
  }
  if (url.pathname.endsWith('/private')) {
    response.writeHead(200, {'Content-Type': 'text/plain'}); response.end('private cross-origin fixture'); return;
  }
  response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
  response.end(html(url.pathname.endsWith('/top')));
});

async function evaluate(expression, selected = session, context, extra = {}) {
  const result = await client.call('Runtime.evaluate', {
    expression, returnByValue: true, ...context && {uniqueContextId: context.uniqueId}, ...extra,
  }, selected);
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
  return result.result.value;
}

async function setup(selected) {
  sessions.add(selected);
  await client.call('Runtime.enable', {}, selected);
  await client.call('Page.enable', {}, selected);
  await client.call('Runtime.addBinding', {name: binding, executionContextName: worldA}, selected);
  await client.call('Page.addScriptToEvaluateOnNewDocument', {worldName: worldA,
    source: sourceA + `\n${binding}(JSON.stringify({claimedExtension:'b',probe:${JSON.stringify(nonce)}}));`}, selected);
  await client.call('Page.addScriptToEvaluateOnNewDocument', {worldName: worldB, source: sourceB}, selected);
  try {
    await client.call('Target.setAutoAttach', {autoAttach: true, waitForDebuggerOnStart: true, flatten: true,
      filter: [{type: 'iframe'}, {exclude: true}]}, selected);
  } catch (error) { report.observations.autoAttachError = error.message; }
}

async function loaded(url) {
  const navigation = await client.call('Page.navigate', {url}, session);
  if (navigation.errorText) throw new Error(navigation.errorText);
  await eventually(() => evaluate(`location.href === ${JSON.stringify(url)} && globalThis.__loaded === true`));
}

try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  fixturePort = server.address().port;
  adb('reverse', `tcp:${fixturePort}`, `tcp:${fixturePort}`);
  forwardPort = adb('forward', 'tcp:0', 'localabstract:chrome_devtools_remote');
  adb('shell', 'am', 'start', '-W', '-n', 'com.android.chrome/com.google.android.apps.chrome.Main');
  const version = await eventually(async () => {
    const response = await fetch(`http://127.0.0.1:${forwardPort}/json/version`, {signal: AbortSignal.timeout(2000)});
    return response.json();
  });
  report.browser = version;
  report.android = adb('shell', 'getprop', 'ro.build.version.release');
  client = await CdpProbeClient.connect(version.webSocketDebuggerUrl);
  report.observations.browserVersion = await client.call('Browser.getVersion');
  // Do not query /json/protocol here: on the tested Chrome 154 device that extra discovery
  // request was followed by a native SIGTRAP. Protocol schemas must be audited offline.
  ({targetId} = await client.call('Target.createTarget', {url: 'about:blank'}));
  ({sessionId: session} = await client.call('Target.attachToTarget', {targetId, flatten: true}));
  client.on('event', message => {
    if (!sessions.has(message.sessionId)) return;
    const {method, params, sessionId} = message;
    if (method === 'Runtime.executionContextCreated') {
      const context = {...params.context, sessionId};
      contexts.set(`${sessionId}:${context.id}`, context);
    } else if (method === 'Runtime.executionContextDestroyed') {
      contexts.delete(`${sessionId}:${params.executionContextId}`);
    } else if (method === 'Runtime.executionContextsCleared') {
      for (const [key, context] of contexts) if (context.sessionId === sessionId) contexts.delete(key);
    } else if (method === 'Runtime.bindingCalled' && params.name === binding) {
      const context = contexts.get(`${sessionId}:${params.executionContextId}`);
      bindings.push({name: context?.name, uniqueId: context?.uniqueId, frameId: context?.auxData?.frameId,
        sessionId, payload: params.payload});
    } else if (method === 'Target.attachedToTarget') {
      const child = params.sessionId;
      childSetups.push(setup(child)
        .then(() => client.call('Runtime.runIfWaitingForDebugger', {}, child))
        .catch(error => { report.observations.childSetupError = error.message; }));
    } else if (method === 'Target.detachedFromTarget') {
      sessions.delete(params.sessionId);
      for (const [key, context] of contexts) if (context.sessionId === params.sessionId) contexts.delete(key);
    } else if (method.startsWith('Page.')) events.push({method, sessionId, params});
  });
  await setup(session);
  const base = `http://127.0.0.1:${fixturePort}/${nonce}`;
  await loaded(`${base}/top`);
  await Promise.all(childSetups);
  record('request-response-and-continuous-events', contexts.size > 0 && events.length > 0,
    {contexts: contexts.size, pageEvents: events.length, sessions: sessions.size});
  const mainContexts = [...contexts.values()].filter(c => c.auxData?.isDefault &&
    [new URL(base).origin, `http://localhost:${fixturePort}`].includes(c.origin));
  const firstScripts = await Promise.all(mainContexts.map(async c => ({frame: c.auxData.frameId, origin: c.origin,
    first: await evaluate('globalThis.__first', c.sessionId, c)})));
  record('document-start-top-same-origin-cross-origin', firstScripts.length >= 3 && firstScripts.every(row =>
    row.first?.a === 'before-first-script' && row.first?.b === 'before-first-script'), firstScripts);
  const isolated = [...contexts.values()].filter(c => [worldA, worldB].includes(c.name));
  const isolation = await Promise.all(isolated.map(async c => ({world: c.name === worldA ? 'a' : 'b',
    state: await evaluate(`({a: typeof __probeSecretA,b: typeof __probeSecretB,main: typeof __probeMain,binding: typeof ${binding}})`, c.sessionId, c)})));
  record('isolated-worlds-and-main-world-boundary', firstScripts.every(row => row.first?.secretA === 'undefined' &&
    row.first?.secretB === 'undefined' && row.first?.binding === 'undefined') && isolation.length >= 6 && isolation.every(row =>
      row.state.main === 'undefined' && (row.world === 'a' ? row.state.a === 'string' && row.state.b === 'undefined' &&
      row.state.binding === 'function' : row.state.a === 'undefined' && row.state.b === 'string' && row.state.binding === 'undefined')), isolation);
  record('host-context-identifies-spoofed-binding-payload', bindings.length >= 3 && bindings.every(row =>
    row.name === worldA && row.uniqueId && row.frameId && JSON.parse(row.payload).claimedExtension === 'b'), bindings);
  report.observations.oopif = {observed: sessions.size > 1, sessions: sessions.size,
    requirement: 'If no OOPIF was created by this Chrome configuration, that case remains unverified'};

  const mainFrame = (await client.call('Page.getFrameTree', {}, session)).frameTree.frame.id;
  const identity = [...contexts.values()].find(c => c.name === worldA && c.auxData.frameId === mainFrame);
  await evaluate(`history.pushState({}, '', ${JSON.stringify(`${base}/spa`)}); true`);
  record('spa-preserves-document-identity', [...contexts.values()].some(c => c.uniqueId === identity.uniqueId) &&
    events.some(e => e.method === 'Page.navigatedWithinDocument'), {uniqueId: identity.uniqueId});

  const priorFrames = new Set([...contexts.values()].map(c => c.auxData?.frameId));
  await evaluate(`(() => { const frame = document.createElement('iframe'); frame.id = 'dynamic';
    frame.src = ${JSON.stringify(`${base}/frame?dynamic`)}; document.body.append(frame); return true; })()`);
  const dynamic = await eventually(() => [...contexts.values()].find(c => c.auxData?.isDefault &&
    c.auxData.frameId && !priorFrames.has(c.auxData.frameId)));
  const dynamicFirst = await eventually(() => evaluate('globalThis.__loaded && globalThis.__first', dynamic.sessionId, dynamic));
  await evaluate("document.getElementById('dynamic').remove(); true");
  await eventually(() => ![...contexts.values()].some(c => c.uniqueId === dynamic.uniqueId));
  let staleFrameRejected = false;
  try { await evaluate('true', dynamic.sessionId, dynamic); } catch (error) { staleFrameRejected = Number.isInteger(error.code); }
  record('dynamic-frame-injection-and-destruction', dynamicFirst.a === 'before-first-script' &&
    dynamicFirst.b === 'before-first-script' && staleFrameRejected, {dynamicFirst, staleFrameRejected});

  const held = client.call('Runtime.evaluate', {expression: 'new Promise(() => {})', awaitPromise: true,
    uniqueContextId: identity.uniqueId}, identity.sessionId, 10000).then(() => ({rejected: false}),
      error => ({rejected: Number.isInteger(error.code), error: error.message}));
  const redirect = await client.call('Page.navigate', {url: `${base}/redirect`}, session);
  await eventually(() => evaluate(`location.href === ${JSON.stringify(`${base}/top?redirected`)} && globalThis.__loaded`));
  const heldResult = await held;
  let staleNavigationRejected = false;
  try { await evaluate('true', identity.sessionId, identity); } catch (error) { staleNavigationRejected = Number.isInteger(error.code); }
  record('redirect-invalidates-context-and-pending-call', !redirect.errorText && staleNavigationRejected && heldResult.rejected,
    {staleNavigationRejected, heldResult});
  const beforeReload = [...contexts.values()].find(c => c.name === worldA && c.auxData.frameId === mainFrame)?.uniqueId;
  await client.call('Page.reload', {}, session);
  await eventually(() => [...contexts.values()].some(c => c.name === worldA && c.auxData.frameId === mainFrame && c.uniqueId !== beforeReload));
  const afterReload = await eventually(() => evaluate('globalThis.__loaded && globalThis.__first'));
  record('reload-reinjects-before-first-script', afterReload.a === 'before-first-script' && afterReload.b === 'before-first-script', afterReload);
  const history = await client.call('Page.getNavigationHistory', {}, session);
  const returnEntry = history.entries[history.currentIndex].id;
  await loaded(`${base}/away`);
  await client.call('Page.navigateToHistoryEntry', {entryId: returnEntry}, session);
  const restored = await eventually(() => evaluate(`location.href.includes('/top?redirected') && globalThis.__loaded &&
    ({first: globalThis.__first, persisted: globalThis.__fromBFCache})`));
  record('back-navigation-restores-fixture', restored.first.a === 'before-first-script' && restored.first.b === 'before-first-script', restored);
  report.observations.bfcache = {observed: restored.persisted === true,
    requirement: 'A normal back navigation does not prove BFCache restoration'};
  const cors = await evaluate(`fetch('http://localhost:${fixturePort}/${nonce}/private').then(() => false, () => true)`, session, null,
    {awaitPromise: true});
  record('web-origin-cors-remains-enforced', cors === true, {blocked: cors});

  const direct = await client.call('Page.navigate', {url: extensionBase + 'popup.html'}, session);
  let intercepted = 0;
  const fulfillments = [];
  client.on('event', message => {
    if (message.sessionId !== session || message.method !== 'Fetch.requestPaused') return;
    const {requestId, request} = message.params;
    if (!request.url.startsWith(extensionBase)) return;
    intercepted++;
    fulfillments.push((async () => {
      const name = new URL(request.url).pathname.slice(1);
      const allowed = ['popup.html', 'popup.js', 'worker.js', 'public.txt', 'private.txt'];
      const body = allowed.includes(name) ? await readFile(resolve(fixture, 'extension-a', name)) : Buffer.from('Not found');
      await client.call('Fetch.fulfillRequest', {requestId, responseCode: allowed.includes(name) ? 200 : 404,
        responseHeaders: [{name: 'Content-Type', value: name.endsWith('.html') ? 'text/html' : name.endsWith('.js') ? 'text/javascript' : 'text/plain'},
          {name: 'Content-Security-Policy', value: "default-src 'self'; script-src 'self'; object-src 'none'"}],
        body: body.toString('base64')}, session);
    })().catch(error => { report.observations.fulfillError = error.message; }));
  });
  await client.call('Fetch.enable', {patterns: [{urlPattern: extensionBase + '*', requestStage: 'Request'}]}, session);
  const interceptedNavigation = await client.call('Page.navigate', {url: extensionBase + 'popup.html?intercepted'}, session);
  await Promise.all(fulfillments);
  if (!interceptedNavigation.errorText) {
    try { await eventually(() => evaluate('globalThis.extensionPageLoaded === true'), 5000); }
    catch (error) { report.observations.extensionPageLoadError = error.message; }
  }
  let nativePage;
  try {
    nativePage = await evaluate('({origin: location.origin, loaded: globalThis.extensionPageLoaded === true, runtimeId: globalThis.chrome?.runtime?.id ?? null})');
  } catch (error) { nativePage = {loaded: false, error: error.message}; }
  report.observations.extensionOrigin = {direct, interceptedNavigation, intercepted, nativePage};
  report.gates.B = nativePage.loaded ? 'needs-extension-host-semantics' : 'blocked-on-native-extension-origin';
  report.observations.workerHost = {
    proven: false, reason: 'A browser-registered MV3 extension service worker host has not been established. A page worker is not a substitute.',
  };
  report.observations.remainingA = ['first navigation of arbitrary newly created tabs', 'browser background/freezer recovery',
    'renderer loss and Chrome process restart', ...(report.observations.oopif.observed ? [] : ['OOPIF']),
    ...(restored.persisted ? [] : ['BFCache restoration'])];
  console.log(`OBSERVED extension origin: ${report.gates.B}; arbitrary extension execution remains disabled`);
} catch (error) {
  report.fatal = {message: error.message, code: error.code, stack: error.stack};
  process.exitCode = 1;
  console.error(error.message);
} finally {
  if (targetId && client) {
    try { await client.call('Target.closeTarget', {targetId}); report.closedOwnTarget = true; }
    catch (error) { report.cleanupError = error.message; }
  }
  client?.close();
  server.closeAllConnections();
  server.close();
  if (fixturePort) { try { adb('reverse', '--remove', `tcp:${fixturePort}`); } catch {} }
  if (forwardPort) { try { adb('forward', '--remove', `tcp:${forwardPort}`); } catch {} }
  report.finished = new Date().toISOString();
  await mkdir(dirname(resolve(args.output)), {recursive: true});
  await writeFile(args.output, JSON.stringify(report, null, 2) + '\n');
  if (report.checks.some(check => !check.passed)) process.exitCode = 1;
  console.log(`Report: ${args.output}`);
}
