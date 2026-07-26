package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;

/**
 * 用户 plan/role 字段的读取与受控写入服务（关联需求 9.1、9.3、9.4、9.5）。
 *
 * <p>本期 {@code plan/role} 仅作存储用途，<strong>不用于任何功能门控或访问控制</strong>
 * （需求 9.4、9.5）。本服务提供对这两个枚举字段的受控写入路径：写入前先做枚举校验，
 * 非法取值一律拒绝且不产生任何持久化副作用（需求 9.3）。</p>
 *
 * <p>零副作用保证：枚举解析在加载/修改实体<strong>之前</strong>完成，非法取值直接抛出
 * {@link ApiException#enumValueInvalid(String)}（{@code ENUM_VALUE_INVALID}, HTTP 400），
 * 因此字段原有值保持不变，不会发生任何写库动作。</p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final Clock clock;

    public UserService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * 将指定用户的 {@code plan} 更新为给定编码。
     *
     * @param userId   目标用户主键
     * @param planCode 目标套餐编码，仅接受 free/pro/lifetime
     * @return 更新后的用户
     * @throws ApiException ENUM_VALUE_INVALID(取值非法，字段 plan) / NOT_FOUND(用户不存在)
     */
    @Transactional
    public User updatePlan(Long userId, String planCode) {
        // 校验前置：非法取值在触及实体前即拒绝，保证零副作用（需求 9.3）。
        Plan plan = parsePlan(planCode);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        user.setPlan(plan);
        user.setUpdatedAt(LocalDateTime.now(clock));
        return userRepository.save(user);
    }

    /**
     * 将指定用户的 {@code role} 更新为给定编码。
     *
     * @param userId   目标用户主键
     * @param roleCode 目标角色编码，仅接受 user/admin
     * @return 更新后的用户
     * @throws ApiException ENUM_VALUE_INVALID(取值非法，字段 role) / NOT_FOUND(用户不存在)
     */
    @Transactional
    public User updateRole(Long userId, String roleCode) {
        Role role = parseRole(roleCode);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        user.setRole(role);
        user.setUpdatedAt(LocalDateTime.now(clock));
        return userRepository.save(user);
    }

    /**
     * 将 plan 编码解析为枚举；非法取值抛出 {@code ENUM_VALUE_INVALID}（需求 9.3）。
     * 该方法为纯函数、无任何副作用，可供转换器与写入路径复用。
     */
    public static Plan parsePlan(String code) {
        if (code != null) {
            for (Plan p : Plan.values()) {
                if (p.getCode().equals(code)) {
                    return p;
                }
            }
        }
        throw ApiException.enumValueInvalid("plan");
    }

    /**
     * 将 role 编码解析为枚举；非法取值抛出 {@code ENUM_VALUE_INVALID}（需求 9.3）。
     */
    public static Role parseRole(String code) {
        if (code != null) {
            for (Role r : Role.values()) {
                if (r.getCode().equals(code)) {
                    return r;
                }
            }
        }
        throw ApiException.enumValueInvalid("role");
    }
}
