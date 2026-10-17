# A/B/C Review Repairs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Repair confirmed defects in the existing A/B/C backend, integrate main and feature/opportunity, verify the combined result, and fast-forward both remote branches without losing either history.

**Architecture:** Retain Java 21, Spring Boot 4.1.1, Maven, JDBC, MySQL, Redis and the current package layout. Repair behavior at its owning service and keep server-authoritative permissions, immutable input evidence, transactional state transitions and minimum database privileges. Do not implement unrelated planned modules or fake unavailable integrations.

**Tech Stack:** Java 21; Spring Boot 4.1.1; Maven; MySQL 8.x/Flyway; Redis; existing S3/ClamD and DeepSeek adapters.

**Spec:** `campus-backend-api-v1.1-reviewed.md`, `DATABASE.md`, the user's supplied AGENTS.md, and the approved repair findings in `C:/Users/19683/Documents/材智通前后端对齐审查报告-2026-10-15.md` and `C:/Users/19683/Documents/材智通-feature-opportunity-复审报告-2026-10-15.md`.

## Global Constraints

- User approved an isolated worktree and updating both main and feature/opportunity to one integrated result; no force push, reset, destructive overwrite, or loss of concurrent changes.
- Work exclusively in `C:/Users/19683/source/repos/caizhitong-abc-repairs` on `fix/abc-integration-20261015`; the original checkout stays unchanged.
- Preserve existing frameworks, dependencies, route paths and naming. A necessary response fix must update its consumers/tests and API documentation.
- No API keys, credentials, cookies, private keys or real student data in source, output or commits. Do not read or modify the user's MySQL84/3306 data. Do not grant the business account SELECT on pseudo_identities or access to security_investigations.
- Preserve V1–V15 migration files byte-for-byte; only append migrations. Reserve V16 for A, V17 for B, V18 for C privacy, V19 for C workflow. Test schemas may change to mirror new columns/constraints.
- TDD is required: add a behavior regression, run it and record the expected failure, implement the minimal correction, then record the passing test. No assertions on source text or implementation-only constants.
- Use portable JDK `C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1` and Maven `C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd`; set JAVA_HOME and MAVEN_OPTS only in the command process.
- Full verification command: `mvn -B -ntp -f backend/pom.xml test`; distinguish passed, skipped, failed and unverified live services. A mock is not real WeChat, DeepSeek, MySQL or storage acceptance.
- Implementers do not spawn other agents, edit another task's report, merge/push, switch branches or delete the worktree. Commit only their scoped files after validation; controller owns final integration and publishing.

## Setup already performed

The native worktree tool could not locate a Git project in this chat; user-approved Git fallback created the worktree above. Merge `347adc0` combines main `6e87d51` and opportunity `6e56062`. Both sides' SessionHttpBoundaryTest setup was preserved. implementation-status.md now marks integrated modules as under repair rather than finished. No remote update has occurred.

## Task 1: A — immutable prechecks and trustworthy notice import

**Files:** Modify `backend/src/main/java/com/caizhitong/material/{checklist,precheck}/`, `opportunity/OpportunityInputs.java`, `OpportunityViews.java`, `admin/opportunity/{AdminNoticeService,AdminOpportunityService}.java`, `ai/notice/{NoticeParseSchema,NoticeParseWorker}.java`, `integration/{notice/SafeUrlReader,ai/DeepSeekClient}.java` only where required. Tests: existing OpportunityHttpTest, SafeUrlReaderTest, DeepSeekClientTest, MaterialPrecheckRulesTest and focused new test files in the same packages. Migration if needed: `sql/V16__opportunity_review_repairs.sql`. Update `docs/backend/opportunity-module.md` and relevant A sections of the API doc.

**Consumes:** Existing material_versions attachment_id/object_key_encrypted, clean file_attachments, BusinessWrite transaction boundary, async_jobs lease fencing, CurrentActor and signed cursors.

**Produces:** Existing 28 A routes keep their contract; tests can prove selected immutable versions are read, stale work cannot affect current state, compressed original text is complete, and parsing failures are recoverable.

- [ ] Step 1 — Reproduce R-01/R-02 using two different valid PDF objects: bind v1, append v2, enqueue v1 and assert the result's evidence/object belongs to v1; enqueue a missing-item checklist v0, bind v1 to make checklist v1, run old work, assert current item stays uploaded and history is stale.

