package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;

import com.damien.youyu.config.TimeConfig;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 统计口径与分页语义的属性测试（Property 9）：两个计数的口径自洽、不受分页影响，
 * 逐页取完全部页对全集不重不漏，且返回序列严格是全序列
 * {@code (register_time desc, invite_id desc)} 的切片。
 *
 * <h2>测试层级选择</h2>
 * <p>本属性的核心断言是<b>真实的分页与排序语义</b>：偏移量切片、`register_time` 并列时的
 * {@code invite_id} 次级排序、以及「同一 {@code size} 逐页取完全部页，各页并集恰为全集」。
 * 这些行为由 {@code PageRequest} → JPA → SQL {@code ORDER BY ... LIMIT ... OFFSET} 这条链路
 * 共同决定，用测试替身把 {@code findByInviterId} 换掉就等于把被测对象换成了测试自己写的分页器
 * ——断言随即变成自证，「翻页重复/漏行」这类真实缺陷（例如漏掉 {@code invite_id} 次级排序键）
 * 再也测不出来。故走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}，表结构由实体生成），
 * 只额外导入被测服务及其依赖组件。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 静态上下文缓存复用，
 * 200 次迭代只加载一次）。同理也没有测试事务回滚：每次迭代用<b>全新的邀请人 id</b> 隔离数据，
 * 而被测查询硬性带 {@code inviter_id} 过滤，跨迭代残留因此不影响任何断言——反过来，这些残留正好
 * 变成了「必须被 {@code inviter_id} 过滤掉」的天然噪声。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>期望序列不复用被测的排序与分页代码：测试用 JDBC 直接把该邀请人的全部行读出来，按自己的
 * {@link Comparator}（{@code registerTime} 倒序、{@code inviteId} 倒序）排全序，再用
 * {@code subList} 切片。两个计数由生成的规格直接数出，不问数据库。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>关系集合规模 0–200；{@code status} 按随机比例混合 {@code REGISTERED} / {@code INVALID}
 *       （含全 {@code REGISTERED}、全 {@code INVALID} 与空集）。</li>
 *   <li>{@code register_time} 取自 5 个槽位的小值域，<b>刻意大量制造并列</b>——这是唯一能暴露
 *       「只按 {@code register_time} 排序」缺陷的输入形状。</li>
 *   <li>{@code size ∈ [1, 50]}（另取一个不同的 {@code size} 验证计数与分页无关）、
 *       {@code page ∈ [0, 100000]}（含远超数据范围的页码）。</li>
 *   <li>另一名邀请人的交叉数据 0–5 行，验证 {@code inviter_id} 过滤。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 9: 统计口径自洽与分页不重不漏</p>
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.5, 7.6, 7.10</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteService.class, InviteCodeGenerator.class, InviteRateLimiter.class, TimeConfig.class })
class InviteStatsPagingPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    /** 同一个 H2 库跨迭代复用：用序号保证 email / 邀请码全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * 邀请码编码基数：与同库其它测试（如 {@code InviteSavepointPropertyTest}）的取值区间再错开一层。
     * 真正保证不相交的是 {@link #codeOf} 的类专属前缀 {@code S2}，本基数只是额外的保险。
     */
    private static final long CODE_BASE = 2_000_000_000L;

    /**
     * 被邀请人 id 序号。本属性不关心昵称填充，故 {@code invitee_id} 是<b>不存在对应 users 行</b>
     * 的悬空 id（该列无外键，见需求 9.6）；起点取足够大的值，与真实自增用户 id 区间不相交。
     */
    private static final AtomicLong INVITEE_SEQ = new AtomicLong(1_000_000L);

    private static final String INSERT_RELATION_SQL =
            "INSERT INTO invite_relations "
                    + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    /** 期望排序：{@code register_time} 倒序，并列时 {@code invite_id} 倒序（需求 7.2）。 */
    private static final Comparator<Row> EXPECTED_ORDER =
            Comparator.<Row, LocalDateTime>comparing(Row::registerTime)
                    .thenComparingLong(Row::inviteId)
                    .reversed();

    @Autowired
    private InviteService inviteService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteStatsPagingPropertyTest.class).prepareTestInstance(this);
    }

    // ---------------- 生成器 ----------------

    /**
     * 邀请关系集合：规模 0–200，{@code status} 按 3:2 的比例混合，{@code register_time}
     * 取自 5 个槽位以制造并列。
     */
    @Provide
    Arbitrary<List<RelationSpec>> relationSets() {
        Arbitrary<RelationSpec> one = Combinators.combine(
                        Arbitraries.integers().between(0, 4),
                        Arbitraries.frequency(
                                Tuple.of(3, InviteStatus.REGISTERED),
                                Tuple.of(2, InviteStatus.INVALID)))
                .as(RelationSpec::new);
        return one.list().ofMinSize(0).ofMaxSize(200);
    }

    /** 页码输入：数据范围内的小页码 ∪ 任意合法页码 ∪ 上界 100000（远超任何数据范围）。 */
    @Provide
    Arbitrary<Integer> probePages() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(0, 10),
                Arbitraries.integers().between(0, InviteService.MAX_PAGE),
                Arbitraries.of(0, 1, InviteService.MAX_PAGE));
    }

    // ---------------- Property 9 ----------------

    /**
     * Feature: invite-system, Property 9: 统计口径自洽与分页不重不漏
     *
     * <p>对任意邀请关系集合（{@code REGISTERED}/{@code INVALID} 混合、{@code register_time}
     * 可重复）与任意生效分页参数 {@code (page, size)}：</p>
     * <ul>
     *   <li>{@code invitedCount} 等于 {@code REGISTERED} 行数且 ≤ {@code total}，
     *       {@code total} 等于全部行数（含 {@code INVALID}），
     *       {@code total - invitedCount} 等于 {@code INVALID} 行数（需求 7.5、7.6）；</li>
     *   <li>两个计数都不随 {@code page}/{@code size} 变化，且与
     *       {@code getInviteInfo} 返回的已邀请人数一致（需求 7.5、7.6）；</li>
     *   <li>以同一 {@code size} 逐页取完全部页：各页条数之和等于 {@code total}，各页项的并集等于
     *       全集且互不重复，拼接结果严格等于 {@code (register_time desc, invite_id desc)}
     *       全序列（需求 7.2、7.3、7.5）；</li>
     *   <li>任意页码返回的都是该全序列自第 {@code page × size + 1} 条起、长度 ≤ {@code size}
     *       的切片；超出数据范围的页码返回空列表与真实 {@code total} 且不报错（需求 7.3、7.10）；</li>
     *   <li>结果集全部属于会话用户（另一名邀请人的行一条都不出现，需求 7.2）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.5, 7.6, 7.10</p>
     */
    @Property(tries = 25)
    void property9_statsSelfConsistencyAndPagingCoverage(
            @ForAll("relationSets") List<RelationSpec> specs,
            @ForAll @IntRange(min = 1, max = InviteService.MAX_SIZE) int size,
            @ForAll @IntRange(min = 1, max = InviteService.MAX_SIZE) int otherSize,
            @ForAll("probePages") int probePage,
            @ForAll @IntRange(min = 0, max = 5) int crossInviterRows) {

        long seq = SEQ.incrementAndGet();
        LocalDateTime now = BASE.plusSeconds(seq);

        Long inviterId = persistUser(seq, "stats-inviter", codeOf(CODE_BASE + seq * 2), now).getId();
        Long crossInviterId = persistUser(seq, "stats-cross", codeOf(CODE_BASE + seq * 2 + 1), now).getId();

        insertRelations(inviterId, specs, now);
        // 交叉数据：另一名邀请人名下的行必须被 inviter_id 过滤掉，一条都不能出现在结果里。
        insertRelations(crossInviterId, crossSpecs(crossInviterRows), now);

        // ---- 期望值：JDBC 读原始行 + 测试自己的全序排序，不复用被测的排序与分页 ----
        List<Row> expected = fetchRowsSorted(inviterId);
        List<Long> expectedIds = expected.stream().map(Row::inviteId).toList();
        long expectedTotal = specs.size();
        long expectedRegistered = specs.stream()
                .filter(s -> s.status() == InviteStatus.REGISTERED).count();
        long expectedInvalid = expectedTotal - expectedRegistered;
        assertThat(expected).as("预置行数").hasSize((int) expectedTotal);

        List<Long> crossIds = fetchRowsSorted(crossInviterId).stream().map(Row::inviteId).toList();

        // ---- 逐页取完全部页（再多取一页，验证越界页为空） ----
        int pages = (int) ((expectedTotal + size - 1) / size);
        List<Long> seen = new ArrayList<>();
        long sumOfPageSizes = 0;
        for (int page = 0; page <= pages; page++) {
            InviteeListView view = inviteService.listInvitees(inviterId, page, size);
            assertCounts(view, expectedTotal, expectedRegistered, expectedInvalid);
            assertSlice(view, expected, page, size);
            sumOfPageSizes += view.items().size();
            view.items().forEach(item -> seen.add(item.inviteId()));
        }

        // 各页条数之和等于总条数（需求 7.5）；并集等于全集、互不重复、且顺序即全序列（需求 7.2、7.3）。
        assertThat(sumOfPageSizes).as("各页条数之和 == 总条数").isEqualTo(expectedTotal);
        // isEqualTo 而不是 containsExactlyElementsOf：后者在期望集合为空（无邀请关系）时会抛
        // IllegalArgumentException，而"空集也必须不重不漏"正是本属性要覆盖的边界。
        assertThat(seen).as("不重不漏且全序有序").isEqualTo(expectedIds);
        assertThat(seen).as("翻页不得重复同一行").doesNotHaveDuplicates();
        assertThat(Collections.disjoint(seen, crossIds))
                .as("不得串入他人的邀请关系").isTrue();

        // ---- 任意页码（含远超数据范围）：切片语义 + 真实总条数，且不抛错（需求 7.3、7.10） ----
        InviteeListView probe = inviteService.listInvitees(inviterId, probePage, size);
        assertCounts(probe, expectedTotal, expectedRegistered, expectedInvalid);
        assertSlice(probe, expected, probePage, size);

        // ---- 计数不随 size 变化（需求 7.5、7.6） ----
        InviteeListView otherSized = inviteService.listInvitees(inviterId, 0, otherSize);
        assertCounts(otherSized, expectedTotal, expectedRegistered, expectedInvalid);
        assertSlice(otherSized, expected, 0, otherSize);

        // ---- 缺省分页（page/size 均为 null）同样是 (0, 20) 切片，计数不变（需求 7.1） ----
        InviteeListView defaults = inviteService.listInvitees(inviterId, (Integer) null, null);
        assertCounts(defaults, expectedTotal, expectedRegistered, expectedInvalid);
        assertSlice(defaults, expected, InviteService.DEFAULT_PAGE, InviteService.DEFAULT_SIZE);

        // ---- 邀请信息接口的已邀请人数与列表接口同口径（需求 7.6） ----
        assertThat(inviteService.getInviteInfo(inviterId).invitedCount())
                .as("getInviteInfo 与 listInvitees 的已邀请人数同口径")
                .isEqualTo(expectedRegistered);
    }

    // ---------------- 断言 ----------------

    /** 两个计数的口径自洽：总条数含 INVALID，已邀请人数仅 REGISTERED 且 ≤ 总条数。 */
    private static void assertCounts(InviteeListView view, long expectedTotal,
            long expectedRegistered, long expectedInvalid) {
        assertThat(view.total()).as("总条数（含 INVALID）").isEqualTo(expectedTotal);
        assertThat(view.invitedCount()).as("已邀请人数（仅 REGISTERED）").isEqualTo(expectedRegistered);
        assertThat(view.total() - view.invitedCount()).as("总条数 - 已邀请人数 == INVALID 行数")
                .isEqualTo(expectedInvalid);
        assertThat(view.invitedCount()).as("已邀请人数 ≤ 总条数")
                .isLessThanOrEqualTo(view.total());
    }

    /** 返回项恰为全序列自 {@code page × size} 起、长度 ≤ {@code size} 的切片，逐项三列相等。 */
    private static void assertSlice(InviteeListView view, List<Row> expected, int page, int size) {
        long from = Math.min((long) page * size, expected.size());
        long to = Math.min(from + size, expected.size());
        List<Row> slice = expected.subList((int) from, (int) to);

        assertThat(view.items()).as("单页条数不超过生效的 size").hasSizeLessThanOrEqualTo(size);
        assertThat(view.items()).as("page=%d size=%d 的切片".formatted(page, size))
                .hasSize(slice.size());
        for (int i = 0; i < slice.size(); i++) {
            Row row = slice.get(i);
            InviteeItemView item = view.items().get(i);
            assertThat(item.inviteId()).isEqualTo(row.inviteId());
            assertThat(item.registerTime()).isEqualTo(row.registerTime());
            assertThat(item.status()).isEqualTo(row.status());
        }
    }

    // ---------------- 测试基础设施 ----------------

    /** 一条待插入的邀请关系：{@code register_time} 槽位（小值域，制造并列）+ 状态。 */
    record RelationSpec(int slot, InviteStatus status) {
    }

    /** 从库里读出的原始行（不经服务层）。 */
    private record Row(long inviteId, LocalDateTime registerTime, String status) {
    }

    /** 交叉数据：状态与槽位交替，只为验证 {@code inviter_id} 过滤，不参与任何计数期望。 */
    private static List<RelationSpec> crossSpecs(int count) {
        List<RelationSpec> specs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            specs.add(new RelationSpec(i % 5,
                    i % 2 == 0 ? InviteStatus.REGISTERED : InviteStatus.INVALID));
        }
        return specs;
    }

    /**
     * 把 n 编码成 8 位邀请码：前两位固定为本类专属前缀 {@code S2}，后 6 位取字母表 32 进制。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code uk_users_invite_code} 会随机爆掉。
     * 同理 {@link #persistUser} 的邮箱一律带 {@code s2-} 前缀。</p>
     */
    private static String codeOf(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'S';
        out[1] = '2';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET.charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    private User persistUser(long seq, String tag, String inviteCode, LocalDateTime now) {
        User u = new User();
        // s2- 前缀：库跨测试类共用，邮箱唯一约束同样需要类专属命名空间（见 codeOf 的说明）。
        u.setEmail("s2-" + tag + "-" + seq + "@example.com");
        u.setNickname("s2-" + tag + "-" + seq);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    /** 刻意走 JDBC 批量插入：预置数据不经持久化上下文，{@code invite_id} 由自增按插入顺序分配。 */
    private void insertRelations(Long inviterId, List<RelationSpec> specs, LocalDateTime audit) {
        if (specs.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(specs.size());
        for (RelationSpec spec : specs) {
            args.add(new Object[] { inviterId, INVITEE_SEQ.incrementAndGet(),
                    BASE.plusMinutes(spec.slot()), spec.status().name(), audit, audit });
        }
        jdbcTemplate.batchUpdate(INSERT_RELATION_SQL, args);
    }

    /** 该邀请人名下全部行，按测试自己的全序排序（期望值不复用被测排序）。 */
    private List<Row> fetchRowsSorted(Long inviterId) {
        List<Row> rows = jdbcTemplate.query(
                "SELECT invite_id, register_time, status FROM invite_relations WHERE inviter_id = ?",
                (rs, i) -> new Row(rs.getLong("invite_id"),
                        rs.getTimestamp("register_time").toLocalDateTime(),
                        rs.getString("status")),
                inviterId);
        return rows.stream().sorted(EXPECTED_ORDER).toList();
    }
}
