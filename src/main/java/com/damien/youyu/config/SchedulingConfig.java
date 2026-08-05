package com.damien.youyu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关：本项目<strong>首次</strong>引入 {@code @EnableScheduling}（需求 3.1）。
 *
 * <p>自定义提醒（custom-reminder）的 {@code ReminderScheduler} 是本项目第一个 {@code @Scheduled}
 * 任务；此前应用没有任何定时任务，故在此单独立一个配置类启用调度，而非把注解挂到
 * {@code YouyuApplication} 上——这样定时能力的引入点集中、可检索，日后新增定时任务也不必再改入口类。</p>
 *
 * <p>调度故障、微信故障与额度耗尽一律由 {@code ReminderScheduler}/{@code ReminderDispatchService}
 * 就地捕获、绝不回灌记账/登录/注销/结算等主路径（需求 6.7、11.6）。</p>
 *
 * <p>Feature: custom-reminder。覆盖需求 3.1。</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
