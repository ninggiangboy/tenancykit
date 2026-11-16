---
phase: 2
title: "Shared visitor library in core"
status: done
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 2: Shared Visitor Library in Core

## Goal

A `core/core-visitor` module that turns what a request carries into the bounded, non-identifying facts the suite is
allowed to store: device class, browser family, traffic class, referrer domain, country and an anonymous daily visitor
hash.

## Context

No such code exists in the repository (verified by search); the survey plan would otherwise build it inside `survey/`
(its phase 6). `core/` is the suite's shared code (decision recorded 2026-11-12). Check first:
`git ls-files survey | head -1`. If `survey/` exists with its own classifier, extract it here and move survey onto
this module in the same change; otherwise create it here and leave survey's plan untouched.

## Files to create / modify

- Create: `core/core-visitor/build.gradle.kts`, `core/core-visitor/src/main/java/dev/ngb/core/visitor/` with
  `UserAgentClassifier`, `DeviceClass`, `TrafficClass`, `ReferrerDomain`, `GeoCountryReader`, `DailyVisitorHash`,
  `BoundedParams`
- Modify: `core/settings.gradle.kts` (include the module), `core/core-arch/.../ArchitectureRules.java` (one
  self-containment rule for `core-visitor`, as the other core modules have)
- Create: tests under `core/core-visitor/src/test/` with fixture files for user agents and referrers

## Tasks

1. **Classifier.** An ordered rule set over the user agent string: `BOT` (crawlers, link-preview fetchers such as chat
   and social unfurlers, headless clients, empty UA), else `TABLET`, `MOBILE`, `DESKTOP`, `UNKNOWN`; browser family from a
   small list; no version numbers, nothing that identifies a person. Return enums, never the raw string.
2. **Referrer.** Reduce a referrer URL to its registrable domain; drop path and query. Use the public-suffix list in
   `httpclient5` (`PublicSuffixMatcher`) if it handles `example.co.uk` and `example.com.vn`; otherwise Guava's
   `InternetDomainName`. A test over a fixed list decides.
3. **GeoIP.** `GeoCountryReader` reads a DB-IP Lite country `.mmdb` with `com.maxmind.db:maxmind-db` from a configured path.
   A missing or unreadable file yields an empty country and `available() == false`; the file's build date is exposed for
   health. The address is used in memory and never returned or logged.
4. **Daily hash.** `DailyVisitorHash.of(tenantId, ip, userAgent, day)` is `HMAC-SHA256(dayKey, tenantId ‖ ip ‖ ua)`
   where `dayKey = HMAC(masterKey, yyyy-MM-dd)`, so the hash differs each UTC day and a hash alone cannot be linked across
   days. Output is 16 bytes base64url.
5. **Bounded params.** `BoundedParams.utm(Map)` keeps `utm_source|medium|campaign|term|content`, each trimmed to 100
   characters and control characters removed.
6. **No Spring.** Plain Java so both servlet and worker code can use it; wiring stays in each app.

## Verification

- `cd core && ./gradlew :core-visitor:test` green; `./gradlew build` green including the architecture rules.
- Tests: a fixture list of at least 40 real user agents classifies as expected (including `Slackbot`, `facebookexternalhit`,
  `WhatsApp`, an iPhone, an iPad, Chrome on Android, Firefox on Linux); `https://www.bbc.co.uk/news?x=1` gives `bbc.co.uk`;
  a `.com.vn` host gives its registrable domain; a missing `.mmdb` gives an empty country; the daily hash differs between
  two days and is stable within one; no test or log line prints an IP.
- `cd billing && ./gradlew build` and `cd mailroom && ./gradlew build` still green (the shared build is consumed through
  `includeBuild`).

## Risks

- User-agent rules age. The rule file is data in the module with a test fixture; document how to extend it.
- DB-IP Lite requires attribution (CC BY 4.0): `docs/attribution.md` carries it (phase 12). The file is fetched, never
  committed.
