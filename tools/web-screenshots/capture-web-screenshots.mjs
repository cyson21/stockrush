#!/usr/bin/env node
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const ROOT_DIR = new URL('../..', import.meta.url).pathname.replace(/\/$/, '');
const SCREENSHOT_DIR = `${ROOT_DIR}/docs/assets/screenshots`;
const CHROME_PATHS = [
  process.env.CHROME_BIN,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
].filter(Boolean);

const customerUrl = process.env.CUSTOMER_APP_URL ?? 'http://localhost:15173/';
const adminUrl = process.env.ADMIN_APP_URL ?? 'http://localhost:15174/';
const keycloakTokenUrl =
  process.env.KEYCLOAK_TOKEN_URL ??
  'http://localhost:28088/realms/stockrush/protocol/openid-connect/token';
const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:18080';
const customerUser = process.env.KEYCLOAK_CUSTOMER_USERNAME ?? 'customer.demo@stockrush.local';
const customerPassword = process.env.KEYCLOAK_CUSTOMER_PASSWORD ?? 'demo-customer-pass';
const adminUser = process.env.KEYCLOAK_ADMIN_USERNAME ?? 'admin.demo@stockrush.local';
const adminPassword = process.env.KEYCLOAK_ADMIN_PASSWORD ?? 'demo-admin-pass';
const smokeClientId = process.env.KEYCLOAK_SMOKE_CLIENT_ID ?? 'stockrush-demo-smoke';

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function findChrome() {
  for (const path of CHROME_PATHS) {
    if (!path) {
      continue;
    }
    try {
      const stat = await import('node:fs/promises').then((fs) => fs.stat(path));
      if (stat.isFile()) {
        return path;
      }
    } catch {
      // try next candidate
    }
  }
  throw new Error('Chrome executable not found. Set CHROME_BIN to a Chromium-compatible browser.');
}

async function fetchJson(url, init) {
  const response = await fetch(url, init);
  if (!response.ok) {
    throw new Error(`${url} failed with ${response.status}`);
  }
  return response.json();
}

async function issueToken(username, password) {
  const body = new URLSearchParams({
    client_id: smokeClientId,
    grant_type: 'password',
    username,
    password,
  });
  const token = await fetchJson(keycloakTokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!token.access_token) {
    throw new Error(`No access token returned for ${username}`);
  }
  return token.access_token;
}

async function findProductWithStock() {
  const products = await fetchJson(`${gatewayUrl}/api/products?status=ON_SALE`);
  for (const product of products.data ?? []) {
    const stocks = await fetchJson(`${gatewayUrl}/api/stocks?productCode=${encodeURIComponent(product.productCode)}`);
    const stock = (stocks.data ?? []).find((item) => item.availableQuantity > 0);
    if (stock) {
      return { product, stock };
    }
  }
  throw new Error('No product with available stock found.');
}

class CdpClient {
  constructor(ws) {
    this.ws = ws;
    this.seq = 1;
    this.pending = new Map();
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (!message.id) {
        return;
      }
      const callback = this.pending.get(message.id);
      if (!callback) {
        return;
      }
      this.pending.delete(message.id);
      if (message.error) {
        callback.reject(new Error(message.error.message));
      } else {
        callback.resolve(message.result);
      }
    };
  }

  send(method, params = {}) {
    const id = this.seq++;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
  }

  close() {
    this.ws.close();
  }
}

async function waitForChrome(port) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      return await fetchJson(`http://127.0.0.1:${port}/json/version`);
    } catch {
      await delay(250);
    }
  }
  throw new Error('Chrome remote debugging endpoint did not start.');
}

async function newPage(port) {
  await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' }).catch(async () => {
    await fetch(`http://127.0.0.1:${port}/json/new`);
  });
  const pages = await fetchJson(`http://127.0.0.1:${port}/json/list`);
  const page = pages.find((item) => item.type === 'page');
  if (!page?.webSocketDebuggerUrl) {
    throw new Error('No debuggable page target found.');
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });
  const cdp = new CdpClient(ws);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  return cdp;
}

async function navigate(cdp, url, viewport) {
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: 1,
    mobile: viewport.mobile ?? false,
  });
  await cdp.send('Page.navigate', { url });
  await delay(1200);
}

async function evaluate(cdp, expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.text ?? 'Runtime evaluation failed.');
  }
  return result.result.value;
}

async function waitForText(cdp, text) {
  const escaped = JSON.stringify(text);
  for (let attempt = 0; attempt < 80; attempt += 1) {
    const found = await evaluate(cdp, `document.body?.innerText.includes(${escaped})`);
    if (found) {
      return;
    }
    await delay(250);
  }
  throw new Error(`Text not found: ${text}`);
}

async function captureFullPage(cdp, filePath, viewport) {
  const metrics = await evaluate(
    cdp,
    `(() => {
      const body = document.body;
      const doc = document.documentElement;
      return {
        width: Math.max(doc.scrollWidth, body.scrollWidth, window.innerWidth),
        height: Math.max(doc.scrollHeight, body.scrollHeight, window.innerHeight),
      };
    })()`,
  );
  const width = viewport.width;
  const height = Math.max(viewport.height, metrics.height);

  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: viewport.mobile ?? false,
  });
  await delay(350);

  const screenshot = await cdp.send('Page.captureScreenshot', {
    format: 'png',
    fromSurface: true,
    captureBeyondViewport: true,
    clip: { x: 0, y: 0, width, height, scale: 1 },
  });
  await writeFile(filePath, Buffer.from(screenshot.data, 'base64'));
  return { width, height };
}

