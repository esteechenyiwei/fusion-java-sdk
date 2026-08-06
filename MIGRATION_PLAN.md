# Fusion Java SDK — Java 8 → Java 17 Migration Plan

**Repo:** `esteechenyiwei/fusion-java-sdk` (`io.github.jpmorganchase.fusion:fusion-sdk`)
**Branch:** `migration` (all work isolated from `main`)
**Constraint:** the SDK's **public API and observable behaviour must not change**. Downstream consumers must be able to swap the jar in without touching their code.

---

## 0. Executive summary

| | Before | After |
|---|---|---|
| Language / bytecode level | Java 8 (`source/target 1.8`, class file 52) | Java 17 (`maven.compiler.release=17`, class file 61) |
| Minimum consumer JDK | 8 | **17** (breaking for the consumer's *runtime*, not their *source*) |
| HTTP transport | `HttpURLConnection` (`JdkClient`) | `java.net.http.HttpClient` (JEP 321), same `Client` interface |
| Dependencies pinned "for JDK 8" | logback 1.3.x, spotless 2.30.0, wiremock-jre8, mockito 4.x, JUnit 5.9 | current, supported versions |
| CI | `setup-java` with `java-version: 8` | `java-version: 17` |

The migration is split into **4 phases ordered by effort-vs-impact**. Every phase ends with a green `mvn -B clean verify` (spotless check → 352 unit tests → PIT mutation ≥85% → package → integration test), so the SDK is releasable at the end of *each* phase and can be stopped/shipped at any phase boundary.

---

## 1. Architecture of the codebase

### 1.1 Component diagram

```mermaid
graph TD
    subgraph Consumer["Downstream consumer application"]
      APP[Client code]
    end

    APP -->|"Fusion.builder()...build()"| F[Fusion<br/><i>public façade / orchestrator</i>]

    F --> CFG[FusionConfiguration<br/><i>Lombok @Builder, defaults,<br/>credential file resolution</i>]
    F --> B[builders/<br/>APIConfiguredBuilders<br/><i>fluent model builders</i>]
    F --> AM[api/APIManager<br/>FusionAPIManager<br/><i>call/list/create/update</i>]

    AM --> OPS[api/operations/<br/>FusionAPIDownloadOperations<br/>FusionAPIUploadOperations<br/><i>multi-part transfer, thread pools</i>]
    AM --> PARSE[parsing/<br/>GsonAPIResponseParser<br/><i>JSON → model, varArgs</i>]
    AM --> SER[serializing/<br/>GsonAPIRequestSerializer<br/><i>model → JSON, adapters</i>]

    OPS --> REQ[api/request/<br/>PartFetcher, CallablePart(s),<br/>Up/DownloadRequest]
    OPS --> STREAM[api/stream/<br/>DeferredMultiPartInputStream<br/>IntegrityCheckingInputStream]
    OPS --> DIG[digest/<br/>AlgoSpecificDigestProducer<br/>checksum/ CRC32·CRC32C·CRC64NVME<br/><i>AWS-CRT native</i>]

    AM --> HTTP[http/Client<br/><i>interface</i>]
    HTTP --> JDKC[http/JdkClient<br/><b>HttpURLConnection</b> ← Phase 3]
    HTTP --> HRESP[http/HttpResponse&lt;T&gt;<br/>HttpConnectionInputStream]

    AM --> OAUTH[oauth/<br/>provider/ · retriever/ · credential/ · model/<br/><i>session + dataset bearer tokens, expiry</i>]
    OAUTH --> HTTP
    OAUTH --> TIME[time/TimeProvider]

    AM --> MODEL[model/<br/>Catalog · Dataset · DatasetSeries ·<br/>Distribution · Attribute(s) · Flow ·<br/>DataProduct · Lineage · CatalogResource]
    MODEL --> F

    JDKC --> API[("J.P. Morgan<br/>Fusion REST API")]
```

### 1.2 Layer responsibilities

| Package | Responsibility | Migration exposure |
|---|---|---|
| `Fusion`, `FusionConfiguration` | Public entrypoint + config. **Frozen API surface.** | Low — must not change |
| `builders/` | Fluent builders returned to consumers | Low |
| `model/` | Domain POJOs (Lombok `@Value`/`@Builder`), `varArgs` dynamic attributes | Low; `record` conversion deliberately **out of scope** (Lombok + Gson + API compat) |
| `api/` + `api/operations/` + `api/request/` | Call orchestration, multi-part up/download, executor pools | Medium — executors, `Callable`s |
| `api/stream/`, `digest/` | Streaming integrity checks, SHA-256 digest-of-digests, AWS-CRT checksums | Medium — AWS-CRT native lib must be exercised on 17 |
| `http/` | `Client` SPI + `JdkClient` (`HttpURLConnection`) | **High — Phase 3 rewrite** |
| `oauth/` | Token lifecycle, credentials, expiry | Medium — consumes `http/` |
| `parsing/`, `serializing/` | Gson ↔ model | Low, but Gson + JPMS reflection needs a check |

### 1.3 Build / quality gates (must all stay green)

`spotless:check` (palantir-java-format) → `surefire` (352 unit tests, `integration` group excluded) → `pitest` mutation coverage, **threshold 85%** → `javadoc` + `sources` jars → `failsafe` integration test (`src/it`, packaging/manifest assertions) → `central-publishing` / `maven-release` on `release/*` branches.

---

## 2. Baseline (Phase 0) — **DONE**

**What:** build the untouched `main` code with a real JDK 8 (`temurin/openjdk-8`) and with the JDK 17 toolchain at `source/target 1.8`.

**Result:** `mvn -B clean verify` on **JDK 8 → BUILD SUCCESS**, 352 unit tests + 1 integration test pass, mutation threshold met. Pre-existing non-blocking issues flagged: 100 javadoc "no comment" warnings; PIT minions occasionally `TIMED_OUT`; `wiremock-jre8` and `logback 1.3.x` explicitly pinned to keep JDK 8 support.

**Why it matters — engineer:** without a recorded green baseline you cannot attribute a later failure to the migration vs. a pre-existing flake. **Business:** it is the evidence that "the SDK behaved like *this* before we touched it" — the control group for the behaviour-preservation claim we make to consumers.

---

## 3. Phase 1 — Toolchain flip (low effort / highest impact)

| Step | Change | Why it matters |
|---|---|---|
| 1.1 | `maven.compiler.source/target 1.8` → **`maven.compiler.release=17`** | `release` (vs `source`/`target`) also validates against the *Java 17 API signatures*, so you cannot accidentally compile code that links against APIs absent from the target JDK. **Business:** produces class-file 61 bytecode, the prerequisite for consumers on modern, *supported* JVMs. |
| 1.2 | GitHub Actions `build.yml` + `release.yml`: `java-version: 8` → `17` | **Engineer:** CI is the enforcement point; without it, main can silently regress. **Business:** the released artifact on Maven Central is built by the same JDK the consumers run — no "works on my machine" release risk. |
| 1.3 | Drop Java-8-only pins: `logback-classic` 1.3.12 → 1.5.x, `spotless-maven-plugin` 2.30.0 → 2.4x (remove the "later versions require Java > 8" comment), `wiremock-jre8` → `wiremock-standalone`/`wiremock` 3.x, `mockito` 4.x → 5.x, `junit-jupiter` 5.9.2 → 5.11+, `pitest` 1.9.8 → 1.17+ with a matching `pitest-junit5-plugin` | **Engineer:** those versions were frozen years ago solely because of JDK 8; they carry known CVEs and cannot see modern bytecode. Mockito 4 / PIT 1.9 literally fail or mis-instrument class-file 61. **Business:** removes stale-dependency audit findings — for a JPMC-published SDK this is a security-review gate, not a nicety. |
| 1.4 | Re-run full `mvn clean verify`; confirm 352/352 + PIT ≥85% | Proves the flip is behaviour-neutral before any source is touched. |

**Risk:** low. **Rollback:** revert one commit.

---

## 4. Phase 2 — Language & API modernization (medium effort / medium impact)

Purely internal; **no signature changes**.

| Step | Change | Why it matters |
|---|---|---|
| 2.1 | **Switch expressions / arrow labels** (JEP 361) in `APICallException.getMessage()`, `DigestProviderService`, `OAuthTokenRetriever.retrieve()` | **Engineer:** eliminates the whole class of fall-through bugs and, for the enum switch in `OAuthTokenRetriever`, gives *compile-time exhaustiveness* — adding a new credential type becomes a compile error instead of a runtime surprise. **Business:** a missed auth branch is a production auth outage; the compiler now catches it. |
| 2.2 | **`java.net.URI` over the deprecated `new URL(String)`** in `APIManager` / `JdkClient` (`URL(String)` constructors are deprecated for removal since Java 20) | **Engineer:** removes deprecation debt now, so the *next* LTS hop (21/25) is not blocked. **Business:** `URI` parsing is stricter and rejects malformed endpoints early — better error messages instead of an obscure IO failure mid-transfer. |
| 2.3 | **Immutable collection factories** `List.of` / `Map.of` / `Collectors.toUnmodifiableList()` replacing `Collections.unmodifiableList(Arrays.asList(...))`, and `Map.copyOf` for defensive copies in `VarArgsHelper` / providers | **Engineer:** less allocation, truly immutable, intent is explicit. **Business:** genuinely immutable model objects are safe to share across the SDK's own upload/download thread pools — removes a latent concurrency-corruption class. |
| 2.4 | **`var` for obvious local inference**, text blocks for multi-line JSON in tests, enhanced `instanceof` pattern matching | Readability only; lowers the cost of every future change. Applied conservatively — never where it hides a type. |
| 2.5 | **`InputStream.readAllBytes()` / `transferTo()`** replacing hand-rolled 8 KB copy loops (`JdkClient.executeRequestWithBody`, digest paths) | **Engineer:** deletes hand-written loops that mutation tests have to cover anyway. **Business:** the JDK intrinsics are faster and better tested than our copy loop — direct win on large-file distribution transfers. |
| 2.6 | `spotless:apply` + full `verify` after **each** sub-step | Formatting is a build gate (`spotless:check` in `process-sources`); catching it per-step keeps diffs reviewable. |

**Risk:** low–medium (mechanical, test-covered). **Rollback:** per-step commits.

---

## 5. Phase 3 — `HttpURLConnection` → `java.net.http.HttpClient` (high effort / high impact)

**Scope:** rewrite the body of `http/JdkClient` only. `http/Client`, `http/HttpResponse<T>`, `ClientException`, and `JdkClient.builder()` (including the `url`/`port`/`noProxy` proxy builder) **keep identical signatures and semantics**.

| Step | Change |
|---|---|
| 3.1 | Build one shared `java.net.http.HttpClient` per `JdkClient` (`ProxySelector` from the existing `Proxy` config, `HTTP_1_1` to preserve current wire behaviour) |
| 3.2 | Map each verb to `HttpRequest` + `BodyPublishers` (`noBody`/`ofString`/`ofInputStream`); `DELETE`-with-body via `.method("DELETE", …)` |
| 3.3 | Response mapping: `BodyHandlers.ofString(UTF_8)` for `String`, `BodyHandlers.ofInputStream()` for the streaming `getInputStream` path; populate `HttpResponse.headers` from `HttpHeaders.map()` |
| 3.4 | **Behaviour parity guards:** on non-2xx, `HttpURLConnection` returned the *error* stream and the SDK's `DEFAULT_ERROR` JSON when it was null — replicate exactly; keep the header map case-insensitive as before; keep `ClientException` wrapping for every IO/interrupt failure |
| 3.5 | Run `JdkClientTest` (WireMock, incl. proxy tests) + the OAuth and up/download suites unchanged — **the existing tests are the parity oracle; they are not modified** |

**Why it matters — engineer:** `HttpURLConnection` is a 1996-era API with no timeouts by default, no HTTP/2, awkward error-stream handling, and connection-leak footguns; it is also the single biggest blocker to ever adopting async or HTTP/2. `java.net.http` is the supported, actively-maintained JDK client. **Business:** an explicit connect/request timeout means a hung Fusion endpoint can no longer wedge a consumer's thread indefinitely — today the default is *wait forever*. It also opens the door to HTTP/2 multiplexing for large multi-part distribution downloads.

**Risk: highest in the plan** — this is the one step where behaviour can drift. Mitigations: no test file is edited; contract (Pact) and WireMock suites must be byte-identical green; the change is isolated to one class so it can be reverted independently of Phases 1–2.

---

## 6. Phase 4 — Release engineering & validation

| Step | Change | Why |
|---|---|---|
| 4.1 | `mvn -B clean verify` → `target/fusion-sdk-<version>.jar` + `-sources` + `-javadoc` + `-pact` jars | The releasable artifact set, identical in shape to today's release. |
| 4.2 | Verify `Build-Jdk`/`Created-By` in the jar manifest and `javap -verbose` major version = **61** | Objective proof the bytecode actually moved, not just the pom. |
| 4.3 | Push `migration`, confirm the **Build** GitHub Action is green on JDK 17 | CI is the shared source of truth for reviewers. |
| 4.4 | PR into `main` documenting the consumer-facing minimum-JDK change | The compatibility contract has to be written down where consumers look. |

---

## 7. Downstream compatibility implications

**Not breaking (source & binary compatible):**
- Every public type, method signature, builder, and exception is unchanged. Consumer *source code* compiles untouched.
- JSON wire format, request/response semantics, OAuth flows, digest algorithms, multi-part chunking: unchanged.

**Breaking (runtime):**
- **A consumer running JDK 8/11 cannot load class-file 61.** They get `UnsupportedClassVersionError` at load time. This is *the* migration cost and must be communicated loudly.
- Anything still on JDK 8 must stay on the last Java-8 release line (`0.0.18`/`0.0.19`) until they upgrade their JVM.

**Recommendation:** publish this as a **minor/major version bump with a clearly documented minimum-JDK-17 requirement**, keep the last Java-8 line available on Maven Central (it is immutable there anyway), and state a support window for it.

---

## 8. Risks & constraints of Java 17

| Risk / constraint | Impact here | Mitigation |
|---|---|---|
| **Strong encapsulation of JDK internals** (JEP 403, enforced since 16) — reflective access to `sun.*`/`jdk.internal.*` now throws | Gson reflects over our model POJOs (our own classes → fine), but transitive libs can trip on this | Full test suite + Pact contract tests exercise the real serialization paths |
| **JPMS / module path** — consumers on the module path need our jar to be a well-behaved automatic module | We ship no `module-info.java`; the automatic module name derives from the jar name | Follow-up: add an explicit `Automatic-Module-Name` manifest entry, then a real `module-info` later |
| **AWS-CRT native library** (`aws-crt` 0.39.4) — JNI, platform-specific `.so`/`.dylib` | Used for CRC32C/CRC64NVME checksums; JNI + JDK 17 needs verification on every target OS | Covered by `digest/checksum` tests in CI; recommend a matrix build (linux/mac/win) as follow-up |
| **Security Manager deprecated for removal** (JEP 411) | Not used by this SDK, but a *consumer* running under one may see warnings | Note in release comms |
| **`--illegal-access=deny` default & removed flags** | Older agents/instrumentation (some APM agents) may need upgrading in consumer runtimes | Call out in the consumer note |
| **Default charset is UTF-8 (JEP 400, Java 18+)** | Not yet at 18, but code should never rely on the platform default | We already pass `StandardCharsets.UTF_8` explicitly everywhere |
| **PIT mutation threshold 85%** is a hard gate | New/rewritten code that is under-tested will *fail the build*, not warn | Keep changes test-covered; PIT run per step catches it immediately |
| **Toolchain drift** — devs still on JDK 8 locally | `mvn` will fail with a clear "release 17 not supported" message | Document required JDK in README; consider `maven-enforcer-plugin` requireJavaVersion |

---

## 9. Follow-up migration tasks (not in this PR)

1. **`Automatic-Module-Name`** manifest entry, then a real `module-info.java` (JPMS-clean consumers).
2. **Records for the `model/` layer** — a large, API-visible refactor; needs a Lombok/Gson strategy and a compatibility review of its own.
3. **`Sealed` interfaces** for the credential/`Client` hierarchies to make the switch exhaustiveness in Phase 2 total.
4. **HTTP/2 + async** (`sendAsync`, `CompletableFuture`) for multi-part transfers — now unlocked by Phase 3; measure before adopting.
5. **Virtual threads** for the up/download executor pools (requires the next hop to **Java 21 LTS**) — this SDK's fan-out I/O is close to the ideal workload.
6. **Cross-platform CI matrix** (linux/macos/windows × JDK 17/21) to de-risk the AWS-CRT native dependency.
7. **Replace the deprecated `finalize`-style/legacy patterns** and adopt `Objects.requireNonNull` consistently.
8. **Plan the Java 21 hop now** — 17 is already mid-life; treat this as step one of a rolling-LTS policy, not a one-off.

---

## 10. Next steps for the engineer (release & rollout)

**Before merge**
1. Get the PR reviewed with an explicit reviewer sign-off on `JdkClient` (the only behavioural-risk area).
2. Run the SDK against a **real Fusion sandbox/UAT tenant** — the suite is WireMock/Pact based, so a live smoke test of download + multi-part upload + token refresh is the missing coverage.
3. Confirm the AWS-CRT checksums on every OS your consumers actually run.

**Release strategy (safest rollout)**
1. Merge to `main`; publish a **`-SNAPSHOT` / release-candidate** first (e.g. `0.1.0-RC1`) rather than going straight to GA.
2. **Version signal:** bump the **minor or major** version, never a patch. A patch bump that silently raises the minimum JDK is how you break a consumer's build at 2am.
3. **Canary:** onboard 1–2 friendly internal consumers already on JDK 17 onto the RC for a full business cycle (including a large multi-part upload and a token-expiry window).
4. **GA** via the existing `release/*` branch → `maven-release-plugin` → Maven Central path, with `-Psign-artifacts,no-mutation` as today.
5. **Keep the last Java 8 line published** and state how long it will receive security fixes (recommend one to two quarters, security-only).
6. Update the README with the minimum-JDK requirement **in the same release**.

**What to communicate to consumers (release note skeleton)**
> - **Action required:** from `vX.Y.0`, fusion-sdk requires **Java 17 or later** at runtime. On Java 8/11 you will get `UnsupportedClassVersionError`.
> - **No code changes required:** the public API is unchanged — upgrade the dependency version and your JDK, nothing else.
> - **Staying on Java 8?** Pin to `0.0.x`, which remains on Maven Central and will receive security-only fixes until `<date>`.
> - **Under the hood:** HTTP transport now uses the modern JDK HTTP client (adds request timeouts; no API change).
> - **Support:** `<team channel>`, and a deprecation date for the Java 8 line.

Send this to consumers **at RC time, not at GA** — they need lead time to schedule their own JVM upgrade.