```java
// Literal behavior expectations, not a mirrored implementation.
assertEquals("uploaded", currentChecklist.items().getFirst().status());
assertTrue(oldResult.stale());
assertEquals("601", singleResult.path("materialVersionId").asString());
```

- [ ] Step 2 — Run these focused tests to RED. Correct worker/file metadata reads to use the immutable version's own clean attachment/object/hash, not material_files' latest object. Snapshot the relevant immutable facts. In completion, lock parent and compare checklist version before applying current item state; stale tasks retain only their own results. Multiple tasks for the same current version must not let an older task overwrite a later completed result. Verify consent and ownership where processing sensitive files; pending/infected files must never be read for precheck.
- [ ] Step 3 — Add and run RED tests for identity, gzip with compressed Content-Length, plain chunked, chunked+gzip and deflate; original text must equal a literal repeated string, not merely be nonempty. Fix transfer decoding before content decoding, enforce compressed/decompressed size and overall read deadline, reject truncation/malformed framing. Set read timeout before TLS handshake and maintain shared deadline across redirects. Preserve pinned DNS/TLS hostname validation and never follow a redirect to forbidden addresses. Reuse existing libraries; no new dependency or production test bypass.

```java
assertEquals("notice fixture ".repeat(200), importedText);
assertEquals(400, invalidCreateStatus);
```

- [ ] Step 4 — Add and run RED test for the final parse lease expiring after a crash: ai_parse_tasks and receipt must fail, latest notice must return to a retryable/manual state, and an older generation must not reset a newer one. Correct recovery atomically and ensure failure does not leave visible prechecking/parsing state stuck. Test the real empty-key DeepSeek adapter exception passing through worker; expose `AI_KEY_MISSING`, never `AI_AI_KEY_MISSING`. Close HTTP response streams on non-200 paths and bound body reading, not only header acquisition.
- [ ] Step 5 — Add and run RED schema/input tests: missing/null/non-string evidence is not a valid supported extraction; category and version are required for write inputs; malformed booleans and arrays return 400 before DB access. Keep human review and existing three-way eligibility behavior.
- [ ] Step 6 — Preserve administrator-confirmed notice text rather than silently losing it on the road to publication. Free-text conditions/materials must be visibly available in the review path and publication must require corresponding explicit structured rules/material requirements when confirmed text indicates they exist. Do not invent a parser that treats one arbitrary uploaded file as satisfying a multi-document list. Test confirmed materials/conditions followed by publishing an empty draft is rejected until explicitly resolved by human structured editing. Keep draft-only review and separate publication permission.
- [ ] Step 7 — Implement the existing `validationRules` requirement with a small explicit, bounded schema: optional `mimeTypes` (subset of existing allowed formats), `maxSizeBytes` (positive <=20 MiB), `fileNameContains` (1..128 chars); reject unknown keys and wrong types. Persist/copy rules into opportunity requirements, immutable rule snapshots, created checklist items and precheck input snapshots. Apply per-item checks to the selected version, preserve passed/confirm/missing findings and basicComplete. Existing missing rules remain generic basic checks. Add V16 columns only if needed; do not edit earlier migrations. Document the exact schema and add tests proving a configured PDF-only/max-size/name rule cannot be silently ignored.
- [ ] Step 8 — Run focused tests, then full suite once, self-review the diff, commit scoped changes, write the task report with RED/GREEN evidence and exact counts. Do not claim real model/TLS service/MySQL testing from fakes.

## Task 2: B — services, issues and reservations

**Files:** `backend/src/main/java/com/caizhitong/{resource,issue,workorder}/`; `backend/src/test/java/com/caizhitong/service/ServiceHttpTest.java` and focused package tests; `backend/src/test/resources/service-schema.sql`; migration `sql/V17__service_review_repairs.sql` if needed. Relevant API doc sections 8, 9, 16.4. Shared source-fields/attachment contracts may be used but do not edit C services.

**Consumes:** A integration, BusinessWrite transaction locking and idempotency, ownership-checked attachments and current session campus. Inspect actual SQL V3, V12, V13 before changes.

**Produces:** Implemented B endpoints obey current contracts, including public visibility, stable DTO shapes/source fields, real filters and valid state machines. Future endpoints remain out of scope.

