import { readFile } from "node:fs/promises";
import {
  parseTrivyIgnoreYaml,
  verifyDependencyLock,
  verifyTrivyIgnoreDocument,
} from "./dependency-lock-policy.mjs";

const appRoot = new URL("../", import.meta.url);
const repoRoot = new URL("../../../", import.meta.url);
const packageJson = JSON.parse(
  await readFile(new URL("package.json", appRoot), "utf8"),
);
const packageLock = JSON.parse(
  await readFile(new URL("package-lock.json", appRoot), "utf8"),
);
const trivyIgnoreText = await readFile(new URL(".trivyignore.yaml", repoRoot), "utf8");

const checked = verifyDependencyLock(packageJson, packageLock);
verifyTrivyIgnoreDocument(parseTrivyIgnoreYaml(trivyIgnoreText));

console.log(`Verified ${checked.length} security-sensitive lockfile entries:`);
for (const entry of checked) {
  console.log(`- ${entry}`);
}
console.log("Verified .trivyignore.yaml brace-expansion@2.1.3 suppression.");
