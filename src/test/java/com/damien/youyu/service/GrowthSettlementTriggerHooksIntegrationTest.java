package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.api.dto.AccountResponse;
import com.damien.youyu.api.dto.TransactionResponse;
import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 结算触发挂载点的集成测试（任务 8.4，需求 9.2、9.4、9.5、9.6、9.7、9.8、9.9）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：经真实 HTTP、真实 Spring Security 过滤链与 JWT、
 * 真实服务/持久化层（H2 {@code MODE=MySQL}）验证「哪些路径触发结算、哪些不触发、结算故障如何被
 * 隔离」。结算挂在业务事务的 {@code afterCommit} 上（{@link GrowthSettlementTrigger}），因此<b>必须
 * 让被测事务真实提交</b>——真实 HTTP 请求天然满足：每个请求各自开启并提交业务事务，
 * {@code afterCommit} 于是在<b>处理该请求的线程内同步触发</b>，响应返回时结算已完成，断言因而确定。
 * 若改用测试级 {@code @Transactional} 包裹会在方法结束时回滚，{@code afterCommit} 永不触发，
 * 「未触发结算」的断言便会假绿——这条与兄弟测试 {@code GrowthSettlementTriggerPropertyTest} 的
 * 驱动方式说明一致。</p>
 *
 * <h2>如何观测结算「有没有发生 / 发生几次 / 在哪个线程」</h2>
 * <p>用 {@link RecordingSettlementService}（一个 {@code @Primary} 的 {@link GrowthSettlementService}
 * 子类）装饰真实结算：默认<b>委托</b>给被 Spring 事务代理包裹的真实 bean（{@code REQUIRES_NEW}
 * 因而照常生效），委托前记录每次 {@code settle} 的调用计数与执行线程，并可按需在委托前抛出注入的
 * 异常。<b>刻意不用 {@code @SpyBean}</b>：对带 {@code @Transactional} 的类做 Mockito spy 会绕过 Spring
 * 的事务代理，令 {@code REQUIRES_NEW} 失效，从而破坏「失败后下次结算自愈」这一步所需的真实独立事务；
 * 委托型装饰器保留了真实事务语义，与兄弟测试同一手法。</p>
 *
 * <h2>四组断言</h2>
 * <ol>
 *   <li><b>不触发路径零副作用</b>（需求 9.2）：转账、余额调整、交易修改、软删除、回收站恢复与彻底删除、
 *       预算写入、登录/注册、注销、邀请绑定各走一遍，断言 {@code settle} 调用数为 0、
 *       {@code growth_events} 与 {@code user_growth} 两表的行数与列取值逐行不变。</li>
 *   <li><b>批量导入恰好一次结算</b>（需求 9.4）：账单导入 200 行、数据导入 200 笔各一次，
 *       用计数装饰器断言 {@code settle} 恰好被调用 1 次（整批是单个事务，多次 {@code requestSettlement}
 *       在同一事务内被合并为一轮）。</li>
 *   <li><b>故障隔离 + 失败自愈</b>（需求 9.5、9.6、9.7、9.8）：让 {@code settle} 抛异常，断言记账仍返回
 *       201、响应体不含任何成长字段、交易与账户余额已提交、两表对该用户零变更；随后放开注入、触发一次
 *       正常结算，断言经验事件被补齐（幂等可重入、失败自愈）。</li>
 *   <li><b>结算在调用线程内同步执行</b>（需求 9.9）：在测试线程上直接经 {@link TransactionService#create}
 *       记一笔，断言结算发生在<b>测试线程本身</b>——比断言「上下文里没有线程池 Bean」更直接地证明
 *       没有任何 {@code @Async} / 定时任务 / 线程池 / 执行器驱动结算。</li>
 * </ol>
 *
 * <p>使用独立命名的内存库，避免污染其它共享内存库的切片测试；放宽发码 IP 限额（本类要建多个账号，
 * 请求全部同源自 127.0.0.1，发码防刷在别处覆盖）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-hooks-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "app.auth.email-code.ip-per-minute=100000",
        "app.auth.email-code.ip-per-day=100000"
})
@Import(GrowthSettlementTriggerHooksIntegrationTest.RecordingConfig.class)
class GrowthSettlementTriggerHooksIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private GrowthEventRepository growthEventRepository;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private RecordingSettlementService recording;

    // ============ 1) 不触发路径：两表零副作用（需求 9.2）============

    /**
     * 转账、余额调整、交易修改、软删除、回收站恢复、彻底删除、预算写入各走一遍，断言 {@code settle}
     * 一次都没被调用，且 {@code growth_events} 与 {@code user_growth} 两表对该用户的行数与列取值逐行不变。
     *
     * <p>先记一笔有效收支<b>触发一次结算</b>，让两表落下真实数据并快照；此后所有操作都属于「不触发路径」
     * ——转账与余额调整各自建行且 {@code ledger_id} 为 null（不是有效记账交易），修改/删除/恢复/彻底删除
     * 都不新增有效记账行，预算写入不碰交易表——因此重置计数器后 {@code settle} 计数应恒为 0。</p>
     */
    @Test
    void nonTriggeringTransactionAndBudgetPaths_produceZeroGrowthSideEffect() {
        String email = "hook_nontrigger@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);

        AccountResponse cash = createAccount(token, "现金", "CASH", "100000.00", 0);
        AccountResponse card = createAccount(token, "银行卡", "BANK_CARD", "0.00", 1);
        long catExpense = createCategory(token, "EXPENSE", "餐饮");
        createCategory(token, "INCOME", "工资");

        // 唯一的「触发路径」：记一笔有效收支 → 结算真实执行、两表落数据。
        TransactionResponse seed = postExpense(token, "50.00", cash.id(), catExpense).getBody();
        long recordId = seed.id();
        assertThat(userGrowthCountOf(userId)).isEqualTo(1L);
        assertThat(growthEventCountOf(userId)).isPositive();

        // 快照两表当前状态（逐行、含全部列）。
        List<Map<String, Object>> eventsBefore = growthEventsOf(userId);
        List<Map<String, Object>> profileBefore = userGrowthRowsOf(userId);

        recording.reset();

        // --- 不触发路径，各走一遍 ---
        transfer(token, "10.00", cash.id(), card.id());                 // 转账（ledger_id=null）
        adjustBalance(token, card.id(), "12345.00");                    // 余额调整（ledger_id=null）
        updateExpense(token, recordId, "60.00", cash.id(), catExpense); // 交易修改
        deleteTransaction(token, recordId);                             // 软删除（移入回收站）
        restoreTransaction(token, recordId);                            // 回收站恢复
        deleteTransaction(token, recordId);                             // 再软删除，供彻底删除
        purgeTransaction(token, recordId);                              // 彻底删除
        setTotalBudget(token, "8000.00");                               // 预算写入

        // settle 一次未被调用；两表逐行快照与列取值原样不变（需求 9.2）。
        assertThat(recording.settleCount()).as("不触发路径不得触发任何结算").isZero();
        assertThat(growthEventsOf(userId)).isEqualTo(eventsBefore);
        assertThat(userGrowthRowsOf(userId)).isEqualTo(profileBefore);
    }

    /**
     * 登录/注册、邀请绑定、注销三条路径均<b>不触发结算</b>（需求 9.2）：断言全程 {@code settle} 计数为 0，
     * 且这些路径不为相关用户创建任何成长行。
     */
    @Test
    void authInviteBindingAndDeregistration_doNotTriggerSettlement() {
        recording.reset();

        // 登录/注册（不带邀请码）。
        String emailB = "hook_auth_b@example.com";
        String tokenB = registerAndLogin(emailB);
        long idB = userIdOf(emailB);

        // 邀请绑定：C 携带 B 的邀请码建号，真实建立邀请关系。
        String codeB = inviteCodeOf(idB);
        String emailC = "hook_auth_c@example.com";
        ResponseEntity<Map> loginC = emailLogin(emailC, codeB);
        assertThat(loginC.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyOf(loginC)).containsEntry("inviteBound", true);
        long idC = userIdOf(emailC);

        // 注销：一次性建一个可注销的用户 D（仅个人默认账本、无协作成员）后走完整注销流程。
        String emailD = "hook_auth_d@example.com";
        String tokenD = registerAndLogin(emailD);
        long idD = userIdOf(emailD);
        deleteAccount(tokenD, emailD);

        assertThat(recording.settleCount())
                .as("登录/注册、邀请绑定、注销均不得触发结算").isZero();
        // 这些路径不为任何相关用户创建成长行。
        assertThat(userGrowthCountOf(idB)).isZero();
        assertThat(growthEventCountOf(idB)).isZero();
        assertThat(userGrowthCountOf(idC)).isZero();
        assertThat(growthEventCountOf(idC)).isZero();
        assertThat(userGrowthCountOf(idD)).isZero();
        assertThat(growthEventCountOf(idD)).isZero();
        assertThat(tokenB).isNotBlank();
    }

    // ============ 2) 批量导入：恰好一次结算（需求 9.4）============

    /**
     * 账单导入 200 行是单个业务事务，故一次请求恰好触发 1 次结算（需求 9.4）：导入内 200 行各不单独触发，
     * 同一事务内的多次 {@code requestSettlement} 被合并为一轮。
     */
    @Test
    void billImportOfTwoHundredRows_triggersExactlyOneSettlement() {
        String email = "hook_billimport@example.com";
        String token = registerAndLogin(email);

        AccountResponse acc = createAccount(token, "现金", "CASH", "0.00", 0);
        long defExpense = createCategory(token, "EXPENSE", "默认支出");
        long defIncome = createCategory(token, "INCOME", "默认收入");

        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Map<String, Object> e = new HashMap<>();
            e.put("type", "expense");
            e.put("amount", "1.00");
            e.put("occurredAt", "2025-06-15T12:00:00");
            e.put("externalId", "bill-" + i);
            e.put("note", "账单" + i);
            entries.add(e);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("accountId", acc.id());
        req.put("defaultExpenseCategoryId", defExpense);
        req.put("defaultIncomeCategoryId", defIncome);
        req.put("entries", entries);

        recording.reset();
        ResponseEntity<Map> resp = rest.exchange(url("/api/imports/bills"), HttpMethod.POST,
                new HttpEntity<>(req, authJson(token)), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Number) bodyOf(resp).get("imported")).intValue()).isEqualTo(200);
        assertThat(recording.settleCount()).as("200 行账单导入恰好 1 次结算").isEqualTo(1);
    }

    /**
     * 数据导入 200 笔是单个业务事务，故一次请求恰好触发 1 次结算（需求 9.4）。
     */
    @Test
    void dataImportOfTwoHundredRecords_triggersExactlyOneSettlement() {
        String email = "hook_dataimport@example.com";
        String token = registerAndLogin(email);

        String json = buildImportJson(200);

        recording.reset();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(url("/api/import"), HttpMethod.POST,
                new HttpEntity<>(json, headers), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Number) bodyOf(resp).get("transactions")).intValue()).isEqualTo(200);
        assertThat(recording.settleCount()).as("200 笔数据导入恰好 1 次结算").isEqualTo(1);
    }

    // ============ 3) 故障隔离 + 失败自愈（需求 9.5、9.6、9.7、9.8）============

    /**
     * {@code settle} 抛异常时记账<b>完全不受影响</b>：返回 201、响应体不含任何成长字段、交易与账户余额
     * 已提交、两表对该用户零变更（需求 9.5、9.6、9.7）；随后放开注入并触发一次正常结算，经验事件被补齐
     * （失败自愈，需求 9.8）。
     */
    @Test
    void settlementFailure_isInvisibleToRecordApi_thenSelfHealsOnNextSettlement() {
        String email = "hook_faultiso@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);

        AccountResponse cash = createAccount(token, "现金", "CASH", "1000.00", 0);
        long catExpense = createCategory(token, "EXPENSE", "餐饮");

        // 注入结算故障：afterCommit 里 settle 会抛异常，trigger 必须吞掉。
        recording.reset();
        recording.throwOnSettle(new IllegalStateException("注入：结算故障"));

        ResponseEntity<String> created = rest.exchange(url("/api/transactions"), HttpMethod.POST,
                new HttpEntity<>(expenseBody("50.00", cash.id(), catExpense), authJson(token)),
                String.class);

        // 记账照常返回 201（结算故障对记账不可见，需求 9.6）。
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 结算确实被尝试过（并抛错）。
        assertThat(recording.settleCount()).isEqualTo(1);
        // 记账响应体不含任何成长字段（需求 9.6：记账响应不含成长字段）。
        String json = created.getBody();
        assertThat(json).doesNotContain(
                "\"level\"", "\"badges\"", "\"currentStreakDays\"", "\"maxStreakDays\"",
                "\"totalRecordDays\"", "\"maxLevelReached\"", "\"nextLevelExp\"", "\"expInCurrentLevel\"");

        // 交易与账户余额已提交（结算回滚不连坐已提交的记账，需求 9.7）。
        List<TransactionResponse> txs = listTransactions(token);
        assertThat(txs).hasSize(1);
        assertThat(balanceOf(token, cash.id())).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("950.00"));
        // 两表对该用户零变更：结算失败无部分写入（需求 9.7）。
        assertThat(userGrowthCountOf(userId)).isZero();
        assertThat(growthEventCountOf(userId)).isZero();

        // --- 失败自愈：放开注入，再记一笔触发一次正常结算，事件被补齐（需求 9.8）---
        recording.reset();
        recording.clearThrow();
        ResponseEntity<TransactionResponse> created2 = rest.exchange(url("/api/transactions"),
                HttpMethod.POST,
                new HttpEntity<>(expenseBody("20.00", cash.id(), catExpense), authJson(token)),
                TransactionResponse.class);
        assertThat(created2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recording.settleCount()).isEqualTo(1);

        // 结算成功补齐：档案落地、FIRST_RECORD 与今日 DAILY_RECORD 已写入、等级已到 Lv2。
        assertThat(userGrowthCountOf(userId)).isEqualTo(1L);
        List<String> keys = growthEventRepository.findEventKeysByUserId(userId);
        assertThat(keys).contains("FIRST_RECORD");
        assertThat(keys).anyMatch(k -> k.startsWith("DAILY_RECORD:"));
        UserGrowth profile = userGrowthRepository.findById(userId).orElseThrow();
        assertThat(profile.getLevel()).isGreaterThanOrEqualTo(2);
    }

    // ============ 4) 结算在调用线程内同步执行（需求 9.9）============

    /**
     * 记账触发的结算发生在<b>调用线程本身</b>：在测试线程上直接经 {@link TransactionService#create} 记一笔
     * （其 {@code afterCommit} 于是在本测试线程内同步触发），断言装饰器记录到的结算线程恰为测试线程。
     * 这直接锁死「没有任何 {@code @Async} / 定时任务 / 线程池 / 执行器驱动结算」（需求 9.9）——一旦结算被
     * 挪到别的线程，比对立即失败。
     */
    @Test
    void settlementRunsSynchronouslyOnTheCallingThread() {
        String email = "hook_thread@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);

        AccountResponse cash = createAccount(token, "现金", "CASH", "1000.00", 0);
        long catExpense = createCategory(token, "EXPENSE", "餐饮");
        Long ledgerId = ledgerRepository.findFirstByUserIdAndIsDefaultTrue(userId).orElseThrow().getId();

        recording.reset();
        Thread callingThread = Thread.currentThread();

        // 直接在测试线程上调用（create 自带 @Transactional，提交后 afterCommit 在本线程同步触发）。
        transactionService.create(userId, ledgerId, "expense", new BigDecimal("30.00"),
                cash.id(), catExpense, LocalDateTime.now(), "同线程结算");

        assertThat(recording.settleCount()).isEqualTo(1);
        assertThat(recording.threads()).containsExactly(callingThread);
    }

    // ---------------------------------- 成长两表读辅助 ----------------------------------

    private long userGrowthCountOf(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long growthEventCountOf(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private List<Map<String, Object>> growthEventsOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, user_id, event_type, event_key, exp_amount, created_at "
                        + "FROM growth_events WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> userGrowthRowsOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                        + "last_record_date, last_settled_at, created_at, updated_at "
                        + "FROM user_growth WHERE user_id = ?", userId);
    }

    // ---------------------------------- HTTP 辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    /** 邮箱验证码登录/注册合一（不带邀请码），返回 JWT。 */
    private String registerAndLogin(String email) {
        ResponseEntity<Map> login = emailLogin(email, null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) bodyOf(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 以新鲜 LOGIN 验证码执行 email-login（清历史码规避 60s 冷却）；{@code inviteCode} 可为 null。 */
    private ResponseEntity<Map> emailLogin(String email, String inviteCode) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("code", latestCode(email, EmailCodePurpose.LOGIN));
        payload.put("inviteCode", inviteCode);
        return rest.postForEntity(url("/api/auth/email-login"), payload, Map.class);
    }

    private AccountResponse createAccount(String token, String name, String type,
                                          String initialBalance, int sortOrder) {
        Map<String, Object> body = Map.of(
                "name", name, "type", type, "initialBalance", initialBalance, "sortOrder", sortOrder);
        ResponseEntity<AccountResponse> resp = rest.exchange(url("/api/accounts"), HttpMethod.POST,
                new HttpEntity<>(body, authJson(token)), AccountResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private long createCategory(String token, String kind, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("name", name);
        body.put("parentId", null);
        ResponseEntity<Map> resp = rest.exchange(url("/api/categories"), HttpMethod.POST,
                new HttpEntity<>(body, authJson(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) bodyOf(resp).get("id")).longValue();
    }

    private Map<String, Object> expenseBody(String amount, Long accountId, Long categoryId) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "expense");
        body.put("amount", amount);
        body.put("accountId", accountId);
        body.put("categoryId", categoryId);
        body.put("occurredAt", "2025-06-15T12:30:00");
        body.put("note", "记一笔");
        return body;
    }

    private ResponseEntity<TransactionResponse> postExpense(String token, String amount,
                                                            Long accountId, Long categoryId) {
        ResponseEntity<TransactionResponse> resp = rest.exchange(url("/api/transactions"),
                HttpMethod.POST, new HttpEntity<>(expenseBody(amount, accountId, categoryId), authJson(token)),
                TransactionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp;
    }

    private void transfer(String token, String amount, Long sourceId, Long destId) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("sourceAccountId", sourceId);
        body.put("destinationAccountId", destId);
        body.put("occurredAt", "2025-06-15T12:30:00");
        body.put("note", "转账");
        ResponseEntity<TransactionResponse> resp = rest.exchange(url("/api/accounts/transfer"),
                HttpMethod.POST, new HttpEntity<>(body, authJson(token)), TransactionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void adjustBalance(String token, Long accountId, String targetBalance) {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("balance", targetBalance);
        body.put("occurredAt", "2025-06-15T12:30:00");
        body.put("note", "余额调整");
        ResponseEntity<TransactionResponse> resp = rest.exchange(url("/api/transactions/adjust"),
                HttpMethod.POST, new HttpEntity<>(body, authJson(token)), TransactionResponse.class);
        // 有差额返回 201；无差额返回 204。构造上必有差额。
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private void updateExpense(String token, long id, String amount, Long accountId, Long categoryId) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "expense");
        body.put("amount", amount);
        body.put("accountId", accountId);
        body.put("categoryId", categoryId);
        body.put("occurredAt", "2025-06-15T12:30:00");
        body.put("note", "修改后");
        ResponseEntity<TransactionResponse> resp = rest.exchange(url("/api/transactions/" + id),
                HttpMethod.PUT, new HttpEntity<>(body, authJson(token)), TransactionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void deleteTransaction(String token, long id) {
        ResponseEntity<Void> resp = rest.exchange(url("/api/transactions/" + id),
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void restoreTransaction(String token, long id) {
        ResponseEntity<TransactionResponse> resp = rest.exchange(url("/api/transactions/" + id + "/restore"),
                HttpMethod.POST, new HttpEntity<>(bearer(token)), TransactionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void purgeTransaction(String token, long id) {
        ResponseEntity<Void> resp = rest.exchange(url("/api/transactions/" + id + "/purge"),
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void setTotalBudget(String token, String amount) {
        ResponseEntity<Map> resp = rest.exchange(url("/api/budgets?month=2025-06"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("amount", amount), authJson(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<TransactionResponse> listTransactions(String token) {
        ResponseEntity<List<TransactionResponse>> resp = rest.exchange(
                url("/api/transactions?page=0&size=200"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private BigDecimal balanceOf(String token, Long accountId) {
        ResponseEntity<List<AccountResponse>> resp = rest.exchange(url("/api/accounts"),
                HttpMethod.GET, new HttpEntity<>(bearer(token)), new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().stream()
                .filter(a -> a.id().equals(accountId))
                .map(AccountResponse::currentBalance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("账户不存在: " + accountId));
    }

    /** 走完整注销流程并断言成功（204 + users 行消失）。 */
    private void deleteAccount(String token, String email) {
        long userId = userIdOf(email);
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String code = latestCode(email, EmailCodePurpose.DELETE);

        ResponseEntity<Void> deleted = rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code), authJson(token)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    private String inviteCodeOf(long userId) {
        String code = userRepository.findById(userId)
                .orElseThrow(() -> new AssertionError("用户不存在: " + userId))
                .getInviteCode();
        assertThat(code).as("建号时应写入邀请码").isNotBlank();
        return code;
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }

    /** 构造一个可导入的 JSON 文档：1 账户、1 支出分类、{@code count} 笔支出交易。 */
    private String buildImportJson(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"accounts\":[{\"ref\":\"a1\",\"name\":\"现金\",\"type\":\"CASH\","
                + "\"initialBalance\":\"0.00\",\"sortOrder\":0}],");
        sb.append("\"categories\":[{\"ref\":\"c1\",\"kind\":\"EXPENSE\",\"name\":\"餐饮\"}],");
        sb.append("\"transactions\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"expense\",\"amount\":\"1.00\",")
                    .append("\"occurredAt\":\"2025-06-15T12:00:00+08:00\",")
                    .append("\"accountRef\":\"a1\",\"categoryRef\":\"c1\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---------------------------------- 记录型结算装饰器 ----------------------------------

    /**
     * 记录并可注入故障的 {@link GrowthSettlementService}：默认委托给真实（被 Spring 事务代理包裹的）bean，
     * {@code REQUIRES_NEW} 因而照常生效。它<b>不是</b> Mockito 替身，也不替换真实结算——只在委托前记录
     * 调用计数与执行线程，并可在委托前抛出注入异常。构造时给父类传 {@code null}：本类覆盖 {@code settle}
     * 并只委托给 {@code delegate}，父类字段永不被触及（与兄弟测试同一手法）。
     */
    static class RecordingSettlementService extends GrowthSettlementService {

        private final GrowthSettlementService delegate;
        private final AtomicInteger settleCount = new AtomicInteger();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private volatile RuntimeException toThrow;

        RecordingSettlementService(GrowthSettlementService delegate) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.delegate = delegate;
        }

        @Override
        public SettleOutcome settle(Long userId, TriggerSource source) {
            settleCount.incrementAndGet();
            threads.add(Thread.currentThread());
            RuntimeException injected = this.toThrow;
            if (injected != null) {
                throw injected;
            }
            return delegate.settle(userId, source);   // 经事务代理 → REQUIRES_NEW 生效
        }

        void reset() {
            settleCount.set(0);
            threads.clear();
            toThrow = null;
        }

        void throwOnSettle(RuntimeException e) {
            this.toThrow = e;
        }

        void clearThrow() {
            this.toThrow = null;
        }

        int settleCount() {
            return settleCount.get();
        }

        List<Thread> threads() {
            // 去重：断言「都在调用线程」只需看去重后的集合。
            return threads.stream().distinct().toList();
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        @Primary
        RecordingSettlementService recordingSettlementService(
                @Qualifier("growthSettlementService") GrowthSettlementService real) {
            return new RecordingSettlementService(real);
        }
    }
}
