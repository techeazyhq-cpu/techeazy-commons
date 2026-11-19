<!--
  Copyright 2026 Vasantha Kumar

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

  @author Vasantha Kumar <vasantha.kumar@hotmail.com>
-->

# Standard: the error dictionary

Every TechEazyHQ product answers errors the same way, from one dictionary per product, so clients, SDKs and operators learn it once.

## Shape

REST errors are `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)) built by `Problems`:

```json
{ "type": "urn:<product>:problem:quota-exceeded", "code": "quota-exceeded", "title": "Allowance used up",
  "status": 429, "detail": "...", "retryable": true, "correlationId": "..." }
```

FHIR endpoints answer an `OperationOutcome` built by `OperationOutcomes`: `issue.code` is the FHIR issue type from the dictionary and `issue.details.coding` is `urn:<product>:errors` plus the code.

## Layers

1. **Base** (`kit-web`, `META-INF/techeazy/base-error-dictionary.json`): the generic codes every product raises the same way (`malformed-request`, `unauthenticated`, `forbidden`, `not-found`, `conflict`, `quota-exceeded`, `dependency-unavailable`, `internal-error`, and more).
2. **Product** (`META-INF/techeazy/error-dictionary.json` in the product's service module): names the product (`"product": "ogh"`) and adds its own codes. Any module on the class path may contribute one file; a code listed twice, or two different product names, stops the service at start-up.

## Rules

- Codes are lower-case words joined by hyphens, **never renamed or reused once released**. One code has one status.
- Titles are short, have no full stop, never carry personal or clinical data, and are translated by code.
- Status use: 400 malformed, 401 unauthenticated, 403 forbidden, 404 not found, 409 conflict, 422 business-rule failure, 429 rate limit with `Retry-After`, 503 dependency unavailable with `Retry-After` (never used to mean "denied").
- Operational log codes are `TZ-...` (shared) or `<PRODUCT>-...`, in square brackets at the start of a message.

## CI check

`tools/error-dictionary` checks a product's sources against its dictionary: a code raised but not listed, a status that differs from the dictionary's, a hand-built problem URN, a released code renamed or removed (baseline file), a malformed entry, a bad FHIR issue type, and a stale catalogue. A listed code that nothing raises yet is a warning (a failure with `--strict`).
