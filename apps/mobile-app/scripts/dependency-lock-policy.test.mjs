import assert from "node:assert/strict";
import test from "node:test";

import { verifyDependencyLock } from "./dependency-lock-policy.mjs";

const validPackageJson = {
  overrides: {
    "shell-quote": "1.8.4",
    undici: "6.27.0",
    "@react-native/dev-middleware": { ws: "6.2.4" },
    "react-native": { ws: "6.2.4" },
    metro: { ws: "7.5.11" },
    "react-devtools-core": { ws: "7.5.11" },
  },
};

const validPackageLock = {
  packages: {
    "node_modules/shell-quote": { version: "1.8.4" },
    "node_modules/undici": { version: "6.27.0" },
    "node_modules/react-native/node_modules/ws": { version: "6.2.4" },
    "node_modules/ws": { version: "7.5.11" },
    "node_modules/expo/node_modules/ws": { version: "8.20.1" },
  },
};

test("accepts the reviewed minimum versions", () => {
  assert.equal(verifyDependencyLock(validPackageJson, validPackageLock).length, 5);
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

test("rejects an unreviewed major release", () => {
  const packageLock = structuredClone(validPackageLock);
  packageLock.packages["node_modules/ws"].version = "9.0.0";

  assert.throws(
    () => verifyDependencyLock(validPackageJson, packageLock),
    /uses an unreviewed ws major/,
  );
});
