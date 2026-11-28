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
 * Schema-registry style compatibility check for event schemas (JSON Schema), the check that makes "a breaking change fails CI"
 * true for events. Events are kept and replayed (projections are rebuilt from them) and read by consumers that are deployed on
 * their own, so by default the check is FULL: new consumers must read old events (BACKWARD) and old consumers must read new
 * events (FORWARD).
 *
 *   node schema-compat.mjs <released-dir> <current-dir> [--mode backward|forward|full] [--class-key x-ogh-class]
 *   node schema-compat.mjs <released.json> <current.json> ...
 *
 * With directories, every *.json of the released directory must still exist (a removed event schema is breaking) and be
 * compatible; a new file is always fine. Exit 1 with the list of breaking changes, one per line, naming the path in the schema.
 *
 * What "b accepts everything a accepts" means here (a to b is one direction; FULL checks both):
 *  - a property required in b but not in a is breaking (old data lacks it);
 *  - a type changed is breaking, except integer to number (a widening) and a type list that grows;
 *  - an enum that loses a value, or appears where there was none, is breaking; a const that changes is breaking;
 *  - a constraint tightened (minLength up, maxLength down, minimum up, maximum down, minItems up, maxItems down, a pattern or format
 *    added or changed) is breaking;
 *  - additionalProperties going from open to false is breaking;
 *  - anything under oneOf, anyOf, allOf or not that is not identical is treated as breaking: the checker does not guess;
 *  - with --class-key, a property whose data class (the digits at the end, D1 to D4) goes up is breaking in either direction: an
 *    event must not start carrying more sensitive data than the consumers were approved for.
 * It is a conservative check of the shapes events use, not a full JSON Schema reasoner.
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const MIN_KEYS = ['minLength', 'minimum', 'exclusiveMinimum', 'minItems', 'minProperties'];
const MAX_KEYS = ['maxLength', 'maximum', 'exclusiveMaximum', 'maxItems', 'maxProperties'];
const COMBINATORS = ['oneOf', 'anyOf', 'allOf', 'not'];

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const asList = (t) => (t === undefined ? undefined : Array.isArray(t) ? t : [t]);
const classOf = (value) => {
  const m = /(\d+)$/.exec(String(value ?? ''));
  return m ? Number(m[1]) : undefined;
};

/** The ways in which data valid under `a` can be refused by `b`. Empty when `b` accepts everything `a` does. */
function refusals(a, b, path, options, out) {
  if (a === undefined || b === undefined || typeof a !== 'object' || typeof b !== 'object') return;
  for (const key of COMBINATORS) {
    if ((a[key] !== undefined || b[key] !== undefined) && !same(a[key], b[key])) out.push(`${path}: ${key} changed`);
  }
  const ta = asList(a.type);
  const tb = asList(b.type);
  if (ta && tb) {
    const covered = ta.every((t) => tb.includes(t) || (t === 'integer' && tb.includes('number')));
    if (!covered) out.push(`${path}: type ${ta.join('|')} became ${tb.join('|')}`);
  } else if (!ta && tb) {
    out.push(`${path}: a type (${tb.join('|')}) was added where there was none`);
  }
  if (a.const !== undefined && !same(a.const, b.const)) out.push(`${path}: const changed`);
  if (b.const !== undefined && a.const === undefined) out.push(`${path}: a const was added`);
  if (b.enum !== undefined) {
    if (a.enum === undefined) out.push(`${path}: an enum was added where any value was allowed`);
    else for (const value of a.enum) if (!b.enum.some((v) => same(v, value))) out.push(`${path}: enum value ${JSON.stringify(value)} was removed`);
  }
  for (const key of MIN_KEYS) {
    if (b[key] !== undefined && (a[key] === undefined || b[key] > a[key])) out.push(`${path}: ${key} was tightened`);
  }
  for (const key of MAX_KEYS) {
    if (b[key] !== undefined && (a[key] === undefined || b[key] < a[key])) out.push(`${path}: ${key} was tightened`);
  }
  for (const key of ['pattern', 'format']) {
    if (b[key] !== undefined && a[key] !== b[key]) out.push(`${path}: ${key} was ${a[key] === undefined ? 'added' : 'changed'}`);
  }
  if (b.additionalProperties === false && a.additionalProperties !== false) out.push(`${path}: additionalProperties went from open to false`);
  for (const name of b.required ?? []) {
    if (!(a.required ?? []).includes(name)) out.push(`${path}: property ${name} became required`);
  }
  const pa = a.properties ?? {};
  const pb = b.properties ?? {};
  for (const name of Object.keys(pa)) {
    if (pb[name] !== undefined) refusals(pa[name], pb[name], `${path}.${name}`, options, out);
  }
  if (a.items !== undefined && b.items !== undefined) refusals(a.items, b.items, `${path}[]`, options, out);
  if (options.classKey) {
    const ca = classOf(a[options.classKey]);
    const cb = classOf(b[options.classKey]);
    if (ca !== undefined && cb !== undefined && cb > ca) out.push(`${path}: data class went up (${a[options.classKey]} to ${b[options.classKey]})`);
  }
}

/** Breaking changes from `released` to `current`. mode: backward, forward or full (default). */
export function compare(released, current, { mode = 'full', classKey } = {}) {
  const options = { classKey };
  const found = [];
  if (mode === 'backward' || mode === 'full') {
    const out = [];
    refusals(released, current, '$', options, out);
    out.forEach((m) => found.push(`BACKWARD (a new consumer cannot read an old event): ${m}`));
  }
  if (mode === 'forward' || mode === 'full') {
    const out = [];
    refusals(current, released, '$', options, out);
    out.forEach((m) => found.push(`FORWARD (an old consumer cannot read a new event): ${m}`));
  }
  return found;
}

function schemaFiles(dir) {
  return readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
}

function main(argv) {
  const args = argv.filter((a) => !a.startsWith('--'));
  const flag = (name) => {
    const i = argv.indexOf(name);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  const mode = flag('--mode') ?? 'full';
  const classKey = flag('--class-key');
  const [released, current] = args.filter((a) => a !== mode && a !== classKey);
  if (!released || !current || !['backward', 'forward', 'full'].includes(mode)) {
    console.error('usage: schema-compat.mjs <released> <current> [--mode backward|forward|full] [--class-key x-class]');
    return 2;
  }
  const problems = [];
  if (statSync(released).isDirectory()) {
    for (const file of schemaFiles(released)) {
      if (!existsSync(join(current, file))) {
        problems.push(`${file}: the event schema was removed`);
        continue;
      }
      compare(JSON.parse(readFileSync(join(released, file), 'utf8')), JSON.parse(readFileSync(join(current, file), 'utf8')), { mode, classKey })
        .forEach((m) => problems.push(`${file}: ${m}`));
    }
  } else {
    compare(JSON.parse(readFileSync(released, 'utf8')), JSON.parse(readFileSync(current, 'utf8')), { mode, classKey }).forEach((m) => problems.push(m));
  }
  if (problems.length > 0) {
    console.error(`${problems.length} breaking change(s) to the event schemas:`);
    problems.forEach((p) => console.error(`  ${p}`));
    return 1;
  }
  console.log(`The event schemas are ${mode} compatible with the released ones.`);
  return 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
