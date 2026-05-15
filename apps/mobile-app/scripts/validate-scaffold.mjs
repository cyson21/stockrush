import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const appRoot = dirname(dirname(fileURLToPath(import.meta.url)));

function readJson(relativePath) {
  const target = join(appRoot, relativePath);
  assert.ok(existsSync(target), `${relativePath} must exist`);
  return JSON.parse(readFileSync(target, 'utf8'));
}

function readText(relativePath) {
  const target = join(appRoot, relativePath);
  assert.ok(existsSync(target), `${relativePath} must exist`);
  return readFileSync(target, 'utf8');
}

const packageJson = readJson('package.json');
assert.equal(packageJson.name, 'stockrush-mobile-app');
assert.equal(packageJson.private, true);
assert.equal(packageJson.main, 'index.js');
assert.equal(packageJson.scripts.start, 'expo start');
assert.equal(packageJson.scripts.android, 'expo start --android');
assert.equal(packageJson.scripts.ios, 'expo start --ios');
assert.equal(packageJson.scripts['smoke:preflight'], 'node scripts/smoke-preflight.mjs');
assert.equal(packageJson.scripts['smoke:evidence'], 'node scripts/collect-smoke-evidence.mjs');
assert.equal(packageJson.scripts['test:scaffold'], 'node scripts/validate-scaffold.mjs');
assert.equal(packageJson.dependencies.expo, '~54.0.34');
assert.equal(packageJson.dependencies.react, '19.1.0');
assert.equal(packageJson.dependencies['react-native'], '0.81.5');
assert.equal(packageJson.dependencies['expo-status-bar'], '~3.0.9');
assert.equal(packageJson.devDependencies.typescript, '~5.9.2');

const appConfig = readJson('app.json');
assert.equal(appConfig.expo.name, 'StockRush Mobile');
assert.equal(appConfig.expo.slug, 'stockrush-mobile');
assert.deepEqual(appConfig.expo.platforms, ['ios', 'android']);
assert.equal(appConfig.expo.scheme, 'stockrush');

[
  'index.js',
  'App.tsx',
  'tsconfig.json',
  'src/config/runtime.ts',
  'src/types/api.ts',
  'src/api/client.ts',
  'src/api/catalog.ts',
  'src/api/inventory.ts',
  'src/api/promotion.ts',
  'src/api/orders.ts',
  'src/api/readModel.ts',
  'scripts/smoke-preflight.mjs',
  'scripts/collect-smoke-evidence.mjs',
].forEach(readText);

const runtimeConfig = readText('src/config/runtime.ts');
assert.ok(runtimeConfig.includes('EXPO_PUBLIC_API_BASE_URL'));
assert.ok(runtimeConfig.includes('http://localhost:18080'));
assert.ok(runtimeConfig.includes('http://10.0.2.2:18080'));

const readModelClient = readText('src/api/readModel.ts');
assert.ok(readModelClient.includes('/api/read-model/orders'));

const promotionClient = readText('src/api/promotion.ts');
assert.ok(promotionClient.includes('/api/coupons/quote'));

const readme = readText('README.md');
assert.ok(readme.includes('EXPO_PUBLIC_API_BASE_URL'));
assert.ok(readme.includes('Android emulator'));
assert.ok(readme.includes('10.0.2.2'));
assert.ok(readme.includes('Demo stack'));
assert.ok(readme.includes('28080'));
assert.ok(readme.includes('Windows 11 Android'));

const smokePreflight = readText('scripts/smoke-preflight.mjs');
assert.ok(smokePreflight.includes('EXPO_PUBLIC_API_BASE_URL'));
assert.ok(smokePreflight.includes('simctl'));
assert.ok(smokePreflight.includes('emulator'));
assert.ok(smokePreflight.includes('ANDROID_HOME'));
assert.ok(smokePreflight.includes('ANDROID_SDK_ROOT'));
assert.ok(smokePreflight.includes('SUPPORTED_TARGETS'));
assert.ok(smokePreflight.includes('unsupported target'));

const smokeEvidence = readText('scripts/collect-smoke-evidence.mjs');
assert.ok(smokeEvidence.includes('npm test'));
assert.ok(smokeEvidence.includes('npm run typecheck'));
assert.ok(smokeEvidence.includes('npm run test:scaffold'));
assert.ok(smokeEvidence.includes('node_modules/jest/bin/jest.js'));
assert.ok(smokeEvidence.includes('node_modules/typescript/bin/tsc'));
assert.ok(smokeEvidence.includes('smoke:preflight'));

console.log('Mobile scaffold validation passed.');