async function setupCustomerPage(cdp, token, productCode, viewport) {
  const escapedToken = JSON.stringify(token);
  await navigate(cdp, customerUrl, viewport);
  await evaluate(
    cdp,
    `sessionStorage.setItem('stockrush.customer-web.auth.session', JSON.stringify({
      accessToken: ${escapedToken},
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3600000
    }))`,
  );
  await navigate(cdp, customerUrl, viewport);
  await waitForText(cdp, '인증 상태: 인증됨');
  await evaluate(
    cdp,
    `(() => {
      const code = ${JSON.stringify(productCode)};
      const setNativeValue = (element, value) => {
        const setter = Object.getOwnPropertyDescriptor(element.constructor.prototype, 'value')?.set;
        setter.call(element, value);
        element.dispatchEvent(new Event('input', { bubbles: true }));
        element.dispatchEvent(new Event('change', { bubbles: true }));
      };
      const search = Array.from(document.querySelectorAll('input')).find((input) => input.placeholder === '상품명 또는 코드 검색');
      setNativeValue(search, code.slice(0, 21));
    })()`,
  );
  await delay(1000);
  await evaluate(
    cdp,
    `Array.from(document.querySelectorAll('button')).find((button) => button.textContent.includes(${JSON.stringify(productCode)})).click()`,
  );
  await waitForText(cdp, '선택됨');
  await evaluate(
    cdp,
    `(() => {
      const setNativeValue = (element, value) => {
        const setter = Object.getOwnPropertyDescriptor(element.constructor.prototype, 'value')?.set;
        setter.call(element, value);
        element.dispatchEvent(new Event('input', { bubbles: true }));
        element.dispatchEvent(new Event('change', { bubbles: true }));
      };
      const member = Array.from(document.querySelectorAll('input')).find((input) => input.placeholder === 'member-1');
      setNativeValue(member, 'portfolio-member');
    })()`,
  );
  await delay(500);
}

async function setupAdminPage(cdp, token) {
  const escapedToken = JSON.stringify(token);
  await navigate(cdp, adminUrl, { width: 1440, height: 1200 });
  await evaluate(
    cdp,
    `localStorage.setItem('stockrush-admin-access-token', ${escapedToken});
     localStorage.setItem('stockrush-admin-access-token-expires-at', String(Date.now() + 3600000));`,
  );
  await navigate(cdp, adminUrl, { width: 1440, height: 1200 });
  await waitForText(cdp, 'authenticated');
}

async function clickTab(cdp, label) {
  await evaluate(
    cdp,
    `Array.from(document.querySelectorAll('[role="tab"]')).find((tab) => tab.textContent.trim() === ${JSON.stringify(label)}).click()`,
  );
  await delay(1200);
}

async function main() {
  await mkdir(SCREENSHOT_DIR, { recursive: true });

  const chromePath = await findChrome();
  const port = 9222 + Math.floor(Math.random() * 1000);
  const userDataDir = await mkdtemp(join(tmpdir(), 'stockrush-screenshots-'));
  const chrome = spawn(chromePath, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    'about:blank',
  ], { stdio: 'ignore' });

  try {
    await waitForChrome(port);
    const [customerToken, adminToken, productContext] = await Promise.all([
      issueToken(customerUser, customerPassword),
      issueToken(adminUser, adminPassword),
      findProductWithStock(),
    ]);

    const cdp = await newPage(port);

    await setupCustomerPage(
      cdp,
      customerToken,
      productContext.product.productCode,
      { width: 1440, height: 1200 },
    );
    const customerDesktop = await captureFullPage(
      cdp,
      `${SCREENSHOT_DIR}/customer-home-desktop.png`,
      { width: 1440, height: 1200 },
    );
    await setupCustomerPage(
      cdp,
      customerToken,
      productContext.product.productCode,
      { width: 390, height: 1200, mobile: true },
    );
    const customerMobile = await captureFullPage(
      cdp,
      `${SCREENSHOT_DIR}/customer-home-mobile.png`,
      { width: 390, height: 1200, mobile: true },
    );

    await setupAdminPage(cdp, adminToken);
    await clickTab(cdp, 'Dashboard');
    const adminDashboard = await captureFullPage(
      cdp,
      `${SCREENSHOT_DIR}/admin-dashboard-desktop.png`,
      { width: 1440, height: 1000 },
    );
    await clickTab(cdp, 'Outbox');
    const adminOutbox = await captureFullPage(
      cdp,
      `${SCREENSHOT_DIR}/admin-outbox-desktop.png`,
      { width: 1440, height: 1000 },
    );

    cdp.close();
    console.log(JSON.stringify({
      productCode: productContext.product.productCode,
      screenshots: {
        customerDesktop,
        customerMobile,
        adminDashboard,
        adminOutbox,
      },
    }, null, 2));
  } finally {
    chrome.kill('SIGTERM');
    await new Promise((resolve) => {
      chrome.once('exit', resolve);
      setTimeout(resolve, 1000);
    });
    try {
      await rm(userDataDir, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
    } catch {
      // Temporary browser profiles are safe to leave behind if Chrome still has a late file handle.
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
