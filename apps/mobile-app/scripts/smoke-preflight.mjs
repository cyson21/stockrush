// 모바일 앱 사전 점검/스모크/증빙 수집을 자동화하는 스크립트입니다.
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const SUPPORTED_TARGETS = new Set(['all', 'ios', 'android']);

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

const target = argValue('--target', 'all');
if (!SUPPORTED_TARGETS.has(target)) {
  console.error(`unsupported target: ${target}`);
  console.error(`supported targets: ${Array.from(SUPPORTED_TARGETS).join(', ')}`);
  process.exit(2);
}
const apiBaseUrl = trimTrailingSlash(
  argValue('--api-base-url', process.env.EXPO_PUBLIC_API_BASE_URL || defaultApiBaseUrl(target)),
);
const hostApiBaseUrl = trimTrailingSlash(argValue('--host-api-base-url', apiBaseUrl));

function defaultApiBaseUrl(selectedTarget) {
  if (selectedTarget === 'android') {
    return 'http://10.0.2.2:18080';
  }
  return 'http://localhost:18080';
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, '');
}

function commandOutput(command, args) {
  try {
    return {
      ok: true,
      output: execFileSync(command, args, {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 5000,
      }).trim(),
    };
  } catch (error) {
    return {
      ok: false,
      output: `${error.stderr || error.message}`.trim(),
    };
  }
}

function androidSdkRoots() {
  return [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    join(homedir(), 'Library', 'Android', 'sdk'),
    join(homedir(), 'Android', 'Sdk'),
    process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'Android', 'Sdk') : '',
  ].filter(Boolean);
}

function androidEmulatorCandidates() {
  const executable = process.platform === 'win32' ? 'emulator.exe' : 'emulator';
  return [
    'emulator',
    ...androidSdkRoots().map((sdkRoot) => join(sdkRoot, 'emulator', executable)),
  ];
}

function gatewayHealth(url) {
  return new Promise((resolve) => {
    const targetUrl = new URL('/actuator/health', url);
    const client = targetUrl.protocol === 'https:' ? https : http;
    const request = client.get(targetUrl, { timeout: 5000 }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => {
        body += chunk;
      });
      response.on('end', () => {
        if (response.statusCode && response.statusCode >= 200 && response.statusCode < 300) {
          resolve({ ok: true, detail: `${response.statusCode} ${body.trim()}` });
          return;
        }
        resolve({ ok: false, detail: `${response.statusCode} ${body.trim()}` });
      });
    });
    request.on('timeout', () => {
      request.destroy(new Error('timeout'));
    });
    request.on('error', (error) => {
      resolve({ ok: false, detail: error.message });
    });
  });
}

function checkNodeModules() {
  const present = existsSync(join(appRoot, 'node_modules'));
  return {
    name: 'node_modules',
    status: present ? 'PASS' : 'BLOCKED',
    detail: present ? 'dependencies installed' : 'run npm install in apps/mobile-app before Expo smoke',
  };
}

async function checkGateway() {
  const result = await gatewayHealth(hostApiBaseUrl);
  return {
    name: 'gateway-health',
    status: result.ok ? 'PASS' : 'BLOCKED',
    detail: `${hostApiBaseUrl}/actuator/health -> ${result.detail}`,
  };
}

function checkIosSimulator() {
  const result = commandOutput('xcrun', ['simctl', 'list', 'devices', 'available']);
  return {
    name: 'ios-simulator',
    status: result.ok ? 'PASS' : 'BLOCKED',
    detail: result.ok ? 'simctl is available' : result.output || 'xcrun simctl unavailable',
  };
}

function checkAndroidEmulator() {
  const attempts = [];
  for (const candidate of androidEmulatorCandidates()) {
    const result = commandOutput(candidate, ['-list-avds']);
    attempts.push({ candidate, result });
    if (!result.ok) {
      continue;
    }
    const avds = result.output.split('\n').map((line) => line.trim()).filter(Boolean);
    return {
      name: 'android-emulator',
      status: avds.length > 0 ? 'PASS' : 'BLOCKED',
      detail: avds.length > 0
        ? `AVDs: ${avds.join(', ')} (${candidate})`
        : `no Android Virtual Device found (${candidate})`,
    };
  }
  const detail = attempts.map(({ candidate, result }) => `${candidate}: ${result.output || 'unavailable'}`).join('; ');
  return {
    name: 'android-emulator',
    status: 'BLOCKED',
    detail: detail || 'emulator command unavailable',
  };
}

const checks = [
  checkNodeModules(),
  await checkGateway(),
];

if (target === 'all' || target === 'ios') {
  checks.push(checkIosSimulator());
}
if (target === 'all' || target === 'android') {
  checks.push(checkAndroidEmulator());
}

const ready = checks.every((check) => check.status === 'PASS');
const report = {
  target,
  apiBaseUrl,
  hostApiBaseUrl,
  ready,
  checks,
};

if (hasFlag('--json')) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log(`Mobile smoke preflight`);
  console.log(`Target: ${target}`);
  console.log(`EXPO_PUBLIC_API_BASE_URL: ${apiBaseUrl}`);
  console.log(`Host health URL: ${hostApiBaseUrl}`);
  for (const check of checks) {
    console.log(`[${check.status}] ${check.name} - ${check.detail}`);
  }
  console.log(`Ready: ${ready ? 'yes' : 'no'}`);
}

if (hasFlag('--strict') && !ready) {
  process.exitCode = 1;
}
