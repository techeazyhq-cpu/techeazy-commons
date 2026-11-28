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

package com.techeazyhq.kit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A product's one list of errors and operational log codes, layered from the class path: the shared
 * {@code META-INF/techeazy/base-error-dictionary.json} (generic codes every product raises the same way) plus each
 * product module's own {@code META-INF/techeazy/error-dictionary.json}. For each API problem it gives the stable code,
 * status, human title, what it means, what the caller should do, whether trying again can help, and the FHIR issue
 * type. Every service answers problems from it ({@link Problems}), so the same code always carries the same title and
 * status, whichever service raises it.
 *
 * <p>The product file names its product ({@code "product": "acme"}); that name makes the problem type URN
 * ({@code urn:acme:problem:quota-exceeded}) and the FHIR coding system ({@code urn:acme:errors}). Two different product
 * names, or a code listed twice, stop the service at start-up.
 *
 * <p>{@code tools/error-dictionary} in this repository checks a product's source against its dictionary in CI. At
 * runtime an unlisted code still gets an answer (a title made from its name), is logged once, and never breaks a request.
 */
public final class ErrorDictionary {

    /** One line of the dictionary. {@code status} is null for a log code; {@code level} the other way round. */
    public record Entry(String code, String kind, Integer status, String category, String title, String description,
                        String action, boolean retryable, String level, List<String> raisedBy, String fhirIssueType) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ErrorDictionary.class);
    static final String BASE_RESOURCE = "META-INF/techeazy/base-error-dictionary.json";
    static final String PRODUCT_RESOURCE = "META-INF/techeazy/error-dictionary.json";
    static final String DEFAULT_PRODUCT = "techeazy";
    private static final ErrorDictionary INSTANCE = loadAll(ErrorDictionary.class.getClassLoader());

    private final Map<String, Entry> entries;
    private final String product;
    private final Set<String> reportedUnknown = ConcurrentHashMap.newKeySet();

    private ErrorDictionary(Map<String, Entry> entries, String product) {
        this.entries = entries;
        this.product = product;
    }

    /** The product's dictionary, loaded once. */
    public static ErrorDictionary instance() {
        return INSTANCE;
    }

    static ErrorDictionary loadAll(ClassLoader loader) {
        try {
            List<JsonNode> documents = new ArrayList<>();
            URL base = loader.getResource(BASE_RESOURCE);
            if (base == null) {
                throw new IllegalStateException(BASE_RESOURCE + " is missing from the class path");
            }
            documents.add(read(base));
            for (URL url : Collections.list(loader.getResources(PRODUCT_RESOURCE))) {
                documents.add(read(url));
            }
            return parse(documents);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the error dictionaries", e);
        }
    }

    private static JsonNode read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return new ObjectMapper().readTree(in);
        }
    }

    static ErrorDictionary parse(JsonNode root) {
        return parse(List.of(root));
    }

    static ErrorDictionary parse(List<JsonNode> roots) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        String product = null;
        for (JsonNode root : roots) {
            String named = root.hasNonNull("product") ? root.get("product").asText() : null;
            if (named != null && !named.isBlank()) {
                if (product != null && !product.equals(named)) {
                    throw new IllegalStateException("The error dictionaries name two products: " + product + " and " + named);
                }
                product = named;
            }
            for (JsonNode n : root.path("entries")) {
                List<String> raisedBy = new ArrayList<>();
                n.path("raisedBy").forEach(s -> raisedBy.add(s.asText()));
                Entry entry = new Entry(n.path("code").asText(), n.path("kind").asText(),
                        n.hasNonNull("status") ? n.get("status").asInt() : null, n.path("category").asText(),
                        n.path("title").asText(), n.path("description").asText(), n.path("action").asText(),
                        n.path("retryable").asBoolean(false), n.hasNonNull("level") ? n.get("level").asText() : null,
                        List.copyOf(raisedBy), n.hasNonNull("fhirIssueType") ? n.get("fhirIssueType").asText() : null);
                if (entries.put(entry.code(), entry) != null) {
                    throw new IllegalStateException("The error dictionary lists " + entry.code() + " twice");
                }
            }
        }
        return new ErrorDictionary(Collections.unmodifiableMap(entries), product == null ? DEFAULT_PRODUCT : product);
    }

    /** The product's short name, for example {@code acme}. */
    public String product() {
        return product;
    }

    /** The start of every problem type for this product, for example {@code urn:acme:problem:}. */
    public String urnPrefix() {
        return "urn:" + product + ":problem:";
    }

    /** The FHIR coding system for this product's error codes, for example {@code urn:acme:errors}. */
    public String errorSystem() {
        return "urn:" + product + ":errors";
    }

    public Optional<Entry> find(String code) {
        return Optional.ofNullable(entries.get(code));
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    /** The entry, or an {@link IllegalArgumentException} for a code nobody has registered. */
    public Entry require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Not in the error dictionary: " + code));
    }

    /**
     * The title for an API problem. A code that is not listed (the CI check stops that reaching a release) is logged once
     * and gets a title made from its name, so the answer is still a proper problem.
     */
    public String title(String code) {
        Entry entry = entries.get(code);
        if (entry != null) {
            return entry.title();
        }
        if (reportedUnknown.add(code)) {
            LOG.error("[TZ-ERRDICT-UNLISTED] The problem '{}' is raised but not in the error dictionary", code);
        }
        String words = code.replace('-', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    /** Whether trying the same request again later can work. Unlisted: only when the status says so. */
    public boolean retryable(String code, int status) {
        Entry entry = entries.get(code);
        return entry != null ? entry.retryable() : status == 429 || status == 503;
    }

    /** The FHIR issue-type code for a problem (OperationOutcome.issue.code); {@code exception} when it is not listed. */
    public String fhirIssueType(String code) {
        Entry entry = entries.get(code);
        return entry != null && entry.fhirIssueType() != null ? entry.fhirIssueType() : "exception";
    }

    /** The status the dictionary gives a problem, or the one passed when it is unlisted. */
    public int status(String code, int fallback) {
        Entry entry = entries.get(code);
        return entry != null && entry.status() != null ? entry.status() : fallback;
    }
}
