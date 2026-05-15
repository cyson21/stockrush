import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';

function argValue(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index === -1 || index + 1 >= process.argv.length) {
    return fallback;
  }
  return process.argv[index + 1];
}

const target = argValue('--target', 'all');
const apiBaseUrl = argValue('--api-base-url', process.env.EXPO_PUBLIC_API_BASE_URL || 'http://localhost:28080');
const hostApiBaseUrl = argValue('--host-api-base-url', apiBaseUrl);
const outputPath = argValue('--output', '');

function runCommand(label, command, args) {
  const startedAt = Date.now();
  const result = spawnSync(command, args, {
    cwd: appRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  return {
    label,
    command: [command, ...args].join(' '),
    exitCode: result.status ?? 1,
    durationMs: Date.now() - startedAt,
    stdout: result.stdout.trim(),
    stderr: result.stderr.trim(),
  };
}

function runPreflight() {
  const result = runCommand('smoke:preflight', process.execPath, [
    'scripts/smoke-preflight.mjs',
    '--target',
    target,
    '--api-base-url',
    apiBaseUrl,
    '--host-api-base-url',
    hostApiBaseUrl,
    '--json',
  ]);

  try {
    return {
      result,
      report: JSON.parse(result.stdout),
    };
  } catch {
    return {
      result,
      report: null,
    };
  }
}

function fenced(value) {
  if (!value) {
    return '_empty_';
  }
  return `\n\`\`\`text\n${value}\n\`\`\``;
}

function commandLine(command) {
  return command.replace(process.execPath, 'node').replace(appRoot, '.');
}

const hardChecks = [
  runCommand('npm test', npmCommand, ['test', '--', '--runInBand']),
  runCommand('npm run typecheck', npmCommand, ['run', 'typecheck']),
  runCommand('npm run test:scaffold', npmCommand, ['run', 'test:scaffold']),
];
const preflight = runPreflight();
const allHardChecksPassed = hardChecks.every((check) => check.exitCode === 0);
const generatedAt = new Date().toISOString();

const lines = [
  '# Mobile Smoke Evidence',
  '',
  `- generatedAt: ${generatedAt}`,
  `- target: ${target}`,
  `- apiBaseUrl: ${apiBaseUrl}`,
  `- hostApiBaseUrl: ${hostApiBaseUrl}`,
  `- hardChecksPassed: ${allHardChecksPassed ? 'yes' : 'no'}`,
  `- simulatorReady: ${preflight.report?.ready ? 'yes' : 'no'}`,
  '',
  '## Command Results',
  '',
  '| Check | Exit | Duration | Command |',
  '|---|---:|---:|---|',
  ...[...hardChecks, preflight.result].map((check) => (
    `| ${check.label} | ${check.exitCode} | ${check.durationMs}ms | \`${commandLine(check.command)}\` |`
  )),
  '',
  '## Preflight Checks',
  '',
];

if (preflight.report) {
  lines.push('| Check | Status | Detail |');
  lines.push('|---|---|---|');
  for (const check of preflight.report.checks) {
    lines.push(`| ${check.name} | ${check.status} | ${String(check.detail).replaceAll('|', '\\|')} |`);
  }
} else {
  lines.push('Preflight did not return JSON.');
}

lines.push('');
lines.push('## Output');
for (const check of [...hardChecks, preflight.result]) {
  lines.push('');
  lines.push(`### ${check.label}`);
  lines.push('');
  lines.push('stdout:');
  lines.push(fenced(check.stdout));
  lines.push('');
  lines.push('stderr:');
  lines.push(fenced(check.stderr));
}

const markdown = `${lines.join('\n')}\n`;

if (outputPath) {
  const resolvedOutputPath = resolve(appRoot, outputPath);
  mkdirSync(dirname(resolvedOutputPath), { recursive: true });
  writeFileSync(resolvedOutputPath, markdown, 'utf8');
} else {
  process.stdout.write(markdown);
}

if (!allHardChecksPassed) {
  process.exitCode = 1;
}
