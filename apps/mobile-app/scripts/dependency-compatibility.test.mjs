import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

const require = createRequire(import.meta.url);
const { getAssetData } = require("metro/private/Assets");

test("Metro reads PNG dimensions and density variants after security updates", async () => {
  const directory = await mkdtemp(join(tmpdir(), "stockrush-metro-asset-"));
  try {
    const png = Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1XcAAAAASUVORK5CYII=",
      "base64",
    );
    const asset = join(directory, "pixel.png");
    await writeFile(asset, png);
    await writeFile(join(directory, "pixel@2x.png"), png);
    const result = await getAssetData(asset, "pixel.png", [], null, "/assets");
    assert.equal(result.width, 1);
    assert.equal(result.height, 1);
    assert.equal(result.type, "png");
    assert.deepEqual(result.scales, [1, 2]);
    assert.equal(result.files.length, 2);
    assert.match(result.hash, /^[a-f0-9]+$/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
