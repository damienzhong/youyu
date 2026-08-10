package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 周期记账<b>端到端全流程</b>自动化集成测试（tasks 10.1，需求：全部）。
 *
 * <p>照抄 {@link RecurringRuleControllerTest} / {@link RecurringPendingItemControllerTest} 的
 * {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate} + 手工签发 JWT 范式，经<b>真实 HTTP</b>、
 * 真实 Spring Security 过滤链、真实 {@code CurrentLedger}（{@code X-Ledger-Id} 解析）、真实
 * {@code RecurringRuleService} / {@code RecurringPendingItemService} 与真实 {@code TransactionService} 记账链路
 * （账户加锁 + 余额更新），把整条特性从头到尾串起来跑，断言 Property 4–11 在<b>真实链路</b>成立、金额闭合。</p>
 *
 * <h2>为什么用「可调时钟」（{@link MutableClock}）而非固定时钟</h2>
 * <p>懒生成的生成下界为 {@code max(startDate, updatedAt.toLocalDate())}——规则若在「今天」经真实 POST 创建，
 * 其 {@code updated_at} 即今天，下界随之为今天，故只会补齐「今天」当日到期的期次（design.md 明列的 MVP 简化）。
 * 为在<b>真实端点链路</b>上如实产出「过去若干期已到期」的堆积待确认项，本测试提供一个 {@code @Primary} 的
 * <b>可变</b> {@link Clock}：先把时钟拨到过去（{@code 2025-06-10}）经 POST 建规则（{@code updated_at} 落在过去），
 * 再把时钟前拨到 {@code 2025-06-15}，于是 GET 触发的懒生成沿真实链路补齐 06-10..06-15 共 6 期。服务层持有的是
 * 同一个可变时钟实例，拨动即对全链路生效。</p>
 *
 * <h2>覆盖的全流程（对应任务 10.1 的 1–6 步）</h2>
 * <ol>
 *   <li>POST 建规则（DAILY 支出，开始日期在过去）。</li>
 *   <li>GET 懒生成 → 断言产出的 PENDING 期次与到期日、快照字段正确（含幂等：重复 GET 不重复生成，Property 4）。</li>
 *   <li>确认一条 → 真实流水 + 扣款；修改后确认另一条（覆盖金额）→ 用改后值扣款、规则模板不变；跳过一条 → 零副作用
 *       （Property 6、7）。</li>
 *   <li>批量确认 / 批量跳过其余 → 逐条结果、余额与流水效果（Property 6、7、9）。</li>
 *   <li>生命周期：暂停（不再生成）→ 恢复（仅恢复当日及之后，不回补）→ 编辑（前向生效、既有 PENDING 快照不变、
 *       CONFIRMED 历史不变）→ 删除（PENDING 消失、CONFIRMED/SKIPPED 历史保留，Property 10）。</li>
 *   <li>横切不变式：状态机幂等（已处理 → RECURRING_ITEM_ALREADY_PROCESSED，Property 8）、账本/用户隔离
 *       （跨租户 → NOT_FOUND，Property 11）、金额闭合（账户净变动 == 已确认金额按方向求和）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-e2e;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringEndToEndTest {

    private static final String RULES = "/api/recurring/rules";
    private static final String ITEMS = "/api/recurring/pending-items";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 规则创建当日（Asia/Shanghai）：把时钟拨到过去建规则，使生成下界落在过去以便懒生成堆积回补。 */
    private static final LocalDate CREATE_DAY = LocalDate.of(2025, 6, 10);
    /** 「今天」：懒生成时拨到此日，06-10..06-15 共 6 期到期。 */
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    private static final long ALICE = 9001L;
    private static final long BOB = 9002L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 全链路共享的可变时钟实例（静态：@Bean 方法与测试均引用同一实例）。 */
    private static final MutableClock E2E_CLOCK = new MutableClock(instantAt(CREATE_DAY), ZONE);

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        Clock e2eMutableClock() {
            return E2E_CLOCK;
        }
    }

    @LocalServerPort
    private int port;
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository memberRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;

    private Long ledgerId;
    private Long accountId;
    private Long expenseCategoryId;
    private Long incomeCategoryId;

    @BeforeEach
    void reset() {
        // 可变时钟归位到「创建当日」；每个用例前硬清相关表（真实提交、不靠回滚）。
        E2E_CLOCK.setInstant(instantAt(CREATE_DAY));
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
        ledgerRepository.deleteAll();

        ledgerId = seedLedger(ALICE).getId();
        accountId = seedAccount(ALICE, "1000.00");
        linkAccountToLedger(accountId, ledgerId);
        expenseCategoryId = seedCategory(ledgerId, CategoryKind.EXPENSE, "房租");
        incomeCategoryId = seedCategory(ledgerId, CategoryKind.INCOME, "工资");
    }

    // ==================================================================================
    // 主流程：建规则 → 懒生成 → 确认/改后确认/跳过 → 批量 → 暂停/恢复/编辑/删除，金额全程闭合。
    // ==================================================================================

    @Test
    void fullFlow_create_generate_confirm_modify_skip_batch_lifecycle() {
        // ---- 1) 建规则（DAILY 支出，开始日期 = 创建当日 06-10；时钟此刻在 06-10，updated_at 落在过去）。----
        clockTo(CREATE_DAY);
        long ruleId = createDailyExpenseRule("50.00", CREATE_DAY);
        // 建规则不触账、不生成流水（需求 3.2）。
        assertThat(transactionRepository.count()).isZero();
        assertThat(balance()).isEqualByComparingTo("1000.00");

        // ---- 2) 拨到「今天」06-15，GET 触发懒生成 → 06-10..06-15 共 6 条 PENDING（真实链路回补）。----
        clockTo(TODAY);
        List<Map<String, Object>> items = listItems(ALICE, ledgerId);
        assertThat(items).hasSize(6);
        assertThat(items.stream().map(i -> i.get("occurrenceDate").toString()).toList())
                .containsExactly("2025-06-10", "2025-06-11", "2025-06-12",
                        "2025-06-13", "2025-06-14", "2025-06-15");
        // 每项携带来源规则 id、快照字段（需求 5.1）。
        for (Map<String, Object> it : items) {
            assertThat(Long.parseLong(it.get("ruleId").toString())).isEqualTo(ruleId);
            assertThat(it).containsEntry("status", "PENDING");
            assertThat(it).containsEntry("type", "expense");
            assertThat(it).containsEntry("categoryId", expenseCategoryId.intValue());
            assertThat(it).containsEntry("accountId", accountId.intValue());
            assertThat(new BigDecimal(it.get("amount").toString())).isEqualByComparingTo("50.00");
        }
        // Property 4（生成幂等，真实链路）：再次 GET 不重复生成，仍恰 6 条，库内恰 6 行。
        assertThat(listItems(ALICE, ledgerId)).hasSize(6);
        assertThat(pendingItemRepository.count()).isEqualTo(6);

        long id0610 = itemIdByDate(items, "2025-06-10");
        long id0611 = itemIdByDate(items, "2025-06-11");
        long id0612 = itemIdByDate(items, "2025-06-12");
        long id0613 = itemIdByDate(items, "2025-06-13");
        long id0614 = itemIdByDate(items, "2025-06-14");
        long id0615 = itemIdByDate(items, "2025-06-15");

        // ---- 3a) 确认一条（06-10）→ 真实流水 + 支出扣款 50（Property 6）。----
        Map<String, Object> confirmed = parse(assertOk(
                post(ITEMS + "/" + id0610 + "/confirm", null, headers(ALICE, ledgerId))));
        assertThat(confirmed).containsEntry("status", "CONFIRMED");
        assertThat(confirmed.get("confirmedTransactionId")).isNotNull();
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("950.00");

        // ---- 3b) 修改后确认另一条（06-11，覆盖金额 80 + 备注）→ 用改后值扣款，规则模板不变（Property 6）。----
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("amount", "80.00");
        overrides.put("note", "改后备注");
        Map<String, Object> modConfirmed = parse(assertOk(
                post(ITEMS + "/" + id0611 + "/confirm", overrides, headers(ALICE, ledgerId))));
        assertThat(modConfirmed).containsEntry("status", "CONFIRMED");
        // 改后值入账：账户按 80 扣款（950 - 80 = 870）；恰新增一条流水。
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(balance()).isEqualByComparingTo("870.00");
        // 修改后确认不改动原规则模板字段（需求 4.3）：规则金额仍 50。
        Map<String, Object> ruleAfterMod = parse(assertOk(get(RULES + "/" + ruleId, headers(ALICE, ledgerId))));
        assertThat(new BigDecimal(ruleAfterMod.get("amount").toString())).isEqualByComparingTo("50.00");

        // ---- 3c) 跳过一条（06-12）→ 置 SKIPPED、零副作用（Property 7）。----
        Map<String, Object> skipped = parse(assertOk(
                post(ITEMS + "/" + id0612 + "/skip", null, headers(ALICE, ledgerId))));
        assertThat(skipped).containsEntry("status", "SKIPPED");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(balance()).isEqualByComparingTo("870.00");

        // ---- 4) 批量确认 06-13/06-14；批量跳过 06-15（Property 6、7、9）。----
        Map<String, Object> batchConfirm = parse(assertOk(post(ITEMS + "/batch-confirm",
                Map.of("ids", List.of(id0613, id0614)), headers(ALICE, ledgerId))));
        assertThat(batchConfirm).containsEntry("successCount", 2);
        assertThat(batchConfirm).containsEntry("failureCount", 0);
        assertThat(transactionRepository.count()).isEqualTo(4);
        assertThat(balance()).isEqualByComparingTo("770.00");

        Map<String, Object> batchSkip = parse(assertOk(post(ITEMS + "/batch-skip",
                Map.of("ids", List.of(id0615)), headers(ALICE, ledgerId))));
        assertThat(batchSkip).containsEntry("successCount", 1);
        assertThat(batchSkip).containsEntry("failureCount", 0);
        assertThat(transactionRepository.count()).isEqualTo(4);
        assertThat(balance()).isEqualByComparingTo("770.00");

        // 处理完毕：当前账本无 PENDING（需求 5.1 空列表不报错）。
        assertThat(listItems(ALICE, ledgerId)).isEmpty();

        // 金额闭合（Property 6 的账户守恒 + 方向）：账户净变动 == 已确认支出之和（50+80+50+50=230），方向为负。
        assertThat(new BigDecimal("1000.00").subtract(balance())).isEqualByComparingTo("230.00");
        assertThat(confirmedExpenseSum()).isEqualByComparingTo("230.00");

        // ---- 5a) 状态机幂等（Property 8）：对已处理项再确认 / 再跳过 → 409 RECURRING_ITEM_ALREADY_PROCESSED，无副作用。----
        ResponseEntity<String> reconfirm = post(ITEMS + "/" + id0610 + "/confirm", null, headers(ALICE, ledgerId));
        assertThat(reconfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(reconfirm)).containsEntry("code", "RECURRING_ITEM_ALREADY_PROCESSED");
        ResponseEntity<String> reskip = post(ITEMS + "/" + id0612 + "/skip", null, headers(ALICE, ledgerId));
        assertThat(reskip.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(reskip)).containsEntry("code", "RECURRING_ITEM_ALREADY_PROCESSED");
        assertThat(transactionRepository.count()).isEqualTo(4);
        assertThat(balance()).isEqualByComparingTo("770.00");

        // ---- 5b) 暂停 → 拨到 06-17，暂停期间不生成新 PENDING（需求 6.1、Property 10）。----
        assertOk(post(RULES + "/" + ruleId + "/pause", null, headers(ALICE, ledgerId)));
        clockTo(LocalDate.of(2025, 6, 17));
        assertThat(listItems(ALICE, ledgerId)).isEmpty();

        // ---- 5c) 恢复 → 仅生成恢复当日（06-17）及之后期次，不回补暂停区间（06-16 不生成，需求 6.2、Property 10）。----
        assertOk(post(RULES + "/" + ruleId + "/resume", null, headers(ALICE, ledgerId)));
        List<Map<String, Object>> afterResume = listItems(ALICE, ledgerId);
        assertThat(afterResume.stream().map(i -> i.get("occurrenceDate").toString()).toList())
                .containsExactly("2025-06-17");
        long id0617 = itemIdByDate(afterResume, "2025-06-17");

        // ---- 隔离（Property 11，真实链路）：跨租户 / 跨账本操作 06-17 项 → 404，零副作用。----
        assertCrossTenantIsolation(id0617);

        // ---- 5d) 编辑 → 前向生效；既有 06-17 PENDING 快照不变、CONFIRMED 历史不变（需求 6.3、6.4、Property 10）。----
        Map<String, Object> editBody = dailyExpenseBody("999.00", CREATE_DAY);
        assertOk(rest.exchange(url(RULES + "/" + ruleId), HttpMethod.PUT,
                new HttpEntity<>(editBody, headers(ALICE, ledgerId)), String.class));
        // 既有 PENDING（06-17）快照仍为生成时的 50（编辑只对之后新项生效）。
        List<Map<String, Object>> afterEdit = listItems(ALICE, ledgerId);
        Map<String, Object> item0617 = afterEdit.stream()
                .filter(i -> i.get("occurrenceDate").toString().equals("2025-06-17")).findFirst().orElseThrow();
        assertThat(new BigDecimal(item0617.get("amount").toString())).isEqualByComparingTo("50.00");
        // CONFIRMED 历史 / 余额不因编辑改变。
        assertThat(transactionRepository.count()).isEqualTo(4);
        assertThat(balance()).isEqualByComparingTo("770.00");

        // 前向生效：拨到 06-18，新期次采用编辑后的金额 999。
        clockTo(LocalDate.of(2025, 6, 18));
        List<Map<String, Object>> afterForward = listItems(ALICE, ledgerId);
        Map<String, Object> item0618 = afterForward.stream()
                .filter(i -> i.get("occurrenceDate").toString().equals("2025-06-18")).findFirst().orElseThrow();
        assertThat(new BigDecimal(item0618.get("amount").toString())).isEqualByComparingTo("999.00");
        // 06-17 依旧保持旧快照 50。
        Map<String, Object> stillOld = afterForward.stream()
                .filter(i -> i.get("occurrenceDate").toString().equals("2025-06-17")).findFirst().orElseThrow();
        assertThat(new BigDecimal(stillOld.get("amount").toString())).isEqualByComparingTo("50.00");

        // ---- 5e) 删除 → PENDING 消失；CONFIRMED / SKIPPED 历史保留（需求 6.5、6.6、Property 10）。----
        ResponseEntity<String> deleted = rest.exchange(url(RULES + "/" + ruleId), HttpMethod.DELETE,
                new HttpEntity<>(headers(ALICE, ledgerId)), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // PENDING 全部消失。
        assertThat(listItems(ALICE, ledgerId)).isEmpty();
        // CONFIRMED（4 条）与 SKIPPED（06-12、06-15 两条）记录保留在生成项表。
        assertThat(pendingItemRepository
                .findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(ledgerId, PendingStatus.CONFIRMED))
                .hasSize(4);
        assertThat(pendingItemRepository
                .findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(ledgerId, PendingStatus.SKIPPED))
                .hasSize(2);
        // 已确认历史流水与余额一律不因删除回滚（需求 6.6）。
        assertThat(transactionRepository.count()).isEqualTo(4);
        assertThat(balance()).isEqualByComparingTo("770.00");
    }

    // ==================================================================================
    // 收入方向：确认收入项 → 账户 +amount（Property 6 的方向另一半 + 金额闭合的正号）。
    // ==================================================================================

    @Test
    void confirmIncomeItem_increasesBalance_byAmount() {
        // 06-10 建收入规则，开始日期 06-15；拨到 06-15 → 恰 1 条收入 PENDING。
        clockTo(CREATE_DAY);
        Map<String, Object> body = new HashMap<>();
        body.put("amount", "200.00");
        body.put("categoryId", incomeCategoryId);
        body.put("accountId", accountId);
        body.put("type", "income");
        body.put("note", "工资");
        body.put("frequency", "DAILY");
        body.put("startDate", TODAY.toString());
        body.put("endCondition", "NEVER");
        assertThat(post(RULES, body, headers(ALICE, ledgerId)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        clockTo(TODAY);
        List<Map<String, Object>> items = listItems(ALICE, ledgerId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("type", "income");
        long incomeItemId = itemIdByDate(items, "2025-06-15");

        assertOk(post(ITEMS + "/" + incomeItemId + "/confirm", null, headers(ALICE, ledgerId)));
        // 收入按 +amount 入账：1000 + 200 = 1200（金额闭合的正号方向）。
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("1200.00");
    }

    // ==================================================================================
    // 账本 / 用户隔离（Property 11，真实链路）：跨用户 / 跨账本查询与操作。
    // ==================================================================================

    @Test
    void isolation_crossTenantCannotSeeOrOperate() {
        clockTo(CREATE_DAY);
        createDailyExpenseRule("50.00", CREATE_DAY);
        clockTo(TODAY);
        List<Map<String, Object>> aliceItems = listItems(ALICE, ledgerId);
        assertThat(aliceItems).hasSize(6);
        long aliceItemId = itemIdByDate(aliceItems, "2025-06-10");

        assertCrossTenantIsolation(aliceItemId);
    }

    /**
     * 断言对 ALICE 归属项 {@code aliceItemId} 的跨租户 / 跨账本操作一律 404 且零副作用：
     * ① BOB 在自己账本查询看不到 ALICE 的项；② BOB 确认 ALICE 的项 → NOT_FOUND；
     * ③ ALICE 用另一账本头确认该项 → NOT_FOUND。全程 ALICE 余额 / 流水 / 项状态不变。
     */
    private void assertCrossTenantIsolation(long aliceItemId) {
        BigDecimal balanceBefore = balance();
        long txCountBefore = transactionRepository.count();

        // BOB 有自己的账本；其待确认项列表看不到 ALICE 的数据（需求 8.4）。
        Long bobLedgerId = seedLedger(BOB).getId();
        assertThat(listItems(BOB, bobLedgerId)).isEmpty();

        // BOB 以自己账本头确认 ALICE 的项 → 404 NOT_FOUND（需求 8.5）。
        ResponseEntity<String> byBob = post(ITEMS + "/" + aliceItemId + "/confirm", null,
                headers(BOB, bobLedgerId));
        assertThat(byBob.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(byBob)).containsEntry("code", "NOT_FOUND");

        // ALICE 用另一账本头（跨账本）确认该项 → 404 NOT_FOUND（需求 8.3、8.5）。
        Long aliceOtherLedgerId = seedLedger(ALICE).getId();
        ResponseEntity<String> crossLedger = post(ITEMS + "/" + aliceItemId + "/confirm", null,
                headers(ALICE, aliceOtherLedgerId));
        assertThat(crossLedger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(crossLedger)).containsEntry("code", "NOT_FOUND");

        // 零副作用：ALICE 余额 / 流水数不变。
        assertThat(balance()).isEqualByComparingTo(balanceBefore);
        assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
    }

    // ---------------------------------- 业务辅助 ----------------------------------

    /** 经真实 POST 创建一条 DAILY 支出规则，返回其 id。 */
    private long createDailyExpenseRule(String amount, LocalDate startDate) {
        ResponseEntity<String> resp = post(RULES, dailyExpenseBody(amount, startDate), headers(ALICE, ledgerId));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.parseLong(parse(resp).get("id").toString());
    }

    private Map<String, Object> dailyExpenseBody(String amount, LocalDate startDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("categoryId", expenseCategoryId);
        body.put("accountId", accountId);
        body.put("type", "expense");
        body.put("note", "房租");
        body.put("frequency", "DAILY");
        body.put("startDate", startDate.toString());
        body.put("endCondition", "NEVER");
        return body;
    }

    /** GET 待确认项列表（断言 200）。 */
    private List<Map<String, Object>> listItems(long userId, Long ledgerId) {
        return parseList(assertOk(get(ITEMS, headers(userId, ledgerId))));
    }

    private long itemIdByDate(List<Map<String, Object>> items, String date) {
        return items.stream()
                .filter(i -> date.equals(i.get("occurrenceDate").toString()))
                .map(i -> Long.parseLong(i.get("id").toString()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到到期日为 " + date + " 的待确认项"));
    }

    private BigDecimal balance() {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    /** 已确认（CONFIRMED）支出流水金额之和（用于金额闭合断言）。 */
    private BigDecimal confirmedExpenseSum() {
        return transactionRepository.findAll().stream()
                .map(t -> t.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void clockTo(LocalDate day) {
        E2E_CLOCK.setInstant(instantAt(day));
    }

    private static Instant instantAt(LocalDate day) {
        // Asia/Shanghai 当日 00:00 → LocalDate.now(clock) == day。
        return day.atStartOfDay(ZONE).toInstant();
    }

    // ---------------------------------- 数据播种 ----------------------------------

    private Ledger seedLedger(long ownerId) {
        LocalDateTime now = LocalDateTime.now(E2E_CLOCK);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("个人账本");
        l.setType(Ledger.TYPE_PERSONAL);
        l.setSortOrder(0);
        l.setDefault(false);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(l);
        LedgerMember m = new LedgerMember();
        m.setLedgerId(saved.getId());
        m.setUserId(ownerId);
        m.setRole(LedgerMember.ROLE_OWNER);
        m.setCreatedAt(now);
        memberRepository.save(m);
        return saved;
    }

    private Long seedAccount(long userId, String balance) {
        LocalDateTime now = LocalDateTime.now(E2E_CLOCK);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a).getId();
    }

    private void linkAccountToLedger(Long accountId, long ledgerId) {
        AccountLedger link = new AccountLedger();
        link.setAccountId(accountId);
        link.setLedgerId(ledgerId);
        link.setVisibleToOthers(true);
        link.setShowBalance(true);
        link.setCreatedAt(LocalDateTime.now(E2E_CLOCK));
        accountLedgerRepository.save(link);
    }

    private Long seedCategory(long ledgerId, CategoryKind kind, String name) {
        LocalDateTime now = LocalDateTime.now(E2E_CLOCK);
        Category c = new Category();
        c.setUserId(ALICE);
        c.setLedgerId(ledgerId);
        c.setParentId(null);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c).getId();
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> assertOk(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private HttpHeaders headers(long userId, Long ledgerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        return headers;
    }

    private String token(long userId, String secret, Duration ttl) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date issuedAt = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", "user")
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    private Map<String, Object> parse(ResponseEntity<String> response) {
        String raw = response.getBody();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON 对象: " + raw, e);
        }
    }

    private List<Map<String, Object>> parseList(ResponseEntity<String> response) {
        String raw = response.getBody();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON 数组: " + raw, e);
        }
    }

    // ---------------------------------- 可变时钟 ----------------------------------

    /**
     * 进程内可变时钟：服务层构造期注入本实例的引用，测试通过 {@link #setInstant} 拨动当前时刻即对全链路生效
     * （{@code LocalDate.now(clock)} / {@code Instant.now(clock)} 每次读取最新值）。用于在真实端点链路上模拟
     * 「先在过去建规则、再前拨到今天触发懒生成回补」的时间演进。
     */
    static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