- [ ] Step 1 — Add and observe RED tests for BIGINT UNSIGNED Map values using real/representative BigInteger results, cross-campus/inactive/deleted resource reads, private/nonpublic issues and evidence reads, invalid date/bbox/limit/filter input. Replace unsafe casts at the data boundary (Number/getLong), not by weakening database types or catching all exceptions as 404. Not-found data must return 404 instead of EmptyResult/500.
- [ ] Step 2 — Repair reservation transitions: a completed/checked_in/cancelled/rejected record cannot be approved again; approval/rejection only valid pending states; occupancy remains unique and transactional. Reserve must respect active resource, slot date/time/status and explicit server-controlled reservationMode, not infer mode from a missing capacity. One-time legacy backfill may preserve prior slot-capacity-derived behavior, but the old algorithm used resource_slots.capacity, not resources.capacity. Do not delete/reseat existing occupancy or reservations. Mixed legacy semantics or incompatible occupied seat numbers must block new booking with an explicit configuration error until safely repaired, not guess or appear unoccupied. Test a resource with nonnull parent capacity but null slot capacity and occupied seat0. Capacity edits must not invalidate occupied seats or switch mode with occupancy. Test simultaneous attempts for the last seat, cancel/retry, existing occupied slots and overlap policy from the API. Default missing slot capacity consistently with the explicitly configured parent capacity or reject ambiguous configuration. Preserve true source fields: never label a manual/official booking demo to auto-confirm; demo may auto-confirm, manual confirmation returns pending with bounded persisted expiry and release, unavailable official integration fails closed. Existing pending and demo behavior are being repaired, not a new official provider implementation.
- [ ] Step 3 — Fix check-in token validation so an invalid token/version/state cannot consume another legitimate token; validate state and version before one-time consumption, bind token to reservation/campus and keep replay rejection. A narrow option that avoids changing the shared OneTimeSecrets interface is to key the claim/consume digest by reservation+campus+submitted random token, store a fixed marker, and consume only after the locked DB validation; a wrong token then addresses a different key. For the existing demo-token flow, allow issue/use only during slot start <= campus local now < slot end, using the root API's first-release Asia/Shanghai timezone; TTL is at most five minutes and never extends beyond slot end. Do not invent an early/late grace period or bypass formal rules with a demo token. Document this previously unspecified policy and test the time boundaries. Do not log token values. Add wrong-token-then-correct-token and replay tests.
- [ ] Step 4 — Implement all exposed dateFrom/dateTo/resourceId/regionId filters and bind actual effective filters in signed cursors; validate ranges. Both public list and detail/slots must apply the same resource/issue visibility. Default tourist campus must match the root API. No date parse exception should become 500.
- [ ] Step 5 — Repair issue visibility and transitions at service boundary: follow/vote/evidence cannot access an invisible target; private content stays owner/authorized staff only; required human moderation evidence persists. Lock target before aggregate counter writes; count votes with documented semantics. Validate state transitions instead of allowing arbitrary status changes. Map rendering accepts only finite, strictly ordered, valid coordinates and limit 1..200. Enforce the required maximum bbox area via positive finite server-controlled `app.issue-map.max-area-km2` (environment `APP_ISSUE_MAP_MAX_AREA_KM2`), default 100 km²; use a spherical rectangle calculation, reject larger requests with 400 and test a configured smaller limit. This is a configurable query-size bound, not a fabricated school geographic boundary. Campus isolation and result limits still apply with no bbox; document the default/setting and the lack of a school boundary model.
- [ ] Step 6 — Align existing B DTOs to API minimal fields, flat detail payloads, sourceFields, resource/slot/reservation composition and remaining capacity. Expose attachment metadata only with safe linked-read policy; do not make buckets public. Equipment/region inputs must be validated and persisted rather than silently ignored. Work orders must persist an auditable approval/awaiting acceptance stage and only their owner can rate once at awaiting_rating; validate assignee identity/campus and maintain event history.

```java
assertEquals(404, crossCampusStatus);
assertEquals("awaiting_rating", completedWorkOrder.serviceStage());
assertEquals(1, finalSeatSuccessCount);
assertEquals(409, replayCheckInStatus);
```

- [ ] Step 7 — Run focused tests, full suite, inspect generated SQL against MySQL schema, update B contract documentation if any formerly ignored optional input is explicitly unsupported, commit only B changes and record RED/GREEN evidence. Any speculative finding must be verified before changing behavior.

