package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.api.dto.AccountResponse;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.MonthlyReportResponse;
import com.damien.youyu.api.dto.TransactionResponse;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.api.dto.UserSummaryResponse;
import com.damien.youyu.service.ImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 端到端主路径联调（任务 12.2，需求 11.4）。
 *
 * <p>本测试为全栈 {@code @SpringBootTest}(RANDOM_PORT)，经真实 HTTP、真实 Spring Security 过滤链与
 * JWT、真实服务/持久化层（H2），串起「有余」记账的完整幸福路径，验证各模块协同工作且服务端为单一
 * 数据源（重新加载即得最新已保存数据，需求 11.4）：</p>
 *
 * <ol>
 *   <li>注册新用户并登录换取 JWT，其后所有请求均携带 {@code Authorization: Bearer <token>}。</li>
 *   <li>建账户（现金 + 银行卡，供转账使用）。</li>
 *   <li>建分类（支出父分类「餐饮」+ 子分类「外卖」，收入分类「工资」）。</li>
 *   <li>记一笔：支出、收入、转账各一，断言账户余额按记账语义更新且总额守恒。</li>
 *   <li>看报表：月报 / 分类占比 / 月度趋势，断言收支结余一致、转账被排除、分类占比合计 100%。</li>
 *   <li>导出：GET /api/export?format=json，断言导出内容包含已创建数据。</li>
 *   <li>重新加载一致（11.4）：重新拉取 /api/accounts、/api/transactions、/api/me，断言持久化状态
 *       与创建结果一致（服务端为唯一数据源）。</li>
 *   <li>往返：把导出 JSON 导入到第二个全新用户，断言还原记录数与余额一致。</li>
 *   <li>多租户隔离：第二个用户在导入前看不到第一个用户的任何数据（需求 2.3）。</li>
 * </ol>
 *
 * <p>使用独立命名的内存库，避免与其它共享内存库的切片测试相互污染。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-e2e-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class EndToEndMainPathIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 交易发生时间固定在 2025-06，使报表断言可确定。 */
    private static final String OCCURRED_AT = "2025-06-15T12:30:00";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void mainHappyPath_registerToExportToReload_isConsistentAndIsolated() throws Exception {
        // 1) 注册 + 登录 → JWT
        String token = registerAndLogin("e2e_owner", "password123");

        // 2) 建账户：现金（初始 1000.00）+ 银行卡（初始 0.00），供转账使用
        AccountResponse cash = createAccount(token, "现金", "CASH", "1000.00", 0);
        AccountResponse card = createAccount(token, "招商银行卡", "BANK_CARD", "0.00", 1);
        assertThat(cash.currentBalance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1000.00"));
        assertThat(card.currentBalance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("0.00"));

        // 3) 建分类：支出父「餐饮」> 子「外卖」，收入「工资」
        Long food = createCategory(token, "EXPENSE", "餐饮", null).get("id").asLong();
        Long takeout = createCategory(token, "EXPENSE", "外卖", food).get("id").asLong();
        Long salary = createCategory(token, "INCOME", "工资", null).get("id").asLong();

        // 4) 记一笔：支出 38.80（现金/外卖）、收入 12500.00（银行卡/工资）、转账 2000.00（银行卡→现金）
        createExpense(token, "38.80", cash.id(), takeout, "午餐，麻辣烫");
        createIncome(token, "12500.00", card.id(), salary, "六月工资");
        createTransfer(token, "2000.00", card.id(), cash.id(), "取现金");

        // 余额断言：现金 1000 - 38.80 + 2000 = 2961.20；银行卡 0 + 12500 - 2000 = 10500.00
        Map<Long, AccountResponse> accounts = listAccountsById(token);
        assertThat(accounts.get(cash.id()).currentBalance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("2961.20"));
        assertThat(accounts.get(card.id()).currentBalance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("10500.00"));
        // 守恒：账户余额之和 = 初始之和 + 收入 - 支出（转账内部对冲，不改变总额）
        BigDecimal sum = accounts.values().stream()
                .map(AccountResponse::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("13461.20")); // 1000 + 0 + 12500 - 38.80

        // 5) 报表：月报（收入/支出/结余，转账排除）
        MonthlyReportResponse monthly = getForBody(token, "/api/reports/monthly?month=2025-06",
                MonthlyReportResponse.class);
        assertThat(monthly.totalIncome()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("12500.00"));
        assertThat(monthly.totalExpense()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("38.80"));
        assertThat(monthly.balance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("12461.20"));

        // 分类占比：仅一笔支出 → 外卖占 100%
        CategoryReportResponse category = getForBody(token,
                "/api/reports/category?from=2025-06-01&to=2025-06-30", CategoryReportResponse.class);
        assertThat(category.totalExpense()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("38.80"));
        assertThat(category.categories()).hasSize(1);
        assertThat(category.categories().get(0).categoryId()).isEqualTo(takeout);
        assertThat(category.categories().get(0).percentage()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("100.00"));

        // 月度趋势：2025-06 收入/支出与月报一致
        TrendReportResponse trend = getForBody(token,
                "/api/reports/trend?fromMonth=2025-06&toMonth=2025-06", TrendReportResponse.class);
        assertThat(trend.months()).hasSize(1);
        assertThat(trend.months().get(0).month()).isEqualTo("2025-06");
        assertThat(trend.months().get(0).income()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("12500.00"));
        assertThat(trend.months().get(0).expense()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("38.80"));

        // 6) 导出 JSON：断言包含已创建数据
        String exportedJson = getForBody(token, "/api/export?format=json", String.class);
        JsonNode root = MAPPER.readTree(exportedJson);
        assertThat(root.get("accounts")).hasSize(2);
        assertThat(root.get("categories")).hasSize(3);
        assertThat(root.get("transactions")).hasSize(3);
        assertThat(exportedJson).contains("现金", "招商银行卡", "餐饮", "外卖", "工资");
        assertThat(exportedJson).contains("午餐，麻辣烫", "六月工资", "取现金");

        // 7) 重新加载一致（需求 11.4）：服务端为唯一数据源，重新拉取应还原已保存状态
        Map<Long, AccountResponse> reloaded = listAccountsById(token);
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(cash.id()).currentBalance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("2961.20"));
        assertThat(reloaded.get(card.id()).currentBalance()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("10500.00"));

        TransactionResponse[] txs = getForBody(token, "/api/transactions?page=0&size=50",
                TransactionResponse[].class);
        assertThat(txs).hasSize(3);
        assertThat(List.of(txs)).extracting(TransactionResponse::type)
                .containsExactlyInAnyOrder("expense", "income", "transfer");

        UserSummaryResponse me = getForBody(token, "/api/me", UserSummaryResponse.class);
        assertThat(me.username()).isEqualTo("e2e_owner");
        assertThat(me.plan()).isEqualTo("free");
        assertThat(me.role()).isEqualTo("user");

        // 8/9) 第二个全新用户：先验证多租户隔离（看不到用户一的数据），再往返导入
        String token2 = registerAndLogin("e2e_other", "password456");
        assertThat(listAccountsById(token2)).isEmpty(); // 隔离：无法看到用户一的账户（需求 2.3）
        assertThat(getForBody(token2, "/api/transactions", TransactionResponse[].class)).isEmpty();

        // 往返：把用户一导出的 JSON 导入用户二，记录数应一致
        ImportService.ImportResult imported = importJson(token2, exportedJson);
        assertThat(imported.accounts()).isEqualTo(2);
        assertThat(imported.categories()).isEqualTo(3);
        assertThat(imported.transactions()).isEqualTo(3);

        // 导入后用户二的账户余额与用户一一致（除自增 ID 外业务状态相等）
        Map<String, BigDecimal> ownerBalances = balancesByName(reloaded.values());
        Map<String, BigDecimal> otherBalances = balancesByName(listAccountsById(token2).values());
        assertThat(otherBalances.get("现金")).usingComparator(BigDecimal::compareTo)
                .isEqualTo(ownerBalances.get("现金"));
        assertThat(otherBalances.get("招商银行卡")).usingComparator(BigDecimal::compareTo)
                .isEqualTo(ownerBalances.get("招商银行卡"));

        // 用户一数据不受用户二导入影响（隔离仍成立）
        assertThat(listAccountsById(token)).hasSize(2);
    }

    // ---------------- HTTP 辅助 ----------------

    private String registerAndLogin(String username, String password) {
        ResponseEntity<Map> reg = rest.postForEntity(url("/api/auth/register"),
                Map.of("username", username, "password", password), Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/login"),
                Map.of("username", username, "password", password), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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

    private JsonNode createCategory(String token, String kind, String name, Long parentId)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kind", kind);
        body.put("name", name);
        body.put("parentId", parentId);
        ResponseEntity<String> resp = rest.exchange(url("/api/categories"), HttpMethod.POST,
                new HttpEntity<>(body, authJson(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return MAPPER.readTree(resp.getBody());
    }

    private void createExpense(String token, String amount, Long accountId, Long categoryId,
            String note) {
        Map<String, Object> body = Map.of("type", "expense", "amount", amount,
                "accountId", accountId, "categoryId", categoryId,
                "occurredAt", OCCURRED_AT, "note", note);
        postTransaction(token, body);
    }

    private void createIncome(String token, String amount, Long accountId, Long categoryId,
            String note) {
        Map<String, Object> body = Map.of("type", "income", "amount", amount,
                "accountId", accountId, "categoryId", categoryId,
                "occurredAt", OCCURRED_AT, "note", note);
        postTransaction(token, body);
    }

    private void createTransfer(String token, String amount, Long sourceId, Long destId,
            String note) {
        Map<String, Object> body = Map.of("type", "transfer", "amount", amount,
                "sourceAccountId", sourceId, "destinationAccountId", destId,
                "occurredAt", OCCURRED_AT, "note", note);
        postTransaction(token, body);
    }

    private void postTransaction(String token, Map<String, Object> body) {
        ResponseEntity<TransactionResponse> resp = rest.exchange(url("/api/transactions"),
                HttpMethod.POST, new HttpEntity<>(body, authJson(token)), TransactionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<Long, AccountResponse> listAccountsById(String token) {
        ResponseEntity<List<AccountResponse>> resp = rest.exchange(url("/api/accounts"),
                HttpMethod.GET, new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().stream()
                .collect(java.util.stream.Collectors.toMap(AccountResponse::id, a -> a));
    }

    private Map<String, BigDecimal> balancesByName(java.util.Collection<AccountResponse> accounts) {
        return accounts.stream()
                .collect(java.util.stream.Collectors.toMap(
                        AccountResponse::name, AccountResponse::currentBalance));
    }

    private <T> T getForBody(String token, String path, Class<T> type) {
        ResponseEntity<T> resp = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), type);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ImportService.ImportResult importJson(String token, String json) {
        ResponseEntity<ImportService.ImportResult> resp = rest.exchange(url("/api/import"),
                HttpMethod.POST, new HttpEntity<>(json, authJson(token)),
                ImportService.ImportResult.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
