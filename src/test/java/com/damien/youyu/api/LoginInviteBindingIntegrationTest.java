package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.InviteCodeGenerator;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

/**
 * 登录绑定全路径集成测试（任务 8.5，需求 5.2、5.3、5.4、5.5、5.8、6.2、6.6）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实事务边界、真实 H2 持久化层。
 * 每个用例都在 {@code /api/auth/email-login} 与 {@code /api/auth/wx-login} 两个端点上各跑一遍
 * （{@link LoginKind} 参数化），因为「建号即绑定」这条链路在两个端点上是两段独立的代码，
 * 只测一个端点等于漏掉一半。</p>
 *
 * <p>覆盖的六种终局（需求 5.4 的封闭取值域）：<b>已绑定</b> + {@code NO_CODE} /
 * {@code NOT_NEW_USER} / {@code CODE_NOT_FOUND} / {@code SELF_INVITE} / {@code ALREADY_BOUND}，
 * 每次都断言响应的两个新字段与 {@code invite_relations} 中的实际行数。另含两个专门的用例：</p>
 *
 * <ul>
 *   <li><b>优先级</b>：老用户带畸形码登录得 {@code NOT_NEW_USER} 而非 {@code CODE_NOT_FOUND}
 *       （需求 5.3、6.6）——判定链里 {@code NOT_NEW_USER} 刻意排在格式校验之前。</li>
 *   <li><b>重复登录</b>：同一被邀请人连续登录 2–10 次，行数恒为 1，且第 2 次起原因恒为
 *       {@code NOT_NEW_USER}（需求 5.3、6.9 的另一面：邀请关系一次写定、不可改绑）。</li>
 * </ul>
 *
 * <p><b>{@code register_time} 一律读库比对</b>（需求 5.8）：断言取的是 {@link JdbcTemplate} 从
 * {@code invite_relations.register_time} 与 {@code users.created_at} 两列各读回来的值，
 * <b>不比较内存中的实体字段</b>。内存里的 {@code LocalDateTime} 带纳秒、库中已被截断，比较内存值
 * 会写出「内存相等而库中不等」的假绿测试。</p>
 *
 * <h2>两条近乎不可达分支的构造方式</h2>
 *
 * <p>设计文档「风险与权衡 6」指出 {@code SELF_INVITE} 与 {@code ALREADY_BOUND} 在「只在建号那一刻
 * 绑定」的模型下概率上不可达，测试必须靠受控注入构造。本类都在<b>真实 HTTP 端点</b>上把它们构造出来：</p>
 *
 * <ul>
 *   <li>{@code SELF_INVITE}：{@link MockitoSpyBean} 包住 {@link InviteCodeGenerator}，只把
 *       「抽取新码」这一次产出钉成固定取值（等价于注入一个受控随机源，但保留 {@code normalize} /
 *       {@code isWellFormed} 的真实行为），请求再携带同一个码——新用户于是被分配到自己请求时带的码。</li>
 *   <li>{@code ALREADY_BOUND}：把 {@code users} 的自增计数器 {@code RESTART WITH} 到一个确定值，
 *       从而<b>预知</b>下一个新用户的 id，据此预置一行 {@code invitee_id} 相同的邀请关系。
 *       这样冲突是由真实唯一索引在真实登录事务里抛出的，同时顺带在 HTTP 层复验了保存点方案：
 *       冲突之后新用户行与其邀请码照常提交、令牌照常签发。</li>
 * </ul>
 *
 * <p>微信 {@code jscode2session} 以 {@link MockitoBean} 替身按 {@code code → openid} 稳定映射，
 * 使「同一 code 再登录一次」等价于「同一微信用户重复登录」。使用独立命名的内存库，避免污染其它
 * 共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-loginbind-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建大量账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=100000",
        "app.auth.email-code.ip-per-day=100000"
})
class LoginInviteBindingIntegrationTest {

    /** 两个登录端点：每个用例都在两者上各跑一遍。 */
    enum LoginKind { EMAIL, WX }

    /**
     * SELF_INVITE 用例中钉给新用户的邀请码（格式合法、库中不存在）。
     *
     * <p>两个端点各用一个不同取值：邀请码全局唯一，两次参数化运行若共用同一取值，后跑的那次
     * 「该码未被占用」前置断言会被前一次留下的用户破坏。</p>
     */
    private static String forcedSelfCode(LoginKind kind) {
        return kind == LoginKind.EMAIL ? "K7M2Q9XT" : "K7M2Q9XU";
    }

    /** 格式合法但库中不存在的码（CODE_NOT_FOUND 用）。 */
    private static final String ABSENT_WELL_FORMED_CODE = "ZZZZZZZZ";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InviteRelationRepository inviteRelationRepository;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    /** 微信换 openid 的替身：code → openid 稳定映射，同一 code 即同一微信用户。 */
    @MockitoBean
    private WeChatClient weChatClient;

    /** 仅 SELF_INVITE 用例用到：钉住「抽取新码」的产出，其余行为保持真实。 */
    @MockitoSpyBean
    private InviteCodeGenerator inviteCodeGenerator;

    @BeforeEach
    void stubWeChat() {
        when(weChatClient.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession("openid-" + inv.getArgument(0, String.class), null));
    }

    // ==================== 1) 已绑定：新用户 + 有效邀请码（需求 5.2、5.8）====================

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void newUserWithValidCode_bindsExactlyOneRelation_andRegisterTimeEqualsCreatedAtInDb(LoginKind kind) {
        String inviterKey = key(kind, "bound_inviter");
        registerNewUser(kind, inviterKey);
        long inviterId = userIdOf(kind, inviterKey);
        String inviteCode = inviteCodeOf(inviterId);

        String inviteeKey = key(kind, "bound_invitee");
        // 刻意带首尾空白与小写：规整（trim + 大写）后仍应命中（需求 1.9）。
        ResponseEntity<Map> login = login(kind, inviteeKey, "  " + inviteCode.toLowerCase() + "  ");

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = body(login);
        assertThat(body.get("token")).asString().isNotBlank();
        assertThat(body).containsEntry("inviteBound", true);
        assertThat(body).containsKey("inviteUnboundReason");
        assertThat(body.get("inviteUnboundReason")).isNull();

        long inviteeId = userIdOf(kind, inviteeKey);
        assertThat(relationCount(inviteeId)).as("恰好 1 条邀请关系").isEqualTo(1);

        assertThat(relationColumn(inviteeId, "inviter_id", Long.class)).isEqualTo(inviterId);
        assertThat(relationColumn(inviteeId, "status", String.class))
                .isEqualTo(InviteStatus.REGISTERED.name());
        // created_at 与 updated_at 建行时相等（需求 9.15）。
        assertThat(relationColumn(inviteeId, "created_at", LocalDateTime.class))
                .isEqualTo(relationColumn(inviteeId, "updated_at", LocalDateTime.class));

        // 需求 5.8：register_time 与被邀请人 users.created_at 严格相等，两值都从库里读回来比对。
        LocalDateTime registerTimeInDb = relationColumn(inviteeId, "register_time", LocalDateTime.class);
        LocalDateTime createdAtInDb = jdbc.queryForObject(
                "select created_at from users where id = ?", LocalDateTime.class, inviteeId);
        assertThat(registerTimeInDb).isNotNull().isEqualTo(createdAtInDb);
    }

    // ==================== 2) NO_CODE：未携带邀请码（需求 5.4）====================

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void newUserWithoutCode_isUnboundWithNoCode(LoginKind kind) {
        // 字段完全缺失。
        String missingKey = key(kind, "nocode_missing");
        assertUnbound(login(kind, missingKey, null), "NO_CODE");
        assertThat(relationCount(userIdOf(kind, missingKey))).isZero();

        // 去空白后为空：与字段缺失同一结论（需求 5.1）。
        String blankKey = key(kind, "nocode_blank");
        assertUnbound(login(kind, blankKey, "   "), "NO_CODE");
        assertThat(relationCount(userIdOf(kind, blankKey))).isZero();
    }

    // ============ 3) NOT_NEW_USER：老用户带码登录，且优先于格式校验（需求 5.3、6.6）============

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void existingUserWithCode_isUnboundWithNotNewUser_evenWhenCodeIsMalformed(LoginKind kind) {
        String inviterKey = key(kind, "notnew_inviter");
        registerNewUser(kind, inviterKey);
        String validCode = inviteCodeOf(userIdOf(kind, inviterKey));

        // 先建号（不带码），此后该身份的每次登录都不是建号路径。
        String userKey = key(kind, "notnew_user");
        assertUnbound(login(kind, userKey, null), "NO_CODE");
        long userId = userIdOf(kind, userKey);

        // 老用户 + 完全有效的邀请码 → 仍不绑定。
        assertUnbound(login(kind, userKey, validCode), "NOT_NEW_USER");
        assertThat(relationCount(userId)).isZero();

        // 优先级用例：老用户 + 畸形码 → NOT_NEW_USER，而不是 CODE_NOT_FOUND。
        assertUnbound(login(kind, userKey, "not a code!!"), "NOT_NEW_USER");
        assertUnbound(login(kind, userKey, "A".repeat(65)), "NOT_NEW_USER");
        assertThat(relationCount(userId)).isZero();
    }

    // ==================== 4) CODE_NOT_FOUND（需求 5.5、5.6）====================

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void newUserWithUnusableCode_isUnboundWithCodeNotFound(LoginKind kind) {
        // 每种输入都需要一个全新身份：同一身份第二次登录就落到 NOT_NEW_USER 了。
        assertCodeNotFound(kind, "cnf_absent", ABSENT_WELL_FORMED_CODE);   // 格式合法但库中不存在
        assertCodeNotFound(kind, "cnf_malformed", "abc");                  // 长度不足
        assertCodeNotFound(kind, "cnf_illegalchar", "K7M2Q9X!");           // 含字母表以外字符
        assertCodeNotFound(kind, "cnf_ambiguous", "IO01IO01");             // 8 位但全是被剔除的易混字符
        assertCodeNotFound(kind, "cnf_overlong", "A".repeat(65));          // 原始长度 > 64
    }

    private void assertCodeNotFound(LoginKind kind, String keySuffix, String rawCode) {
        String userKey = key(kind, keySuffix);
        assertUnbound(login(kind, userKey, rawCode), "CODE_NOT_FOUND");
        assertThat(relationCount(userIdOf(kind, userKey))).as(keySuffix).isZero();
    }

    // ==================== 5) SELF_INVITE：自己邀请自己（需求 6.2）====================

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void newUserAssignedTheCodeItSent_isUnboundWithSelfInvite(LoginKind kind) {
        String forcedCode = forcedSelfCode(kind);
        assertThat(userRepository.findByInviteCode(forcedCode)).as("前置：该码未被占用").isEmpty();
        // 受控注入点：新用户这次被分配到的码就是它请求时携带的码（自然输入下概率约 32^-8）。
        doReturn(forcedCode).when(inviteCodeGenerator).generateUnique(any());

        String userKey = key(kind, "self_invite");
        // 小写 + 空白：规整后与被分配到的码相同，持有者即新用户本人。
        assertUnbound(login(kind, userKey, " " + forcedCode.toLowerCase() + " "), "SELF_INVITE");

        long userId = userIdOf(kind, userKey);
        // 需求 6.2：保留本次新建的 users 行，且 invite_relations 无任何行。
        assertThat(inviteCodeOf(userId)).isEqualTo(forcedCode);
        assertThat(relationCount(userId)).isZero();
    }

    // ============ 6) ALREADY_BOUND：invitee_id 唯一约束冲突（需求 5.10、6.3、6.8）============

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void newUserWhoseIdAlreadyHasRelation_isUnboundWithAlreadyBound_andLoginStillSucceeds(LoginKind kind) {
        // 邀请码持有者（请求里带它的码）与预置行的邀请人刻意是两个人：
        // 若冲突分支被错误实现成「覆盖已有行」，下面的快照断言会立刻失败。
        String codeHolderKey = key(kind, "ab_codeholder");
        registerNewUser(kind, codeHolderKey);
        String inviteCode = inviteCodeOf(userIdOf(kind, codeHolderKey));

        String otherInviterKey = key(kind, "ab_otherinviter");
        registerNewUser(kind, otherInviterKey);
        long otherInviterId = userIdOf(kind, otherInviterKey);

        // 预知下一个新用户的 id：把自增计数器重置到一个确定且远高于现有 id 的取值。
        long predictedInviteeId = 900_000L + kind.ordinal() * 1000L + 1L;
        jdbc.execute("alter table users alter column id restart with " + predictedInviteeId);

        LocalDateTime preexistingRegisterTime = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
        saveRelation(otherInviterId, predictedInviteeId, preexistingRegisterTime);
        long relationsBefore = inviteRelationRepository.count();

        String inviteeKey = key(kind, "ab_invitee");
        ResponseEntity<Map> login = login(kind, inviteeKey, inviteCode);

        // 登录照常成功、令牌照常签发（需求 5.10、6.8）。
        assertUnbound(login, "ALREADY_BOUND");
        long inviteeId = userIdOf(kind, inviteeKey);
        assertThat(inviteeId).as("自增计数器重置后新用户拿到预知的 id").isEqualTo(predictedInviteeId);
        // 保存点只回滚了那条插入：新用户行与其邀请码照常提交。
        assertThat(inviteCodeOf(inviteeId)).isNotBlank();

        // 已存在那一行的 inviter_id / register_time / status 不变，且表内不增不减（需求 6.3）。
        assertThat(inviteRelationRepository.count()).isEqualTo(relationsBefore);
        assertThat(relationCount(inviteeId)).isEqualTo(1);
        assertThat(relationColumn(inviteeId, "inviter_id", Long.class)).isEqualTo(otherInviterId);
        assertThat(relationColumn(inviteeId, "status", String.class))
                .isEqualTo(InviteStatus.REGISTERED.name());
        assertThat(relationColumn(inviteeId, "register_time", LocalDateTime.class))
                .isEqualTo(preexistingRegisterTime);
    }

    // ============ 7) 同一被邀请人重复登录 2–10 次：行数恒为 1（需求 5.3、6.9）============

    @ParameterizedTest
    @EnumSource(LoginKind.class)
    void repeatedLoginsOfSameInvitee_keepExactlyOneRelation_andReportNotNewUserFromSecondOn(LoginKind kind) {
        String inviterKey = key(kind, "repeat_inviter");
        registerNewUser(kind, inviterKey);
        long inviterId = userIdOf(kind, inviterKey);
        String inviteCode = inviteCodeOf(inviterId);

        String inviteeKey = key(kind, "repeat_invitee");
        // 第 1 次：建号并绑定。
        ResponseEntity<Map> first = login(kind, inviteeKey, inviteCode);
        assertThat(body(first)).containsEntry("inviteBound", true);
        long inviteeId = userIdOf(kind, inviteeKey);
        LocalDateTime registerTimeAfterFirst =
                relationColumn(inviteeId, "register_time", LocalDateTime.class);

        // 第 2–10 次：同一身份、同一邀请码，一律 NOT_NEW_USER，行数恒为 1。
        for (int attempt = 2; attempt <= 10; attempt++) {
            assertUnbound(login(kind, inviteeKey, inviteCode), "NOT_NEW_USER");
            assertThat(relationCount(inviteeId)).as("第 " + attempt + " 次登录后的行数").isEqualTo(1);
        }

        // 关系行的取值一次写定、不被后续登录改写（需求 6.9）。
        assertThat(relationColumn(inviteeId, "inviter_id", Long.class)).isEqualTo(inviterId);
        assertThat(relationColumn(inviteeId, "status", String.class))
                .isEqualTo(InviteStatus.REGISTERED.name());
        assertThat(relationColumn(inviteeId, "register_time", LocalDateTime.class))
                .isEqualTo(registerTimeAfterFirst);
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言登录成功（有令牌）但未绑定，且未绑定原因恰为期望取值（需求 5.4）。 */
    private void assertUnbound(ResponseEntity<Map> login, String expectedReason) {
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = body(login);
        assertThat(body.get("token")).asString().isNotBlank();
        assertThat(body).containsEntry("inviteBound", false);
        assertThat(body).containsEntry("inviteUnboundReason", expectedReason);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    // ---------------------------------- 读库辅助 ----------------------------------

    private int relationCount(long inviteeId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from invite_relations where invitee_id = ?", Integer.class, inviteeId);
        return count == null ? 0 : count;
    }

    /** 读回该被邀请人那一行的某一列（逐列读，避免依赖驱动返回的列名大小写）。 */
    private <T> T relationColumn(long inviteeId, String column, Class<T> type) {
        return jdbc.queryForObject(
                "select " + column + " from invite_relations where invitee_id = ?", type, inviteeId);
    }

    private String inviteCodeOf(long userId) {
        String inviteCode = jdbc.queryForObject("select invite_code from users where id = ?",
                String.class, userId);
        assertThat(inviteCode).as("建号时应写入邀请码").isNotBlank();
        return inviteCode;
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** 身份键：带上端点名，避免两次参数化运行争抢同一邮箱 / openid。 */
    private static String key(LoginKind kind, String suffix) {
        return "invitebind_" + kind.name().toLowerCase() + "_" + suffix;
    }

    /**
     * 经真实 HTTP 执行一次登录。
     *
     * @param inviteCode 邀请码原始取值；{@code null} 表示请求体里根本没有该字段（老客户端形态）
     */
    private ResponseEntity<Map> login(LoginKind kind, String key, String inviteCode) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        if (kind == LoginKind.EMAIL) {
            String email = key + "@example.com";
            // 清历史码以规避 60s 发码冷却（同一身份要连续登录多次）。
            verificationCodeRepository.deleteByEmail(email);
            ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                    Map.of("email", email, "purpose", "LOGIN"), Void.class);
            assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            requestBody.put("email", email);
            requestBody.put("code", latestCode(email, EmailCodePurpose.LOGIN));
        } else {
            requestBody.put("code", wxCode(key));
        }
        if (inviteCode != null) {
            requestBody.put("inviteCode", inviteCode);
        }
        String path = kind == LoginKind.EMAIL ? "/api/auth/email-login" : "/api/auth/wx-login";
        return rest.postForEntity(url(path), requestBody, Map.class);
    }

    /** 建号（不带邀请码），并断言这一次确实是新用户。 */
    private void registerNewUser(LoginKind kind, String key) {
        assertUnbound(login(kind, key, null), "NO_CODE");
    }

    private long userIdOf(LoginKind kind, String key) {
        return (kind == LoginKind.EMAIL
                ? userRepository.findByEmail(key + "@example.com")
                : userRepository.findByWxOpenid("openid-" + wxCode(key)))
                .orElseThrow(() -> new AssertionError("用户未建立: " + key))
                .getId();
    }

    private static String wxCode(String key) {
        return "wxcode-" + key;
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }

    /** 预置一行 REGISTERED 邀请关系（用于制造 invitee_id 唯一约束冲突）。 */
    private void saveRelation(long inviterId, long inviteeId, LocalDateTime registerTime) {
        InviteRelation relation = new InviteRelation();
        relation.setInviterId(inviterId);
        relation.setInviteeId(inviteeId);
        relation.setRegisterTime(registerTime);
        relation.setStatus(InviteStatus.REGISTERED);
        relation.setCreatedAt(registerTime);
        relation.setUpdatedAt(registerTime);
        inviteRelationRepository.save(relation);
    }
}
