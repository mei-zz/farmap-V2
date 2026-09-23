import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDirectory, "..");
const deploymentEnvFile = path.resolve(frontendRoot, "..", "deployment", ".env");

function readDeploymentMapKey() {
  if (!existsSync(deploymentEnvFile)) return "";

  for (const line of readFileSync(deploymentEnvFile, "utf8").split(/\r?\n/)) {
    const match = line.match(/^\s*GAODE_API_KEY\s*=\s*(.*)$/);
    if (!match) continue;

    const value = match[1].trim();
    if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
      return value.slice(1, -1).trim();
    }
    return value;
  }
  return "";
}

const buildEnvironment = { ...process.env };
if (!buildEnvironment.VITE_GAODE_API_KEY) {
  buildEnvironment.VITE_GAODE_API_KEY = buildEnvironment.GAODE_API_KEY || readDeploymentMapKey();
}

if (!buildEnvironment.VITE_GAODE_API_KEY) {
  console.warn("FarMap frontend build: VITE_GAODE_API_KEY is not configured; the map will show its unavailable state.");
}

// The repository's pnpm-workspace.yaml is intentionally only used for
// build-approval settings and does not declare workspace packages. Keep the
// nested commands in single-package mode as well, otherwise pnpm 9 rejects
// `exec` on Linux even when the outer build command used --ignore-workspace.
for (const args of [["--ignore-workspace", "exec", "tsc", "-b"], ["--ignore-workspace", "exec", "vite", "build"]]) {
  const isWindows = process.platform === "win32";
  const result = spawnSync(
    isWindows ? "cmd.exe" : "pnpm",
    isWindows ? ["/d", "/s", "/c", `pnpm ${args.join(" ")}`] : args,
    {
    cwd: frontendRoot,
    env: buildEnvironment,
    stdio: "inherit",
    },
  );
  if (result.error) {
    console.error(`FarMap frontend build command could not start: ${result.error.message}`);
    process.exit(1);
  }
  if (result.status !== 0) process.exit(result.status ?? 1);
}
