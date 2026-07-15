import { readFile } from "node:fs/promises";
import { verifyDependencyLock } from "./dependency-lock-policy.mjs";

const appRoot = new URL("../", import.meta.url);
const packageJson = JSON.parse(
  await readFile(new URL("package.json", appRoot), "utf8"),
);
const packageLock = JSON.parse(
  await readFile(new URL("package-lock.json", appRoot), "utf8"),
);

const checked = verifyDependencyLock(packageJson, packageLock);

console.log(`Verified ${checked.length} security-sensitive lockfile entries:`);
for (const entry of checked) {
  console.log(`- ${entry}`);
}
