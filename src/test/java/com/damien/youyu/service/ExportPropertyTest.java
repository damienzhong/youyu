package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ExportService} / {@link ImportService} 的属性测试，覆盖设计文档 Correctness Properties 中的
 * Property 18（JSON 导出往返一致性）与 Property 19（导出隔离），关联需求 8.4、8.5。
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code TransactionPropertyTest}、
 * {@code ImportServiceRoundTripTest}）：在 {@code @DataJpaTest} + 真实 H2 与真实
 * {@link ExportService}/{@link ImportService}/{@link AccountService} 上，以固定种子的
 * {@link Random} 在 {@code @Test} 循环内智能生成受约束的随机数据集（账户、两级分类、
 * 支出/收入/转账三类交易），被测业务逻辑全部真实执行，不使用任何 mock。时间以固定
 * {@link Clock} 注入以获得确定性。每个属性至少驱动 ≥100 次迭代。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExportPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZONE);
    private static final LocalDateTime BASE = LocalDateTime.ofInstant(T0, ZONE);

    /** Property 18 往返一致性迭代次数（每次含一整套 导出→导入→再导出 流程）。 */
    private static final int ROUND_TRIP_ITERATIONS = 100;
    /** Property 19 导出隔离迭代次数。 */
    private static final int ISOLATION_ITERATIONS = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private TransactionTagRepository transactionTagRepository;
    @Autowired
    private UserRepository userRepository;

    private ExportService exportService() {
        return new ExportService(accountRepository, categoryRepository, transactionRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository,
                userRepository, CLOCK);
    }

    private ImportService importService() {
        return new ImportService(accountRepository, categoryRepository, transactionRepository, CLOCK);
    }

    private AccountService accountService() {
        return new AccountService(accountRepository, transactionRepository, CLOCK);
    }

    /** 一个用户数据集的记录数摘要。 */
    private record DatasetCounts(int accounts, int categories, int transactions) {
    }

    // ---------------- Property 18：JSON 导出往返一致性 ----------------

    /**
     * Feature: youyu-ledger, Property 18: 对任意用户的业务数据集合（Account、Transaction、Category，
     * 各类不超过 10 万条），将其导出为 JSON 后再导入到一个空账户，应还原出与导出前记录数分别相等、
     * 且除系统生成标识符外各业务字段值逐一相等的 Account、Transaction 与 Category 集合。
     *
     * <p>采用稳健判定：为用户 A 生成随机数据并导出 JSON；导入到空账户用户 B 后，(1) 断言三类记录数
     * 分别相等；(2) 将 B 再次导出的 JSON 与 A 的导出<b>逐字节相等</b>（结构、业务引用键、记录顺序、
     * 各业务字段完全一致，仅系统自增 id 不出现在导出中）；(3) 断言 B 每个账户的 current_balance 与
     * 由初始余额+全量流水重算(recomputeBalance)的结果一致，并与 A 对应账户（按名称配对）相等。</p>
     */
    @Test
    void property18_jsonExportImportRoundTripPreservesAllBusinessFields() {
        Random rng = new Random(1_800_018L);

        for (int iter = 0; iter < ROUND_TRIP_ITERATIONS; iter++) {
            long userA = 18_000_000L + iter * 2L;
            long userB = 18_000_000L + iter * 2L + 1L;

            // 为 A 生成随机数据集（token 为空：单用户场景，名称在用户内自洽唯一）。
            DatasetCounts countsA = buildDataset(userA, rng, "");
            // 将 A 的 current_balance 校正为重算值，便于与 B 比对（导出本身不含 current_balance）。
            AccountService accountService = accountService();
            for (Account a : accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userA)) {
                a.setCurrentBalance(accountService.recomputeBalance(userA, a.getId()));
                accountRepository.save(a);
            }

            byte[] exportA = exportJson(userA);

            // 导入到空账户用户 B。
            assertThat(accountRepository.countByUserId(userB)).isZero();
            ImportService.ImportResult result = importService()
                    .importJson(userB, userB, new ByteArrayInputStream(exportA));

            // (1) 记录数分别相等。
            assertThat(result.accounts()).as("iter=%d 账户数", iter).isEqualTo(countsA.accounts());
            assertThat(result.categories()).as("iter=%d 分类数", iter).isEqualTo(countsA.categories());
            assertThat(result.transactions()).as("iter=%d 交易数", iter).isEqualTo(countsA.transactions());
            assertThat(accountRepository.countByUserId(userB)).isEqualTo(countsA.accounts());
            assertThat(categoryRepository.countByLedgerId(userB)).isEqualTo(countsA.categories());
            assertThat(transactionRepository.findByLedgerId(userB)).hasSize(countsA.transactions());

            // (2) B 再次导出与 A 的导出逐字节相等（除系统 id 外各业务字段逐一相等）。
            byte[] exportB = exportJson(userB);
            assertThat(new String(exportB, StandardCharsets.UTF_8))
                    .as("iter=%d 再导出应与原导出逐字节相等", iter)
                    .isEqualTo(new String(exportA, StandardCharsets.UTF_8));

            // (3) current_balance 与重算一致，且按名称配对与 A 相等（余额守恒还原，需求 4.13）。
            Map<String, Account> accountsA = accountRepository
                    .findByUserIdOrderBySortOrderAscIdAsc(userA).stream()
                    .collect(Collectors.toMap(Account::getName, a -> a));
            for (Account b : accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userB)) {
                BigDecimal recomputed = accountService.recomputeBalance(userB, b.getId());
                assertThat(b.getCurrentBalance())
                        .as("iter=%d 账户[%s] current_balance == 重算值", iter, b.getName())
                        .isEqualByComparingTo(recomputed);
                assertThat(b.getUserId()).isEqualTo(userB);
                Account a = accountsA.get(b.getName());
                assertThat(a).as("iter=%d B 账户[%s] 应有同名 A 账户", iter, b.getName()).isNotNull();
                assertThat(b.getCurrentBalance())
                        .as("iter=%d 账户[%s] B 与 A 的 current_balance 相等", iter, b.getName())
                        .isEqualByComparingTo(a.getCurrentBalance());
            }
        }
    }

    // ---------------- Property 19：导出隔离 ----------------

    /**
     * Feature: youyu-ledger, Property 19: 对任意多用户共存的数据集合，某用户的导出文件应仅包含该请求
     * 用户自己的 Account、Transaction 与 Category 数据。
     *
     * <p>每次迭代随机创建 2-3 个用户，各自数据集的所有名称/备注均以「用户专属 token」前缀标注；随后随机
     * 选定其中一个用户导出 JSON，断言：(1) 导出的三类记录数分别等于该用户在库中的记录数；(2) 导出内每个
     * 账户/分类的名称、每条交易的备注均以该用户 token 起头；(3) 导出全文不含任何其他用户的 token。</p>
     */
    @Test
    void property19_exportContainsOnlyRequestingUsersData() {
        Random rng = new Random(1_900_019L);

        for (int iter = 0; iter < ISOLATION_ITERATIONS; iter++) {
            int userCount = 2 + rng.nextInt(2); // 2-3 个用户
            long baseLedgerId = 19_000_000L + iter * 10L;
            List<Long> ledgerIds = new ArrayList<>();
            for (int u = 0; u < userCount; u++) {
                long ledgerId = baseLedgerId + u;
                ledgerIds.add(ledgerId);
                buildDataset(ledgerId, rng, token(ledgerId));
            }

            // 随机选定导出目标用户。
            long target = ledgerIds.get(rng.nextInt(userCount));
            String targetToken = token(target);

            String raw = new String(exportJson(target), StandardCharsets.UTF_8);
            JsonNode root;
            try {
                root = MAPPER.readTree(raw);
            } catch (Exception e) {
                throw new AssertionError("iter=" + iter + " 导出 JSON 解析失败", e);
            }

            // (1) 记录数等于目标用户在库中的记录数。
            assertThat(root.get("accounts")).as("iter=%d accounts 数", iter)
                    .hasSize((int) accountRepository.countByUserId(target));
            assertThat(root.get("categories")).as("iter=%d categories 数", iter)
                    .hasSize((int) categoryRepository.countByLedgerId(target));
            assertThat(root.get("transactions")).as("iter=%d transactions 数", iter)
                    .hasSize(transactionRepository.findByLedgerId(target).size());

            // (2) 导出内每个业务名称/备注均以目标用户 token 起头（仅含目标用户数据）。
            for (JsonNode a : root.get("accounts")) {
                assertThat(a.get("name").asText()).startsWith(targetToken);
            }
            for (JsonNode c : root.get("categories")) {
                assertThat(c.get("name").asText()).startsWith(targetToken);
            }
            for (JsonNode t : root.get("transactions")) {
                JsonNode note = t.get("note");
                if (note != null && !note.isNull()) {
                    assertThat(note.asText()).startsWith(targetToken);
                }
            }

            // (3) 导出全文不含任何其他用户的 token。
            for (long other : ledgerIds) {
                if (other != target) {
                    assertThat(raw).as("iter=%d 不含用户 %d 的数据", iter, other)
                            .doesNotContain(token(other));
                }
            }
        }
    }

    // ---------------- 随机数据集生成器 ----------------

    /** 用户专属 token：末尾以 'z' 作分隔，保证任一用户 token 不是另一用户 token 的子串。 */
    private static String token(long ledgerId) {
        return "usr" + ledgerId + "z";
    }

    /**
     * 为某用户生成一套随机但合法的数据集：2-5 个账户、每种(支出/收入)若干父分类及其 0-2 个子分类、
     * 0-15 笔随机交易（支出/收入引用同 kind 分类，转账源≠目标）。所有名称/备注以 {@code token} 前缀，
     * 名称在用户内自洽唯一以避免分类唯一约束冲突。金额两位小数、{@code occurredAt} 取整秒。
     */
    private DatasetCounts buildDataset(long ledgerId, Random rng, String token) {
        int accCount = 2 + rng.nextInt(4); // 2-5
        List<Long> accountIds = new ArrayList<>();
        for (int i = 0; i < accCount; i++) {
            Account a = new Account();
            a.setUserId(ledgerId);
            a.setName(token + "acc" + i);
            a.setType(AccountType.values()[rng.nextInt(AccountType.values().length)]);
            BigDecimal init = randomInitialBalance(rng);
            a.setInitialBalance(init);
            a.setCurrentBalance(init);
            a.setSortOrder(rng.nextInt(1000));
            a.setCreatedAt(BASE);
            a.setUpdatedAt(BASE);
            accountIds.add(accountRepository.save(a).getId());
        }

        int categoryCount = 0;
        List<Long> expenseCats = new ArrayList<>();
        categoryCount += buildCategoryTree(ledgerId, rng, token, "exp", CategoryKind.EXPENSE,
                1 + rng.nextInt(3), expenseCats);
        List<Long> incomeCats = new ArrayList<>();
        categoryCount += buildCategoryTree(ledgerId, rng, token, "inc", CategoryKind.INCOME,
                1 + rng.nextInt(2), incomeCats);

        int txCount = rng.nextInt(16); // 0-15
        for (int i = 0; i < txCount; i++) {
            BigDecimal amount = randomAmount(rng);
            // 300 天内的整秒时刻，跨自然月边界。
            LocalDateTime when = BASE.plusSeconds(rng.nextInt(60 * 60 * 24 * 300));
            String note = rng.nextBoolean() ? token + "note" + i : null;
            int kind = rng.nextInt(3);
            if (kind == 2) {
                int si = rng.nextInt(accCount);
                int di = rng.nextInt(accCount);
                while (di == si) {
                    di = rng.nextInt(accCount);
                }
                saveTx(ledgerId, TransactionType.TRANSFER, amount, null, null,
                        accountIds.get(si), accountIds.get(di), when, note);
            } else if (kind == 1) {
                saveTx(ledgerId, TransactionType.INCOME, amount,
                        accountIds.get(rng.nextInt(accCount)),
                        incomeCats.get(rng.nextInt(incomeCats.size())),
                        null, null, when, note);
            } else {
                saveTx(ledgerId, TransactionType.EXPENSE, amount,
                        accountIds.get(rng.nextInt(accCount)),
                        expenseCats.get(rng.nextInt(expenseCats.size())),
                        null, null, when, note);
            }
        }

        return new DatasetCounts(accCount, categoryCount, txCount);
    }

    /** 生成某 kind 的父分类及其 0-2 子分类，收集全部分类 id，返回创建的分类总数。 */
    private int buildCategoryTree(long ledgerId, Random rng, String token, String tag,
            CategoryKind kind, int parentCount, List<Long> collected) {
        int created = 0;
        for (int p = 0; p < parentCount; p++) {
            Category parent = saveCategory(ledgerId, kind, token + tag + p, null);
            collected.add(parent.getId());
            created++;
            int children = rng.nextInt(3); // 0-2
            for (int c = 0; c < children; c++) {
                Category child = saveCategory(ledgerId, kind, token + tag + p + "c" + c, parent.getId());
                collected.add(child.getId());
                created++;
            }
        }
        return created;
    }

    /** 合法初始余额：范围 [-10,000.00, 10,000.00]，恰好两位小数（含负值，覆盖信用卡欠款）。 */
    private static BigDecimal randomInitialBalance(Random rng) {
        long cents = rng.nextLong(-1_000_000L, 1_000_001L);
        return new BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 合法金额：范围 [0.01, 10,000.00]，恰好两位小数。 */
    private static BigDecimal randomAmount(Random rng) {
        long cents = 1 + (long) (rng.nextDouble() * 999_999L);
        return new BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    private Category saveCategory(long ledgerId, CategoryKind kind, String name, Long parentId) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setParentId(parentId);
        c.setCreatedAt(BASE);
        c.setUpdatedAt(BASE);
        return categoryRepository.save(c);
    }

    private void saveTx(long ledgerId, TransactionType type, BigDecimal amount, Long accountId,
            Long categoryId, Long sourceId, Long destId, LocalDateTime when, String note) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(type);
        t.setAmount(amount);
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setSourceAccountId(sourceId);
        t.setDestinationAccountId(destId);
        t.setOccurredAt(when);
        t.setNote(note);
        t.setCreatedAt(BASE);
        t.setUpdatedAt(BASE);
        transactionRepository.save(t);
    }

    private byte[] exportJson(long ledgerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService().writeJson(ledgerId, ledgerId, out);
        return out.toByteArray();
    }
}
