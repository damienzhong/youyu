package com.damien.youyu.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.WechatSubscribeTemplate;
import com.damien.youyu.repository.WechatSubscribeTemplateRepository;

/**
 * 微信订阅消息模板 id 的<strong>唯一只读来源</strong>：按业务类型（{@code REMINDER} 记账提醒 /
 * {@code BUDGET} 预算超支通知）从 {@code wechat_subscribe_templates} 表读取启用中的模板 id。
 *
 * <p>模板 id 迁到数据库配置后，运营调整模板不再需要改环境变量并重启。本 provider 只读查库，
 * 模板未配置、未启用或查库异常时一律返回 {@link Optional#empty()}——调用方（记账 / 预算提醒的发送编排）
 * 据此按「未配置安全降级」处理：视为发送失败、不外呼微信、不消耗额度、不影响任何主路径。</p>
 *
 * <p>业务类型常量收敛在此处，避免各调用方各写魔法字符串。</p>
 */
@Service
public class SubscribeTemplateProvider {

    private static final Logger log = LoggerFactory.getLogger(SubscribeTemplateProvider.class);

    /** 业务类型：记账提醒（每日记账提醒模板）。 */
    public static final String BIZ_REMINDER = "REMINDER";

    /** 业务类型：预算提醒（预算超支通知模板）。 */
    public static final String BIZ_BUDGET = "BUDGET";

    private final WechatSubscribeTemplateRepository repository;

    public SubscribeTemplateProvider(WechatSubscribeTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 读取某业务类型启用中的模板 id。
     *
     * @param bizType 业务类型（{@link #BIZ_REMINDER} / {@link #BIZ_BUDGET}）
     * @return 启用中的模板 id；未配置 / 未启用 / 查库异常时为 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<String> templateId(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return Optional.empty();
        }
        try {
            return repository.findByBizTypeAndEnabledTrue(bizType)
                    .map(WechatSubscribeTemplate::getTemplateId)
                    .filter(id -> id != null && !id.isBlank());
        } catch (RuntimeException ex) {
            // 查库异常安全降级为「未配置」：只记不含敏感信息的告警日志，不外抛、不影响主路径。
            log.warn("读取微信订阅消息模板配置失败，视为未配置, bizType={}", bizType, ex);
            return Optional.empty();
        }
    }
}
