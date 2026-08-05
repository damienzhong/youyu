package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 成就三个接口的<b>响应契约</b>集成测试（任务 6.2，需求 6.1、6.2、6.3、6.4、6.5、6.12、6.14、
 * 6.18、5.4、5.7、5.15、5.16）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实 Spring Security 过滤链、真实
 * Jackson 序列化与 H2 持久化层，覆盖四件事：</p>
 *
 * <ol>
 *   <li><b>三个响应的字段集恰好为规定项数</b>（需求 6.1、6.2、5.4、5.7）：成就清单顶层恰好 3 项、
 *       成就视图恰好 9 项、待播报顶层恰好 2 项且每项恰好 6 项、ack 顶层恰好 1 项。断言用的是
 *       {@code containsExactlyInAnyOrderElementsOf}（集合相等），因此多一个键与少一个键都会失败。</li>
 *   <li><b>未解锁项的 {@code unlockedAt} 与 {@code eventId} 键仍存在且为 null</b>（需求 6.3、2.13）：
 *       既断言解析后的 Map 里键存在而取值为 {@code null}，也断言<b>原始 JSON 文本</b>里确实出现
 *       {@code "unlockedAt":null} / {@code "eventId":null}——只看 Map 无法区分「键存在且为 null」
 *       与「键被省略」（Jackson 的 {@code NON_NULL} 省略会让前者退化成后者，而 Map 的
 *       {@code get()} 两种情形都返回 {@code null}）。同时逐项排除 {@code 0}、空字符串与当前时刻
 *       三种冒充空值的写法。</li>
 *   <li><b>不泄漏敏感字段与金额字段</b>（需求 6.12）：三个端点序列化后的 JSON 文本一律不出现
 *       {@code email} / {@code wx_openid} / {@code wx_unionid} / {@code invite_code} / {@code plan}
 *       / {@code role} 六个键与取值，也不出现任何 {@code amount} 字段。</li>
 *   <li><b>零数据新用户的形状</b>（需求 6.18）：既无交易、又无成长事件与游标行的用户请求成就清单，
 *       返回 16 项全未解锁、当前值全 0、{@code unlockedCount} 为 0、{@code total} 为 16，且不报错。</li>
 * </ol>
 *
 * <p>另外按需求 6.14、5.15 断言服务端耗时：成就清单、待播报与 ack 各自 ≤2000ms（本测试经 localhost
 * 回环，网络传输耗时可忽略，故直接以客户端往返耗时作上界——它恒不小于服务端处理耗时，断言只会更严）。</p>
 *
 * <p>成就数据经真实链路生成：先落有效记账交易，再以 {@code GET /api/achievements} 触发一次同步结算
 * （成就清单与成长概览同为写入型 GET）。使用<b>独立命名</b>的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-contract-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class AchievementApiContractIntegrationTest {

    /** 成就清单顶层字段集，恰好 3 项（需求 6.1）。 */
    private static final Set<String> LIST_TOP_KEYS = Set.of("achievements", "unlockedCount", "total");

    /** 成就视图字段集，恰好 9 项（需求 6.2）。 */
    private static final Set<String> VIEW_KEYS = Set.of("code", "name", "description", "category",
            "target", "current", "unlocked", "unlockedAt", "eventId");

    /** 待播报响应顶层字段集，恰好 2 项（需求 5.4）。 */
    private static final Set<String> PENDING_TOP_KEYS = Set.of("items", "total");

    /** 待播报成就项字段集，恰好 6 项（需求 5.4）。 */
    private static final Set<String> PENDING_ITEM_KEYS = Set.of("code", "name", "description",
            "category", "unlockedAt", "eventId");

    /** ack 响应顶层字段集，恰好 1 项（需求 5.7）。 */
    private static final Set<String> ACK_TOP_KEYS = Set.of("lastNotifiedEventId");

    /** 成就总数恒为 16（需求 6.1）。 */
    private static final int TOTAL_ACHIEVEMENTS = 16;

    /** 服务端处理耗时上界（需求 6.14、5.15）。 */
    private static final long BUDGET_MS = 2000L;

    /** 不得出现在任何响应里的六个敏感字段（需求 6.12）。 */
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

    // ============ 1) 零数据新用户：16 项全未解锁、当前值全 0（需求 6.18、6.1、6.2）============

    @Test
    void zeroDataUser_getsSixteenLockedAchievements_withNullKeysPresent() {
        String token = registerAndLogin("ach_contract_zero@example.com");

        long startedAt = System.nanoTime();
        ResponseEntity<Map> response = get("/api/achievements", bearer(token));
        assertWithinBudget(startedAt, "成就清单");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = body(response);

        // 顶层恰好 3 项：多一个键或少一个键都失败（需求 6.1）。
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(LIST_TOP_KEYS);
        assertThat(body).containsEntry("total", TOTAL_ACHIEVEMENTS);
        assertThat(body).containsEntry("unlockedCount", 0);

        List<Map<String, Object>> views = achievementsOf(body);
        assertThat(views).hasSize(TOTAL_ACHIEVEMENTS);
        for (Map<String, Object> view : views) {
            String code = (String) view.get("code");
            assertThat(view.keySet()).as("成就 " + code + " 的字段集").containsExactlyInAnyOrderElementsOf(VIEW_KEYS);
            assertThat(view).as("成就 " + code + " 未解锁").containsEntry("unlocked", false);
            assertThat(view).as("成就 " + code + " 当前值为 0").containsEntry("current", 0);
            assertNullValuedKeyPresent(view, "unlockedAt", code);
            assertNullValuedKeyPresent(view, "eventId", code);
        }

        // 键存在且为 null 而不是被省略：只有原始 JSON 文本能区分这两件事（需求 6.3）。
        String raw = rawGet("/api/achievements", bearer(token));
        assertThat(raw).contains("\"unlockedAt\":null");
        assertThat(raw).contains("\"eventId\":null");
    }

    // ============ 2) 部分解锁：字段集不变、未解锁项两键仍为 null（需求 6.2、6.3、6.4、6.5）============

    @Test
    void partiallyUnlockedUser_keepsExactFieldSets_andNullKeysForLockedItems() {
        String token = registerAndLogin("ach_contract_partial@example.com");
        long userId = userIdOf("ach_contract_partial@example.com");
        // 10 笔当天有效记账：命中 FIRST_RECORD（门槛 1）与 RECORD_10（门槛 10）两枚，其余 14 枚未解锁。
        seedValidRecords(userId, 91_001L, 10);

        Map<String, Object> body = body(get("/api/achievements", bearer(token)));
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(LIST_TOP_KEYS);
        assertThat(body).containsEntry("total", TOTAL_ACHIEVEMENTS);

        List<Map<String, Object>> views = achievementsOf(body);
        assertThat(views).hasSize(TOTAL_ACHIEVEMENTS);

        List<String> unlockedCodes = new ArrayList<>();
        for (Map<String, Object> view : views) {
            String code = (String) view.get("code");
            // 字段集与列表项数不随交易笔数、成长事件条数变化（需求 6.2）。
            assertThat(view.keySet()).as("成就 " + code + " 的字段集").containsExactlyInAnyOrderElementsOf(VIEW_KEYS);

            int target = ((Number) view.get("target")).intValue();
            int current = ((Number) view.get("current")).intValue();
            boolean unlocked = (Boolean) view.get("unlocked");
            // 当前值恒落在 [0, target]（需求 6.4）。
            assertThat(current).as("成就 " + code + " 的当前值落在 [0, " + target + "]")
                    .isBetween(0, target);

            if (unlocked) {
                unlockedCodes.add(code);
                // 已解锁：当前值恒等于门槛，解锁时刻与事件 id 均非空（需求 6.3、6.4）。
                assertThat(current).as("已解锁成就 " + code + " 的当前值等于门槛").isEqualTo(target);
                assertThat(view.get("unlockedAt")).as("已解锁成就 " + code + " 的解锁时刻").isNotNull();
                assertThat(view.get("eventId")).as("已解锁成就 " + code + " 的事件 id").isNotNull();
                assertThat(((Number) view.get("eventId")).longValue()).isPositive();
            } else {
                // 未解锁：两键仍存在且为 null，不以 0 / 空字符串 / 当前时刻替代（需求 6.3、2.13）。
                assertNullValuedKeyPresent(view, "unlockedAt", code);
                assertNullValuedKeyPresent(view, "eventId", code);
            }
        }

        assertThat(unlockedCodes).containsExactly("FIRST_RECORD", "RECORD_10");
        // 已解锁成就数等于列表中已解锁项的个数，落在 [0, 16]（需求 6.5）。
        assertThat(((Number) body.get("unlockedCount")).intValue())
                .isEqualTo(unlockedCodes.size())
                .isBetween(0, TOTAL_ACHIEVEMENTS);

        // 未解锁项的两个键在原始 JSON 里确实是 null 字面量而非被省略（需求 6.3）。
        String raw = rawGet("/api/achievements", bearer(token));
        assertThat(raw).contains("\"unlockedAt\":null");
        assertThat(raw).contains("\"eventId\":null");
    }

    // ============ 3) 待播报与 ack 的字段集（需求 5.4、5.7、5.16、5.15）============

    @Test
    void pendingAndAck_haveExactFieldSets_andPendingItemsMatchAchievementViews() {
        String token = registerAndLogin("ach_contract_pending@example.com");
        long userId = userIdOf("ach_contract_pending@example.com");
        seedValidRecords(userId, 91_002L, 10);

        // 触发一次结算，写入两枚 BADGE 事件。
        Map<String, Object> listBody = body(get("/api/achievements", bearer(token)));
        Map<String, Map<String, Object>> viewByCode = viewByCode(listBody);

        long pendingStartedAt = System.nanoTime();
        ResponseEntity<Map> pending = get("/api/achievements/pending", bearer(token));
        assertWithinBudget(pendingStartedAt, "待播报成就");
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> pendingBody = body(pending);
        // 顶层恰好 2 项（需求 5.4）。
        assertThat(pendingBody.keySet()).containsExactlyInAnyOrderElementsOf(PENDING_TOP_KEYS);
        assertThat(((Number) pendingBody.get("total")).longValue()).isEqualTo(2L);

        List<Map<String, Object>> items = itemsOf(pendingBody);
        assertThat(items).hasSize(2);
        long previousEventId = 0L;
        for (Map<String, Object> item : items) {
            String code = (String) item.get("code");
            // 每项恰好 6 项（需求 5.4）。
            assertThat(item.keySet()).as("待播报项 " + code + " 的字段集")
                    .containsExactlyInAnyOrderElementsOf(PENDING_ITEM_KEYS);

            // 六个字段与成就清单的同名字段逐项相等（需求 5.4 与 6.2 共用同一份清单常量与同一行事件）。
            Map<String, Object> view = viewByCode.get(code);
            assertThat(view).as("待播报项 " + code + " 在成就清单中存在").isNotNull();
            for (String key : PENDING_ITEM_KEYS) {
                assertThat(item.get(key)).as("待播报项 " + code + " 的 " + key + " 与成就视图一致")
                        .isEqualTo(view.get(key));
            }

            // 按成就事件 id 升序（需求 5.4）。
            long eventId = ((Number) item.get("eventId")).longValue();
            assertThat(eventId).as("待播报项按事件 id 升序").isGreaterThan(previousEventId);
            previousEventId = eventId;
        }

        // ack：顶层恰好 1 项（需求 5.7）。
        long ackStartedAt = System.nanoTime();
        ResponseEntity<Map> ack = postAck(token, Map.of("lastEventId", String.valueOf(previousEventId)));
        assertWithinBudget(ackStartedAt, "推进播报游标");
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> ackBody = body(ack);
        assertThat(ackBody.keySet()).containsExactlyInAnyOrderElementsOf(ACK_TOP_KEYS);
        assertThat(((Number) ackBody.get("lastNotifiedEventId")).longValue()).isEqualTo(previousEventId);

        // 推进后无待播报：空列表 + total 0，且不报错（需求 5.16）。
        Map<String, Object> afterAck = body(get("/api/achievements/pending", bearer(token)));
        assertThat(afterAck.keySet()).containsExactlyInAnyOrderElementsOf(PENDING_TOP_KEYS);
        assertThat(itemsOf(afterAck)).isEmpty();
        assertThat(((Number) afterAck.get("total")).longValue()).isZero();
    }

    // ============ 4) 三个端点都不泄漏敏感字段与金额字段（需求 6.12）============

    @Test
    void noEndpointLeaksSensitiveOrAmountFields() {
        String token = registerAndLogin("ach_contract_noleak@example.com");
        long userId = userIdOf("ach_contract_noleak@example.com");
        seedValidRecords(userId, 91_003L, 3);

        assertNoSensitiveFields(rawGet("/api/achievements", bearer(token)), "成就清单");
        assertNoSensitiveFields(rawGet("/api/achievements/pending", bearer(token)), "待播报成就");
        assertNoSensitiveFields(rawAck(token, Map.of("lastEventId", "0")), "推进播报游标");
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言某个键<b>存在</b>且取值为 {@code null}，且不是 0 / 空字符串 / 当前时刻的冒充（需求 6.3）。 */
    private void assertNullValuedKeyPresent(Map<String, Object> view, String key, String code) {
        assertThat(view).as("成就 " + code + " 的 " + key + " 键仍存在").containsKey(key);
        assertThat(view.get(key)).as("成就 " + code + " 的 " + key + " 为空值").isNull();
        assertThat(view.get(key)).as("成就 " + code + " 的 " + key + " 不以 0 / 空字符串替代空值")
                .isNotEqualTo(0).isNotEqualTo(0L).isNotEqualTo("");
    }

    /** 断言 JSON 文本不含六个敏感字段与任何金额字段（需求 6.12）。 */
    private void assertNoSensitiveFields(String rawJson, String label) {
        assertThat(rawJson).as(label + " / 有响应体").isNotBlank();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(rawJson).as(label + " / 不含 " + forbidden).doesNotContain(forbidden);
        }
        assertThat(rawJson).as(label + " / 不含任何金额字段").doesNotContain("amount");
    }

    /** 断言往返耗时不超过 2000ms（需求 6.14、5.15）。 */
    private void assertWithinBudget(long startedAtNanos, String label) {
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        assertThat(elapsedMs).as(label + " 的耗时（ms）").isLessThanOrEqualTo(BUDGET_MS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> achievementsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("achievements");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private Map<String, Map<String, Object>> viewByCode(Map<String, Object> listBody) {
        Map<String, Map<String, Object>> byCode = new java.util.LinkedHashMap<>();
        for (Map<String, Object> view : achievementsOf(listBody)) {
            byCode.put((String) view.get("code"), view);
        }
        return byCode;
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> postAck(String token, Map<String, Object> payload) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/achievements/notices/ack"), HttpMethod.POST,
                new HttpEntity<>(payload, headers), Map.class);
    }

    /** 取原始 JSON 文本：Map 解析会丢掉「键存在且为 null」与「键被省略」的区别。 */
    private String rawGet(String path, HttpHeaders headers) {
        ResponseEntity<String> response =
                rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String rawAck(String token, Map<String, Object> payload) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(url("/api/achievements/notices/ack"),
                HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
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
     * 不重复覆盖记账链路——本类验的是成就接口的响应契约。
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
