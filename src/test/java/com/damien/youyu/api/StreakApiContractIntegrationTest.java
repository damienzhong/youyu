package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 连续记账两个接口的<b>响应契约</b>集成测试（任务 7.1，需求 6.1、6.2、6.3、6.14、6.17、7.8、7.9）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实 Spring Security 过滤链、真实
 * Jackson 序列化与 H2 持久化层，覆盖四件事：</p>
 *
 * <ol>
 *   <li><b>概览响应顶层字段集恰好为 14 项</b>（需求 6.1）：断言用的是
 *       {@code containsExactlyInAnyOrderElementsOf}（集合相等），因此多一个键（第 15 项）与少一个键
 *       都会失败；且取值为 {@code null} 的键仍存在、不省略、不以 {@code 0} 或空串冒充——只有原始 JSON
 *       文本能区分「键存在且为 null」与「键被省略」（Jackson 的 {@code NON_NULL} 省略会让前者退化成
 *       后者，而 Map 的 {@code get()} 两种情形都返回 {@code null}）。</li>
 *   <li><b>历史分页顶层恰好 2 项、每项恰好 3 项</b>（需求 6.2、6.3）。</li>
 *   <li><b>不泄漏敏感字段与金额/交易标识</b>（需求 6.14）：两个端点序列化后的 JSON 文本一律不出现
 *       {@code email} / {@code wx_openid} / {@code wx_unionid} / {@code invite_code} / {@code plan}
 *       / {@code role} 六个键与取值，也不出现任何 {@code amount} 金额字段。</li>
 *   <li><b>零数据新用户的形状</b>（需求 6.1、6.17）：既无交易、又无成长事件的用户请求概览，返回
 *       {@code todayDone=false}、三个天数 0、四个端点空值、{@code broken=false}；请求历史分页返回
 *       空区间列表 + 真实总条数 0，且不报错。</li>
 * </ol>
 *
 * <p>另按需求 7.8、7.9 断言服务端耗时：概览与历史分页各自 ≤2000ms（本测试经 localhost 回环，网络
 * 传输耗时可忽略，故直接以客户端往返耗时作上界——它恒不小于服务端处理耗时，断言只会更严）。</p>
 *
 * <p>连续记账数据经真实链路生成：先落有效记账交易，再以 {@code GET /api/streak} 触发一次同步结算
 * 写档案、事件与段行（概览是写入型 GET）。使用<b>独立命名</b>的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-contract-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class StreakApiContractIntegrationTest {

    /** 连续记账概览顶层字段集，恰好 14 项（需求 6.1）。 */
    private static final Set<String> OVERVIEW_KEYS = Set.of(
            "todayDone", "currentStreakDays", "broken", "currentSegmentStart", "currentSegmentEnd",
            "lastStreakDays", "lastStreakEnd", "maxStreakDays", "longestSegmentStart",
            "longestSegmentEnd", "totalRecordDays", "segmentCount", "nextMilestone", "daysToNextMilestone");

    /** 历史分页顶层字段集，恰好 2 项（需求 6.2）。 */
    private static final Set<String> PAGE_TOP_KEYS = Set.of("items", "total");

    /** 区间项字段集，恰好 3 项（需求 6.3）。 */
    private static final Set<String> ITEM_KEYS = Set.of("startDate", "endDate", "days");

    /** 服务端处理耗时上界（需求 7.8、7.9）。 */
    private static final long BUDGET_MS = 2000L;

    /** 不得出现在任何响应里的六个敏感字段（需求 6.14）。 */
    private static final List<String> FORBIDDEN_FIELDS =
            List.of("email", "wx_openid", "wx_unionid", "invite_code", "plan", "role");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    // ============ 1) 零数据新用户：概览形状 + 历史分页空列表（需求 6.1、6.17）============

    @Test
    void zeroDataUser_overviewHasFourteenKeys_andSegmentsEmptyWithoutError() {
        String token = registerAndLogin("streak_contract_zero@example.com");

        long startedAt = System.nanoTime();
        ResponseEntity<Map> overview = get("/api/streak", bearer(token));
        assertWithinBudget(startedAt, "连续记账概览");

        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = body(overview);

        // 顶层恰好 14 项：多一个键（第 15 项）或少一个键都失败（需求 6.1）。
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(OVERVIEW_KEYS);

        // 零数据形状（需求 6.1 第 4 条、需求 1.4、2.7）。
        assertThat(body).containsEntry("todayDone", false);
        assertThat(body).containsEntry("broken", false);
        assertThat(((Number) body.get("currentStreakDays")).intValue()).isZero();
        assertThat(((Number) body.get("maxStreakDays")).intValue()).isZero();
        assertThat(((Number) body.get("totalRecordDays")).intValue()).isZero();
        assertThat(((Number) body.get("segmentCount")).longValue()).isZero();
        // 四个端点日期为空值（当前段起止、最长段起止）。
        assertNullValuedKeyPresent(body, "currentSegmentStart");
        assertNullValuedKeyPresent(body, "currentSegmentEnd");
        assertNullValuedKeyPresent(body, "longestSegmentStart");
        assertNullValuedKeyPresent(body, "longestSegmentEnd");
        // 连续未中断 → 上次连续两项为空值（需求 2.6、2.7）。
        assertNullValuedKeyPresent(body, "lastStreakDays");
        assertNullValuedKeyPresent(body, "lastStreakEnd");

        // 键存在且为 null 而非被省略：只有原始 JSON 文本能区分这两件事（需求 6.1）。
        String raw = rawGet("/api/streak", bearer(token));
        assertThat(raw).contains("\"currentSegmentStart\":null");
        assertThat(raw).contains("\"lastStreakDays\":null");

        // 历史分页：空区间列表 + 真实总条数 0，且不报错（需求 6.17）。
        long segStartedAt = System.nanoTime();
        ResponseEntity<Map> segments = get("/api/streak/segments", bearer(token));
        assertWithinBudget(segStartedAt, "历史连续区间");

        assertThat(segments.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> segBody = body(segments);
        assertThat(segBody.keySet()).containsExactlyInAnyOrderElementsOf(PAGE_TOP_KEYS);
        assertThat(itemsOf(segBody)).isEmpty();
        assertThat(((Number) segBody.get("total")).longValue()).isZero();
    }

    // ============ 2) 有数据用户：字段集不变、区间项恰好 3 项（需求 6.1、6.2、6.3）============

    @Test
    void userWithData_keepsExactFieldSets_andSegmentItemsHaveThreeKeys() {
        String token = registerAndLogin("streak_contract_data@example.com");
        long userId = userIdOf("streak_contract_data@example.com");
        seedValidRecords(userId, 92_001L, 5);

        Map<String, Object> body = body(get("/api/streak", bearer(token)));
        // 字段集与交易笔数、段总数无关，恒为 14 项（需求 6.1）。
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(OVERVIEW_KEYS);
        // 当天记账：今日已完成、当前连续 1 天、累计记账 1 天、段总数 1（同日多笔仍为 1 天）。
        assertThat(body).containsEntry("todayDone", true);
        assertThat(((Number) body.get("currentStreakDays")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("totalRecordDays")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("segmentCount")).longValue()).isEqualTo(1L);

        Map<String, Object> segBody = body(get("/api/streak/segments", bearer(token)));
        assertThat(segBody.keySet()).containsExactlyInAnyOrderElementsOf(PAGE_TOP_KEYS);
        assertThat(((Number) segBody.get("total")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> items = itemsOf(segBody);
        assertThat(items).hasSize(1);
        for (Map<String, Object> item : items) {
            // 每项恰好 3 项：起始日、结束日、段天数（需求 6.3）。
            assertThat(item.keySet()).containsExactlyInAnyOrderElementsOf(ITEM_KEYS);
            assertThat(item.get("startDate")).isNotNull();
            assertThat(item.get("endDate")).isNotNull();
            assertThat(((Number) item.get("days")).intValue()).isEqualTo(1);
        }
    }

    // ============ 3) 两个端点都不泄漏敏感字段与金额/交易标识（需求 6.14）============

    @Test
    void noEndpointLeaksSensitiveOrAmountFields() {
        String token = registerAndLogin("streak_contract_noleak@example.com");
        long userId = userIdOf("streak_contract_noleak@example.com");
        seedValidRecords(userId, 92_002L, 3);

        assertNoSensitiveFields(rawGet("/api/streak", bearer(token)), "连续记账概览");
        assertNoSensitiveFields(rawGet("/api/streak/segments", bearer(token)), "历史连续区间");
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言某个键<b>存在</b>且取值为 {@code null}，且不是 0 / 空字符串的冒充（需求 6.1）。 */
    private void assertNullValuedKeyPresent(Map<String, Object> body, String key) {
        assertThat(body).as(key + " 键仍存在").containsKey(key);
        assertThat(body.get(key)).as(key + " 为空值").isNull();
        assertThat(body.get(key)).as(key + " 不以 0 / 空字符串替代空值")
                .isNotEqualTo(0).isNotEqualTo(0L).isNotEqualTo("");
    }

    /** 断言 JSON 文本不含六个敏感字段与任何金额字段（需求 6.14）。 */
    private void assertNoSensitiveFields(String rawJson, String label) {
        assertThat(rawJson).as(label + " / 有响应体").isNotBlank();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(rawJson).as(label + " / 不含 " + forbidden).doesNotContain(forbidden);
        }
        assertThat(rawJson).as(label + " / 不含任何金额字段").doesNotContain("amount");
    }

    /** 断言往返耗时不超过 2000ms（需求 7.8、7.9）。 */
    private void assertWithinBudget(long startedAtNanos, String label) {
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        assertThat(elapsedMs).as(label + " 的耗时（ms）").isLessThanOrEqualTo(BUDGET_MS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    /** 取原始 JSON 文本：Map 解析会丢掉「键存在且为 null」与「键被省略」的区别。 */
    private String rawGet(String path, HttpHeaders headers) {
        ResponseEntity<String> response =
                rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /**
     * 落 {@code count} 笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = expense}、{@code ledger_id} 非 NULL），记账日均为当天。直接经仓储落库，
     * 不重复覆盖记账链路——本类验的是连续记账接口的响应契约。
     */
    private void seedValidRecords(long userId, long ledgerId, int count) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setLedgerId(ledgerId);
            tx.setCreatedBy(userId);
            tx.setType(TransactionType.EXPENSE);
            tx.setAmount(new BigDecimal("12.34"));
            tx.setAccountId(ledgerId);
            tx.setCategoryId(ledgerId);
            tx.setOccurredAt(now);
            tx.setCreatedAt(now);
            tx.setUpdatedAt(now);
            transactionRepository.save(tx);
        }
    }

    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String code = verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, EmailCodePurpose.LOGIN)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email))
                .getCode();

        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }
}
