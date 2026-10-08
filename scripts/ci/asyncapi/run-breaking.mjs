#!/usr/bin/env node
// Runs the catalog's AsyncAPI breaking-change gate (asyncapi-breaking.mjs and lib/, copied unchanged from
// fintechbankx-governance-api-contracts-asyncapi-catalog a7b9b9d) on this repository's layout.
//
// The catalog script looks for top-level specs in asyncapi/ at the repository root; this service keeps them in
// api/asyncapi/ (SPEC_DIR). This wrapper repeats the catalog's main() loop with that directory and reuses its
// compareSpecs and readAccepted unchanged, so the rule set is identical. Remove it once the shared CI template
// runs the gate with a configurable spec directory.
//
// Run from the repository root: node scripts/ci/asyncapi/run-breaking.mjs (BASE_REF defaults to origin/main).
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { compareSpecs, readAccepted } from './asyncapi-breaking.mjs';

const specDir = (process.env.SPEC_DIR || 'api/asyncapi').replace(/\/+$/, '');
const git = (args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const gitShow = (rev, file) => {
  try {
    return git(['show', `${rev}:${file}`]);
  } catch {
    return null;
  }
};

const baseRef = process.env.BASE_REF || 'origin/main';
try {
  git(['rev-parse', '--verify', '--quiet', `${baseRef}^{commit}`]);
} catch {
  console.error(`BASE_REF ${baseRef} is not available. Fetch full history (actions/checkout fetch-depth: 0) or set BASE_REF.`);
  process.exit(2);
}
let base = baseRef;
try {
  base = git(['merge-base', baseRef, 'HEAD']).trim();
} catch {
  // unrelated histories: compare with the ref itself
}
const escaped = specDir.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const isSpec = (f) => new RegExp(`^${escaped}/[^/]+\\.ya?ml$`).test(f);
// --full-tree: paths from the repository root, whatever the working directory.
const baseSpecs = git(['ls-tree', '--full-tree', '--name-only', base, `${specDir}/`]).split('\n').filter(isSpec);
const headSpecs = fs.existsSync(specDir)
  ? fs.readdirSync(specDir).map((n) => `${specDir}/${n}`).filter((f) => isSpec(f) && fs.statSync(f).isFile())
  : [];
console.log(`asyncapi breaking check: ${specDir}, base ${baseRef} (merge base ${base.slice(0, 12)})`);
const readHead = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null);
const readBase = (f) => gitShow(base, f);
let failed = 0;
for (const f of headSpecs.filter((x) => !baseSpecs.includes(x)).sort()) {
  console.log(`skip ${f}: new file (not at base)`);
}
for (const f of baseSpecs.sort()) {
  const accepted = readAccepted(readHead(f.replace(/\.ya?ml$/, '.accepted-breaking.txt')));
  let findings;
  if (!headSpecs.includes(f)) {
    findings = [{ rule: 'removed-spec', key: `removed-spec ${f}`, detail: `spec ${f} was removed` }];
  } else {
    try {
      findings = compareSpecs(f, readBase, readHead);
    } catch (e) {
      console.error(`ERR ${f}: ${e.message}`);
      failed++;
      continue;
    }
  }
  const open = findings.filter((x) => !accepted.has(x.key));
  findings.filter((x) => accepted.has(x.key)).forEach((x) => console.log(`accepted ${f}: ${x.key}`));
  if (open.length === 0) {
    console.log(`ok   ${f}: no breaking changes`);
  } else {
    open.forEach((x) => console.error(`BREAKING ${f}: ${x.key}  (${x.detail})`));
    failed += open.length;
  }
}
if (failed > 0) {
  console.error(`asyncapi breaking check failed: ${failed} finding(s). Publish a new major version on a new topic (.v2) with dual-publish, or list accepted findings in ${specDir}/<spec-name>.accepted-breaking.txt.`);
  process.exit(1);
}
console.log('asyncapi breaking check passed');
