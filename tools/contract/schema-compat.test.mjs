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

import test from 'node:test';
import assert from 'node:assert/strict';
import { compare } from './schema-compat.mjs';

const base = () => ({
  type: 'object',
  required: ['id', 'tenantId'],
  properties: {
    id: { type: 'string', format: 'uuid', 'x-class': 'D1' },
    tenantId: { type: 'string', format: 'uuid', 'x-class': 'D1' },
    kind: { type: 'string', enum: ['created', 'archived'] },
    count: { type: 'integer', minimum: 0, maximum: 100 },
  },
});

const breaks = (next, options) => compare(base(), next, options);

test('the same schema is compatible', () => {
  assert.deepEqual(breaks(base()), []);
});

test('adding an optional property is compatible in both directions', () => {
  const next = base();
  next.properties.note = { type: 'string' };
  assert.deepEqual(breaks(next), []);
});

test('a new required property breaks the new consumer reading old events', () => {
  const next = base();
  next.properties.note = { type: 'string' };
  next.required.push('note');
  const found = breaks(next);
  assert.ok(found.some((m) => m.startsWith('BACKWARD') && m.includes('property note became required')), found.join('\n'));
});

test('removing a required property breaks the old consumer reading new events', () => {
  const next = base();
  next.required = ['id'];
  delete next.properties.tenantId;
  const found = breaks(next);
  assert.ok(found.some((m) => m.startsWith('FORWARD') && m.includes('tenantId became required')), found.join('\n'));
});

test('removing an optional property is compatible', () => {
  const next = base();
  delete next.properties.count;
  assert.deepEqual(breaks(next), []);
});

test('a changed type is breaking, an integer widened to a number is not', () => {
  const next = base();
  next.properties.id = { type: 'integer' };
  assert.ok(breaks(next).some((m) => m.includes('$.id: type string became integer')));
  const widened = base();
  widened.properties.count = { type: 'number', minimum: 0, maximum: 100 };
  assert.deepEqual(breaks(widened, { mode: 'backward' }), []);
  assert.ok(breaks(widened, { mode: 'forward' }).some((m) => m.includes('type number became integer')));
});

test('losing an enum value breaks backward, gaining one breaks forward', () => {
  const lost = base();
  lost.properties.kind.enum = ['created'];
  assert.ok(breaks(lost).some((m) => m.startsWith('BACKWARD') && m.includes('"archived" was removed')));
  const gained = base();
  gained.properties.kind.enum = ['created', 'archived', 'deleted'];
  assert.ok(breaks(gained).some((m) => m.startsWith('FORWARD') && m.includes('"deleted" was removed')));
  assert.deepEqual(breaks(gained, { mode: 'backward' }), []);
});

test('a tightened constraint breaks backward, a loosened one breaks forward', () => {
  const tight = base();
  tight.properties.count.maximum = 50;
  assert.ok(breaks(tight, { mode: 'backward' }).some((m) => m.includes('maximum was tightened')));
  const loose = base();
  loose.properties.count.maximum = 200;
  assert.deepEqual(breaks(loose, { mode: 'backward' }), []);
  assert.ok(breaks(loose, { mode: 'forward' }).some((m) => m.includes('maximum was tightened')));
});

test('closing the schema to extra properties is breaking', () => {
  const next = base();
  next.additionalProperties = false;
  assert.ok(breaks(next).some((m) => m.includes('additionalProperties went from open to false')));
});

test('a changed format is breaking', () => {
  const next = base();
  next.properties.id.format = 'date-time';
  assert.ok(breaks(next).some((m) => m.includes('format was changed')));
});

test('a changed oneOf is not guessed at', () => {
  const a = { oneOf: [{ type: 'string' }, { type: 'integer' }] };
  const b = { oneOf: [{ type: 'string' }] };
  assert.ok(compare(a, b).some((m) => m.includes('oneOf changed')));
  assert.deepEqual(compare(a, a), []);
});

test('nested objects and arrays are compared', () => {
  const a = { type: 'object', properties: { items: { type: 'array', items: { type: 'object', required: ['a'], properties: { a: { type: 'string' } } } } } };
  const b = structuredClone(a);
  b.properties.items.items.properties.a.type = 'integer';
  assert.ok(compare(a, b).some((m) => m.includes('$.items[].a: type string became integer')));
});

test('with a class key, an event that starts carrying more sensitive data is breaking', () => {
  const next = base();
  next.properties.id['x-class'] = 'D3';
  assert.deepEqual(breaks(next), []);
  const found = breaks(next, { classKey: 'x-class' });
  assert.ok(found.some((m) => m.includes('$.id: data class went up (D1 to D3)')), found.join('\n'));
  const lowered = base();
  lowered.properties.id['x-class'] = 'D0';
  assert.ok(breaks(lowered, { classKey: 'x-class' }).some((m) => m.includes('went up (D0 to D1)')), 'forward: old consumers');
});