## Task 3: C privacy — read boundaries and anonymous identity isolation

**Files:** mutualaid ratings/public detail mapping; community service/DTO and new narrow anonymous ownership/investigation components; moderation governance services/controllers; attachment linked-read policy implementations; CurrentActor call sites only as needed; GlobalExceptionHandler privacy-safe unexpected-error logging. Tests: CommunityHttpTest, focused privacy/permission and log-capture tests, schema community-schema.sql. Config: dedicated investigation configuration, deployment prerequisite docs and DATABASE.md grants. Migration `sql/V18__community_privacy_repairs.sql`.

**Consumes:** Existing documented separation: application may INSERT pseudo_identities but never SELECT it; investigator alone may SELECT mapping and append security_investigations. Preserve business/migration/investigator account separation.

**Produces:** Normal business responses never expose real anonymous authors/private proofs; owner maintenance works under documented minimum privileges; privileged investigation is campus scoped, uses X-Reauth-Token and a separately configured connection, and is audited before revealing identity.

- [ ] Step 1 — Add RED regressions for anonymous/cross-campus/nonpublic ratings, privateProofHint in every public response, unverified community reads and comments on restricted/deleted parents. Make ratings inherit the exact post visible-to-reader policy; strip private proof from public DTOs rather than deleting stored evidence; enforce S reads and parent visibility for comments.
- [ ] Step 2 — Replace business SELECT on pseudo_identities with a narrow controlled ownership mechanism. Preferred minimal MySQL implementation: SQL SECURITY DEFINER routines accepting campus, target and keyed owner-search proof, returning only ownership boolean or the caller's post IDs, never mapping/user IDs; application gets EXECUTE on those named routines, not SELECT. Scope every lookup to campus; bind keyed proofs server-side to current actor. Production cannot fall back to unrestricted JDBC. Test implementation belongs only in src/test; deployment must document routine ownership/creation grants and exact execution grants. Use V18 and real MySQL testing before calling this closed.
- [ ] Step 3 — Separate investigation connection via explicit qualified gateway and transaction manager; missing credentials fail closed with 503, never use the business connection. Ensure secondary data source cannot make Spring Boot drop its primary application data source. Validate permission, target type, target campus, optional report relationship and X-Reauth-Token before decryption; append investigation audit in the dedicated transaction before returning the identity. No broad mapping query. Test business account denial and investigator minimal access; no real credentials in committed config/examples.
- [ ] Step 4 — Scope penalties/lifts/appeal/credit-dispute handling to target campus, preserve same-campus audit/reasons and state validation. Fix unsafe Number casts. PUT blocks with documented no body must work with ordinary idempotency headers. Comment DTO must include documented postId/canDelete without stable real identity.
- [ ] Step 5 — Supply narrow attachment read policies for published same-campus mutual aid and owner/authorized issue/workorder reads using current persisted visibility and purpose; preserve material/avatar/roster privacy. Avoid circular service injections by using narrow SQL policies. Test A owner, B same-school viewer, C cross-school, anonymous visitor under the documented public rule, and private material denial.

```java
assertEquals(404, hiddenPostRatingsStatus);
assertFalse(publicJson.contains("privateProofHint"));
assertEquals(403, unverifiedCommunityStatus);
assertEquals(404, restrictedParentCommentsStatus);
```

- [ ] Step 6 — Ensure unexpected-error logs do not print raw Throwable messages/SQL/arguments, URI path values or nested sensitive values. Keep a request correlation ID and safe diagnostic type; prove an exception containing a synthetic token/student value is redacted with a log-capture regression. Run focused tests and full suite, update docs for exact least-privilege setup, commit scoped changes and record evidence. Never solve permission errors with business SELECT mapping or root credentials.

## Task 4: C workflows — teams, lost return and response contracts

**Files:** `mutualaid/{MutualAidService,MutualAidViews,MutualAidController,MutualAidInputs}.java`, `team/`, focused tests and schemas; V19 `sql/V19__mutual_team_review_repairs.sql`; relevant contract docs. Coordinate with Task 3's public DTO mapping and attachment rules.

**Consumes:** C privacy boundaries and current root API sections 10, 11, 12. Preserve existing borrow_events two-party logic.

