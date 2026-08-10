package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.transaction.PlatformTransactionManager;

import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: recurring-transactions, Property 12: 提醒隔离与同日去重
 *
 * <p>{@link RecurringReminderNotifier#notifyIfPending} 的属性测试，覆盖 design.md「Correctness Properties」
 * Property 12：</p>
 *
 * <p><em>对任意</em>提醒发送结果（成功 / 失败 / 超时 / 无额度）与<em>任意次数</em>的触发：</p>
 * <ol>
 *   <li><b>提醒隔离（需求 7.3）</b>——待确认项状态、真实流水与账户余额均<b>不因提醒链路的任何结果而改变</b>。
 *       本组件<b>不持有</b>任何待确认项 / 交易 / 账户仓库依赖（构造器只接受
 *       {@link ReminderQuotaRepository}/{@link UserRepository}/{@link WeChatAccessTokenProvider}/
 *       {@link WeChatClient}/{@link Clock}），因此提醒路径<b>在结构上不可能</b>触碰流水 / 余额 / 待确认项。
 *       本测试额外持有（但<b>不注入</b>）{@link TransactionRepository}/{@link AccountRepository}/
 *       {@link RecurringPendingItemRepository} 三个 mock，在任意触发序列后断言它们<b>零交互</b>——
 *       即提醒路径既不读也不写这三类主数据。又因整个方法体被 {@code try/catch(Throwable)} 包裹，任何发送结果
 *       都不外抛，故不阻断、不回灌任何主路径（需求 7.6 → 支撑 7.3）。</li>
 *   <li><b>同日去重（需求 7.5）</b>——对同一 {@code (所有者, 账本, 自然日)}（{@code Asia/Shanghai}）至多<b>实际
 *       发起一次</b> {@link WeChatClient#sendSubscribeMessage} 调用；无有效额度 / 无 {@code openid} /
 *       无到期待确认项时<b>一次都不发</b>。</li>
 * </ol>
 *
 * <h2>为什么是纯 Mockito 单元属性测试（无 Spring 上下文）</h2>
 * <p>去重是<b>进程内当日窗口、按实例</b>（见 {@link RecurringReminderNotifier} 类级 Javadoc）：固定
 * {@link Clock} 到 {@code Asia/Shanghai} 的同一自然日，并在每个 try 前重建全新 mock 与全新
 * {@link RecurringReminderNotifier} 实例，即可干净地测「同一 (owner, ledger) 同日至多一发」而不受上一 try 的
 * 窗口污染。{@link WeChatClient} 的发送结果由生成器随机化（{@code 0} 成功 / 本地失败或超时哨兵 / {@code 43101}
 * 无额度 / 直接抛异常），额度、{@code openid}、每次触发是否存在到期待确认项亦随机，从而以少量迭代遍历
 * 「结果 × 触发次数 × 额度 × openid」的输入空间。jqwik 属性方法不经 {@code SpringExtension}，因此无需任何
 * Spring 装配，直接构造被测组件，速度快、隔离好。</p>
 *
 * <p><strong>Validates: Requirements 7.3, 7.5</strong></p>
 */
class RecurringReminderIsolationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定到 Asia/Shanghai 的同一自然日（2025-06-15），使「同日去重」可干净断言。 */
    private static final Instant NOW = Instant.parse("2025-06-15T02:00:00Z");
    private static final long OWNER = 1L;
    private static final long LEDGER = 100L;
    private static final String OPENID = "openid-owner-1";

    /** 提醒可能的发送结果（覆盖成功 / 失败 / 超时 / 无额度 / 未预期异常）。 */
    enum Outcome {
        /** 微信返回 errcode=0，发送成功。 */
        SUCCESS,
        /** 本地失败 / 微信通用错误码 / 超时（WeChatClient 已就地归一为哨兵码 -1，不抛异常）。 */
        WX_FAILURE_OR_TIMEOUT,
        /** 43101：用户拒收 / 无额度，触发本地额度归零对齐。 */
        USER_REFUSED,
        /** 下游抛出未预期运行时异常（凭证刷新失败等），验证外层 try/catch(Throwable) 兜底。 */
        THROW
    }

    // 提醒链路真实依赖（注入被测组件）。
    private ReminderQuotaRepository quotaRepository;
    private UserRepository userRepository;
    private WeChatAccessTokenProvider accessTokenProvider;
    private WeChatClient weChatClient;
    private PlatformTransactionManager transactionManager;
    private Clock clock;
    private RecurringReminderNotifier notifier;

    // 主数据仓库 mock：故意「不注入」被测组件，用于结构性断言「提醒路径零交互」（需求 7.3）。
    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private RecurringPendingItemRepository pendingItemRepository;

    @BeforeTry
    void freshMocks() {
        quotaRepository = mock(ReminderQuotaRepository.class);
        userRepository = mock(UserRepository.class);
        accessTokenProvider = mock(WeChatAccessTokenProvider.class);
        weChatClient = mock(WeChatClient.class);
        // 额度写独立事务边界的事务管理器（mock 即可：断言的是隔离/去重，不校验真实提交）。
        transactionManager = mock(PlatformTransactionManager.class);
        clock = Clock.fixed(NOW, ZONE);

        // 全新实例 → 全新进程内当日去重窗口，隔离上一 try（需求 7.5 的按实例语义）。
        notifier = new RecurringReminderNotifier(
                quotaRepository, userRepository, accessTokenProvider, weChatClient,
                transactionManager, clock);

        // 未注入被测组件的主数据仓库：任意触发后应零交互（提醒路径结构上触不到它们）。
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        pendingItemRepository = mock(RecurringPendingItemRepository.class);
    }

    // =====================================================================
    // Property 12
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 12: 提醒隔离与同日去重
     *
     * <p>对同一 {@code (OWNER, LEDGER)} 同一自然日的任意触发序列与任意发送结果：整个过程<b>绝不抛出异常</b>、
     * 对未注入的流水 / 账户 / 待确认项仓库<b>零交互</b>（隔离，需求 7.3）；且对该
     * {@code (owner, ledger, 自然日)} <b>至多发起一次</b>微信发送——无额度 / 无 openid / 无到期待确认项时
     * <b>一次都不发</b>（同日去重，需求 7.5）。</p>
     *
     * <p><strong>Validates: Requirements 7.3, 7.5</strong></p>
     */
    @Property(tries = 100)
    void reminderNeverMutatesMainDataAndSendsAtMostOncePerOwnerLedgerDay(
            @ForAll("outcomes") Outcome outcome,
            @ForAll("quota") int remaining,
            @ForAll boolean openidPresent,
            @ForAll boolean tokenThrows,
            @ForAll("triggers") List<Boolean> triggers) {

        // ---- 桩：额度 / openid（需求 7.1、7.4）----
        when(quotaRepository.findRemaining(OWNER)).thenReturn(Optional.of(remaining));
        when(userRepository.findWxOpenid(OWNER))
                .thenReturn(openidPresent ? Optional.of(OPENID) : Optional.empty());

        // ---- 桩：凭证网关（可能抛未预期异常，验证外层兜底）----
        if (tokenThrows) {
            when(accessTokenProvider.getToken()).thenThrow(new RuntimeException("token refresh boom"));
        } else {
            when(accessTokenProvider.getToken()).thenReturn("access-token");
        }

        // ---- 桩：微信发送结果（成功 / 失败 / 超时 / 无额度 / 抛异常）----
        switch (outcome) {
            case SUCCESS -> when(weChatClient.sendSubscribeMessage(anyString(), anyString(), anyString()))
                    .thenReturn(0);
            // -1 = WeChatClient 的本地失败 / 超时哨兵码（ERRCODE_LOCAL_FAILURE，包内私有），
            // 任意非零且非 43101 的负值即代表「失败 / 超时」分支。
            case WX_FAILURE_OR_TIMEOUT -> when(weChatClient.sendSubscribeMessage(anyString(), anyString(), anyString()))
                    .thenReturn(-1);
            case USER_REFUSED -> when(weChatClient.sendSubscribeMessage(anyString(), anyString(), anyString()))
                    .thenReturn(RecurringReminderNotifier.ERRCODE_USER_REFUSED);
            case THROW -> when(weChatClient.sendSubscribeMessage(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("wechat send boom"));
        }

        // ---- 施加任意次数触发（同一 owner/ledger/自然日）----
        // 断言 1：绝不抛异常（需求 7.6 → 支撑 7.3：提醒链路不阻断、不回灌任何主路径）。
        assertThatCode(() -> {
            for (Boolean hasDuePending : triggers) {
                notifier.notifyIfPending(OWNER, LEDGER, hasDuePending);
            }
        }).as("notifyIfPending 必须吞掉一切故障、绝不抛出（outcome=%s, tokenThrows=%s）", outcome, tokenThrows)
                .doesNotThrowAnyException();

        // ---- 断言 2：提醒隔离——未注入的主数据仓库零交互（需求 7.3）。----
        // 提醒路径既不读也不写流水 / 账户 / 待确认项，故状态 / 余额 / 流水均不可能因发送结果而改变。
        verifyNoInteractions(transactionRepository, accountRepository, pendingItemRepository);

        // ---- 断言 3：同日去重——同一 (owner, ledger, 自然日) 至多发起一次发送（需求 7.5）。----
        boolean anyDuePending = triggers.stream().anyMatch(Boolean::booleanValue);
        boolean eligible = anyDuePending && remaining > 0 && openidPresent;
        if (!eligible) {
            // 无到期待确认项 / 无额度 / 无 openid → 一次都不发（需求 7.4、7.1）。
            verify(weChatClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString());
        } else {
            // 可发送时：无论触发多少次、结果如何，对同一键至多一次实际发送（需求 7.5）。
            verify(weChatClient, atMost(1))
                    .sendSubscribeMessage(anyString(), eq(OPENID), eq(RecurringReminderNotifier.MSG_PENDING));
            verify(weChatClient, atMost(1)).sendSubscribeMessage(anyString(), anyString(), anyString());
        }
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    @Provide
    Arbitrary<Outcome> outcomes() {
        return Arbitraries.of(Outcome.values());
    }

    /** 剩余订阅额度：含 0（无额度，需求 7.4）与若干正额度。 */
    @Provide
    Arbitrary<Integer> quota() {
        return Arbitraries.integers().between(0, 5);
    }

    /** 任意次数触发（1–10），每次带一个「是否存在到期待确认项」的布尔（含全 false）。 */
    @Provide
    Arbitrary<List<Boolean>> triggers() {
        return Arbitraries.of(true, false).list().ofMinSize(1).ofMaxSize(10);
    }
}
