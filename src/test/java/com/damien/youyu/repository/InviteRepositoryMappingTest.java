package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;

/**
 * 邀请数据层的映射与查询验证（H2，表由 Hibernate 依实体生成，Flyway 关闭）。
 *
 * <p>覆盖：{@code invite_relations} 实体↔表结构一致（恰好 7 列、列名、status 以枚举名字符串存取）、
 * {@code users.invite_code} 的映射与唯一性、两个 count 的不同口径（需求 7.5、7.6）、
 * {@code markInvalidByInviteeId} 的影响行数 ≤ 1 且只改 status 与 updated_at（需求 9.15、10.2）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InviteRepositoryMappingTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InviteRelationRepository inviteRelationRepository;

    private User newUser(String nickname, String inviteCode) {
        User u = new User();
        u.setEmail(nickname + "@example.com");
        u.setNickname(nickname);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(BASE);
        u.setPlanExpiresAt(BASE.plusDays(365));
        u.setCreatedAt(BASE);
        u.setUpdatedAt(BASE);
        return userRepository.save(u);
    }

    private InviteRelation newRelation(Long inviterId, Long inviteeId, LocalDateTime registerTime,
            InviteStatus status) {
        InviteRelation r = new InviteRelation();
        r.setInviterId(inviterId);
        r.setInviteeId(inviteeId);
        r.setRegisterTime(registerTime);
        r.setStatus(status);
        r.setCreatedAt(registerTime);
        r.setUpdatedAt(registerTime);
        return inviteRelationRepository.save(r);
    }

    /** 让后续读取一定回库，避免持久化上下文里的旧实体掩盖 @Modifying 更新的效果。 */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private List<String> columnNamesOf(String table) {
        return em.getEntityManager()
                .createNativeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = ?1 ORDER BY COLUMN_NAME")
                .setParameter(1, table)
                .getResultList();
    }

    // ---- 实体↔表结构一致（需求 9.2、9.13） ----

    @Test
    void inviteRelationsTableHasExactlySevenMappedColumns() {
        List<String> columns = columnNamesOf("INVITE_RELATIONS");

        assertThat(columns).containsExactlyInAnyOrder(
                "INVITE_ID", "INVITER_ID", "INVITEE_ID", "REGISTER_TIME",
                "STATUS", "CREATED_AT", "UPDATED_AT");
        // users 上新增的邀请码列存在
        assertThat(columnNamesOf("USERS")).contains("INVITE_CODE");
    }

    @Test
    void inviteRelationRoundTripsAllColumnsAndStoresStatusAsEnumName() {
        User inviter = newUser("inviter-map", "AAAAAAAA");
        User invitee = newUser("invitee-map", "BBBBBBBB");
        InviteRelation saved = newRelation(inviter.getId(), invitee.getId(), BASE, InviteStatus.REGISTERED);
        flushAndClear();

        InviteRelation reloaded = inviteRelationRepository.findById(saved.getInviteId()).orElseThrow();
        assertThat(reloaded.getInviteId()).isNotNull();
        assertThat(reloaded.getInviterId()).isEqualTo(inviter.getId());
        assertThat(reloaded.getInviteeId()).isEqualTo(invitee.getId());
        assertThat(reloaded.getRegisterTime()).isEqualTo(BASE);
        assertThat(reloaded.getStatus()).isEqualTo(InviteStatus.REGISTERED);
        // 需求 9.15：创建时 created_at 与 updated_at 为同一时刻
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(reloaded.getCreatedAt());

        // status 以枚举名(大写字符串)入库，与迁移脚本的 CHECK 约束取值一致
        Object rawStatus = em.getEntityManager()
                .createNativeQuery("SELECT status FROM invite_relations WHERE invite_id = ?1")
                .setParameter(1, saved.getInviteId())
                .getSingleResult();
        assertThat(rawStatus).isEqualTo("REGISTERED");

        // 邀请人/被邀请人是裸 id：删除 users 行后关系行仍可读出（悬空 id 不抛异常）
        userRepository.delete(invitee);
        flushAndClear();
        InviteRelation dangling = inviteRelationRepository.findById(saved.getInviteId()).orElseThrow();
        assertThat(dangling.getInviteeId()).isEqualTo(invitee.getId());
        assertThat(userRepository.findById(invitee.getId())).isEmpty();
    }

    @Test
    void inviteeIdIsUniqueAcrossRelations() {
        User inviterA = newUser("inviter-uniq-a", "CCCCCCCC");
        User inviterB = newUser("inviter-uniq-b", "DDDDDDDD");
        User invitee = newUser("invitee-uniq", "EEEEEEEE");
        newRelation(inviterA.getId(), invitee.getId(), BASE, InviteStatus.REGISTERED);
        flushAndClear();

        InviteRelation duplicate = new InviteRelation();
        duplicate.setInviterId(inviterB.getId());
        duplicate.setInviteeId(invitee.getId());
        duplicate.setRegisterTime(BASE.plusMinutes(1));
        duplicate.setStatus(InviteStatus.REGISTERED);
        duplicate.setCreatedAt(BASE.plusMinutes(1));
        duplicate.setUpdatedAt(BASE.plusMinutes(1));

        assertThatThrownBy(() -> inviteRelationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userInviteCodeIsMappedUniqueAndNullable() {
        User holder = newUser("code-holder", "K7M2Q9XT");
        newUser("code-absent", null);
        newUser("code-absent-2", null);   // 多行 NULL 不冲突
        flushAndClear();

        assertThat(userRepository.findByInviteCode("K7M2Q9XT"))
                .get()
                .extracting(User::getId)
                .isEqualTo(holder.getId());
        assertThat(userRepository.findByInviteCode("ZZZZZZZZ")).isEmpty();
        assertThat(userRepository.existsByInviteCode("K7M2Q9XT")).isTrue();
        assertThat(userRepository.existsByInviteCode("ZZZZZZZZ")).isFalse();

        // 行级写锁查询在 H2 上同样返回该行（锁语义由数据库保证，此处只锁死查询可用）
        assertThat(userRepository.findForUpdateById(holder.getId()))
                .get()
                .extracting(User::getInviteCode)
                .isEqualTo("K7M2Q9XT");
        assertThat(userRepository.findForUpdateById(-1L)).isEmpty();

        User conflicting = new User();
        conflicting.setEmail("conflict@example.com");
        conflicting.setNickname("conflict");
        conflicting.setInviteCode("K7M2Q9XT");
        conflicting.setPlan(Plan.FREE);
        conflicting.setRole(Role.USER);
        conflicting.setPlanStartedAt(BASE);
        conflicting.setPlanExpiresAt(BASE.plusDays(365));
        conflicting.setCreatedAt(BASE);
        conflicting.setUpdatedAt(BASE);
        assertThatThrownBy(() -> userRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- 两个 count 的口径（需求 7.5、7.6） ----

    @Test
    void twoCountsHaveDifferentScopesAndAreInviterScoped() {
        User inviter = newUser("counts-inviter", "FFFFFFFF");
        User other = newUser("counts-other", "GGGGGGGG");
        User i1 = newUser("counts-i1", null);
        User i2 = newUser("counts-i2", null);
        User i3 = newUser("counts-i3", null);
        User i4 = newUser("counts-i4", null);
        newRelation(inviter.getId(), i1.getId(), BASE, InviteStatus.REGISTERED);
        newRelation(inviter.getId(), i2.getId(), BASE.plusMinutes(1), InviteStatus.REGISTERED);
        newRelation(inviter.getId(), i3.getId(), BASE.plusMinutes(2), InviteStatus.INVALID);
        newRelation(other.getId(), i4.getId(), BASE.plusMinutes(3), InviteStatus.REGISTERED);
        flushAndClear();

        long total = inviteRelationRepository.countByInviterId(inviter.getId());
        long registered = inviteRelationRepository
                .countByInviterIdAndStatus(inviter.getId(), InviteStatus.REGISTERED);
        long invalid = inviteRelationRepository
                .countByInviterIdAndStatus(inviter.getId(), InviteStatus.INVALID);

        // 总条数含 INVALID；已邀请人数只数 REGISTERED；两者之差即 INVALID 行数
        assertThat(total).isEqualTo(3);
        assertThat(registered).isEqualTo(2);
        assertThat(invalid).isEqualTo(1);
        assertThat(total - registered).isEqualTo(invalid);

        // 数据范围只认 inviter_id：别人的关系不计入
        assertThat(inviteRelationRepository.countByInviterId(other.getId())).isEqualTo(1);
        assertThat(inviteRelationRepository.countByInviterId(-1L)).isZero();
        assertThat(inviteRelationRepository.countByInviterIdAndStatus(-1L, InviteStatus.REGISTERED)).isZero();
    }

    @Test
    void findByInviterIdIsScopedAndSortedByPageable() {
        User inviter = newUser("page-inviter", "HHHHHHHH");
        User other = newUser("page-other", "JJJJJJJJ");
        User i1 = newUser("page-i1", null);
        User i2 = newUser("page-i2", null);
        User i3 = newUser("page-i3", null);
        User i4 = newUser("page-i4", null);
        InviteRelation older = newRelation(inviter.getId(), i1.getId(), BASE, InviteStatus.REGISTERED);
        // 同一 register_time 两行，用 invite_id 倒序兜底
        InviteRelation tieFirst = newRelation(inviter.getId(), i2.getId(), BASE.plusMinutes(5),
                InviteStatus.REGISTERED);
        InviteRelation tieSecond = newRelation(inviter.getId(), i3.getId(), BASE.plusMinutes(5),
                InviteStatus.INVALID);
        newRelation(other.getId(), i4.getId(), BASE.plusMinutes(9), InviteStatus.REGISTERED);
        flushAndClear();

        var sort = Sort.by(Sort.Order.desc("registerTime"), Sort.Order.desc("inviteId"));
        var page = inviteRelationRepository.findByInviterId(inviter.getId(), PageRequest.of(0, 2, sort));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(InviteRelation::getInviteId)
                .containsExactly(tieSecond.getInviteId(), tieFirst.getInviteId());

        var second = inviteRelationRepository.findByInviterId(inviter.getId(), PageRequest.of(1, 2, sort));
        assertThat(second.getContent()).extracting(InviteRelation::getInviteId)
                .containsExactly(older.getInviteId());

        assertThat(inviteRelationRepository.findByInviteeId(i2.getId()))
                .get()
                .extracting(InviteRelation::getInviteId)
                .isEqualTo(tieFirst.getInviteId());
        assertThat(inviteRelationRepository.findByInviteeId(-1L)).isEmpty();
    }

    // ---- markInvalidByInviteeId（需求 9.15、10.2、10.3） ----

    @Test
    void markInvalidUpdatesAtMostOneRowAndOnlyStatusAndUpdatedAt() {
        User inviter = newUser("mark-inviter", "KKKKKKKK");
        // 双重身份：既是若干行的 inviter，又是某一行的 invitee
        User middle = newUser("mark-middle", "LLLLLLLL");
        User leaf = newUser("mark-leaf", null);
        InviteRelation asInvitee = newRelation(inviter.getId(), middle.getId(), BASE, InviteStatus.REGISTERED);
        InviteRelation asInviter = newRelation(middle.getId(), leaf.getId(), BASE.plusMinutes(1),
                InviteStatus.REGISTERED);
        flushAndClear();

        LocalDateTime now = BASE.plusDays(3);
        int affected = inviteRelationRepository.markInvalidByInviteeId(middle.getId(), now);
        flushAndClear();

        // 唯一索引保证影响行数至多 1
        assertThat(affected).isEqualTo(1);

        InviteRelation updated = inviteRelationRepository.findById(asInvitee.getInviteId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(InviteStatus.INVALID);
        assertThat(updated.getUpdatedAt()).isEqualTo(now);
        // 其余四列一律不动
        assertThat(updated.getInviteId()).isEqualTo(asInvitee.getInviteId());
        assertThat(updated.getInviterId()).isEqualTo(inviter.getId());
        assertThat(updated.getInviteeId()).isEqualTo(middle.getId());
        assertThat(updated.getRegisterTime()).isEqualTo(BASE);
        assertThat(updated.getCreatedAt()).isEqualTo(BASE);

        // 以该用户为 inviter 的行一行不动（含 status）
        InviteRelation untouched = inviteRelationRepository.findById(asInviter.getInviteId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(InviteStatus.REGISTERED);
        assertThat(untouched.getInviterId()).isEqualTo(middle.getId());
        assertThat(untouched.getInviteeId()).isEqualTo(leaf.getId());
        assertThat(untouched.getRegisterTime()).isEqualTo(BASE.plusMinutes(1));
        assertThat(untouched.getCreatedAt()).isEqualTo(BASE.plusMinutes(1));
        assertThat(untouched.getUpdatedAt()).isEqualTo(BASE.plusMinutes(1));
    }

    @Test
    void markInvalidAffectsNoRowWhenUserIsNobodysInvitee() {
        User inviter = newUser("noop-inviter", "MMMMMMMM");
        User invitee = newUser("noop-invitee", null);
        User stranger = newUser("noop-stranger", null);
        InviteRelation existing = newRelation(inviter.getId(), invitee.getId(), BASE, InviteStatus.REGISTERED);
        flushAndClear();

        int affected = inviteRelationRepository.markInvalidByInviteeId(stranger.getId(), BASE.plusDays(1));
        flushAndClear();

        assertThat(affected).isZero();
        InviteRelation unchanged = inviteRelationRepository.findById(existing.getInviteId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(InviteStatus.REGISTERED);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(BASE);
        assertThat(inviteRelationRepository.countByInviterId(inviter.getId())).isEqualTo(1);
    }

    @Test
    void markInvalidIsIdempotentOnAlreadyInvalidRow() {
        User inviter = newUser("idem-inviter", "NNNNNNNN");
        User invitee = newUser("idem-invitee", null);
        InviteRelation relation = newRelation(inviter.getId(), invitee.getId(), BASE, InviteStatus.INVALID);
        flushAndClear();

        LocalDateTime now = BASE.plusDays(2);
        assertThat(inviteRelationRepository.markInvalidByInviteeId(invitee.getId(), now)).isEqualTo(1);
        flushAndClear();

        InviteRelation reloaded = inviteRelationRepository.findById(relation.getInviteId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InviteStatus.INVALID);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(now);
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE);
        assertThat(reloaded.getRegisterTime()).isEqualTo(BASE);
    }
}
