import assert from "node:assert/strict";
import test from "node:test";

import { verifyDependencyLock } from "./dependency-lock-policy.mjs";

const validPackageJson = {
  overrides: {
    "shell-quote": "1.9.0",
    tar: "7.5.19",
    postcss: "8.5.18",
    "js-yaml@3": "3.15.0",
    "js-yaml@4": "4.3.0",
    "brace-expansion@1": "1.1.16",
    "brace-expansion@2": "2.1.3",
    "brace-expansion@5": "5.0.8",
    undici: "6.27.0",
    "@react-native/dev-middleware": { ws: "6.2.4" },
    "@expo/cli": { ws: "8.21.0" },
    jsdom: { ws: "8.21.0" },
    "react-native": { ws: "6.2.4" },
    metro: { ws: "7.5.11" },
    "react-devtools-core": { ws: "7.5.11" },
  },
};

const validPackageLock = {
  packages: {
    "node_modules/shell-quote": { version: "1.9.0" },
    "node_modules/tar": { version: "7.5.19" },
    "node_modules/postcss": { version: "8.5.18" },
    "node_modules/js-yaml": { version: "3.15.0" },
    "node_modules/@expo/xcpretty/node_modules/js-yaml": { version: "4.3.0" },
    "node_modules/brace-expansion": { version: "5.0.8" },
    "node_modules/expo/node_modules/brace-expansion": { version: "2.1.3" },
    "node_modules/rimraf/node_modules/brace-expansion": { version: "1.1.16" },
    "node_modules/undici": { version: "6.27.0" },
    "node_modules/react-native/node_modules/ws": { version: "6.2.4" },
    "node_modules/ws": { version: "7.5.11" },
    "node_modules/expo/node_modules/ws": { version: "8.21.0" },
    "node_modules/jsdom/node_modules/ws": { version: "8.21.0" },
  },
};

test("accepts the reviewed minimum versions", () => {
  assert.equal(verifyDependencyLock(validPackageJson, validPackageLock).length, 13);
});

test("rejects a vulnerable ws 6 lock entry", () => {
  const packageLock = structuredClone(validPackageLock);
  packageLock.packages["node_modules/react-native/node_modules/ws"].version = "6.2.3";

  assert.throws(
    () => verifyDependencyLock(validPackageJson, packageLock),
    /below the reviewed minimum 6\.2\.4/,
  );
});

test("rejects removal of a required transitive override", () => {
  const packageJson = structuredClone(validPackageJson);
  delete packageJson.overrides.undici;

  assert.throws(
    () => verifyDependencyLock(packageJson, validPackageLock),
    /undici override must remain pinned/,
  );
});

test("rejects the vulnerable ws 8 release", () => {
  const packageLock = structuredClone(validPackageLock);
  packageLock.packages["node_modules/expo/node_modules/ws"].version = "8.20.1";

  assert.throws(
    () => verifyDependencyLock(validPackageJson, packageLock),
    /below the reviewed minimum 8\.21\.0/,
  );
});

test("rejects an unreviewed major release", () => {
  const packageLock = structuredClone(validPackageLock);
  packageLock.packages["node_modules/ws"].version = "9.0.0";

  assert.throws(
    () => verifyDependencyLock(validPackageJson, packageLock),
    /uses an unreviewed ws major/,
  );
});

test("rejects vulnerable shell-quote pin", () => {
  const packageJson = structuredClone(validPackageJson);
  packageJson.overrides["shell-quote"] = "1.8.4";

  assert.throws(
    () => verifyDependencyLock(packageJson, validPackageLock),
    /shell-quote override must remain pinned/,
  );
});

test("rejects vulnerable brace-expansion 1.x lock entry", () => {
  const packageLock = structuredClone(validPackageLock);
  packageLock.packages["node_modules/rimraf/node_modules/brace-expansion"].version = "1.1.14";

  assert.throws(
    () => verifyDependencyLock(validPackageJson, packageLock),
    /below the reviewed minimum 1\.1\.16/,
  );
});
