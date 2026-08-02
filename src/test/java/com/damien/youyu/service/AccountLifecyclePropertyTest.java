package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerInviteRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.LoanRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.TransactionTemplateRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.support.InMemoryUserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 账号生命周期的属性测试（jqwik），覆盖设计文档 Correctness Properties 中的 Property 6。
 * 运行 200 次随机迭代（与仓库既有 {@code *PropertyTest} 约定一致）。
 *
 * <p><strong>测试层级选择：</strong>采用较轻的「服务级 + 内存存储」方案（而非 {@code @DataJpaTest} H2）。
 * 因为本属性关注的是「注销释放身份 → 身份可复用」这一业务不变式，其核心正是 {@code deleteAccount}
 * 删除用户行从而释放 {@code email}/{@code wx_openid} 两个唯一键。为忠实执行该路径，这里用真实的
 * {@link InMemoryUserRepository} 承载用户存储并真实运行 {@link AccountDeletionService#deleteAccount(Long)}
 * 与 {@link AuthService} 的登录/绑定逻辑；级联删除涉及的其余仓储用测试替身（默认返回空集，注销者名下无其它
 * 数据，符合本属性关注点）。DB 级联语义由集成测试 {@code AccountDeletionCascadeTest} 覆盖，此处不重复。</p>
 *
 * <p>Property 6（注销释放身份）：Validates Requirements 7.3, 8.4</p>
 */
class AccountLifecyclePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");

    // ---------------- 生成器 ----------------

    /** 合法邮箱：本地部分 [a-z0-9]{1,10} + @example.com。 */
    @Provide
    Arbitrary<String> emails() {
        return Arbitraries.strings().withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(10)
                .map(local -> local + "@example.com");
    }

    /** 微信 openid：[a-z0-9]{1,12}。 */
    @Provide
    Arbitrary<String> openids() {
        return Arbitraries.strings().withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(12);
    }

    // ---------------- Property 6: 注销释放身份 ----------------

    /**
     * Property 6（注销释放身份）：对任意 (email, openid)，一个同时持有该邮箱与该微信身份的账号被注销后，
     * 其原 email 与 openid 立即释放——既可被邮箱登录/注册合一重新占用，也可被微信登录重新占用。
     *
     * <p>步骤：邮箱登录建号（占用 email）→ 绑定微信（占用 openid）→ 注销 → 断言两个身份均已从存储释放 →
     * 用同一 email 重新登录/注册成功（新账号）、用同一 openid 重新微信登录成功（新账号）。</p>
     *
     * <p>Validates: Requirements 7.3, 8.4</p>
     */
    @Property(tries = 200)
    void property6_deletionReleasesIdentitiesForReuse(
            @ForAll("emails") String email,
            @ForAll("openids") String openid) {
        Fixture f = newFixture();

        // 1) 邮箱登录/注册合一建号（占用 email）。
        User user1 = f.authService.emailLogin(email, "123456");
        Long id1 = user1.getId();
        assertThat(user1.getEmail()).isEqualTo(email);

        // 2) 绑定微信（占用 openid）。
        f.authService.bindWechat(id1, openid);
        assertThat(f.userRepository.findById(id1).orElseThrow().getWxOpenid()).isEqualTo(openid);

        // 3) 注销：单事务级联硬删 + 释放身份。
        f.deletionService.deleteAccount(id1);

        // 4) 两个身份立即从存储释放。
        assertThat(f.userRepository.findById(id1)).isEmpty();
        assertThat(f.userRepository.findByEmail(email)).isEmpty();
        assertThat(f.userRepository.findByWxOpenid(openid)).isEmpty();

        // 5) email 可被重新注册/登录（生成的是全新账号）。
        User user2 = f.authService.emailLogin(email, "123456");
        assertThat(user2.getEmail()).isEqualTo(email);
        assertThat(user2.getId()).isNotEqualTo(id1);

        // 6) openid 可被重新微信登录（同样是全新账号）。
        User user3 = f.authService.wxLogin(openid);
        assertThat(user3.getWxOpenid()).isEqualTo(openid);
        assertThat(user3.getId()).isNotEqualTo(id1);
    }

    // ---------------- 测试基础设施 ----------------

    private static final class Fixture {
        final InMemoryUserRepository userRepository;
        final AuthService authService;
        final AccountDeletionService deletionService;

        Fixture(InMemoryUserRepository userRepository, AuthService authService,
                AccountDeletionService deletionService) {
            this.userRepository = userRepository;
            this.authService = authService;
            this.deletionService = deletionService;
        }
    }

    /**
     * 构造一套全新的存储与服务：用户存储为真实的 {@link InMemoryUserRepository}；验证码校验恒真、
     * 微信换取把 code 直接当 openid 返回（由生成器控制值）；级联删除涉及的其余仓储用测试替身
     * （默认返回空集，注销者名下无其它数据）。
     */
    private Fixture newFixture() {
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        Clock clock = Clock.fixed(T0, ZONE);

        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.verifyConsume(anyString(), any(EmailCodePurpose.class), anyString()))
                .thenReturn(true);

        WeChatClient weChatClient = mock(WeChatClient.class);
        when(weChatClient.jscode2session(anyString()))
                .thenAnswer(inv -> new WxSession(inv.getArgument(0), null));

        AuthService authService = new AuthService(
                userRepository, clock, weChatClient, verificationCodeService);

        // 级联删除涉及的其余仓储：测试替身（List 返回默认空集），注销者名下无其它数据。
        AccountDeletionService deletionService = new AccountDeletionService(
                mock(LedgerRepository.class),
                mock(LedgerMemberRepository.class),
                mock(AccountRepository.class),
                mock(TransactionRepository.class),
                userRepository,
                verificationCodeService,
                weChatClient,
                mock(AccountLedgerRepository.class),
                mock(TransactionTagRepository.class),
                mock(CategoryRepository.class),
                mock(BudgetRepository.class),
                mock(CategoryBudgetRepository.class),
                mock(LoanRepository.class),
                mock(com.damien.youyu.repository.LoanRepaymentRepository.class),
                mock(ProjectRepository.class),
                mock(MerchantRepository.class),
                mock(TagRepository.class),
                mock(TransactionTemplateRepository.class),
                mock(LedgerInviteRepository.class),
                mock(VerificationCodeRepository.class));

        return new Fixture(userRepository, authService, deletionService);
    }
}
