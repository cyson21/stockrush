// 모바일 앱 사전 점검/스모크/증빙 수집을 자동화하는 스크립트입니다.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const appRoot = dirname(dirname(scriptPath));
const repoRoot = dirname(dirname(appRoot));
const defaultOutputDir = join(repoRoot, '.ai-runs', '2026-05-16-mobile-android-uiautomator');
const terminalSagaStatuses = new Set(['COMPLETED', 'FAILED', 'PAYMENT_DELAYED']);
let resolvedAdbCommand = '';

function argValue(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index === -1 || index + 1 >= process.argv.length) {
    return fallback;
  }
  return process.argv[index + 1];
}

function hasFlag(name) {
  return process.argv.includes(name);
}

function decodeXml(value) {
  return value
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&amp;', '&');
}

function toBoolean(value) {
  return value === 'true';
}

export function parseNodes(xml) {
  const nodes = [];
  for (const nodeMatch of xml.matchAll(/<node\b([^>]*)\/?>/g)) {
    const attrs = {};
    const attrText = nodeMatch[1] || '';
    for (const attrMatch of attrText.matchAll(/([\w:-]+)="([^"]*)"/g)) {
      attrs[attrMatch[1]] = decodeXml(attrMatch[2]);
    }

    nodes.push({
      text: attrs.text || '',
      resourceId: attrs['resource-id'] || '',
      className: attrs.class || '',
      packageName: attrs.package || '',
      contentDesc: attrs['content-desc'] || '',
      enabled: toBoolean(attrs.enabled),
      clickable: toBoolean(attrs.clickable),
      focused: toBoolean(attrs.focused),
      scrollable: toBoolean(attrs.scrollable),
      password: toBoolean(attrs.password),
      bounds: attrs.bounds || '',
    });
  }
  return nodes;
}

export function boundsCenter(bounds) {
  const match = /^\[(\d+),(\d+)]\[(\d+),(\d+)]$/.exec(bounds);
  if (!match) {
    throw new Error(`invalid bounds: ${bounds}`);
  }
  const [, left, top, right, bottom] = match.map(Number);
  return {
    x: Math.round((left + right) / 2),
    y: Math.round((top + bottom) / 2),
  };
}

export function findNode(xml, criteria) {
  const nodes = parseNodes(xml);
  return nodes.find((node) => nodeMatches(node, criteria)) || null;
}

export function nodeMatches(node, criteria) {
  if (criteria.resourceId && node.resourceId !== criteria.resourceId) {
    return false;
  }
  if (criteria.resourceIdPrefix && !node.resourceId.startsWith(criteria.resourceIdPrefix)) {
    return false;
  }
  if (criteria.text && node.text !== criteria.text) {
    return false;
  }
  if (criteria.textIncludes && !node.text.includes(criteria.textIncludes)) {
    return false;
  }
  if (criteria.contentDesc && node.contentDesc !== criteria.contentDesc) {
    return false;
  }
  if (criteria.clickable !== undefined && node.clickable !== criteria.clickable) {
    return false;
  }
  if (criteria.enabled !== undefined && node.enabled !== criteria.enabled) {
    return false;
  }
  return true;
}

export function redactValue(value, sensitive) {
  if (!sensitive) {
    return value;
  }
  return value ? '<redacted>' : '';
}

