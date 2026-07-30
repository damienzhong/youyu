package com.damien.youyu.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.VerificationCode;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 邮箱验证码服务：负责发码（校验/冷却/IP 限流/存表/发送）与校验（过期/次数/单次消费）。
 *
 * <p>关联需求 1、2。验证码状态存 MySQL（不引入 Redis），防刷四件套：</p>
 * <ul>
 *   <li>邮箱格式正则校验（非法即 {@code EMAIL_INVALID}，不发送邮件，需求 1.1）。</li>
 *   <li>同 {@code (email, purpose)} 冷却期（默认 60s）内拒绝再次发送（{@code CODE_COOLDOWN}，需求 1.3）。</li>
 *   <li>同来源 IP 每分钟/每日上限（默认 3 / 30）（{@code CODE_RATE_LIMITED}，需求 1.4）。</li>
 *   <li>单次消费 + 失败次数上限（默认 5 次）+ 过期（默认 10 分钟）（需求 2.1、2.2、2.3）。</li>
 * </ul>
 *
 * <p>SMTP 发送失败（{@link MailException}）翻译为 {@code EMAIL_SEND_FAILED}（需求 1.5）；
 * 发送通道由 {@link VerificationCodeSender} 抽象（真实 SMTP 或日志降级）。</p>
 *
 * <p>配置项（TTL/冷却/IP 限额）由任务 3.3 通过 {@code app.auth.email-code.*} 注入，
 * 此处以 {@code @Value} 提供合理默认值，服务可独立工作并在 3.3 落地后自动生效。</p>
 */
