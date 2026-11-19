#!/usr/bin/env node
/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

/**
 * Keeps a product's error dictionary honest. The dictionary is layered: the shared base (generic codes, shipped in
 * kit-web) plus the product's own file(s). The check refuses:
 *   - a malformed entry (code pattern, status, category, texts, FHIR issue type);
 *   - a code raised in the source but not listed, or raised with a status that differs from the dictionary's;
 *   - a problem URN built by hand instead of through Problems;
 *   - a released code that was renamed, removed or given another status (the baseline file);
 *   - a stale copy of the readable catalogue.
 * A listed code that nothing raises is a warning, or a failure with --strict (a product seeds codes ahead of features).
 *
 *   node error-dictionary.mjs --config error-dictionary.config.json            # check
 *   node error-dictionary.mjs --config error-dictionary.config.json --write    # also regenerate the catalogue
 *   node error-dictionary.mjs --config error-dictionary.config.json --freeze   # record the released codes as the baseline
 *
 * Config (paths relative to the config file's folder):
 *   { "product": "ogh", "dictionaries": ["..."], "sourceRoots": ["backend"], "services": ["ogh-ref-svc"],
 *     "logPrefix": "OGH", "docOutput": "docs/errors/error-dictionary.md", "baseline": "docs/errors/released-codes.json" }
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE_DICTIONARY = join(HERE, '..', '..', 'kit-web', 'src', 'main', 'resources', 'META-INF', 'techeazy', 'base-error-dictionary.json');

const CATEGORIES = ['validation', 'authentication', 'authorization', 'consent', 'not-found', 'conflict', 'rate-limit',
  'dependency', 'integrity', 'internal', 'maintenance', 'operations'];
const FHIR_ISSUE_TYPES = new Set(['invalid', 'structure', 'required', 'value', 'invariant', 'security', 'login', 'unknown',
  'expired', 'forbidden', 'suppressed', 'processing', 'not-supported', 'duplicate', 'multiple-matches', 'not-found',
  'deleted', 'too-long', 'code-invalid', 'extension', 'too-costly', 'business-rule', 'conflict', 'transient', 'lock-error',
  'no-store', 'exception', 'timeout', 'incomplete', 'throttled', 'informational']);
const PROBLEM_CODE = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

export function parseArgs(argv) {
  const args = { config: null, write: false, strict: false, freeze: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--config') args.config = argv[++i];
    else if (argv[i] === '--write') args.write = true;
    else if (argv[i] === '--strict') args.strict = true;
    else if (argv[i] === '--freeze') args.freeze = true;
  }
  return args;
}

const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));

/** Checks one product. Returns { problems, warnings, entries, rendered }. */
export function check(config, root) {
  const problems = [];
  const warnings = [];
  const fail = (m) => problems.push(m);
  const logPrefix = new RegExp(`^(TZ|${config.logPrefix})-[A-Z0-9]+(-[A-Z0-9]+)*$`);
  const services = new Set(['platform', ...(config.services ?? [])]);

  // ---- the dictionary: base + the product's files
  const base = readJson(BASE_DICTIONARY);
  const files = (config.dictionaries ?? []).map((p) => resolve(root, p));
  const docs = [{ file: BASE_DICTIONARY, doc: base, isBase: true }];
  for (const f of files) {
    if (!existsSync(f)) { fail(`dictionary not found: ${relative(root, f)}`); continue; }
    const doc = readJson(f);
    if (doc.product && doc.product !== config.product) fail(`${relative(root, f)}: names product "${doc.product}", the config says "${config.product}"`);
    docs.push({ file: f, doc, isBase: false });
  }
  const byCode = new Map();
  const entries = [];
  for (const { file, doc, isBase } of docs) {
    for (const e of doc.entries ?? []) {
      const where = `${relative(root, file)}: ${e.code}`;
      if (byCode.has(e.code)) { fail(`${where}: listed twice (also in another dictionary)`); continue; }
      e.__base = isBase;
      byCode.set(e.code, e);
      entries.push(e);
      if (!['problem', 'log'].includes(e.kind)) { fail(`${where}: kind must be problem or log`); continue; }
      if (e.kind === 'problem' && !PROBLEM_CODE.test(e.code ?? '')) fail(`${where}: a problem code is lower-case words joined by hyphens`);
      if (e.kind === 'log' && !logPrefix.test(e.code ?? '')) fail(`${where}: a log code is written ${logPrefix}`);
      if (!CATEGORIES.includes(e.category)) fail(`${where}: category "${e.category}" is not one of ${CATEGORIES.join(', ')}`);
      for (const field of ['title', 'description', 'action']) {
        if (typeof e[field] !== 'string' || e[field].trim() === '') fail(`${where}: ${field} is empty`);
      }
      if (e.title && (e.title.length > 60 || /[.]$/.test(e.title))) fail(`${where}: the title is at most 60 characters and has no full stop`);
      if (e.description && !/[.]$/.test(e.description)) fail(`${where}: the description ends in a full stop`);
      if (e.action && !/[.]$/.test(e.action)) fail(`${where}: the action ends in a full stop`);
      if (!Array.isArray(e.raisedBy) || e.raisedBy.length === 0) fail(`${where}: raisedBy names the services that raise it`);
      for (const s of e.raisedBy ?? []) if (!services.has(s)) fail(`${where}: raisedBy "${s}" is not a listed service`);
      if (typeof e.retryable !== 'boolean') fail(`${where}: retryable is true or false`);
      if (e.kind === 'problem') {
        if (!Number.isInteger(e.status) || e.status < 400 || e.status > 599) fail(`${where}: status must be 400 to 599`);
        if (e.status >= 500 && !['dependency', 'internal', 'maintenance'].includes(e.category)) fail(`${where}: a 5xx is dependency, internal or maintenance`);
        if (e.status === 401 && e.category !== 'authentication') fail(`${where}: 401 means category authentication`);
        if (e.status === 404 && e.category !== 'not-found') fail(`${where}: 404 means category not-found`);
        if (e.retryable && ![429, 503].includes(e.status)) fail(`${where}: only 429 and 503 can be retried as they are`);
        if (!FHIR_ISSUE_TYPES.has(e.fhirIssueType)) fail(`${where}: fhirIssueType "${e.fhirIssueType}" is not a FHIR issue type`);
      }
      if (e.kind === 'log' && !['WARN', 'ERROR'].includes(e.level)) fail(`${where}: a log code has level WARN or ERROR`);
    }
  }

  // ---- the sources
  const used = new Map();
  const note = (code, file, line, status) => {
    if (!used.has(code)) used.set(code, []);
    used.get(code).push({ file, line, status });
  };
  const lineOf = (text, index) => text.slice(0, index).split('\n').length;
  const logRe = new RegExp(`\\[((?:TZ|${config.logPrefix})-[A-Z0-9]+(?:-[A-Z0-9]+)*)\\]`, 'g');
  const PATTERNS = [
    { re: /DomRuleViolation\(\s*(\d{3})\s*,\s*"([a-z0-9-]+)"/g, status: 1, code: 2 },
    { re: /SpecResult\.violated\(\s*"([a-z0-9-]+)"/g, code: 1 },
    { re: /Problems\.\w+\(\s*"([a-z0-9-]+)"/g, code: 1 },
    { re: /OperationOutcomes\.of\(\s*"([a-z0-9-]+)"/g, code: 1 },
    { re: logRe, code: 1 },
  ];
  const handBuilt = [];
  const handRe = new RegExp(`urn:${config.product}:problem:`);
  function* javaMain(dir) {
    for (const name of readdirSync(dir)) {
      if (['build', 'node_modules', '.git', '.gradle', 'test'].includes(name)) continue;
      const path = join(dir, name);
      if (statSync(path).isDirectory()) yield* javaMain(path);
      else if (name.endsWith('.java')) yield path;
    }
  }
  for (const rootDir of config.sourceRoots ?? []) {
    const dir = resolve(root, rootDir);
    if (!existsSync(dir)) { fail(`source root not found: ${rootDir}`); continue; }
    for (const file of javaMain(dir)) {
      const rel = relative(root, file).replaceAll('\\', '/');
      const text = readFileSync(file, 'utf8');
      for (const p of PATTERNS) {
        p.re.lastIndex = 0;
        for (const m of text.matchAll(p.re)) note(m[p.code], rel, lineOf(text, m.index), p.status ? Number(m[p.status]) : undefined);
      }
      for (const [i, line] of text.split('\n').entries()) {
        const t = line.trim();
        if (handRe.test(line) && !t.startsWith('*') && !t.startsWith('//') && !t.startsWith('/*')) handBuilt.push(`${rel}:${i + 1}`);
      }
    }
  }
  for (const [code, sites] of used) {
    const e = byCode.get(code);
    if (!e) { fail(`${code}: raised at ${sites[0].file}:${sites[0].line} but not in the dictionary`); continue; }
    for (const s of sites) {
      if (s.status !== undefined && e.kind === 'problem' && s.status !== e.status) {
        fail(`${code}: ${s.file}:${s.line} raises ${s.status}, the dictionary says ${e.status}. One name, one status`);
      }
    }
  }
  for (const e of entries) {
    if (!e.__base && !used.has(e.code)) warnings.push(`${e.code}: in the dictionary but raised nowhere yet`);
  }
  for (const site of handBuilt) fail(`${site}: builds "urn:${config.product}:problem:" by hand. Use Problems, so the title and code come from the dictionary`);

  // ---- released codes are immutable
  if (config.baseline) {
    const baselinePath = resolve(root, config.baseline);
    if (existsSync(baselinePath)) {
      for (const [code, was] of Object.entries(readJson(baselinePath).codes ?? {})) {
        const e = byCode.get(code);
        if (!e) fail(`${code}: released, but now missing or renamed. A released code never goes away: mark it deprecated instead`);
        else if (e.kind === 'problem' && e.status !== was.status) fail(`${code}: released with status ${was.status}, now ${e.status}. One name, one status`);
      }
    }
  }
  return { problems, warnings, entries, rendered: render(entries, config) };
}

const esc = (s) => String(s).replaceAll('|', '\\|');
const heading = (c) => c[0].toUpperCase() + c.slice(1).replace('-', ' ');

export function render(entries, config) {
  const out = [];
  const problems = entries.filter((e) => e.kind === 'problem');
  const logs = entries.filter((e) => e.kind === 'log');
  out.push('<!--', 'Copyright 2026 Vasantha Kumar', '', 'Licensed under the Apache License, Version 2.0 (the "License");',
    'you may not use this file except in compliance with the License.', 'You may obtain a copy of the License at', '',
    '    http://www.apache.org/licenses/LICENSE-2.0', '', 'Unless required by applicable law or agreed to in writing, software',
    'distributed under the License is distributed on an "AS IS" BASIS,', 'WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.',
    'See the License for the specific language governing permissions and', 'limitations under the License.', '',
    '@author Vasantha Kumar <vasantha.kumar@hotmail.com>', '',
    'Generated by tools/error-dictionary from the dictionaries. Do not edit by hand: edit the dictionary and regenerate.', '-->', '');
  out.push(`# Error dictionary (${config.product})`, '');
  out.push(`${entries.length} entries: ${problems.length} API problems and ${logs.length} operational log codes. Generic codes come from the shared base; the rest are this product's.`, '');
  out.push('## How an API error looks', '', 'Every REST error is `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)):', '', '```json');
  out.push(JSON.stringify({ type: `urn:${config.product}:problem:quota-exceeded`, code: 'quota-exceeded', title: 'Allowance used up', status: 429,
    detail: 'Tenant 6f1c is over its allowance', retryable: true, correlationId: '0b1e7c7a-6f43-4f4e-9d52-8d2a1f4c7a10' }, null, 2), '```', '');
  out.push('- **`code`** (also the end of `type`) is the stable name: write logic against it, never against `title` or `detail`.');
  out.push('- **`title`** is the same short sentence every time for a code. **`detail`** says what was wrong this time and can change.');
  out.push('- **`retryable`** says whether the same request can work later. Honour `Retry-After`. **`correlationId`** is the `X-Correlation-Id` header.');
  out.push(`- FHIR endpoints answer an \`OperationOutcome\`: \`issue.code\` is the FHIR type below, \`issue.details.coding\` is \`urn:${config.product}:errors\` plus the code.`, '');
  out.push('## Rules', '', '- Codes are lower-case words joined by hyphens and never change once released. One code has one status.',
    `- Operational log codes are \`TZ-\` (shared) or \`${config.logPrefix}-\` and upper-case words, in square brackets at the start of a message.`,
    '- No personal or clinical data in any title, detail or log code.', '');
  out.push('## API problems', '');
  for (const cat of CATEGORIES.filter((c) => problems.some((e) => e.category === c))) {
    out.push(`### ${heading(cat)}`, '', '| Code | Status | Title | What it means | What to do | Retry | FHIR | Raised by |', '|---|---|---|---|---|---|---|---|');
    for (const e of problems.filter((x) => x.category === cat).sort((a, b) => a.code.localeCompare(b.code))) {
      out.push(`| \`${e.code}\` | ${e.status} | ${esc(e.title)} | ${esc(e.description)} | ${esc(e.action)} | ${e.retryable ? 'yes' : 'no'} | ${e.fhirIssueType} | ${e.raisedBy.join(', ')} |`);
    }
    out.push('');
  }
  out.push('## Operational log codes', '', '| Code | Level | Title | What it means | What to do | Raised by |', '|---|---|---|---|---|---|');
  for (const e of logs.sort((a, b) => a.code.localeCompare(b.code))) {
    out.push(`| \`${e.code}\` | ${e.level} | ${esc(e.title)} | ${esc(e.description)} | ${esc(e.action)} | ${e.raisedBy.join(', ')} |`);
  }
  out.push('');
  return out.join('\n');
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.config) { console.error('Usage: error-dictionary.mjs --config <file> [--write] [--strict] [--freeze]'); process.exit(2); }
  const configPath = resolve(args.config);
  const root = dirname(configPath);
  const config = readJson(configPath);
  const result = check(config, root);
  const problems = [...result.problems];
  if (config.docOutput) {
    const doc = resolve(root, config.docOutput);
    if (args.write) { mkdirSync(dirname(doc), { recursive: true }); writeFileSync(doc, result.rendered); }
    else if (!existsSync(doc) || readFileSync(doc, 'utf8').replaceAll('\r\n', '\n') !== result.rendered) {
      problems.push(`${relative(root, doc)} is out of date. Run with --write`);
    }
  }
  if (args.freeze && config.baseline) {
    const codes = {};
    for (const e of result.entries) codes[e.code] = e.kind === 'problem' ? { status: e.status } : { level: e.level };
    mkdirSync(dirname(resolve(root, config.baseline)), { recursive: true });
    writeFileSync(resolve(root, config.baseline), JSON.stringify({ version: 1, codes }, null, 2) + '\n');
    console.log(`Recorded ${result.entries.length} released codes in ${config.baseline}.`);
  }
  for (const w of result.warnings) console.warn(`warning: ${w}`);
  if (args.strict && result.warnings.length > 0) problems.push(...result.warnings.map((w) => `${w} (--strict)`));
  if (problems.length > 0) {
    console.error(`${problems.length} problem(s) with the error dictionary:`);
    for (const p of problems) console.error(`  ${p}`);
    process.exit(1);
  }
  console.log(`The error dictionary holds: ${result.entries.length} entries, all well formed${args.write ? ', catalogue regenerated' : ''}.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