**Produces:** Flat mutual/team payloads, stable roles and identifiable application applicants for authorized captains, transactionally bounded recruitment, separately recorded two-party lost-return confirmation.

- [ ] Step 1 — Add RED JSON contract tests: mutual detail has data.title and six top-level source fields, Team has leader/members/opportunityId, applications include applicant. Never add real identity to anonymous community responses. Carry nullability/type contracts and use string IDs. Repair TeamService's existing Map/Object[] to Long casts at the typed data boundary; add a representative BigInteger behavior regression and retain the final real MySQL check, not a production driver coercion.
- [ ] Step 2 — Make team open roles stable, server-issued identifiers; validate openRoleId belongs to the team, persist application-role association, and on approval lock the team/role before checking remaining quantity and duplicate membership. Support existing teams safely with a migration/backfill or explicitly enforced repair path, not ephemeral indices. Account for legacy members when preventing total oversubscription. Active role edits cannot erase occupied slots. Add nonexistent/cross-team role, full role and concurrent-last-slot tests.
- [ ] Step 3 — Record lost/found per-party verification/return events and confirmationStatus; one participant cannot set returned until both different parties confirmed. Ignore/retry/state changes remain version/ownership safe. Reuse existing borrow proof for that flow rather than regressing it. Add both orderings, duplicate and outsider tests; preserve description as audit evidence.
- [ ] Step 4 — Implement or explicitly reject documented regionId filter, default tourist campus, signed-cursor status binding for borrow lists, and nontrading null prices/category validation. Prevent edits from bypassing category-specific detail validation. Do not invent a false region/location match if the storage lacks the field; add explicit modeled field only with migration/docs.

```java
assertEquals("verified", firstPartyReturn.status());
assertEquals("returned", secondPartyReturn.status());
assertEquals(400, nonexistentRoleStatus);
assertEquals(1, lastRoleApprovalSuccessCount);
```

- [ ] Step 5 — Run focused/full suites, update relevant documentation and commit only task changes; preserve Task 3 fixes.

## Task 5: Integration acceptance and safe publishing

**Files:** focused new MySQL regression tests/scripts, deployment docs, implementation-status.md, final repair report. Only own the new plan's `.superpowers/sdd/2026-10-15-abc-review-repairs` scratch directory. No source implementation outside a reviewed fix dispatch.

- [ ] Step 1 — Verify all tasks' reports, tests and reviews, and run full test/package against combined HEAD. Update counts from fresh XML results, not old reports. Re-run the original negative cases against the combined code. Per ledger Ruling17, compare V1–V15 committed blob bytes with both original branch tips wherever each file exists and with merged347adc0's full union; main lacks V10/V11 and feature lacks V12/V13. Report Windows checkout CRLF separately without editing historical migrations. Verify V16–V19 against their first-added commits; new migrations do not edit deployed history.
- [ ] Step 2 — Use an isolated disposable MySQL instance if available; never use user's MySQL84/3306 data. Run migrations, representative unsigned ID reads, minimum application grants, controlled author checks and separately authorized investigation. If runtime is unavailable, continue safe checks and explicitly identify what remains unverified; never label skipped tests passed.
- [ ] Step 3 — Inspect new CONNECTION.zip metadata without exposing credentials. The archive is a reproducible build/internal-workspace bundle, not required source; remove this newly introduced bundle from the integrated source tree, retaining Git recovery and other user files. Add a narrowly scoped ignore entry and document why; do not rewrite history or delete unrelated prototypes/documents. Check tracked config for secrets with redacted findings; real credential rotation requires user coordination.
- [ ] Step 4 — Update implementation status and final MD with per-finding fixes, changed files/migrations, exact tests, deployment actions and limitations. Run broad whole-branch review, address the single final fix wave and scoped re-review per SDD.
- [ ] Step 5 — Fetch both remote heads immediately before publish. If either advanced, integrate preserving changes, resolve and rerun affected/full verification. Check both remote tips are ancestors of HEAD. Use one atomic ordinary push of HEAD to refs/heads/main and refs/heads/feature/opportunity; no --force or history rewrite. Verify both remote SHAs equal tested HEAD. Do not create a PR unless user changes the direct-push request.
- [ ] Step 6 — Report only verified delivery: commit, both branch SHAs, test pass/skip counts, required migration/environment work and any irreducible external prerequisite. Preserve original checkout and user data.
