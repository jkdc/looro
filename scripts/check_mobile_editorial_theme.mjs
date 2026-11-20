import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const read = (path) => readFileSync(new URL("../" + path, import.meta.url), "utf8");
const catalog = JSON.parse(read("shared/design/mobile-theme.json"));
const roles = ["background", "text", "mutedText", "primary", "onPrimary", "sessionSurface", "sessionText", "orbLight", "orbDark"];
const linear = (n) => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4;
const luminance = (hex) => [0, 2, 4].reduce((total, offset, i) =>
  total + linear(parseInt(hex.slice(offset, offset + 2), 16) / 255) * [.2126, .7152, .0722][i], 0);
assert.deepEqual(Object.keys(catalog), ["editorial", "reference"]);
for (const [name, profile] of Object.entries(catalog)) {
  assert.deepEqual(Object.keys(profile), [...roles, "headingStyle"], name);
  assert.equal(profile.headingStyle, name === "editorial" ? "serif" : "rounded");
  for (const role of roles) assert.match(profile[role], /^[0-9A-F]{6}$/);
  for (const [fg, bg] of [["text", "background"], ["mutedText", "background"], ["onPrimary", "primary"], ["sessionText", "sessionSurface"]]) {
    const levels = [luminance(profile[fg]), luminance(profile[bg])].sort((a, b) => b - a);
    assert.ok((levels[0] + .05) / (levels[1] + .05) >= 4.5, name + ": " + fg + " / " + bg);
  }
}
const output = spawnSync(process.execPath, [fileURLToPath(new URL("./export_mobile_editorial_theme.mjs", import.meta.url)), "--check"], { encoding: "utf8" });
assert.equal(output.status, 0, output.stderr);
console.log("Editorial and reference profiles: complete, readable and synchronized.");
