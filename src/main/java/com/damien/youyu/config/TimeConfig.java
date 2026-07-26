package com.damien.youyu.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间相关 Bean 配置。
 *
 * <p>统一提供一个 {@link Clock}（{@code Asia/Shanghai}），服务层通过它获取"当前时刻"，
 * 便于在单元测试中以固定时钟做确定性断言（如套餐到期时刻计算、登录锁定窗口）。</p>
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
