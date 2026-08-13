package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.WechatSubscribeTemplate;

/**
 * 微信订阅消息模板配置仓库（{@code wechat_subscribe_templates}，每种业务类型至多一行，主键即 {@code biz_type}）。
 *
 * <p>只读查询：由 {@code SubscribeTemplateProvider} 按业务类型取启用中的模板 id。查不到 / 未启用时返回空，
 * 由服务层折算为「未配置」并让发送安全降级。</p>
 */
@Repository
public interface WechatSubscribeTemplateRepository extends JpaRepository<WechatSubscribeTemplate, String> {

    /** 读取某业务类型且处于启用状态的模板配置；不存在或已停用时返回空。 */
    Optional<WechatSubscribeTemplate> findByBizTypeAndEnabledTrue(String bizType);
}