export function buildSelectorPlan({
  productCode,
  skuId,
  quantity = '1',
  couponCode = '',
  paymentMethod = 'CARD',
  expectedSagaStatus = 'COMPLETED',
} = {}) {
  const productSelector = productCode
    ? { resourceId: `mobile-product-card-${productCode}` }
    : { resourceIdPrefix: 'mobile-product-card-', clickable: true };
  const skuSelector = skuId
    ? { resourceId: `mobile-stock-row-${skuId}` }
    : { resourceIdPrefix: 'mobile-stock-row-', clickable: true };

  const plan = [
    { id: 'screen-loaded', action: 'wait', selector: { resourceId: 'mobile-product-list-screen' } },
    { id: 'auth-ready', action: 'wait', selector: { resourceId: 'mobile-auth-status-label', text: '로그인됨' } },
    { id: 'select-product', action: 'tap', selector: productSelector },
    { id: 'select-sku', action: 'tap', selector: skuSelector },
    { id: 'set-quantity', action: 'replaceText', selector: { resourceId: 'mobile-checkout-quantity-input' }, value: String(quantity) },
  ];

  if (couponCode) {
    plan.push(
      { id: 'set-coupon', action: 'replaceText', selector: { resourceId: 'mobile-checkout-coupon-input' }, value: couponCode },
      { id: 'apply-coupon', action: 'tap', selector: { resourceId: 'mobile-checkout-apply-coupon-button', clickable: true } },
    );
  }

  plan.push(
    { id: 'select-payment', action: 'tap', selector: { resourceId: `mobile-payment-method-${paymentMethod}`, clickable: true } },
    { id: 'submit-order', action: 'tap', selector: { resourceId: 'mobile-checkout-submit-order-button', clickable: true } },
    { id: 'created-order', action: 'wait', selector: { resourceId: 'mobile-created-order-summary' } },
    {
      id: 'terminal-saga-status',
      action: 'wait',
      selector: { resourceId: 'mobile-created-order-saga-status' },
      predicate: (node) => expectedSagaStatus === 'ANY_TERMINAL'
        ? terminalSagaStatuses.has(node.text)
        : node.text === expectedSagaStatus,
    },
  );

  return plan;
}

export function androidAdbCandidates(env = process.env, platform = process.platform, homeDir = homedir()) {
  const executable = platform === 'win32' ? 'adb.exe' : 'adb';
  const sdkRoots = [
    env.ANDROID_HOME,
    env.ANDROID_SDK_ROOT,
    join(homeDir, 'Library', 'Android', 'sdk'),
    join(homeDir, 'Android', 'Sdk'),
    env.LOCALAPPDATA ? join(env.LOCALAPPDATA, 'Android', 'Sdk') : '',
  ].filter(Boolean);
  return Array.from(new Set([
    'adb',
    ...sdkRoots.map((sdkRoot) => join(sdkRoot, 'platform-tools', executable)),
  ]));
}

function resolveAdbCommand() {
  if (resolvedAdbCommand) {
    return resolvedAdbCommand;
  }

  for (const candidate of androidAdbCandidates()) {
    if (candidate !== 'adb' && !existsSync(candidate)) {
      continue;
    }

    const result = spawnSync(candidate, ['version'], {
      cwd: appRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 5000,
    });
    if ((result.status ?? 1) === 0) {
      resolvedAdbCommand = candidate;
      return candidate;
    }
  }

  throw new Error('adb is not available. Install Android platform-tools or set ANDROID_HOME/ANDROID_SDK_ROOT.');
}

