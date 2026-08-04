import assert from "node:assert/strict";

export const minimumSafeVersions = {
  "shell-quote": { 1: "1.9.0" },
  tar: { 7: "7.5.19" },
  postcss: { 8: "8.5.18" },
  "js-yaml": { 3: "3.15.0", 4: "4.3.0" },
  // Major 1 is no longer allowed: npm override remaps brace-expansion@1 → 2.1.4.
  "brace-expansion": { 2: "2.1.4", 5: "5.0.9" },
  undici: { 6: "6.27.0" },
  ws: { 6: "6.2.4", 7: "7.5.11", 8: "8.21.0" },
};

export const compareVersions = (left, right) => {
  const leftParts = left.split(".").map(Number);
  const rightParts = right.split(".").map(Number);
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference !== 0) {
      return difference;
    }
  }
  return 0;
};

export function verifyDependencyLock(packageJson, packageLock) {
  const overrides = packageJson.overrides ?? {};
  assert.equal(overrides["shell-quote"], "1.9.0", "shell-quote override must remain pinned");
  assert.equal(overrides.tar, "7.5.19", "tar override must remain pinned");
  assert.equal(overrides.postcss, "8.5.18", "postcss override must remain pinned");
  assert.equal(overrides["js-yaml@3"], "3.15.0", "js-yaml@3 override must remain pinned");
  assert.equal(overrides["js-yaml@4"], "4.3.0", "js-yaml@4 override must remain pinned");
  assert.equal(
    overrides["brace-expansion@1"],
    "2.1.4",
    "brace-expansion@1 override must remap residual 1.x requests to patched 2.1.4",
  );
  assert.equal(
    overrides["brace-expansion@2"],
    "2.1.4",
    "brace-expansion@2 override must remain pinned",
  );
  assert.equal(
    overrides["brace-expansion@5"],
    "5.0.9",
    "brace-expansion@5 override must remain pinned",
  );
  assert.equal(overrides.undici, "6.27.0", "undici override must remain pinned");
  assert.equal(
    overrides["@react-native/dev-middleware"]?.ws,
    "6.2.4",
    "React Native dev middleware must use the patched ws 6 release",
  );
  assert.equal(
    overrides["@expo/cli"]?.ws,
    "8.21.0",
    "Expo CLI must use the patched ws 8 release",
  );
  assert.equal(overrides.jsdom?.ws, "8.21.0", "jsdom must use the patched ws 8 release");
  assert.equal(
    overrides["react-native"]?.ws,
    "6.2.4",
    "React Native must use the patched ws 6 release",
  );
  assert.equal(overrides.metro?.ws, "7.5.11", "Metro must use the patched ws 7 release");
  assert.equal(
    overrides["react-devtools-core"]?.ws,
    "7.5.11",
    "React DevTools must use the patched ws 7 release",
  );

  const checked = [];
  for (const [packagePath, metadata] of Object.entries(packageLock.packages ?? {})) {
    const packageName = Object.keys(minimumSafeVersions).find(
      (candidate) => packagePath === `node_modules/${candidate}`
        || packagePath.endsWith(`/node_modules/${candidate}`),
    );
    if (!packageName) {
      continue;
    }

    const version = metadata.version;
    assert.match(version, /^\d+\.\d+\.\d+$/, `${packagePath} must use a plain release version`);
    const major = Number(version.split(".")[0]);
    if (packageName === "brace-expansion" && major === 1) {
      assert.fail(
        `${packagePath} still resolves brace-expansion 1.x (${version}); override must eliminate major 1`,
      );
    }
    const minimum = minimumSafeVersions[packageName][major];
    assert.ok(minimum, `${packagePath} uses an unreviewed ${packageName} major: ${version}`);
    assert.ok(
      compareVersions(version, minimum) >= 0,
      `${packagePath} ${version} is below the reviewed minimum ${minimum}`,
    );
    checked.push(`${packagePath}@${version}`);
  }

  assert.ok(checked.some((entry) => entry.includes("shell-quote@")), "shell-quote is missing");
  assert.ok(checked.some((entry) => entry.includes("tar@")), "tar is missing");
  assert.ok(checked.some((entry) => entry.includes("postcss@")), "postcss is missing");
  assert.ok(checked.some((entry) => entry.includes("js-yaml@")), "js-yaml is missing");
  assert.ok(checked.some((entry) => entry.includes("brace-expansion@")), "brace-expansion is missing");
  assert.ok(
    !checked.some((entry) => /brace-expansion@1\./.test(entry)),
    "brace-expansion 1.x must be absent from the lockfile",
  );
  assert.ok(checked.some((entry) => entry.includes("undici@")), "undici is missing");
  assert.ok(checked.some((entry) => entry.includes("ws@")), "ws is missing");
  return checked.sort();
}

/**
 * Validates that .trivyignore.yaml has no active vulnerability suppressions.
 */
export function verifyTrivyIgnoreDocument(document) {
  assert.ok(document && typeof document === "object", "trivyignore document is required");
  assert.ok(Array.isArray(document.vulnerabilities), "vulnerabilities list is required");
  assert.equal(
    document.vulnerabilities.length,
    0,
    "no active vulnerability suppressions are allowed",
  );

  for (const key of Object.keys(document)) {
    assert.equal(key, "vulnerabilities", `unexpected top-level trivyignore key: ${key}`);
  }
}

/** Minimal structural parse for the repo's .trivyignore.yaml shape (no YAML dependency). */
export function parseTrivyIgnoreYaml(text) {
  assert.equal(typeof text, "string", "trivyignore text is required");

  if (/^vulnerabilities:\s*\[\]\s*$/m.test(text)) {
    return { vulnerabilities: [] };
  }

  assert.match(text, /^vulnerabilities:\s*$/m, "vulnerabilities root is required");

  const idMatch = text.match(/^\s+- id:\s*(\S+)\s*$/m);
  assert.ok(idMatch, "vulnerability id is required");

  const purlMatches = [...text.matchAll(/^\s+- "(pkg:[^"]+)"\s*$/gm)].map((match) => match[1]);
  assert.ok(purlMatches.length > 0, "purl entries are required");

  const expiredMatch = text.match(/^\s+expired_at:\s*(\d{4}-\d{2}-\d{2})\s*$/m);
  assert.ok(expiredMatch, "expired_at is required");

  let statement = "";
  const folded = text.match(/^\s+statement:\s*>-\s*\n((?:\s{6}.+\n?)+)/m);
  if (folded) {
    statement = folded[1]
      .split("\n")
      .map((line) => line.replace(/^\s{6}/, "").trim())
      .filter(Boolean)
      .join(" ");
  } else {
    const inline = text.match(/^\s+statement:\s*(.+)\s*$/m);
    assert.ok(inline, "statement is required");
    statement = inline[1].trim();
  }

  return {
    vulnerabilities: [
      {
        id: idMatch[1],
        purls: purlMatches,
        expired_at: expiredMatch[1],
        statement,
      },
    ],
  };
}