@Service
public class VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);

    /**
     * 邮箱格式正则：本地部分 + @ + 域名（至少一个点）。刻意保守，仅拦截明显非法输入，
     * 不追求 RFC 完整覆盖（真实可达性由 SMTP 投递结果决定）。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 验证码位数（6 位数字）。 */
    private static final int CODE_DIGITS = 6;

    private final VerificationCodeRepository repository;
    private final VerificationCodeSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private final int ttlSeconds;
    private final int cooldownSeconds;
    private final int ipPerMinuteLimit;
    private final int ipPerDayLimit;
    private final int maxAttempts;

    public VerificationCodeService(
            VerificationCodeRepository repository,
            VerificationCodeSender sender,
            Clock clock,
            @Value("${app.auth.email-code.ttl-seconds:600}") int ttlSeconds,
            @Value("${app.auth.email-code.cooldown-seconds:60}") int cooldownSeconds,
            @Value("${app.auth.email-code.ip-per-minute:3}") int ipPerMinuteLimit,
            @Value("${app.auth.email-code.ip-per-day:30}") int ipPerDayLimit,
            @Value("${app.auth.email-code.max-attempts:5}") int maxAttempts) {
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.ipPerMinuteLimit = ipPerMinuteLimit;
        this.ipPerDayLimit = ipPerDayLimit;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 发送验证码：校验邮箱格式 → 冷却判定 → IP 分钟/日限流 → 生成码存表 → 发送。
     *
     * <p>任一前置校验失败均零副作用（不写表、不发送）。发送失败（SMTP 异常）时记录已入表，
     * 但翻译为 {@code EMAIL_SEND_FAILED} 抛出，避免以成功状态返回（需求 1.5）。</p>
     *
     * @param email   目标邮箱
     * @param purpose 用途（LOGIN/BIND/DELETE）
     * @param ip      请求来源 IP（用于限流/审计），可空
     * @throws ApiException EMAIL_INVALID / CODE_COOLDOWN / CODE_RATE_LIMITED / EMAIL_SEND_FAILED
     */
    @Transactional
    public void sendCode(String email, EmailCodePurpose purpose, String ip) {
        String normalized = email == null ? "" : email.trim();
        // 1) 邮箱格式（需求 1.1）：非法即拒绝，不发送邮件。
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw ApiException.emailInvalid();
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 2) 冷却（需求 1.3）：同 (email, purpose) 在冷却窗口内已发过码则拒绝。
        LocalDateTime cooldownSince = now.minusSeconds(cooldownSeconds);
        if (repository.existsByEmailAndPurposeAndCreatedAtAfter(normalized, purpose, cooldownSince)) {
            throw ApiException.codeCooldown();
        }

        // 3) IP 限流（需求 1.4）：分钟窗口与日窗口分别判定（ip 为空则跳过 IP 维度限流）。
        if (ip != null && !ip.isBlank()) {
            long lastMinute = repository.countByIpAndCreatedAtAfter(ip, now.minusMinutes(1));
            if (lastMinute >= ipPerMinuteLimit) {
                throw ApiException.codeRateLimited();
            }
            long lastDay = repository.countByIpAndCreatedAtAfter(ip, now.minusDays(1));
            if (lastDay >= ipPerDayLimit) {
                throw ApiException.codeRateLimited();
            }
        }

        // 4) 生成码并存表（需求 1.2）。
        String code = generateCode();
        VerificationCode vc = new VerificationCode();
        vc.setEmail(normalized);
        vc.setPurpose(purpose);
        vc.setCode(code);
        vc.setExpiresAt(now.plusSeconds(ttlSeconds));
        vc.setConsumed(false);
        vc.setAttemptCount(0);
        vc.setIp(ip);
        vc.setCreatedAt(now);
        repository.save(vc);

        // 5) 发送（需求 1.5）：SMTP 失败翻译为 EMAIL_SEND_FAILED，不以成功状态返回。
        try {
            sender.send(normalized, code, purpose);
        } catch (MailException e) {
            log.warn("验证码邮件发送失败：email={} purpose={}", normalized, purpose, e);
            throw ApiException.emailSendFailed();
        }
    }

    /**
     * 校验并单次消费验证码。
     *
     * <p>取该 {@code (email, purpose)} 下最新一条未消费记录：</p>
     * <ul>
     *   <li>无记录 / 码不匹配 / 已过期：失败累计 +1，达到上限（默认 5）则标记消费失效；返回 {@code false}。</li>
     *   <li>成功：立即标记 {@code consumed=true}（单次消费，防重放）；返回 {@code true}。</li>
     * </ul>
     *
     * <p>调用方将 {@code false} 映射为 {@code CODE_INVALID}（需求 2.2）；不因邮箱是否注册而区分结果。</p>
     *
     * @return 是否校验通过并已单次消费
     */
    @Transactional
    public boolean verifyConsume(String email, EmailCodePurpose purpose, String code) {
        String normalized = email == null ? "" : email.trim();
        Optional<VerificationCode> latest =
                repository.findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(normalized, purpose);

        // 无有效记录：无可累计对象，直接失败（需求 2.2）。
        if (latest.isEmpty()) {
            return false;
        }

        VerificationCode vc = latest.get();
        LocalDateTime now = LocalDateTime.now(clock);

        boolean expired = vc.getExpiresAt() == null || !now.isBefore(vc.getExpiresAt());
        boolean matches = code != null && code.equals(vc.getCode());

        if (expired || !matches) {
            // 失败累计 +1；达到上限则失效（标记消费），后续一律取不到该码（需求 2.3）。
            int attempts = vc.getAttemptCount() + 1;
            vc.setAttemptCount(attempts);
            if (attempts >= maxAttempts) {
                vc.setConsumed(true);
            }
            repository.save(vc);
            return false;
        }

        // 成功：单次消费，立即失效防重放（需求 2.1）。
        vc.setConsumed(true);
        repository.save(vc);
        return true;
    }

    /** 生成 6 位数字验证码（含前导零）。 */
    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_DIGITS); // 1_000_000
        int value = random.nextInt(bound);
        return String.format("%0" + CODE_DIGITS + "d", value);
    }
}