function runAdb(args, options = {}) {
  const result = spawnSync(resolveAdbCommand(), args, {
    cwd: appRoot,
    encoding: options.encoding || 'utf8',
    input: options.input,
    maxBuffer: 10 * 1024 * 1024,
    stdio: options.stdio || ['ignore', 'pipe', 'pipe'],
    timeout: options.timeoutMs || 15000,
  });

  if (result.error && !options.allowFailure) {
    throw result.error;
  }
  if ((result.status ?? 1) !== 0 && !options.allowFailure) {
    throw new Error(`${resolveAdbCommand()} ${args.join(' ')} failed: ${(result.stderr || result.stdout || '').trim()}`);
  }

  return {
    status: result.status ?? 1,
    stdout: result.stdout || '',
    stderr: result.stderr || result.error?.message || '',
  };
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function ensureDir(path) {
  mkdirSync(path, { recursive: true });
}

function shellEscapeText(value) {
  return value.replace(/%/g, '%25').replace(/\s/g, '%s').replace(/'/g, "\\'");
}

function dumpXml(outputDir, label) {
  runAdb(['shell', 'uiautomator', 'dump', '/sdcard/stockrush-window.xml'], { timeoutMs: 10000 });
  const result = runAdb(['exec-out', 'cat', '/sdcard/stockrush-window.xml'], { timeoutMs: 10000 });
  const xml = result.stdout;
  writeFileSync(join(outputDir, `${label}.xml`), xml, 'utf8');
  return xml;
}

function captureScreenshot(outputDir, label) {
  const result = spawnSync(resolveAdbCommand(), ['exec-out', 'screencap', '-p'], {
    cwd: appRoot,
    encoding: 'buffer',
    maxBuffer: 15 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 10000,
  });
  if ((result.status ?? 1) === 0 && result.stdout?.length) {
    writeFileSync(join(outputDir, `${label}.png`), result.stdout);
  }
}

function currentPackage(xml) {
  return parseNodes(xml).find((node) => node.packageName)?.packageName || '';
}

function scrollDown() {
  runAdb(['shell', 'input', 'swipe', '540', '1900', '540', '650', '450'], { allowFailure: true, timeoutMs: 10000 });
}

function waitForNode(outputDir, step, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let attempts = 0;
  let lastXml = '';

  while (Date.now() < deadline) {
    attempts += 1;
    const xml = dumpXml(outputDir, `${step.id}-${String(attempts).padStart(2, '0')}`);
    lastXml = xml;
    const node = findNode(xml, step.selector);
    if (node && (!step.predicate || step.predicate(node))) {
      captureScreenshot(outputDir, `${step.id}-hit`);
      return { node, xml, attempts };
    }
    if (attempts % 3 === 0) {
      scrollDown();
    }
    sleep(1000);
  }

  const packageName = currentPackage(lastXml);
  throw new Error(`step ${step.id} timed out after ${timeoutMs}ms; currentPackage=${packageName || 'unknown'}`);
}

function tapNode(node) {
  const center = boundsCenter(node.bounds);
  runAdb(['shell', 'input', 'tap', String(center.x), String(center.y)]);
  return center;
}

function focusNodeByTab(outputDir, step, maxTabs = 28) {
  for (let tabIndex = 0; tabIndex <= maxTabs; tabIndex += 1) {
    const xml = dumpXml(outputDir, `${step.id}-focus-${String(tabIndex).padStart(2, '0')}`);
    const focusedNode = parseNodes(xml).find((node) => node.focused && nodeMatches(node, step.selector));
    if (focusedNode) {
      captureScreenshot(outputDir, `${step.id}-focus-hit`);
      return focusedNode;
    }
    runAdb(['shell', 'input', 'keyevent', 'KEYCODE_TAB'], { allowFailure: true });
    sleep(180);
  }
  return null;
}

function activateNode(outputDir, step, node) {
  const focusedNode = focusNodeByTab(outputDir, step);
  if (focusedNode) {
    runAdb(['shell', 'input', 'keyevent', 'KEYCODE_ENTER']);
    return boundsCenter(focusedNode.bounds);
  }
  return tapNode(node);
}

function replaceNodeText(node, value) {
  const center = tapNode(node);
  runAdb(['shell', 'input', 'keyevent', 'KEYCODE_MOVE_END'], { allowFailure: true });
  for (let index = 0; index < 16; index += 1) {
    runAdb(['shell', 'input', 'keyevent', 'KEYCODE_DEL'], { allowFailure: true });
  }
  if (value) {
    runAdb(['shell', 'input', 'text', shellEscapeText(value)]);
  }
  return center;
}

function runPlan(options) {
  const outputDir = resolve(appRoot, options.outputDir);
  ensureDir(outputDir);
  const plan = buildSelectorPlan(options);
  const events = [];

  for (const step of plan) {
    const startedAt = new Date().toISOString();
    const { node, attempts } = waitForNode(outputDir, step, options.timeoutMs);
    let tap;

    if (step.action === 'tap') {
      tap = activateNode(outputDir, step, node);
      sleep(options.stepDelayMs);
    } else if (step.action === 'replaceText') {
      tap = replaceNodeText(node, step.value);
      sleep(options.stepDelayMs);
    }

    captureScreenshot(outputDir, `${step.id}-after`);
    events.push({
      id: step.id,
      action: step.action,
      selector: step.selector,
      text: node.text,
      resourceId: node.resourceId,
      contentDesc: node.contentDesc,
      bounds: node.bounds,
      tap,
      attempts,
      value: step.value ? redactValue(step.value, step.sensitive) : undefined,
      startedAt,
      finishedAt: new Date().toISOString(),
    });
  }

  return {
    generatedAt: new Date().toISOString(),
    outputDir,
    options: {
      productCode: options.productCode || '<first product>',
      skuId: options.skuId || '<first sku>',
      quantity: options.quantity,
      couponCode: redactValue(options.couponCode, false),
      paymentMethod: options.paymentMethod,
      expectedSagaStatus: options.expectedSagaStatus,
    },
    events,
  };
}

function markdownReport(report) {
  const lines = [
    '# Android UIAutomator Mobile Smoke',
    '',
    `- generatedAt: ${report.generatedAt}`,
    `- outputDir: ${report.outputDir}`,
    `- productCode: ${report.options.productCode}`,
    `- skuId: ${report.options.skuId}`,
    `- quantity: ${report.options.quantity}`,
    `- couponCode: ${report.options.couponCode || '(none)'}`,
    `- paymentMethod: ${report.options.paymentMethod}`,
    `- expectedSagaStatus: ${report.options.expectedSagaStatus}`,
    '',
    '## Steps',
    '',
    '| Step | Action | Selector | Text | Attempts | Tap |',
    '|---|---|---|---|---:|---|',
  ];

  for (const event of report.events) {
    const selector = event.selector.resourceId || `${event.selector.resourceIdPrefix || ''}${event.selector.text || ''}`;
    const tap = event.tap ? `${event.tap.x},${event.tap.y}` : '';
    lines.push(`| ${event.id} | ${event.action} | \`${selector}\` | ${event.text || ''} | ${event.attempts} | ${tap} |`);
  }

  return `${lines.join('\n')}\n`;
}

function writeReport(report) {
  writeFileSync(join(report.outputDir, 'report.json'), JSON.stringify(report, null, 2), 'utf8');
  writeFileSync(join(report.outputDir, 'report.md'), markdownReport(report), 'utf8');
}

function readOptions() {
  return {
    outputDir: argValue('--output-dir', defaultOutputDir),
    productCode: argValue('--product-code', ''),
    skuId: argValue('--sku-id', ''),
    quantity: argValue('--quantity', '1'),
    couponCode: argValue('--coupon-code', ''),
    paymentMethod: argValue('--payment-method', 'CARD'),
    expectedSagaStatus: argValue('--expected-saga-status', 'COMPLETED'),
    timeoutMs: Number(argValue('--timeout-ms', '45000')),
    stepDelayMs: Number(argValue('--step-delay-ms', '1200')),
  };
}

function printHelp() {
  console.log(`Android UIAutomator protected order smoke

Usage:
  node scripts/android-uiautomator-smoke.mjs [options]

Options:
  --output-dir <path>             Evidence directory
  --product-code <code>           Product to tap; defaults to first product card
  --sku-id <id>                   SKU to tap; defaults to first SKU row
  --quantity <number>             Quantity; default 1
  --coupon-code <code>            Optional coupon code
  --payment-method <method>       CARD, FAIL_CARD, or DELAY_CARD; default CARD
  --expected-saga-status <status> COMPLETED, FAILED, PAYMENT_DELAYED, or ANY_TERMINAL
  --timeout-ms <number>           Per-step wait timeout; default 45000
`);
}

export function main() {
  if (hasFlag('--help')) {
    printHelp();
    return;
  }

  const options = readOptions();
  const result = spawnSync(resolveAdbCommand(), ['get-state'], {
    cwd: appRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 5000,
  });
  if ((result.status ?? 1) !== 0 || !result.stdout.includes('device')) {
    throw new Error('adb device is not ready. Start an Android emulator and run mobile smoke preflight first.');
  }

  const report = runPlan(options);
  writeReport(report);
  console.log(`Android UIAutomator smoke evidence written to ${report.outputDir}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    const outputDir = resolve(appRoot, argValue('--output-dir', defaultOutputDir));
    ensureDir(outputDir);
    const message = error instanceof Error ? error.stack || error.message : String(error);
    writeFileSync(join(outputDir, 'failure.txt'), message, 'utf8');
    console.error(message);
    process.exitCode = 1;
  }
}
