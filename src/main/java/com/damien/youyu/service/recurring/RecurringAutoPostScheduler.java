package com.damien.youyu.service.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.damien.youyu.domain.PostMode;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;

/**
 * 每日<b>自动入账定时任务</b>（recurring-auto-post tasks 5.1；需求 4.2、4.3、4.4、4.5、4.6）：懒入账是事实源，
 * 本任务是<b>兜底 / 及时</b>——即便用户不打开小程序，房租 / 房贷等 {@code AUTO} 规则到期日也能被自动记上。
 * 与懒入账<b>共用</b> {@link RecurringAutoPoster#autoPost} 与同一 {@code (rule_id, occurrence_date)} 幂等键，
 * 两者对同一期次至多产生一条流水、结果一致（需求 4.3）。
 *
 * <h2>触发与时区（需求 4.4）</h2>
 * <p>{@code @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Shanghai")}：每日 00:30（{@code Asia/Shanghai}）触发，
 * 避开跨零点边界抖动。{@code today = LocalDate.now(clock)}（{@code TimeConfig} 固定 {@code Asia/Shanghai}），
 * <b>不依赖 JVM 默认时区</b>（与既有 {@link com.damien.youyu.service.ReminderScheduler} 同源）。</p>
 *
 * <h2>扫描范围（需求 4.2）</h2>
 * <p>经 {@link RecurringRuleRepository#findByStatusAndPostMode}({@code ACTIVE}, {@code AUTO}) 扫描
 * <b>全部账本</b>的启用且自动入账规则（不同于懒入账只扫当前账本），对每条规则「已到期、不早于生成下界且表中
 * 尚无任何状态记录」的期次调 {@link RecurringAutoPoster#autoPost}（或按需求 3 降级）。生成下界与期次计算复用
 * {@link RecurringPendingItemService#generationLowerBound} / {@link RecurringPendingItemService#toRuleSpec} 与
 * {@link OccurrenceCalculator}，与懒入账口径完全一致。</p>
 *
 * <h2>双层故障隔离（需求 4.5、4.6）</h2>
 * <p>规则级 + 期次级<b>各自 try/catch 就地隔离</b>：单个期次自动入账失败（含撞唯一键幂等、非预期异常）
 * 只记日志、继续该规则其余期次；单条规则处理失败只记日志、继续其余规则——<b>一条坏数据不拖垮整轮扫描</b>。
 * 本任务在调度线程执行，处于既有记账 / 登录等主路径的<b>事务边界之外</b>，其成败绝不改变任何主路径的返回
 * （需求 4.6）。{@link RecurringAutoPoster#autoPost} 自身是 {@code REQUIRES_NEW} 独立事务，返回即已提交。</p>
 *
 * <p>Feature: recurring-auto-post。覆盖需求 4.2、4.3、4.4、4.5、4.6。</p>
 */
@Component
public class RecurringAutoPostScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringAutoPostScheduler.class);

    private final RecurringRuleRepository ruleRepository;
    private final RecurringPendingItemRepository pendingItemRepository;
    private final RecurringAutoPoster autoPoster;
    private final RecurringAutoPostNotifier autoPostNotifier;
    private final OccurrenceCalculator calculator;
    private final Clock clock;

    public RecurringAutoPostScheduler(RecurringRuleRepository ruleRepository,
                                      RecurringPendingItemRepository pendingItemRepository,
                                      RecurringAutoPoster autoPoster,
                                      RecurringAutoPostNotifier autoPostNotifier,
                                      Clock clock) {
        this.ruleRepository = ruleRepository;
        this.pendingItemRepository = pendingItemRepository;
        this.autoPoster = autoPoster;
        this.autoPostNotifier = autoPostNotifier;
        this.calculator = new OccurrenceCalculator();
        this.clock = clock;
    }

    /**
     * 每日扫描全部 {@code ACTIVE}+{@code AUTO} 规则并对到期未处理期次自动入账（顺序见类级 Javadoc）。
     *
     * <p>整轮扫描本身不抛异常：规则级与期次级失败均在循环内就地捕获，保证一条坏数据不拖垮整轮
     * （需求 4.5、4.6）。</p>
     */
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Shanghai")
    public void scan() {
        LocalDate today = LocalDate.now(clock);
        List<RecurringRule> autoRules =
                ruleRepository.findByStatusAndPostMode(RuleStatus.ACTIVE, PostMode.AUTO);
        for (RecurringRule rule : autoRules) {
            try {
                scanRule(rule, today);
            } catch (Exception e) {
                // 规则级失败就地隔离：不拖垮整轮扫描（需求 4.5）。
                log.warn("[RECURRING_AUTOPOST_RULE_FAILED] ruleId={}", rule.getId(), e);
            }
        }
    }

    /**
     * 对单条 {@code AUTO} 规则的全部「已到期、不早于生成下界且表中尚无记录」期次逐个自动入账。
     * 期次级失败就地隔离（需求 4.5）：单期次异常仅记日志，继续该规则其余期次。
     */
    private void scanRule(RecurringRule rule, LocalDate today) {
        RuleSpec spec = RecurringPendingItemService.toRuleSpec(rule);
        LocalDate lowerBound = RecurringPendingItemService.generationLowerBound(rule);
        for (LocalDate occurrenceDate : calculator.occurrencesUpTo(spec, today)) {
            if (occurrenceDate.isBefore(lowerBound)) {
                continue;
            }
            if (pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule.getId(), occurrenceDate)) {
                continue;
            }
            try {
                AutoPostResult result = autoPoster.autoPost(rule, occurrenceDate);
                if (result.autoPosted()) {
                    // autoPost 是 REQUIRES_NEW，返回即已提交，事务边界外直接发告知（需求 5.3）。
                    autoPostNotifier.notifyAutoPosted(rule.getUserId(), result.transaction());
                }
            } catch (DataIntegrityViolationException duplicate) {
                // 并发 / 重复触发撞唯一键：懒入账或另一次扫描已处理该期次，静默（需求 2.4、3.4、4.3）。
                log.debug("周期自动入账期次已处理（唯一键幂等），ruleId={}, occurrenceDate={}",
                        rule.getId(), occurrenceDate);
            } catch (Exception e) {
                // 期次级失败就地隔离：不阻断同规则其它期次（需求 4.5）。
                log.warn("[RECURRING_AUTOPOST_FAILED] ruleId={}, occurrenceDate={}",
                        rule.getId(), occurrenceDate, e);
            }
        }
    }
}
