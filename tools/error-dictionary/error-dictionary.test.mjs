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

// node --test tools/error-dictionary
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { check } from './error-dictionary.mjs';

const entry = (over = {}) => ({
  code: 'demo-thing-not-found', kind: 'problem', status: 404, category: 'not-found', title: 'Thing not found',
  description: 'No thing has that id.', action: 'Use the id returned when the thing was created.', retryable: false,
  raisedBy: ['demo-svc'], fhirIssueType: 'not-found', ...over,
});

/** A product with one dictionary and one Java source file. */
function product({ entries = [entry()], java = 'throw new DomRuleViolation(404, "demo-thing-not-found", "x");', baseline } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'errdict-'));
  mkdirSync(join(root, 'src'), { recursive: true });
  writeFileSync(join(root, 'dictionary.json'), JSON.stringify({ version: 1, product: 'demo', entries }));
  writeFileSync(join(root, 'src', 'Thing.java'), `class Thing { void f() { ${java} } }`);
  const config = { product: 'demo', dictionaries: ['dictionary.json'], sourceRoots: ['src'], services: ['demo-svc'], logPrefix: 'DEMO' };
  if (baseline) {
    writeFileSync(join(root, 'released.json'), JSON.stringify({ version: 1, codes: baseline }));
    config.baseline = 'released.json';
  }
  return { root, config };
}

test('a consistent product passes', () => {
  const { root, config } = product();
  const r = check(config, root);
  assert.deepEqual(r.problems, []);
  assert.deepEqual(r.warnings, []);
});

test('a code raised but not listed fails', () => {
  const { root, config } = product({ java: 'throw new DomRuleViolation(404, "demo-unknown", "x");' });
  const r = check(config, root);
  assert.ok(r.problems.some((p) => p.includes('demo-unknown') && p.includes('not in the dictionary')));
});

test('a raise with another status than the dictionary fails', () => {
  const { root, config } = product({ java: 'throw new DomRuleViolation(409, "demo-thing-not-found", "x");' });
  const r = check(config, root);
  assert.ok(r.problems.some((p) => p.includes('One name, one status')));
});

test('a base code can be raised without being in the product dictionary', () => {
  const { root, config } = product({ java: 'throw new DomRuleViolation(404, "demo-thing-not-found", "x"); Problems.body("quota-exceeded", null, null);' });
  assert.deepEqual(check(config, root).problems, []);
});

test('a listed product code that nothing raises is a warning, not a failure', () => {
  const { root, config } = product({ entries: [entry(), entry({ code: 'demo-thing-archived', status: 409, category: 'conflict', fhirIssueType: 'conflict' })] });
  const r = check(config, root);
  assert.deepEqual(r.problems, []);
  assert.ok(r.warnings.some((w) => w.startsWith('demo-thing-archived')));
});

test('a problem URN built by hand fails', () => {
  const { root, config } = product({ java: 'String t = "urn:demo:problem:demo-thing-not-found";' });
  const r = check(config, root);
  assert.ok(r.problems.some((p) => p.includes('by hand')));
});

test('a FHIR issue type that is not in the value set fails', () => {
  const { root, config } = product({ entries: [entry({ fhirIssueType: 'whatever' })] });
  assert.ok(check(config, root).problems.some((p) => p.includes('FHIR issue type')));
});

test('a title with a full stop, or a retryable 404, fails', () => {
  const { root, config } = product({ entries: [entry({ title: 'Thing not found.', retryable: true })] });
  const problems = check(config, root).problems.join('\n');
  assert.match(problems, /no full stop/);
  assert.match(problems, /only 429 and 503/);
});

test('a released code that disappears or changes status fails', () => {
  const gone = product({ entries: [entry({ code: 'demo-renamed' })], java: 'throw new DomRuleViolation(404, "demo-renamed", "x");', baseline: { 'demo-thing-not-found': { status: 404 } } });
  assert.ok(check(gone.config, gone.root).problems.some((p) => p.includes('released, but now missing')));
  const changed = product({ baseline: { 'demo-thing-not-found': { status: 410 } } });
  assert.ok(check(changed.config, changed.root).problems.some((p) => p.includes('released with status 410')));
});

test('the catalogue lists base and product codes and carries the license header', () => {
  const { root, config } = product();
  const { rendered } = check(config, root);
  assert.match(rendered, /`demo-thing-not-found`/);
  assert.match(rendered, /`quota-exceeded`/);
  assert.match(rendered, /Licensed under the Apache License/);
  assert.match(rendered, /urn:demo:problem:quota-exceeded/);
});
