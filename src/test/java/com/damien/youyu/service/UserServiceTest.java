package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;

/**
 * UserService 的示例与边界单元测试（关联需求 9.3、9.4、9.5）。
 *
 * <p>使用 H2 + 真实 {@link UserRepository}，以固定 {@link Clock} 做确定性断言，不使用任何桩。
 * 属性测试（Property 25/26）在 {@link PlanRolePropertyTest} 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");

    @Autowired
    private UserRepository userRepository;

    private UserService service() {
        return new UserService(userRepository, Clock.fixed(T0, ZONE));
    }

    private User seedUser() {
        User u = new User();
        u.setUsername("seed");
        u.setPasswordHash("hash");
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        LocalDateTime t = LocalDateTime.ofInstant(Instant.parse("2020-01-01T00:00:00Z"), ZONE);
        u.setPlanStartedAt(t);
        u.setPlanExpiresAt(t.plusDays(365));
        u.setCreatedAt(t);
        u.setUpdatedAt(t);
        return userRepository.save(u);
    }

    // ---------------- 合法写入 ----------------

    @Test
    void updatePlan_validValue_succeeds() {
        Long id = seedUser().getId();

        User updated = service().updatePlan(id, "pro");

        assertThat(updated.getPlan()).isEqualTo(Plan.PRO);
        assertThat(userRepository.findById(id).orElseThrow().getPlan()).isEqualTo(Plan.PRO);
    }

    @Test
    void updateRole_validValue_succeeds() {
        Long id = seedUser().getId();

        User updated = service().updateRole(id, "admin");

        assertThat(updated.getRole()).isEqualTo(Role.ADMIN);
        assertThat(userRepository.findById(id).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void updatePlan_acceptsAllEnumValues() {
        Long id = seedUser().getId();
        UserService service = service();

        assertThat(service.updatePlan(id, "free").getPlan()).isEqualTo(Plan.FREE);
        assertThat(service.updatePlan(id, "pro").getPlan()).isEqualTo(Plan.PRO);
        assertThat(service.updatePlan(id, "lifetime").getPlan()).isEqualTo(Plan.LIFETIME);
    }

    // ---------------- 非法写入：拒绝且零副作用 ----------------

    @Test
    void updatePlan_invalidValue_rejectedAndPreservesOriginal() {
        Long id = seedUser().getId();

        ApiException ex = catchThrowableOfType(
                () -> service().updatePlan(id, "enterprise"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("ENUM_VALUE_INVALID");
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getField()).isEqualTo("plan");
        // 原值保留不变
        assertThat(userRepository.findById(id).orElseThrow().getPlan()).isEqualTo(Plan.FREE);
    }

    @Test
    void updateRole_invalidValue_rejectedAndPreservesOriginal() {
        Long id = seedUser().getId();

        ApiException ex = catchThrowableOfType(
                () -> service().updateRole(id, "superuser"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("ENUM_VALUE_INVALID");
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getField()).isEqualTo("role");
        assertThat(userRepository.findById(id).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void updatePlan_nullOrEmpty_rejected() {
        Long id = seedUser().getId();

        assertThat(catchThrowableOfType(
                () -> service().updatePlan(id, null), ApiException.class).getCode())
                .isEqualTo("ENUM_VALUE_INVALID");
        assertThat(catchThrowableOfType(
                () -> service().updatePlan(id, ""), ApiException.class).getCode())
                .isEqualTo("ENUM_VALUE_INVALID");
        assertThat(userRepository.findById(id).orElseThrow().getPlan()).isEqualTo(Plan.FREE);
    }
}
