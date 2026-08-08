package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.damien.youyu.api.ClientIpResolver;
import com.damien.youyu.api.InviteController;
import com.damien.youyu.api.dto.InviterBriefResponse;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.support.InMemoryUserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * 公开查询（{@code GET /api/invite/inviter}）的属性测试（jqwik）：设计文档 Correctness Properties
 * 的 Property 14。
 *
 * <h2>测试层级：单元级，与集成测试互补</h2>
 * <p>任务 6.5 的 {@code InvitePublicLookupIntegrationTest} 已用真实过滤链在 HTTP 层核对了报文同构与
 * 限流；本属性走服务/控制器方法调用，覆盖的是<b>整个输入空间</b>：多种邀请码形态 × 多种
 * {@code X-Forwarded-For} 头形态 × 跨 60 秒边界的时刻序列，单次迭代可以打出上百个请求——这在
 * {@code MockMvc} 上跑 200 次迭代的代价里做不到。</p>
 * <ul>
 *   <li>{@link InviteRateLimiter} 与 {@link InviteCodeGenerator} 用<b>真实实例</b>：它们是被测语义
 *       本身，换成替身等于把被测机制替换掉。限流器由可推进的固定 {@link MutableClock} 驱动，
 *       因此 60 秒窗口的半开区间边界可精确到毫秒断言。</li>
 *   <li>{@link InMemoryUserRepository} 是<b>真实存储实现</b>（Map + 自增主键），不是预置桩返回值的
 *       mock；外面包一层计数装饰器，用于断言「查库次数」与「零写入」。</li>
 *   <li>{@link ClientIpResolver} 与 {@link InviteController} 一并纳入：需求 8.6 的「计数键取
 *       {@code X-Forwarded-For} 末位」落在解析器里，而「{@code UNAUTHENTICATED} 优先于字段校验与
 *       限流」落在控制器里，都不该在这条属性里被替身绕过。</li>
 *   <li>{@link InviteQrCodeService} 用 mock 且断言<b>零交互</b>——它只出现在「鉴权先于一切」的
 *       断言里，用来证明未认证请求根本没走到业务。</li>
 * </ul>
 *
 * <h2>预期行为由独立模型给出</h2>
 * <p>每一步先用 {@link Model}（按需求 8.6 的文字重写的一份 60 秒 / 30 次滑动窗口）与一份独立的
 * 「规整 → 格式判定 → 存在性」计算算出本次应放行还是应被限流、应成功还是应 {@code NOT_FOUND}，
 * 再与真实调用的实际行为逐项比对。规整与格式判定<b>不复用被测组件</b>（自己 trim + 大写 + 正则），
 * 否则实现里的偏差会被测试一起带偏。</p>
 *
 * <h2>不在本属性覆盖范围内的部分</h2>
 * <p>需求 4.2 的「服务端处理耗时 ≤ 500 毫秒」不做墙钟断言：内存装置上的耗时与生产无关，
 * 而在 CI 上给单次调用设时间阈值只会换来偶发失败。「不存在时不追加等待、不重试」这一条改为
 * <b>结构断言</b>：格式合法的查询恰好产生 1 次 {@code findByInviteCode}，格式非法的产生 0 次。
 * 令牌签名与有效期的真实校验属于过滤链，由任务 6.4 的集成测试覆盖；本属性在控制器层覆盖的是
 * 过滤链管不到的那一半（无 principal 与「令牌用户已注销」）。</p>
 *
 * <p>Feature: invite-system, Property 14: 公开查询的鉴权、限流与不可区分</p>
 *
 * <p>Validates: Requirements 4.2, 4.4, 8.1, 8.2, 8.4, 8.6, 8.7, 8.9, 8.10</p>
 */
class InvitePublicLookupPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant EPOCH = Instant.parse("2025-05-01T00:00:00Z");

    /** 邀请码字母表（需求 8.9 原文），期望值一侧独立持有，不引用被测组件的常量。 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 期望值一侧的格式判定：长度恰 8 且字符全在字母表内（需求 8.9）。 */
    private static final Pattern WELL_FORMED = Pattern.compile("[" + ALPHABET + "]{8}");

    /** 需求 8.6 的窗口长度与上限，独立写死；实现若改动这两个数，本属性会失败。 */
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int LIMIT = 30;

    /** nginx 追加在 XFF 末位的两个地址（不可被客户端控制）。 */
    private static final String NGINX_IP_A = "203.0.113.11";
    private static final String NGINX_IP_B = "198.51.100.22";
    /** TCP 远端地址：XFF 缺失或末位为空时的回退取值。 */
    private static final String REMOTE_A = "10.0.0.7";
    private static final String REMOTE_B = "10.0.0.8";
    /** 定额爆发阶段专用的键：随机阶段不会用到，因此窗口必为空。 */
    private static final String BURST_IP = "192.0.2.55";

    /** 令牌合法但用户已注销：该 id 不在存储里（需求 8.2 中过滤链管不到的那一情形）。 */
    private static final long DELETED_USER_ID = 9_999L;

    /** 邀请码输入形态：库中存在的码（含大小写与空白变形）。 */
    private static final int KIND_EXISTING = 0;
    /** 邀请码输入形态：格式合法但库中不存在。 */
    private static final int KIND_ABSENT = 1;
    /** 邀请码输入形态：规整后长度不等于 8。 */
    private static final int KIND_BAD_LENGTH = 2;
    /** 邀请码输入形态：长度为 8 但含字母表外字符。 */
    private static final int KIND_ILLEGAL_CHARS = 3;
    /** 邀请码输入形态：入参缺失（{@code null}）。 */
    private static final int KIND_NULL = 4;

    private static final int OUTCOME_SUCCESS = 0;
    private static final int OUTCOME_NOT_FOUND = 1;
    private static final int OUTCOME_RATE_LIMITED = 2;

    /** 邀请人池：邀请码 → 昵称原文（含空白与 NULL 两种「以空值返回」的来源，需求 4.4）。 */
    private static final List<PoolUser> POOL_USERS = List.of(
            new PoolUser("ABCD2345", "小明"),
            new PoolUser("K7M2Q9XT", "   "),
            new PoolUser("WXYZ6789", null),
            new PoolUser("JKLMNPQR", "a".repeat(64)));

    /** 格式合法但不在池中的码。 */
    private static final List<String> ABSENT_CODES = List.of(
            "AAAAAAAA", "BBBBBBBB", "23456789", "STUVWXYZ", "CDEFGHJK", "P9Q8R7S6");

    /** 规整后长度不等于 8 的输入（含空串、纯空白、7 位、9 位、超长）。 */
    private static final List<String> BAD_LENGTH_CODES = List.of(
            "", "   ", "A", "AB", "ABCD234", "ABCD23456", "ABCD2345ABCD2345",
            "abcd234", "  ABCD2345X ", "A".repeat(64));

    /** 规整后长度为 8 但含字母表外字符的输入（{@code I}/{@code O}/{@code 0}/{@code 1} 与符号、汉字）。 */
    private static final List<String> ILLEGAL_CHAR_CODES = List.of(
            "ABCD234I", "ABCD234O", "ABCD2340", "ABCD2341", "abcd234i",
            "ABCD-234", "ABCD 234", "ABCD_234", "ABCD23@4", "邀请码测试八", "ＡBCD2345", "ABCD234\n");

    /** 客户端自填的 XFF 前序取值：轮换伪造，绝不应成为计数键。 */
    private static final List<String> FORGED_PREFIXES = List.of(
            "1.1.1.1", "2.2.2.2", "8.8.8.8", NGINX_IP_B, BURST_IP, "");

    // ---------------- 生成器 ----------------

    /**
     * 请求序列（长度 1–90）：每一步 = XFF 头形态 + 伪造前序 + 邀请码输入 + 请求前推进的时长。
     *
     * <p>推进时长刻意含 0（同一毫秒内的连续请求）与 59999 / 60000 / 60001（窗口半开区间的两侧），
     * 并把 0 与 1 的权重压得很高——只有大量同一时刻的请求才会真的把 30 次额度打满。</p>
     */
    @Provide
    Arbitrary<List<Step>> requestSequences() {
        Arbitrary<Integer> ipShapes = Arbitraries.integers().between(0, IP_SHAPE_COUNT - 1);
        Arbitrary<String> forged = Arbitraries.of(FORGED_PREFIXES);
        Arbitrary<Integer> kinds = Arbitraries.integers().between(0, KIND_NULL);
        Arbitrary<Integer> seeds = Arbitraries.integers().between(0, 63);
        Arbitrary<Long> advances = Arbitraries.frequencyOf(
                Tuple.of(6, Arbitraries.just(0L)),
                Tuple.of(3, Arbitraries.just(1L)),
                Tuple.of(1, Arbitraries.just(1_000L)),
                Tuple.of(1, Arbitraries.just(WINDOW_MILLIS - 1)),
                Tuple.of(1, Arbitraries.just(WINDOW_MILLIS)),
                Tuple.of(1, Arbitraries.just(WINDOW_MILLIS + 1)));
        return Combinators.combine(ipShapes, forged, kinds, seeds, advances)
                .as(Step::new)
                .list().ofMinSize(1).ofMaxSize(90);
    }

    /** 分页参数：用于「非法令牌 + 非法参数」的优先级组合（需求 8.2）。 */
    @Provide
    Arbitrary<String> pageParams() {
        return Arbitraries.of("abc", "-1", "100001", "0", "", "99999999999999999999", "1.5")
                .injectNull(0.2);
    }

    // ---------------- Property 14 ----------------

    /**
     * Feature: invite-system, Property 14: 公开查询的鉴权、限流与不可区分
     *
     * <p>对任意（来源 IP、{@code X-Forwarded-For} 头形态、请求时刻序列、邀请码输入）组合：</p>
     * <ul>
     *   <li>计数键等于 {@code X-Forwarded-For} 末位去空白后的取值；该头缺失、为空白或末位去空白后为空时
     *       等于 TCP 远端地址；客户端自带的前序取值永不作为计数键（需求 8.6）。</li>
     *   <li>任意 60 秒滑动窗口内被放行的请求数 ≤ 30；达上限的请求返回 {@code INVITE_RATE_LIMITED}，
     *       该判定<b>先于</b>规整、格式校验与存在性查询（被拒时查库次数为 0，即便邀请码合法且存在），
     *       且不消耗额度、两表数据不变（需求 8.6、8.7）。</li>
     *   <li>邀请码存在与不存在两种情形同等计入计数，且各只产生 1 次数据库查询（不追加等待、不重试，
     *       需求 8.10）。</li>
     *   <li>格式非法（含长度不为 8、入参缺失）、含字母表外字符、库中不存在三种情形返回的错误码、
     *       HTTP 状态、提示文案与 {@code field} 逐项相同——全程只观察到<b>一个</b>失败签名
     *       （需求 8.9）。</li>
     *   <li>成功只返回昵称一个字段，昵称为 NULL 或去空白为空时以空值返回（需求 4.2、4.4）。</li>
     *   <li>公开端点无论请求处于何种令牌状态都不返回 {@code UNAUTHENTICATED}，且根本不读取会话
     *       上下文（需求 8.4）。</li>
     *   <li>三个受保护端点在无 principal（令牌缺失 / 验签失败 / 过期）与「令牌用户已注销」两种情形下
     *       一律返回 {@code UNAUTHENTICATED}，优先于非法分页参数与限流判定，且不碰业务与两表
     *       （需求 8.1、8.2）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 4.2, 4.4, 8.1, 8.2, 8.4, 8.6, 8.7, 8.9, 8.10</p>
     */
    @Property(tries = 25)
    void property14_publicLookupAuthRateLimitAndIndistinguishability(
            @ForAll("requestSequences") List<Step> steps,
            @ForAll("pageParams") String rawPage,
            @ForAll("pageParams") String rawSize) {

        Fixture fixture = new Fixture();
        Model model = new Model();
        // 三类失败（格式非法 / 含非法字符 / 不存在）的响应签名全部汇入同一个集合：
        // 报文同构等价于「这个集合最终至多只有一个元素」（需求 8.9）。
        Set<String> failureSignatures = new LinkedHashSet<>();

        // ---- 随机序列阶段 ----
        for (Step step : steps) {
            fixture.clock.advance(Duration.ofMillis(step.advanceMillis()));
            MockHttpServletRequest request = requestFor(step);
            performLookup(fixture, model, request, expectedIp(step),
                    step.codeKind(), step.codeSeed(), failureSignatures);
        }

        // ---- 定额爆发阶段：专用键，窗口必为空，因此额度边界是确定的 ----
        int allowed = 0;
        for (int i = 0; i < LIMIT + 15; i++) {
            int kind = i % (KIND_NULL + 1);
            int outcome = performLookup(fixture, model, burstRequest(), BURST_IP,
                    kind, i, failureSignatures);
            if (outcome != OUTCOME_RATE_LIMITED) {
                allowed++;
            }
        }
        assertThat(allowed)
                .as("同一计数键在 60 秒窗口内恰好放行 30 次（需求 8.6）")
                .isEqualTo(LIMIT);

        // 被拒的 15 次不消耗额度：窗口整体滑出后应恢复满额 30 次（需求 8.6）。
        fixture.clock.advance(Duration.ofMillis(WINDOW_MILLIS));
        int allowedAfterSlide = 0;
        for (int i = 0; i < LIMIT + 5; i++) {
            int outcome = performLookup(fixture, model, burstRequest(), BURST_IP,
                    i % (KIND_NULL + 1), i, failureSignatures);
            if (outcome != OUTCOME_RATE_LIMITED) {
                allowedAfterSlide++;
            }
        }
        assertThat(allowedAfterSlide)
                .as("被限流拒绝的请求不消耗额度：窗口滑出后恢复满额")
                .isEqualTo(LIMIT);

        // ---- 三类失败的报文同构（需求 8.9）----
        assertThat(failureSignatures)
                .as("格式非法 / 含非法字符 / 不存在三种情形必须共用同一个 {code, status, message, field}")
                .hasSizeLessThanOrEqualTo(1);

        // ---- 任意 60 秒窗口内放行数 ≤ 30（对每个计数键做事后检查）----
        model.assertNoWindowExceedsLimit();

        // ---- 公开端点不读会话上下文，因此不可能返回 UNAUTHENTICATED（需求 8.4）----
        assertThat(fixture.currentUser.touchCount())
                .as("公开端点不得读取会话上下文（携带无效令牌时按匿名请求处理）")
                .isZero();

        // ---- 受保护端点：UNAUTHENTICATED 优先于字段校验与限流（需求 8.1、8.2）----
        assertUnauthenticatedWins(fixture, rawPage, rawSize);

        // ---- 全程零写入，users 快照不变（需求 8.7、8.2）----
        assertThat(fixture.users.writeCount())
                .as("公开查询与被拒请求都不得写 users 表")
                .isZero();
        assertThat(fixture.users.snapshot())
                .as("users 表数据必须与请求前逐行相同")
                .isEqualTo(fixture.initialSnapshot);
        // invite_relations 全程零交互：公开查询与鉴权失败都不该碰它。
        Mockito.verifyNoInteractions(fixture.inviteRelationRepository);
    }

    // ---------------- 单次请求：模型比对 + 断言 ----------------

    /**
     * 执行一次公开查询并逐项断言，返回本次的实际归类。
     *
     * <p>顺序刻意与需求 8.6 一致：先算出「计数键」并与解析器实际取值比对，再由模型决定应放行还是
     * 应被限流，最后才轮到格式与存在性——这样「限流先于格式校验」是被<b>直接</b>断言的
     * （被拒时查库次数必须为 0），而不是靠调用次数间接推断。</p>
     */
    private int performLookup(Fixture fixture, Model model, MockHttpServletRequest request,
            String expectedIp, int codeKind, int codeSeed, Set<String> failureSignatures) {

        // 计数键：XFF 末位去空白，缺失 / 末位为空回退远端地址；伪造前序永不生效（需求 8.6）。
        assertThat(ClientIpResolver.resolveClientIp(request))
                .as("限流计数键必须取 X-Forwarded-For 末位（客户端自填的前序取值不得生效）")
                .isEqualTo(expectedIp);

        String raw = rawCode(codeKind, codeSeed);
        String normalized = normalize(raw);
        boolean wellFormed = WELL_FORMED.matcher(normalized).matches();
        String expectedNickname = POOL_BY_CODE.get(normalized);
        boolean exists = wellFormed && POOL_BY_CODE.containsKey(normalized);

        boolean expectAllowed = model.tryAcquire(expectedIp, fixture.clock.millis());
        int lookupsBefore = fixture.users.lookupCount();

        if (!expectAllowed) {
            ApiException thrown = catchThrowableOfType(
                    () -> fixture.controller.inviterBrief(raw, request), ApiException.class);
            assertThat(thrown).as("60 秒窗口内已达 30 次，本次必须被拒绝").isNotNull();
            assertThat(thrown.getCode()).isEqualTo("INVITE_RATE_LIMITED");
            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(fixture.users.lookupCount() - lookupsBefore)
                    .as("限流判定先于格式校验与存在性查询：被拒请求不得查库（需求 8.6、8.7）")
                    .isZero();
            return OUTCOME_RATE_LIMITED;
        }

        if (!exists) {
            ApiException thrown = catchThrowableOfType(
                    () -> fixture.controller.inviterBrief(raw, request), ApiException.class);
            assertThat(thrown).as("格式非法或库中不存在时必须返回 NOT_FOUND").isNotNull();
            assertThat(thrown.getCode()).isEqualTo("NOT_FOUND");
            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(thrown.getField())
                    .as("公开查询的失败响应不得带 field（否则成为可区分格式非法与不存在的信号）")
                    .isNull();
            assertThat(thrown.getMessage()).isNotBlank();
            failureSignatures.add(signature(thrown));
            assertThat(fixture.users.lookupCount() - lookupsBefore)
                    .as("格式合法的查询恰好查库 1 次（不重试）；格式非法不查库（需求 8.10）")
                    .isEqualTo(wellFormed ? 1 : 0);
            return OUTCOME_NOT_FOUND;
        }

        InviterBriefResponse body = fixture.controller.inviterBrief(raw, request).getBody();
        assertThat(body).as("邀请码存在时必须成功返回").isNotNull();
        assertThat(body.nickname())
                .as("成功只返回昵称；昵称为 NULL 或去空白为空时以空值返回（需求 4.2、4.4）")
                .isEqualTo(expectedNickname);
        assertThat(fixture.users.lookupCount() - lookupsBefore)
                .as("存在的邀请码同样只产生 1 次数据库查询（需求 8.10）")
                .isEqualTo(1);
        return OUTCOME_SUCCESS;
    }

    /**
     * 三个受保护端点在两种「无效令牌」情形下一律 {@code UNAUTHENTICATED}，且优先于非法分页参数与
     * 限流错误；期间不碰二维码业务、不查邀请码、不写 users（需求 8.1、8.2）。
     */
    private void assertUnauthenticatedWins(Fixture fixture, String rawPage, String rawSize) {
        // null：过滤链未放入 principal（令牌缺失 / 验签失败 / 已过期）；
        // DELETED_USER_ID：令牌合法但用户已注销——过滤链不查库，只有控制器能挡住。
        for (Long tokenUserId : new Long[] {null, DELETED_USER_ID}) {
            fixture.currentUser.setUserId(tokenUserId);
            int lookupsBefore = fixture.users.lookupCount();

            assertUnauthenticated(() -> fixture.controller.inviteInfo());
            assertUnauthenticated(() -> fixture.controller.qrCode());
            assertUnauthenticated(() -> fixture.controller.invitees(rawPage, rawSize));

            assertThat(fixture.users.lookupCount() - lookupsBefore)
                    .as("鉴权失败的请求不得走到邀请码查询")
                    .isZero();
            Mockito.verifyNoInteractions(fixture.qrCodeService);
        }
    }

    private static void assertUnauthenticated(Runnable call) {
        ApiException thrown = catchThrowableOfType(call::run, ApiException.class);
        assertThat(thrown).as("受保护端点在无效令牌下必须返回 UNAUTHENTICATED").isNotNull();
        assertThat(thrown.getCode())
                .as("UNAUTHENTICATED 优先于任何字段校验与限流错误（需求 8.2）")
                .isEqualTo("UNAUTHENTICATED");
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 失败响应的完整签名：错误码 + HTTP 状态 + 提示文案 + field。 */
    private static String signature(ApiException e) {
        return e.getCode() + '|' + e.getStatus().value() + '|' + e.getMessage() + '|' + e.getField();
    }

    // ---------------- 期望值：独立于被测实现 ----------------

    /** 邀请码 → 期望返回的昵称（空白与 NULL 都折成 {@code null}）。 */
    private static final Map<String, String> POOL_BY_CODE = buildPool();

    private static Map<String, String> buildPool() {
        // 取值可以是 null（昵称为 NULL 或空白时以空值返回），因此不能用 Map.copyOf / Map.of。
        Map<String, String> pool = new LinkedHashMap<>();
        for (PoolUser user : POOL_USERS) {
            String nickname = user.nickname();
            pool.put(user.code(), (nickname == null || nickname.isBlank()) ? null : nickname);
        }
        return Collections.unmodifiableMap(pool);
    }

    /** 期望值一侧的规整：裁首尾空白 + 转大写，{@code null} 视作空串（需求 8.9）。 */
    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** 按形态与种子造出邀请码入参原文。 */
    private static String rawCode(int kind, int seed) {
        int s = Math.abs(seed);
        return switch (kind) {
            case KIND_EXISTING -> variant(POOL_USERS.get(s % POOL_USERS.size()).code(), s);
            case KIND_ABSENT -> variant(ABSENT_CODES.get(s % ABSENT_CODES.size()), s);
            case KIND_BAD_LENGTH -> BAD_LENGTH_CODES.get(s % BAD_LENGTH_CODES.size());
            case KIND_ILLEGAL_CHARS -> ILLEGAL_CHAR_CODES.get(s % ILLEGAL_CHAR_CODES.size());
            default -> null;
        };
    }

    /** 大小写与首尾空白变形：规整后应与原码相等，因此不改变期望结果（需求 1.9）。 */
    private static String variant(String code, int seed) {
        return switch (seed % 4) {
            case 1 -> code.toLowerCase(Locale.ROOT);
            case 2 -> "  " + code + "\t";
            case 3 -> code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT);
            default -> code;
        };
    }

    // ---------------- XFF 头形态 ----------------

    /** XFF 头形态数量，与 {@link #requestFor} / {@link #expectedIp} 的分支一一对应。 */
    private static final int IP_SHAPE_COUNT = 8;

    /** 按形态造请求；期望计数键由 {@link #expectedIp} 独立给出，两者刻意不共用代码。 */
    private static MockHttpServletRequest requestFor(Step step) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(step.ipShape() % 2 == 0 ? REMOTE_A : REMOTE_B);
        String forged = step.forgedPrefix();
        switch (step.ipShape()) {
            case 0 -> { /* XFF 缺失 */ }
            case 1 -> request.addHeader("X-Forwarded-For", NGINX_IP_A);
            case 2 -> request.addHeader("X-Forwarded-For", forged + ", " + NGINX_IP_A);
            case 3 -> request.addHeader("X-Forwarded-For",
                    "9.9.9.9, " + forged + ",   " + NGINX_IP_A + "  ");
            case 4 -> request.addHeader("X-Forwarded-For", NGINX_IP_A + ",   ");
            case 5 -> request.addHeader("X-Forwarded-For", "   ");
            case 6 -> request.addHeader("X-Forwarded-For", forged + ", " + NGINX_IP_B);
            default -> request.addHeader("X-Forwarded-For", "");
        }
        return request;
    }

    /** 手写的期望计数键：偶数形态远端地址为 A、奇数为 B；末位可用则取末位，否则回退远端地址。 */
    private static String expectedIp(Step step) {
        String remote = step.ipShape() % 2 == 0 ? REMOTE_A : REMOTE_B;
        return switch (step.ipShape()) {
            case 1, 2, 3 -> NGINX_IP_A;
            case 6 -> NGINX_IP_B;
            default -> remote;   // 0：头缺失；4：末位为空白；5：整头空白；7：整头空串
        };
    }

    /** 定额爆发阶段的请求：固定键，且客户端自填的前序取值每次都换。 */
    private static MockHttpServletRequest burstRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE_A);
        request.addHeader("X-Forwarded-For", "7.7.7." + burstSeq++ % 256 + ", " + BURST_IP);
        return request;
    }

    private static int burstSeq = 1;

    // ---------------- 生成数据模型 ----------------

    /** 一步请求：XFF 形态 + 伪造前序 + 邀请码形态与种子 + 请求前推进的毫秒数。 */
    record Step(int ipShape, String forgedPrefix, int codeKind, int codeSeed, long advanceMillis) {
    }

    /** 邀请人池条目：邀请码 + 昵称原文（可为空白或 {@code null}）。 */
    record PoolUser(String code, String nickname) {
    }

    // ---------------- 需求文字重写的期望模型 ----------------

    /**
     * 按需求 8.6 文字重写的 60 秒 / 30 次滑动窗口，与 {@link InviteRateLimiter} 的实现无关联代码。
     *
     * <p>被拒绝的请求<b>不入队</b>——这正是「拒绝不消耗额度」的模型表达：若实现把被拒请求也记了账，
     * 窗口滑出后两边的放行判定就会分叉，逐步比对必然在某一步失败。</p>
     */
    private static final class Model {

        private final Map<String, ArrayDeque<Long>> windows = new LinkedHashMap<>();
        private final Map<String, List<Long>> allowedTimes = new LinkedHashMap<>();

        boolean tryAcquire(String key, long now) {
            ArrayDeque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
            // 半开区间：距今恰好 60 秒的时刻已滑出。
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MILLIS) {
                q.pollFirst();
            }
            if (q.size() >= LIMIT) {
                return false;
            }
            q.addLast(now);
            allowedTimes.computeIfAbsent(key, k -> new ArrayList<>()).add(now);
            return true;
        }

        /** 事后检查：对每个计数键的每个放行时刻，其前 60 秒内的放行数 ≤ 30。 */
        void assertNoWindowExceedsLimit() {
            for (Map.Entry<String, List<Long>> entry : allowedTimes.entrySet()) {
                List<Long> times = entry.getValue();
                for (int i = 0; i < times.size(); i++) {
                    long end = times.get(i);
                    int count = 0;
                    for (int j = i; j >= 0 && end - times.get(j) < WINDOW_MILLIS; j--) {
                        count++;
                    }
                    assertThat(count)
                            .as("键 %s 在截至 %d 的 60 秒窗口内放行数超限", entry.getKey(), end)
                            .isLessThanOrEqualTo(LIMIT);
                }
            }
        }
    }

    // ---------------- 装置 ----------------

    /** 每次迭代一套全新装置：真实限流器 + 真实生成器 + 真实内存存储 + 真实控制器。 */
    private static final class Fixture {

        private final MutableClock clock = new MutableClock(EPOCH, ZONE);
        private final CountingUserRepository users = new CountingUserRepository();
        private final InviteRelationRepository inviteRelationRepository =
                Mockito.mock(InviteRelationRepository.class);
        private final InviteQrCodeService qrCodeService = Mockito.mock(InviteQrCodeService.class);
        private final StubCurrentUser currentUser = new StubCurrentUser();
        private final InviteController controller;
        private final Map<Long, String> initialSnapshot;

        private Fixture() {
            for (PoolUser poolUser : POOL_USERS) {
                User user = new User();
                user.setNickname(poolUser.nickname());
                user.setInviteCode(poolUser.code());
                users.save(user);
            }
            initialSnapshot = users.snapshot();
            users.resetCounters();

            InviteService inviteService = new InviteService(users, inviteRelationRepository,
                    new InviteCodeGenerator(), new InviteRateLimiter(clock), clock);
            controller = new InviteController(currentUser, users, inviteService, qrCodeService);
        }
    }

    /**
     * 计数装饰器：数「查库次数」与「写入次数」。
     *
     * <p>继承真实内存实现而非改用 mock：邀请码的精确匹配、昵称取值都由真实存储产生，
     * 计数只是加在上面的观察点。</p>
     */
    private static final class CountingUserRepository extends InMemoryUserRepository {

        private int lookupCount;
        private int writeCount;

        @Override
        public java.util.Optional<User> findByInviteCode(String inviteCode) {
            lookupCount++;
            return super.findByInviteCode(inviteCode);
        }

        @Override
        public <S extends User> S save(S entity) {
            writeCount++;
            return super.save(entity);
        }

        @Override
        public void delete(User entity) {
            writeCount++;
            super.delete(entity);
        }

        @Override
        public void deleteById(Long id) {
            writeCount++;
            super.deleteById(id);
        }

        int lookupCount() {
            return lookupCount;
        }

        int writeCount() {
            return writeCount;
        }

        void resetCounters() {
            lookupCount = 0;
            writeCount = 0;
        }

        /** {@code id → 昵称 + 邀请码} 的行快照，用于断言「两表数据不变」。 */
        Map<Long, String> snapshot() {
            Map<Long, String> rows = new LinkedHashMap<>();
            for (User user : findAll()) {
                rows.put(user.getId(), user.getNickname() + "|" + user.getInviteCode());
            }
            return rows;
        }
    }

    /**
     * 会话上下文替身：{@code userId} 为 {@code null} 表示过滤链未放入 principal
     * （令牌缺失 / 验签失败 / 已过期）；非空表示令牌合法但该用户可能已注销。
     *
     * <p>另外记录被读取的次数：公开端点的断言靠它——{@code touchCount == 0} 才说明公开请求
     * 根本没去看令牌（需求 8.4）。</p>
     */
    private static final class StubCurrentUser extends CurrentUser {

        private Long userId;
        private int touchCount;

        @Override
        public Long requireUserId() {
            touchCount++;
            if (userId == null) {
                throw ApiException.unauthenticated();
            }
            return userId;
        }

        void setUserId(Long userId) {
            this.userId = userId;
        }

        int touchCount() {
            return touchCount;
        }
    }

    // ---------------- 可推进的固定时钟 ----------------

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }
    }
}
