package dev.ngb.attribution.boot.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.links.adapter.redirect.cache.LinkCache;
import dev.ngb.attribution.links.adapter.redirect.ratelimit.PublicRateLimiter;
import dev.ngb.attribution.links.adapter.redirect.suspect.SuspectTracker;
import dev.ngb.attribution.links.adapter.redirect.unlock.UnlockAttempts;
import dev.ngb.attribution.links.adapter.redirect.web.ClickFactory;
import dev.ngb.attribution.links.api.redirect.ClickToken;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import dev.ngb.attribution.links.application.redirect.service.RedirectDecider;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.LinkStatus;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DailyVisitorHash;
import dev.ngb.core.visitor.GeoCountryReader;
import dev.ngb.core.visitor.TrafficClass;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The redirect's outcomes, the cap under contention, the token it appends and the click it offers, driven through the
 * controller the way the filter chain would call it.
 *
 * <p>The link store is an in-memory stand-in so the test proves the controller's decisions rather than the database's;
 * the cap's exactness under 100 parallel requests is the store's conditional update, which the integration test of the
 * repository covers against PostgreSQL.
 */
class RedirectControllerTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Map<String, String> CLICK_KEYS = Map.of("k1", "test-click-secret");

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final FixedTenantContext tenantContext = new FixedTenantContext();
    private final CapturingIngest ingest = new CapturingIngest();
    private final InMemoryLinks links = new InMemoryLinks();
    private final PasswordHasher hasher = new PasswordHasher() {
        @Override
        public String hash(String raw) {
            return "hash:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return ("hash:" + raw).equals(hash);
        }
    };
    private final Clock clock = () -> NOW;

    private RedirectController controller;

    @BeforeEach
    void setUp() {
        tenantContext.tenant = tenant;
        LinkCache cache = new LinkCache(links, immediateUnitOfWork(), Duration.ofSeconds(30), 1000);
        RedirectDecider decider = new RedirectDecider(links, new java.util.Random(1));
        ClickFactory clicks = new ClickFactory(
                clock, UUID::randomUUID, tenantContext, new DailyVisitorHash("visitor-secret"),
                GeoCountryReader.open(null));
        controller = new RedirectController(
                cache,
                decider,
                clicks,
                ingest,
                () -> "aid",
                hasher,
                new PublicRateLimiter(10_000, Duration.ofMinutes(10)),
                new PublicRateLimiter(10_000, Duration.ofMinutes(10)),
                new UnlockAttempts(5, Duration.ofMinutes(10)),
                new SuspectTracker(Duration.ofMinutes(1)),
                () -> true,
                new RedirectController.ClickTokenKeys("k1", CLICK_KEYS),
                tenantContext,
                clock);
    }

    @Test
    void anActiveLinkRedirectsWithASignedClickToken() throws Exception {
        links.put(link(LinkStatus.ACTIVE, null, null, null, destination("https://example.com/a", true)));

        MockHttpServletResponse response = get("spring-sale");

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String location = response.getHeader("Location");
        assertThat(location).startsWith("https://example.com/a?");
        String token = queryParam(location, "aid");
        assertThat(token).as("the redirect must append the click parameter").isNotBlank();
        assertThat(ClickToken.verify(token, CLICK_KEYS))
                .as("the redirect's token must verify with the deployment's key")
                .isPresent();
        assertThat(ingest.events).hasSize(1);
        assertThat(ingest.events.getFirst().trafficClass()).isEqualTo(TrafficClass.HUMAN);
    }

    @Test
    void anUnknownSlugIsTheSameConstantNotFoundAsAnArchivedOne() throws Exception {
        links.put(link(LinkStatus.ARCHIVED, null, null, null, destination("https://example.com/a", true)));

        MockHttpServletResponse unknown = get("no-such-link");
        MockHttpServletResponse archived = get("spring-sale");

        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(archived.getStatus()).isEqualTo(404);
        assertThat(archived.getContentAsString()).isEqualTo(unknown.getContentAsString());
    }

    @Test
    void aPausedLinkIsNotFoundAndAPausedLinkIsNotStoredAsAClick() throws Exception {
        links.put(link(LinkStatus.PAUSED, null, null, null, destination("https://example.com/a", true)));

        MockHttpServletResponse response = get("spring-sale");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(ingest.events).isEmpty();
    }

    @Test
    void anExpiredLinkIsGone() throws Exception {
        links.put(link(LinkStatus.ACTIVE, NOW.minusSeconds(60), null, null, destination("https://example.com/a", true)));

        assertThat(get("spring-sale").getStatus()).isEqualTo(410);
        assertThat(ingest.events).isEmpty();
    }

    @Test
    void aProtectedLinkAnswers401UntilTheRightPasswordIsPosted() throws Exception {
        links.put(link(LinkStatus.ACTIVE, null, null, "hash:s3cret", destination("https://example.com/a", true)));

        MockHttpServletResponse guarded = get("spring-sale");
        assertThat(guarded.getStatus()).isEqualTo(401);
        assertThat(guarded.getContentAsString()).contains("password_required");

        MockHttpServletResponse wrong = post("spring-sale", "nope");
        assertThat(wrong.getStatus()).isEqualTo(403);

        MockHttpServletResponse right = post("spring-sale", "s3cret");
        assertThat(right.getStatus()).isEqualTo(302);
        assertThat(right.getHeader("Location")).startsWith("https://example.com/a?");
        assertThat(queryParam(right.getHeader("Location"), "aid")).isNotBlank();
    }

    @Test
    void aCapOfFiveUnderAHundredParallelRequestsAnswersExactlyFiveRedirects() throws Exception {
        Link capped = link(
                LinkStatus.ACTIVE, null, 5L, null, destination("https://example.com/a", true));
        links.put(capped);
        // The cache must load once; a second load would reset the stand-in store's counter and hide a race.
        get("spring-sale");
        AtomicInteger redirects = new AtomicInteger();
        AtomicInteger gone = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(100)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        int status = get("spring-sale").getStatus();
                        if (status == 302) {
                            redirects.incrementAndGet();
                        } else if (status == 410) {
                            gone.incrementAndGet();
                        }
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        // One redirect was spent by the warm-up get before the 100 parallel ones, so 4 more succeed.
        assertThat(redirects.get()).isEqualTo(4);
        assertThat(gone.get()).isEqualTo(96);
        assertThat(links.clicksCounted).isEqualTo(5);
    }

    @Test
    void aBotUserAgentStillRedirectsAndIsStoredAsBot() throws Exception {
        links.put(link(LinkStatus.ACTIVE, null, null, null, destination("https://example.com/a", true)));

        MockHttpServletResponse response = get("spring-sale", "Slackbot-LinkExpanding 1.0");

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(ingest.events).hasSize(1);
        assertThat(ingest.events.getFirst().trafficClass()).isEqualTo(TrafficClass.BOT);
    }

    @Test
    void aHeadAnswersWhereTheLinkWouldSendButSpendsNoClickAndStoresNothing() throws Exception {
        links.put(link(LinkStatus.ACTIVE, null, 5L, null, destination("https://example.com/a", true)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.head("spring-sale", null, request("HEAD", null), response);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(queryParam(response.getHeader("Location"), "aid")).isNull();
        assertThat(links.clicksCounted).isZero();
        assertThat(ingest.events).isEmpty();
    }

    @Test
    void aTenantWithoutTheEntitlementAnswersTheSameNotFound() throws Exception {
        links.put(link(LinkStatus.ACTIVE, null, null, null, destination("https://example.com/a", true)));
        controller = new RedirectController(
                new LinkCache(links, immediateUnitOfWork(), Duration.ofSeconds(30), 1000),
                new RedirectDecider(links, new java.util.Random(1)),
                new ClickFactory(
                        clock, UUID::randomUUID, tenantContext, new DailyVisitorHash("visitor-secret"),
                        GeoCountryReader.open(null)),
                ingest, () -> "aid", hasher,
                new PublicRateLimiter(10_000, Duration.ofMinutes(10)),
                new PublicRateLimiter(10_000, Duration.ofMinutes(10)),
                new UnlockAttempts(5, Duration.ofMinutes(10)),
                new SuspectTracker(Duration.ofMinutes(1)),
                () -> false,
                new RedirectController.ClickTokenKeys("k1", CLICK_KEYS),
                tenantContext,
                clock);

        assertThat(get("spring-sale").getStatus()).isEqualTo(404);
    }

    @Test
    void theClickCarriesNoAddressAndNoUserAgent() throws Exception {
        links.put(link(LinkStatus.ACTIVE, null, null, null, destination("https://example.com/a", true)));

        get("spring-sale", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1");

        ClickEvent click = ingest.events.getFirst();
        assertThat(click.visitorHash()).isNotBlank();
        assertThat(click.country()).isNull();
        assertThat(click.browserFamily()).isEqualTo("Safari");
        assertThat(click.deviceClass()).isEqualTo(dev.ngb.core.visitor.DeviceClass.MOBILE);
        // Nothing on the event can carry the raw string: the record has no such member, and this asserts the shape.
        assertThat(ClickEvent.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getName()).doesNotContain("userAgent").doesNotContain("ip"));
    }

    private MockHttpServletResponse get(String slug) throws Exception {
        return get(slug, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/120 Safari/537.36");
    }

    private MockHttpServletResponse get(String slug, String userAgent) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.redirect(slug, null, request("GET", userAgent), response);
        return response;
    }

    private MockHttpServletResponse post(String slug, String password) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.unlock(
                slug, null, new RedirectController.UnlockRequest(password),
                request("POST", "Mozilla/5.0 Chrome/120"), response);
        return response;
    }

    /** The decoded value of one query parameter, or null when the URL does not carry it. */
    private static String queryParam(String location, String name) {
        String query = java.net.URI.create(location).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name)) {
                return equals < 0 ? "" : java.net.URLDecoder.decode(pair.substring(equals + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static MockHttpServletRequest request(String method, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/go/spring-sale");
        request.setRemoteAddr("203.0.113.7");
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }

    private Link link(LinkStatus status, Instant expiresAt, Long maxClicks, String passwordHash, Destination any) {
        return new Link(
                UUID.randomUUID(), tenant, null, Slug.of("spring-sale"), LinkKind.STANDARD, null, status, expiresAt,
                maxClicks, passwordHash, Map.of("source", "newsletter"), List.of(any), 1, NOW, NOW);
    }

    private static Destination destination(String url, boolean fallback) {
        return new Destination(UUID.randomUUID(), new DestinationUrl(url), 0, 1, DeviceMatch.any(), fallback);
    }

    /** The link store the test drives: in memory, with the one conditional update a cap depends on. */
    private static final class InMemoryLinks implements LinkRepository {

        private final Map<String, Link> bySlug = new HashMap<>();
        private long clicksCounted;

        void put(Link link) {
            bySlug.put(link.slug().value(), link);
        }

        @Override
        public void insert(Link link) {
            put(link);
        }

        @Override
        public Optional<Link> findById(UUID linkId) {
            return bySlug.values().stream().filter(link -> link.id().equals(linkId)).findFirst();
        }

        @Override
        public Optional<Link> findBySlug(String slug) {
            return Optional.ofNullable(bySlug.get(slug));
        }

        @Override
        public boolean update(Link link, long expectedVersion) {
            put(link);
            return true;
        }

        @Override
        public boolean slugTaken(String slug) {
            return bySlug.containsKey(slug);
        }

        @Override
        public synchronized boolean incrementIfBelow(UUID linkId, long cap) {
            if (clicksCounted < cap) {
                clicksCounted++;
                return true;
            }
            return false;
        }

        @Override
        public long clicksCounted(UUID linkId) {
            return clicksCounted;
        }
    }

    private static final class CapturingIngest implements dev.ngb.attribution.tracking.api.ingest.ClickIngest {

        private final List<ClickEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean offer(ClickEvent event) {
            events.add(event);
            return true;
        }
    }

    /** A unit of work that runs the supplier in place: the test has no transaction to open. */
    private static UnitOfWork immediateUnitOfWork() {
        return new UnitOfWork() {
            @Override
            public <T> T transactional(Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static final class FixedTenantContext implements TenantContext {

        private TenantId tenant;

        @Override
        public RequestScope current() {
            return RequestScope.tenant(tenant);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            return work.get();
        }
    }
}
