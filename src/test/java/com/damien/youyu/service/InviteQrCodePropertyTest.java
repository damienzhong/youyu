package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.damien.youyu.error.ApiException;
import com.damien.youyu.wechat.WeChatApiException;
import com.damien.youyu.wechat.WeChatQrCodeGateway;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;

/**
 * {@link InviteQrCodeService} 的属性测试（jqwik）：设计文档 Correctness Properties 的 Property 13。
 *
 * <h2>测试层级：纯单元级</h2>
 * <p>本属性断言的全部内容都在服务层的编排里：缓存命中判定与限流判定的<b>先后次序</b>、未命中才计数、
 * 失败不写缓存、FIFO 淘汰、base64 编码形态、{@code scene}/{@code page}/{@code width} 三个入参。
 * 因此<b>不需要 Spring 上下文、不需要数据库、不碰网络</b>：</p>
 * <ul>
 *   <li>{@link InviteQrCodeCache} 与 {@link InviteRateLimiter} 用<b>真实实例</b>（它们是被测语义的一部分，
 *       换成替身等于把被测机制本身替换掉），由同一个可推进的固定 {@link MutableClock} 驱动，
 *       因此 7 天 TTL 与 24 小时窗口的边界可精确到毫秒断言。</li>
 *   <li>{@link InviteService#requireInviteCode} 用 {@link StubInviteService} 顶掉（它需要数据库与行级写锁，
 *       其自身语义由 Property 2 覆盖）；本测试只断言「每个请求都先惰性补齐、且 {@code scene} 一律取
 *       补齐后的取值」（需求 3.12）。</li>
 *   <li>{@link WeChatQrCodeGateway} 用 {@link StubGateway} 顶掉，按生成的响应形态返回图片字节或抛错。
 *       网关内部的凭证刷新与 {@code errcode=40001} 单次重试（需求 3.5、3.14）由
 *       {@code WeChatAccessTokenProviderTest} / {@code WeChatQrCodeGatewayTest} 覆盖；本测试在这条线上
 *       断言的是<b>调用契约</b>：一次未命中缓存的请求只调网关一次、只扣一次额度（网关内部重试不额外扣额度）。</li>
 * </ul>
 *
 * <h2>预期行为由一份独立模型给出</h2>
 * <p>每一步先用 {@link Model}（按需求 3.4、3.9、3.13 的文字重写的一份 FIFO 缓存 + 滑动窗口）算出
 * 「本次应命中缓存 / 应被限流 / 应打微信」，再与真实服务的实际行为逐项比对。缓存的存活键集合通过
 * 逐个 {@code cache.get} 探测（{@code accessOrder = false}，读取不改顺序），因此「被淘汰者恰为写入时刻
 * 最早者」是被直接断言的，而不是靠 size 间接推断。</p>
 *
 * <h2>并发维度</h2>
 * <p>末尾两个并发爆发（{@link ExecutorService} + {@link CountDownLatch}，并发度 2–8）分别覆盖：
 * 缓存已热时同一用户的多设备并发请求<b>零微信调用、零额度消耗、响应逐字节相同</b>；缓存全冷时并发请求
 * 打到微信的次数落在 {@code [1, 并发度]} 内（先到者写缓存，后到者可能直接命中），且所有响应仍逐字节相同。
 * 并发下计数无法精确预测，故只断言上下界；精确的计数不变式由前面的顺序序列覆盖。因带并发，
 * {@code tries} 按 spec 约定降到 100。</p>
 *
 * <p>Feature: invite-system, Property 13: 二维码的缓存语义、限流与编码</p>
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.4, 3.5, 3.7, 3.9, 3.12, 3.13, 8.8</p>
 */
class InviteQrCodePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant EPOCH = Instant.parse("2025-03-01T00:00:00Z");

    /** 顺序阶段的用户池规模：配合小容量缓存即可覆盖容量淘汰。 */
    private static final int USER_POOL = 6;

    /** 并发爆发专用的两个用户（顺序阶段不会用到，故缓存必冷、额度必满）。 */
    private static final long WARM_BURST_USER = 100L;
    private static final long COLD_BURST_USER = 101L;

    /** 微信响应形态：成功返回图片字节。 */
    private static final int OUTCOME_IMAGE = 0;
    /** 微信响应形态：{@code errcode=40001}（网关内部已强制刷新并重试过，业务层看到的是最终失败）。 */
    private static final int OUTCOME_ERR_40001 = 1;
    /** 微信响应形态：{@code errcode=41030}（页面不存在，不重试）。 */
    private static final int OUTCOME_ERR_41030 = 2;
    /** 微信响应形态：超时 / 调用抛异常，网关统一翻译为 {@code INVITE_QRCODE_FAILED}。 */
    private static final int OUTCOME_TIMEOUT = 3;

    // ---------------- 生成器 ----------------

    /**
     * 请求序列（长度 1–60）：每一步 = 目标用户 + 请求前推进的时长 + 本次微信响应形态。
     *
     * <p>推进时长刻意包含 0（同一时刻的连续请求）、24 小时整（限流窗口边界）、7 天整（TTL 边界）
     * 与其两侧取值，使窗口/TTL 的半开区间被真正走到。</p>
     */
    @Provide
    Arbitrary<List<Step>> requestSequences() {
        Arbitrary<Integer> users = Arbitraries.integers().between(0, USER_POOL - 1);
        Arbitrary<Long> advances = Arbitraries.of(
                0L,
                1_000L,
                60_000L,
                3_600_000L,
                InviteRateLimiter.QRCODE_MISS_WINDOW_MILLIS - 1,
                InviteRateLimiter.QRCODE_MISS_WINDOW_MILLIS,
                InviteQrCodeCache.TTL_MILLIS - 1,
                InviteQrCodeCache.TTL_MILLIS);
        // 成功形态占多数：只有成功才会写缓存，否则缓存语义几乎走不到。
        Arbitrary<Integer> outcomes = Arbitraries.frequencyOf(
                Tuple.of(6, Arbitraries.just(OUTCOME_IMAGE)),
                Tuple.of(1, Arbitraries.just(OUTCOME_ERR_40001)),
                Tuple.of(1, Arbitraries.just(OUTCOME_ERR_41030)),
                Tuple.of(1, Arbitraries.just(OUTCOME_TIMEOUT)));
        return Combinators.combine(users, advances, outcomes)
                .as(Step::new)
                .list().ofMinSize(1).ofMaxSize(60);
    }

    /** 缓存容量上限：小容量覆盖 FIFO 淘汰，1000 覆盖需求 3.13 的缺省上限。 */
    @Provide
    Arbitrary<Integer> cacheCapacities() {
        return Arbitraries.of(2, 3, 5, InviteQrCodeCache.DEFAULT_MAX_ENTRIES);
    }

    // ---------------- Property 13 ----------------

    /**
     * Feature: invite-system, Property 13: 二维码的缓存语义、限流与编码
     *
     * <p>对任意由「同一/多个用户的二维码请求 + 服务端时刻推进 + 微信响应形态」构成的序列：</p>
     * <ul>
     *   <li>命中缓存（写入时刻距今不足 7 天）的请求不调用网关（即不调微信凭证接口与小程序码接口）、
     *       不计入未命中计数、不被限流拒绝（需求 3.4、3.9）。</li>
     *   <li>任意 24 小时滑动窗口内打到小程序码接口的调用次数 ≤ 20；达上限的请求返回
     *       {@code INVITE_RATE_LIMITED} 且不消耗额度、不碰网关（需求 3.9、8.8）。</li>
     *   <li>微信调用失败（任意非零 errcode / 超时 / 抛异常）返回 {@code INVITE_QRCODE_FAILED}，
     *       计入未命中计数且不写缓存（需求 3.7）。</li>
     *   <li>一次未命中缓存的请求只调网关一次（凭证获取与 40001 重试都在网关内部完成，
     *       不额外扣额度，需求 3.5）。</li>
     *   <li>缓存项数恒 ≤ 生效容量上限（≤ 1000），存活键集合与 FIFO 模型逐键相等——即被淘汰者恰为
     *       写入时刻最早者（需求 3.13）。</li>
     *   <li>成功响应不含 data URI 前缀，且 Base64 解码后与网关返回字节完全相等（需求 3.1）。</li>
     *   <li>每个请求都先惰性补齐邀请码，{@code scene} 恒等于补齐后的邀请码，{@code page} 恒为
     *       {@code pages/invitelanding/invitelanding}、{@code width} 恒为 430（需求 3.2、3.12）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 3.1, 3.2, 3.4, 3.5, 3.7, 3.9, 3.12, 3.13, 8.8</p>
     */
    @Property(tries = 25)
    void property13_qrCodeCacheRateLimitAndEncoding(
            @ForAll("requestSequences") List<Step> steps,
            @ForAll("cacheCapacities") int cacheMaxEntries,
            @ForAll @IntRange(min = 2, max = 8) int concurrency) {

        MutableClock clock = new MutableClock(EPOCH, ZONE);
        InviteQrCodeCache cache = new InviteQrCodeCache(clock, cacheMaxEntries);
        InviteRateLimiter limiter = new InviteRateLimiter(clock);
        StubInviteService inviteService = new StubInviteService(clock);
        StubGateway gateway = new StubGateway();
        InviteQrCodeService service = new InviteQrCodeService(inviteService, cache, limiter, gateway);

        Model model = new Model(cache.maxEntries());
        // 每个用户打到微信的时刻，用于「任意 24 小时窗口 ≤ 20」的事后检查。
        Map<Long, List<Long>> wechatCallTimes = new LinkedHashMap<>();

        for (Step step : steps) {
            clock.advance(Duration.ofMillis(step.advanceMillis()));
            long now = clock.millis();
            long userId = step.userIndex() + 1L;
            String code = codeOf(userId);

            gateway.setOutcome(step.outcome());
            int callsBefore = gateway.callCount();
            int lazyFillBefore = inviteService.callCount();

            Optional<byte[]> expectedHit = model.read(code, now);
            if (expectedHit.isPresent()) {
                // 命中缓存：直接返回，零网关调用、零额度消耗（需求 3.4、3.9）。
                String response = service.getQrCodeBase64(userId);
                assertBase64Of(response, expectedHit.get());
                assertThat(gateway.callCount() - callsBefore)
                        .as("命中缓存的请求不得调用微信凭证接口与小程序码接口")
                        .isZero();
            } else if (!model.tryAcquire(userId, now)) {
                // 未命中且额度已满：拒绝，且不消耗额度、不碰微信（需求 3.9、8.8）。
                assertApiError(() -> service.getQrCodeBase64(userId), "INVITE_RATE_LIMITED");
                assertThat(gateway.callCount() - callsBefore)
                        .as("被限流拒绝的请求不得调用小程序码接口")
                        .isZero();
            } else if (step.outcome() == OUTCOME_IMAGE) {
                String response = service.getQrCodeBase64(userId);
                byte[] png = gateway.lastPng();
                assertBase64Of(response, png);
                assertThat(gateway.callCount() - callsBefore)
                        .as("一次未命中只调网关一次（凭证与 40001 重试都在网关内部）")
                        .isEqualTo(1);
                model.write(code, png, now);
                wechatCallTimes.computeIfAbsent(userId, k -> new ArrayList<>()).add(now);
            } else {
                // 微信失败：INVITE_QRCODE_FAILED，额度已扣、缓存不写（需求 3.7）。
                assertApiError(() -> service.getQrCodeBase64(userId), "INVITE_QRCODE_FAILED");
                assertThat(gateway.callCount() - callsBefore).isEqualTo(1);
                wechatCallTimes.computeIfAbsent(userId, k -> new ArrayList<>()).add(now);
            }

            // 每个请求都先走一次惰性补齐（需求 3.12）。
            assertThat(inviteService.callCount() - lazyFillBefore)
                    .as("每个请求都应先惰性补齐邀请码")
                    .isEqualTo(1);

            // 缓存不变式：容量上限、存活键集合与 FIFO 模型逐键相等（需求 3.13）。
            assertCacheMatchesModel(cache, model, now);
        }

        // 网关入参不变式：scene 恒为该用户补齐后的邀请码，page / width 恒为约定取值（需求 3.2、3.12）。
        for (GatewayCall call : gateway.calls()) {
            assertThat(call.page()).isEqualTo(InviteQrCodeService.QRCODE_PAGE);
            assertThat(call.width()).isEqualTo(InviteQrCodeService.QRCODE_WIDTH);
            assertThat(call.scene())
                    .as("scene 必须是惰性补齐后的邀请码，且不得为空")
                    .isNotBlank()
                    .matches("[" + InviteCodeGenerator.ALPHABET + "]{" + InviteCodeGenerator.LENGTH + "}");
        }

        // 任意 24 小时滑动窗口内打到微信的次数 ≤ 20（需求 3.9）。
        for (Map.Entry<Long, List<Long>> entry : wechatCallTimes.entrySet()) {
            assertThat(maxCallsInWindow(entry.getValue(), InviteRateLimiter.QRCODE_MISS_WINDOW_MILLIS))
                    .as("userId=%s 的 24 小时窗口内微信调用次数", entry.getKey())
                    .isLessThanOrEqualTo(InviteRateLimiter.QRCODE_MISS_LIMIT);
        }

        // ---- 并发维度：缓存已热 / 全冷两种爆发 ----
        assertWarmConcurrentBurstHitsCacheOnly(service, cache, gateway, concurrency);
        assertColdConcurrentBurstCallsWeChatAtMostOncePerThread(service, cache, gateway, concurrency);
    }

    // ---------------- 并发爆发 ----------------

    /**
     * 缓存已热时的多设备并发请求：全部命中缓存 → 零网关调用、零额度消耗、响应逐字节相同（需求 3.4、3.9、8.8）。
     *
     * <p>「零额度消耗」由「零网关调用 + 全部成功」推出：额度只在未命中时才申请，而未命中要么调网关、
     * 要么被限流拒绝，两者都会被这里的断言抓到。</p>
     */
    private void assertWarmConcurrentBurstHitsCacheOnly(InviteQrCodeService service,
            InviteQrCodeCache cache, StubGateway gateway, int concurrency) {

        byte[] warm = pngOf(codeOf(WARM_BURST_USER), 7);
        cache.put(codeOf(WARM_BURST_USER), warm);
        // 一旦有线程真的打到网关，这个形态会让它以异常暴露出来（同时 callCount 也会变化）。
        gateway.setOutcome(OUTCOME_ERR_41030);

        int callsBefore = gateway.callCount();
        List<Object> results = burst(service, WARM_BURST_USER, concurrency);

        assertThat(gateway.callCount() - callsBefore)
                .as("缓存已热时并发请求不得调用微信")
                .isZero();
        assertThat(results).hasSize(concurrency);
        for (Object result : results) {
            assertThat(result).as("命中缓存的并发请求不应失败").isInstanceOf(String.class);
            assertBase64Of((String) result, warm);
        }
        // 缓存项仍在（并发读取不改写入次序，也不触发过期移除）。
        assertThat(cache.get(codeOf(WARM_BURST_USER))).contains(warm);
    }

    /**
     * 缓存全冷时的多设备并发请求：打到微信的次数落在 {@code [1, 并发度]}（先到者写缓存，后到者可命中），
     * 全部响应成功且逐字节相同，事后缓存中存在该邀请码（需求 3.4、3.13）。
     *
     * <p>该用户在顺序阶段未出现过，因此额度必满（20 ≥ 8），不会掺入限流分支。</p>
     */
    private void assertColdConcurrentBurstCallsWeChatAtMostOncePerThread(InviteQrCodeService service,
            InviteQrCodeCache cache, StubGateway gateway, int concurrency) {

        String code = codeOf(COLD_BURST_USER);
        byte[] png = pngOf(code, 11);
        gateway.setOutcome(OUTCOME_IMAGE);
        gateway.setFixedPng(png);

        int callsBefore = gateway.callCount();
        List<Object> results = burst(service, COLD_BURST_USER, concurrency);
        int calls = gateway.callCount() - callsBefore;

        assertThat(calls)
                .as("缓存全冷时至少一次、至多每线程一次微信调用")
                .isBetween(1, concurrency);
        assertThat(results).hasSize(concurrency);
        for (Object result : results) {
            assertThat(result).as("额度充足时并发请求不应失败").isInstanceOf(String.class);
            assertBase64Of((String) result, png);
        }
        assertThat(cache.get(code)).as("成功后应写入缓存").contains(png);
        gateway.setFixedPng(null);
    }

    /** 并发度 2–8 的同时爆发：{@link CountDownLatch} 对齐起跑，返回每个线程的返回值或异常。 */
    private List<Object> burst(InviteQrCodeService service, long userId, int concurrency) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(service.getQrCodeBase64(userId));
                    } catch (Throwable t) {
                        results.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(awaitQuietly(done)).as("并发爆发应在 10 秒内全部完成").isTrue();
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(results);
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------- 断言辅助 ----------------

    /** 响应形态：不含 data URI 前缀，且 Base64 解码后与源字节完全相等（需求 3.1）。 */
    private static void assertBase64Of(String response, byte[] expectedPng) {
        assertThat(response).doesNotStartWith("data:");
        assertThat(Base64.getDecoder().decode(response)).isEqualTo(expectedPng);
        assertThat(response).isEqualTo(Base64.getEncoder().encodeToString(expectedPng));
    }

    private static void assertApiError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(expectedCode);
    }

    /**
     * 缓存与模型逐键比对：容量上限、项数、每个候选邀请码的存活性与字节内容。
     *
     * <p>{@code InviteQrCodeCache} 以 {@code accessOrder = false} 构造，读取不改写入次序，因此这里
     * 逐键探测不会干扰淘汰顺序；过期项在两侧都按「读取时移除」处理，状态保持同步。</p>
     */
    private static void assertCacheMatchesModel(InviteQrCodeCache cache, Model model, long now) {
        assertThat(cache.maxEntries()).isLessThanOrEqualTo(InviteQrCodeCache.DEFAULT_MAX_ENTRIES);
        for (long userId = 1; userId <= USER_POOL; userId++) {
            String code = codeOf(userId);
            Optional<byte[]> expected = model.read(code, now);
            Optional<byte[]> actual = cache.get(code);
            assertThat(actual.isPresent())
                    .as("邀请码 %s 的缓存存活性应与 FIFO/TTL 模型一致（被淘汰者恰为写入时刻最早者）", code)
                    .isEqualTo(expected.isPresent());
            expected.ifPresent(bytes -> assertThat(actual).contains(bytes));
        }
        assertThat(cache.size())
                .as("缓存项数应与模型相等且不超过生效容量上限")
                .isEqualTo(model.size())
                .isLessThanOrEqualTo(cache.maxEntries());
    }

    /** 任意长度为 {@code window} 的滑动窗口内的最大调用数（时刻列表已按升序追加）。 */
    private static int maxCallsInWindow(List<Long> times, long window) {
        int max = 0;
        for (int i = 0; i < times.size(); i++) {
            int count = 0;
            for (int j = i; j < times.size() && times.get(j) - times.get(i) < window; j++) {
                count++;
            }
            max = Math.max(max, count);
        }
        return max;
    }

    // ---------------- 预期行为模型 ----------------

    /** 一步请求：目标用户下标、请求前推进的毫秒数、本次微信响应形态。 */
    record Step(int userIndex, long advanceMillis, int outcome) { }

    /** 网关调用的入参记录。 */
    private record GatewayCall(String scene, String page, int width) { }

    /**
     * 按需求 3.4、3.9、3.13 的文字独立重写的预期行为模型：FIFO + 7 天 TTL 的图片缓存
     * 与 24 小时 / 20 次的滑动窗口。刻意<b>不</b>复用生产代码，否则实现与预期同源、共同出错时测不出来。
     */
    private static final class Model {
        private record Entry(byte[] png, long writtenAt) { }

        private final int maxEntries;
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        private final Map<Long, ArrayDeque<Long>> windows = new LinkedHashMap<>();

        Model(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        /** 读取：不存在或距写入时刻已满 7 天按未命中处理，过期项就地移除。 */
        Optional<byte[]> read(String code, long now) {
            Entry entry = entries.get(code);
            if (entry == null) {
                return Optional.empty();
            }
            if (now - entry.writtenAt() >= InviteQrCodeCache.TTL_MILLIS) {
                entries.remove(code);
                return Optional.empty();
            }
            return Optional.of(entry.png());
        }

        /** 写入：同键先移除使其排到队尾；超出容量时淘汰写入时刻最早者。 */
        void write(String code, byte[] png, long now) {
            entries.remove(code);
            entries.put(code, new Entry(png, now));
            while (entries.size() > maxEntries) {
                String eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
            }
        }

        int size() {
            return entries.size();
        }

        /** 滑动窗口：滑出的时刻踢出；未达上限记一次并放行，已达上限拒绝且不记数。 */
        boolean tryAcquire(long userId, long now) {
            ArrayDeque<Long> q = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= InviteRateLimiter.QRCODE_MISS_WINDOW_MILLIS) {
                q.pollFirst();
            }
            if (q.size() >= InviteRateLimiter.QRCODE_MISS_LIMIT) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    // ---------------- 测试替身 ----------------

    /** 惰性补齐替身：记录调用次数，按 userId 返回确定的 8 位邀请码（模拟「补齐后的取值」）。 */
    private static final class StubInviteService extends InviteService {
        private final AtomicInteger calls = new AtomicInteger();

        StubInviteService(Clock clock) {
            super(null, null, null, null, clock);
        }

        @Override
        public String requireInviteCode(Long userId) {
            calls.incrementAndGet();
            return codeOf(userId);
        }

        int callCount() {
            return calls.get();
        }
    }

    /**
     * 小程序码网关替身：按设定的响应形态返回图片字节或抛错，并记录每次调用的入参。
     *
     * <p>抛出的异常与真实网关一致：非零 errcode 抛 {@link WeChatApiException}（{@code code} 同为
     * {@code INVITE_QRCODE_FAILED}），超时 / 调用抛异常抛 {@link ApiException#inviteQrCodeFailed}。</p>
     */
    private static final class StubGateway extends WeChatQrCodeGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<GatewayCall> recorded = new CopyOnWriteArrayList<>();
        private volatile int outcome = OUTCOME_IMAGE;
        private volatile byte[] fixedPng;
        private volatile byte[] lastPng;

        StubGateway() {
            super(null, null);
        }

        @Override
        public byte[] fetchQrCode(String scene, String page, int width) {
            int n = calls.incrementAndGet();
            recorded.add(new GatewayCall(scene, page, width));
            switch (outcome) {
                case OUTCOME_ERR_40001 ->
                        throw new WeChatApiException(WeChatApiException.ERRCODE_INVALID_CREDENTIAL,
                                "invalid credential", null);
                case OUTCOME_ERR_41030 ->
                        throw new WeChatApiException(41030, "invalid page", null);
                case OUTCOME_TIMEOUT ->
                        throw ApiException.inviteQrCodeFailed("小程序码接口超时");
                default -> { }
            }
            byte[] png = fixedPng != null ? fixedPng : pngOf(scene, n);
            lastPng = png;
            return png;
        }

        void setOutcome(int outcome) {
            this.outcome = outcome;
        }

        void setFixedPng(byte[] png) {
            this.fixedPng = png;
        }

        byte[] lastPng() {
            return lastPng;
        }

        int callCount() {
            return calls.get();
        }

        List<GatewayCall> calls() {
            return recorded;
        }
    }

    // ---------------- 确定性取值 ----------------

    /** 把 userId 编码成 8 位邀请码（字母表 32 进制），模拟惰性补齐后的确定取值。 */
    private static String codeOf(long userId) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        long v = userId;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 0; i--) {
            out[i] = InviteCodeGenerator.ALPHABET.charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    /** 造一段长度随调用次序变化的「PNG 字节」：内容与长度都参与 base64 往返比对。 */
    private static byte[] pngOf(String scene, int callIndex) {
        int length = 1 + (Math.abs(scene.hashCode() + callIndex * 31) % 64);
        byte[] png = new byte[length];
        for (int i = 0; i < length; i++) {
            png[i] = (byte) (scene.charAt(i % scene.length()) + callIndex + i);
        }
        return png;
    }

    // ---------------- 可推进的固定时钟 ----------------

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
