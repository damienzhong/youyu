package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 注销与邀请数据的联动集成测试（任务 8.6，需求 10.1～10.7、10.9、10.10）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实过滤链与 JWT、真实
 * {@link com.damien.youyu.service.AccountDeletionService} 与 H2 持久化层。邀请关系一律经
 * {@code /api/auth/email-login} 携带邀请码<b>真实建立</b>（不直接 insert），注销一律经
 * {@code POST /api/me/delete} 走完「协作牵连拦截 → 二次验证 → 单事务级联硬删」全流程，
 * 这样「置 INVALID 与删除 users 行同处一个事务」才是被真正验证的，而不是被测试替身绕过的。</p>
 *
 * <h2>五组断言</h2>
 * <ol>
 *   <li><b>被邀请人注销</b>（需求 10.2、10.7、10.8）：邀请人的 {@code invitedCount} 减 1、
 *       {@code total} 不变、列表仍返回该行且 {@code status} 为 {@code INVALID}、{@code nickname}
 *       为 {@code null}（不是占位文本），{@code inviteId} 与 {@code registerTime} 与注销前相同。</li>
 *   <li><b>邀请人注销 + 双重身份</b>（需求 10.1、10.3）：以其为 {@code inviter_id} 的行六列
 *       （{@code invite_id} / {@code inviter_id} / {@code invitee_id} / {@code register_time} /
 *       {@code status} / {@code created_at}）<b>逐行快照相等</b>；同时它自己作为某行 {@code invitee_id}
 *       的那一行被置 {@code INVALID} 且其余五列不变。</li>
 *   <li><b>前置校验失败零副作用</b>（需求 10.6）：{@code DELETE_BLOCKED_COLLAB}（协作账本仍有他人成员）
 *       与二次验证失败（错误的注销验证码）两条路径下，{@code invite_relations} 全表七列快照与该用户的
 *       {@code users.invite_code} 均保持请求前状态。</li>
 *   <li><b>更新失败整事务回滚</b>（需求 10.5）：让 {@code markInvalidByInviteeId} 抛错，断言注销失败、
 *       {@code users} 行与 {@code invite_relations} 全表七列快照原样还原，且该用户注销前持有的令牌
 *       仍能成功请求邀请信息。</li>
 *   <li><b>邀请码释放后的语义</b>（需求 10.4、10.9、10.10）：公开查询该码得 {@code NOT_FOUND}；
 *       登录携带该码以 {@code CODE_NOT_FOUND} 完成且照常签发令牌；该码被新用户重新占用后，
 *       历史行<b>不</b>出现在新持有者的邀请信息与被邀请人列表中。</li>
 * </ol>
 *
 * <p><b>关于「被新用户重新占用」的构造方式</b>：邀请码由 {@code SecureRandom} 抽取，无法让某个新用户
 * 恰好抽到指定的已释放码（受控随机源属于服务层单元测试的范围）。这里改为在该码释放后<b>直接把它写到
 * 一个新建用户的 {@code users.invite_code} 上</b>（唯一约束 {@code uk_users_invite_code} 能写入成功，
 * 本身即需求 10.4「码随 users 行删除而释放」的证据），再断言新持有者的两个接口响应不含历史行。
 * 这恰好是需求 10.10 想锁住的点：归属判定走 {@code inviter_id}，与邀请码取值无关。</p>
 *
 * <p>{@code markInvalidByInviteeId} 抛错只能靠替身制造（真实路径下这条 UPDATE 不会失败），故对
 * {@link InviteRelationRepository} 用 {@link MockitoSpyBean}：未打桩时全部方法委托真实实现，
 * 其余四组断言因此仍走真实仓储。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-invitedel-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建十余个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class InviteAccountDeletionIntegrationTest {

    /** 邀请关系六列快照（不含 updated_at）：邀请人注销时这六列必须一列不动（需求 10.1）。 */
    private static final String SIX_COLUMNS =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at "
                    + "FROM invite_relations";

    /** 邀请关系七列快照（含 updated_at）：零副作用与回滚断言要连 updated_at 一起比（需求 10.5、10.6）。 */
    private static final String SEVEN_COLUMNS =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations";

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
    private LedgerMemberRepository ledgerMemberRepository;

    /** 未打桩时委托真实仓储；仅第 4 组断言对 {@code markInvalidByInviteeId} 打桩抛错。 */
    @MockitoSpyBean
    private InviteRelationRepository inviteRelationRepository;

    // ============ 1) 被邀请人注销：invitedCount 减 1、total 不变、行仍在且为 INVALID ============

    @Test
    void deletingInvitee_keepsRowAsInvalid_andOnlyDecrementsInvitedCount() {
        String tokenA = registerAndLogin("del_invitee_a@example.com");
        String codeA = inviteCodeOf("del_invitee_a@example.com");

        String tokenB = registerWithInviteCodeExpectingBound("del_invitee_b@example.com", codeA);

        // 注销前基线：1 条关系、1 人已邀请、列表项昵称为被邀请人昵称。
        Map<String, Object> infoBefore = body(get("/api/invite", bearer(tokenA)));
        Map<String, Object> listBefore = body(get("/api/invite/invitees", bearer(tokenA)));
        assertThat(infoBefore).containsEntry("invitedCount", 1);
        assertThat(listBefore).containsEntry("total", 1).containsEntry("invitedCount", 1);
        Map<String, Object> itemBefore = itemsOf(listBefore).get(0);
        assertThat(itemBefore)
                .containsEntry("nickname", "del_invitee_b")
                .containsEntry("status", "REGISTERED");

        deleteAccountExpectingSuccess(tokenB, "del_invitee_b@example.com");

        // 已邀请人数减 1、关系总条数不变（需求 10.7）。
        assertThat(body(get("/api/invite", bearer(tokenA)))).containsEntry("invitedCount", 0);
        Map<String, Object> listAfter = body(get("/api/invite/invitees", bearer(tokenA)));
        assertThat(listAfter).containsEntry("total", 1).containsEntry("invitedCount", 0);

        // 行仍在列表中、status 为 INVALID、昵称以空值返回（不是占位文本），另两个字段取真实值（需求 10.8）。
        List<Map<String, Object>> itemsAfter = itemsOf(listAfter);
        assertThat(itemsAfter).hasSize(1);
        Map<String, Object> itemAfter = itemsAfter.get(0);
        assertThat(itemAfter)
                .containsEntry("status", "INVALID")
                .containsEntry("inviteId", itemBefore.get("inviteId"))
                .containsEntry("registerTime", itemBefore.get("registerTime"));
        assertThat(itemAfter.get("nickname")).isNull();
        assertThat(itemAfter.keySet())
                .containsExactlyInAnyOrder("inviteId", "nickname", "registerTime", "status");
    }

    // ============ 2) 邀请人注销：名下行六列逐行快照不变；双重身份用例（需求 10.1、10.3）============

    @Test
    void deletingInviter_leavesOwnRowsUntouched_evenWithDualRole() {
        // W 邀请 X；X 又邀请 Y 与 Z —— X 既是两行的 inviter，又是某行的 invitee（双重身份）。
        String tokenW = registerAndLogin("del_dual_w@example.com");
        String codeW = inviteCodeOf("del_dual_w@example.com");
        String tokenX = registerWithInviteCodeExpectingBound("del_dual_x@example.com", codeW);
        String codeX = inviteCodeOf("del_dual_x@example.com");
        registerWithInviteCodeExpectingBound("del_dual_y@example.com", codeX);
        registerWithInviteCodeExpectingBound("del_dual_z@example.com", codeX);

        long idW = userIdOf("del_dual_w@example.com");
        long idX = userIdOf("del_dual_x@example.com");

        List<Map<String, Object>> rowsOfXBefore = sixColumnsByInviter(idX);
        assertThat(rowsOfXBefore).hasSize(2);
        assertThat(rowsOfXBefore).allSatisfy(row -> assertThat(row).containsEntry("STATUS", "REGISTERED"));
        Map<String, Object> rowOfXAsInviteeBefore = sevenColumnsByInvitee(idX);
        assertThat(rowOfXAsInviteeBefore).containsEntry("STATUS", "REGISTERED");

        deleteAccountExpectingSuccess(tokenX, "del_dual_x@example.com");

        // 以 X 为 inviter_id 的行：行数与六列逐行快照完全相等（含 status 不变，需求 10.1、10.3）。
        assertThat(sixColumnsByInviter(idX)).isEqualTo(rowsOfXBefore);

        // 以 X 为 invitee_id 的行：仍是 1 行、status 置 INVALID，其余五列不变（需求 10.2、10.3）。
        Map<String, Object> rowOfXAsInviteeAfter = sevenColumnsByInvitee(idX);
        assertThat(rowOfXAsInviteeAfter).containsEntry("STATUS", "INVALID");
        assertThat(rowOfXAsInviteeAfter)
                .containsEntry("INVITE_ID", rowOfXAsInviteeBefore.get("INVITE_ID"))
                .containsEntry("INVITER_ID", rowOfXAsInviteeBefore.get("INVITER_ID"))
                .containsEntry("INVITEE_ID", rowOfXAsInviteeBefore.get("INVITEE_ID"))
                .containsEntry("REGISTER_TIME", rowOfXAsInviteeBefore.get("REGISTER_TIME"))
                .containsEntry("CREATED_AT", rowOfXAsInviteeBefore.get("CREATED_AT"));

        // W 视角：X 注销后其 invitedCount 归零，但关系仍在（total 不变）。
        Map<String, Object> listOfW = body(get("/api/invite/invitees", bearer(tokenW)));
        assertThat(listOfW).containsEntry("total", 1).containsEntry("invitedCount", 0);
        assertThat(itemsOf(listOfW).get(0)).containsEntry("status", "INVALID");
        assertThat(sixColumnsByInviter(idW)).hasSize(1);
    }

    // ============ 3) 前置校验失败：两表零副作用（需求 10.6）============

    @Test
    void preflightFailures_leaveInviteTablesAndInviteCodeUntouched() {
        // P 既是被邀请人（由 O 邀请），又是邀请人（邀请了 Q）：两类行都在，零副作用才有内容可断言。
        String tokenO = registerAndLogin("del_pre_o@example.com");
        String codeO = inviteCodeOf("del_pre_o@example.com");
        String tokenP = registerWithInviteCodeExpectingBound("del_pre_p@example.com", codeO);
        String codeP = inviteCodeOf("del_pre_p@example.com");
        registerWithInviteCodeExpectingBound("del_pre_q@example.com", codeP);
        long idP = userIdOf("del_pre_p@example.com");
        assertThat(tokenO).isNotBlank();

        // --- 3a) 二次验证失败（错误的注销验证码）：requireDeletable 已通过，卡在 verifySecondFactor ---
        List<Map<String, Object>> tableBeforeBadCode = sevenColumnsWholeTable();
        ResponseEntity<Map> badCode = postDelete(tokenP, Map.of("code", "000000"));
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badCode)).containsEntry("code", "CODE_INVALID");
        assertThat(sevenColumnsWholeTable()).isEqualTo(tableBeforeBadCode);
        assertThat(inviteCodeOf("del_pre_p@example.com")).isEqualTo(codeP);
        assertThat(sevenColumnsByInvitee(idP)).containsEntry("STATUS", "REGISTERED");

        // --- 3b) 协作牵连拦截：P 拥有的协作账本仍有他人成员 → DELETE_BLOCKED_COLLAB ---
        seedCollaborativeLedgerWithOtherMember(idP);
        List<Map<String, Object>> tableBeforeCollab = sevenColumnsWholeTable();
        ResponseEntity<Map> blocked = postDelete(tokenP, Map.of("code", freshDeleteCode("del_pre_p@example.com")));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(blocked)).containsEntry("code", "DELETE_BLOCKED_COLLAB");
        assertThat(sevenColumnsWholeTable()).isEqualTo(tableBeforeCollab);
        assertThat(inviteCodeOf("del_pre_p@example.com")).isEqualTo(codeP);
        assertThat(userRepository.findById(idP)).isPresent();
    }

    // ============ 4) markInvalidByInviteeId 抛错：整事务回滚且原令牌仍可用（需求 10.5）============

    @Test
    void markInvalidFailure_rollsBackWholeDeletion_andOriginalTokenStillWorks() {
        String tokenM = registerAndLogin("del_rb_m@example.com");
        String codeM = inviteCodeOf("del_rb_m@example.com");
        String tokenN = registerWithInviteCodeExpectingBound("del_rb_n@example.com", codeM);
        // N 也邀请了一人：回滚断言同时覆盖「N 作为 inviter 的行」。
        String codeN = inviteCodeOf("del_rb_n@example.com");
        registerWithInviteCodeExpectingBound("del_rb_r@example.com", codeN);

        long idN = userIdOf("del_rb_n@example.com");
        User beforeUser = userRepository.findById(idN).orElseThrow();
        String emailBefore = beforeUser.getEmail();
        String nicknameBefore = beforeUser.getNickname();
        String openidBefore = beforeUser.getWxOpenid();
        List<Map<String, Object>> tableBefore = sevenColumnsWholeTable();

        // 让注销联动的那条 UPDATE 抛错（真实路径下不会失败，只能靠替身制造）。
        doThrow(new DataIntegrityViolationException("模拟 invite_relations 更新失败"))
                .when(inviteRelationRepository).markInvalidByInviteeId(eq(idN), any(LocalDateTime.class));

        ResponseEntity<Map> failed = postDelete(tokenN, Map.of("code", freshDeleteCode("del_rb_n@example.com")));
        assertThat(failed.getStatusCode().is5xxServerError()).as("注销应失败: " + failed).isTrue();

        // users 行完好：id / email / wx_openid / nickname / invite_code 与注销前相同（需求 10.5）。
        User afterUser = userRepository.findById(idN).orElseThrow(
                () -> new AssertionError("注销事务应整体回滚，users 行不应被删除"));
        assertThat(afterUser.getId()).isEqualTo(idN);
        assertThat(afterUser.getEmail()).isEqualTo(emailBefore);
        assertThat(afterUser.getWxOpenid()).isEqualTo(openidBefore);
        assertThat(afterUser.getNickname()).isEqualTo(nicknameBefore);
        assertThat(afterUser.getInviteCode()).isEqualTo(codeN);

        // invite_relations 全表七列快照原样还原（含 updated_at）。
        assertThat(sevenColumnsWholeTable()).isEqualTo(tableBefore);

        // 注销前持有的令牌仍可成功请求邀请信息（需求 10.5）。
        ResponseEntity<Map> info = get("/api/invite", bearer(tokenN));
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(info)).containsEntry("inviteCode", codeN).containsEntry("invitedCount", 1);
        // 邀请人 M 视角同样未受影响：关系仍为 REGISTERED。
        assertThat(body(get("/api/invite", bearer(tokenM)))).containsEntry("invitedCount", 1);
    }

    // ============ 5) 已释放的邀请码：公开查询、登录携带与被重新占用（需求 10.4、10.9、10.10）============

    @Test
    void releasedInviteCode_isNotFound_yieldsCodeNotFoundOnLogin_andHistoryStaysWithOldInviter() {
        String tokenR = registerAndLogin("del_rel_r@example.com");
        String releasedCode = inviteCodeOf("del_rel_r@example.com");
        registerWithInviteCodeExpectingBound("del_rel_s@example.com", releasedCode);
        long idR = userIdOf("del_rel_r@example.com");
        List<Map<String, Object>> historyRows = sixColumnsByInviter(idR);
        assertThat(historyRows).hasSize(1);

        deleteAccountExpectingSuccess(tokenR, "del_rel_r@example.com");

        // 码随 users 行删除而释放（需求 10.4）。
        assertThat(userRepository.findByInviteCode(releasedCode)).isEmpty();
        // 历史行仍在，归属仍是已注销的 R（需求 10.1、10.10）。
        assertThat(sixColumnsByInviter(idR)).isEqualTo(historyRows);

        // 5a) 公开查询该已释放的码 → NOT_FOUND（需求 10.9）。
        ResponseEntity<Map> lookup = get("/api/invite/inviter?code=" + releasedCode, noAuth());
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(lookup)).containsEntry("code", "NOT_FOUND");

        // 5b) 登录携带该码 → CODE_NOT_FOUND，但登录成功并签发令牌（需求 10.9）。
        ResponseEntity<Map> login = emailLogin("del_rel_t@example.com", releasedCode);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(login))
                .containsEntry("inviteBound", false)
                .containsEntry("inviteUnboundReason", "CODE_NOT_FOUND");
        assertThat((String) body(login).get("token")).isNotBlank();
        assertThat(inviteRelationRepository.findByInviteeId(userIdOf("del_rel_t@example.com"))).isEmpty();

        // 5c) 该码被新用户重新占用：直接写到新用户的 invite_code 上（唯一约束不冲突即需求 10.4 的证据）。
        String tokenNew = registerAndLogin("del_rel_new@example.com");
        long idNew = userIdOf("del_rel_new@example.com");
        User newHolder = userRepository.findById(idNew).orElseThrow();
        newHolder.setInviteCode(releasedCode);
        userRepository.saveAndFlush(newHolder);
        assertThat(userRepository.findByInviteCode(releasedCode).orElseThrow().getId()).isEqualTo(idNew);

        // 历史行不出现在新持有者的响应中：归属按 inviter_id 判定，与邀请码取值无关（需求 10.10）。
        Map<String, Object> infoOfNew = body(get("/api/invite", bearer(tokenNew)));
        assertThat(infoOfNew).containsEntry("inviteCode", releasedCode).containsEntry("invitedCount", 0);
        Map<String, Object> listOfNew = body(get("/api/invite/invitees", bearer(tokenNew)));
        assertThat(listOfNew).containsEntry("total", 0).containsEntry("invitedCount", 0);
        assertThat(itemsOf(listOfNew)).isEmpty();
        // 历史行一列未动，仍挂在已注销的 R 名下。
        assertThat(sixColumnsByInviter(idR)).isEqualTo(historyRows);
    }

    // ---------------------------------- 快照辅助 ----------------------------------

    private List<Map<String, Object>> sixColumnsByInviter(long inviterId) {
        return jdbcTemplate.queryForList(SIX_COLUMNS + " WHERE inviter_id = ? ORDER BY invite_id", inviterId);
    }

    private Map<String, Object> sevenColumnsByInvitee(long inviteeId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(SEVEN_COLUMNS + " WHERE invitee_id = ?", inviteeId);
        assertThat(rows).as("唯一索引保证以某人为被邀请人的行至多 1 条").hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> sevenColumnsWholeTable() {
        return jdbcTemplate.queryForList(SEVEN_COLUMNS + " ORDER BY invite_id");
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private HttpHeaders noAuth() {
        return new HttpHeaders();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> listBody) {
        return (List<Map<String, Object>>) listBody.get("items");
    }

    private ResponseEntity<Map> postDelete(String token, Map<String, String> payload) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(payload, headers), Map.class);
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /** 邮箱验证码登录/注册合一（不携带邀请码），返回 JWT。 */
    private String registerAndLogin(String email) {
        ResponseEntity<Map> login = emailLogin(email, null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 携带邀请码建号并断言绑定成功（关系经真实登录链路建立），返回新用户的 JWT。 */
    private String registerWithInviteCodeExpectingBound(String email, String inviteCode) {
        ResponseEntity<Map> login = emailLogin(email, inviteCode);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(login)).as("应建立邀请关系: " + email)
                .containsEntry("inviteBound", true)
                .containsEntry("inviteUnboundReason", null);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 以「新鲜」LOGIN 验证码执行 email-login（清历史码以规避 60s 发码冷却）；{@code inviteCode} 可为 null。 */
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

    /** 发一枚新鲜的 DELETE 用途验证码并返回其取值。 */
    private String freshDeleteCode(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return latestCode(email, EmailCodePurpose.DELETE);
    }

    /** 走完整注销流程并断言成功（204 + users 行消失）。 */
    private void deleteAccountExpectingSuccess(String token, String email) {
        long userId = userIdOf(email);
        ResponseEntity<Map> deleted = postDelete(token, Map.of("code", freshDeleteCode(email)));
        assertThat(deleted.getStatusCode()).as("注销应成功: " + deleted).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    /** 给指定用户造一个「仍有他人成员」的协作账本，触发 {@code DELETE_BLOCKED_COLLAB}。 */
    private void seedCollaborativeLedgerWithOtherMember(long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        Ledger ledger = new Ledger();
        ledger.setUserId(ownerId);
        ledger.setName("协作账本");
        ledger.setType(Ledger.TYPE_COLLABORATIVE);
        ledger.setDefault(false);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        ledger = ledgerRepository.saveAndFlush(ledger);

        LedgerMember other = new LedgerMember();
        other.setLedgerId(ledger.getId());
        other.setUserId(ownerId + 100_000L);   // 任意「他人」id：requireDeletable 只看 user_id != 本人
        other.setRole(LedgerMember.ROLE_EDITOR);
        other.setCreatedAt(now);
        ledgerMemberRepository.saveAndFlush(other);
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    private String inviteCodeOf(String email) {
        String inviteCode = userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getInviteCode();
        assertThat(inviteCode).as("建号时应写入邀请码").isNotBlank();
        return inviteCode;
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }
}
