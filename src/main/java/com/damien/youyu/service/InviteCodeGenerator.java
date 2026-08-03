package com.damien.youyu.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.damien.youyu.error.ApiException;

/**
 * 「邀请码是什么」的唯一定义处（需求 1.1、1.6、1.9）。
 *
 * <p>本组件无状态、无外部依赖，只负责三件事：</p>
 * <ul>
 *   <li>{@link #normalize(String)}：把外部传入的任意字符串规整成用于匹配的形态（trim + 大写，
 *       {@code null} 视作空串）。邀请码大小写不敏感由此统一实现，调用方不得自行 {@code toUpperCase}。</li>
 *   <li>{@link #isWellFormed(String)}：判定规整后的取值是否为合法邀请码（长度恰为 8、字符全部取自
 *       {@link #ALPHABET}）。格式非法一律走「不匹配」而非抛异常，避免把邀请码问题传导到登录主路径。</li>
 *   <li>{@link #generateUnique(Predicate)}：抽取一个未被占用的候选码。</li>
 * </ul>
 *
 * <p>字母表刻意剔除 {@code I}/{@code O}/{@code 0}/{@code 1} 四个易混字符，剩 32 个字符，
 * 因此 8 位码空间为 32<sup>8</sup> ≈ 1.1 × 10<sup>12</sup>；在可预见的用户量下 10 次抽取全部撞上
 * 已占用取值的概率可忽略，故 {@link #generateUnique(Predicate)} 尝试 10 次后直接失败而不无限重试。</p>
 *
 * <p><b>随机源可注入</b>（{@link #InviteCodeGenerator(Random)}）：生产走 {@link SecureRandom}，
 * 测试可传入把 {@code nextInt} 压到极小取值范围的实现，人为压缩码空间以稳定制造碰撞，
 * 从而覆盖「10 次全被占用」这条分支。</p>
 *
 * <p>账本邀请码（{@code ledger_invites}）后续也收敛到本组件（同一字母表、同一 10 次重试策略），
 * 但两套邀请机制的数据与业务彼此独立。</p>
 */
@Component
public class InviteCodeGenerator {

    /** 邀请码字母表：大写字母与数字，剔除易混的 {@code I}/{@code O}/{@code 0}/{@code 1}，共 32 个字符。 */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 邀请码长度，与 {@code users.invite_code} 的 {@code VARCHAR(8)} 一致。 */
    public static final int LENGTH = 8;

    /** 候选码被占用时的最大抽取次数（需求 1.6）。 */
    static final int MAX_ATTEMPTS = 10;

    private static final Logger log = LoggerFactory.getLogger(InviteCodeGenerator.class);

    private final Random random;

    /** 生产构造器：使用密码学安全随机源（需求 1.6）。 */
    public InviteCodeGenerator() {
        this(new SecureRandom());
    }

    /**
     * 测试构造器：注入受控随机源以压缩码空间、制造碰撞。
     *
     * @param random 逐字符抽取字母表下标所用的随机源
     */
    InviteCodeGenerator(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * 规整外部传入的邀请码：裁剪首尾空白后转为大写（需求 1.9）。
     *
     * @param raw 原始取值，可为 {@code null}
     * @return 规整后的字符串；{@code null} 返回空串，绝不返回 {@code null}
     */
    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 判定规整后的取值是否为格式合法的邀请码（需求 1.1、1.9）。
     *
     * <p>入参应当是 {@link #normalize(String)} 的返回值：本方法不再做 trim 与大写转换，
     * 因此含首尾空白或小写字符的串一律判为非法。</p>
     *
     * @param normalized 规整后的取值，可为 {@code null}
     * @return 长度恰为 {@link #LENGTH} 且每个字符均属于 {@link #ALPHABET} 时为 {@code true}
     */
    public boolean isWellFormed(String normalized) {
        if (normalized == null || normalized.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 抽取一个未被占用的邀请码：逐字符从字母表随机取字符，以 {@code occupied} 判定占用，
     * 最多尝试 {@link #MAX_ATTEMPTS} 次，返回首个未被占用的候选码（需求 1.6）。
     *
     * <p>10 次候选码全部被占用时抛 {@code INVITE_CODE_GEN_FAILED} 并记 ERROR 日志（说明码空间或
     * 随机源出了问题，需要人介入）。<b>是回滚还是降级由调用方决定</b>：建号路径让整个登录事务回滚
     * （不留 {@code invite_code} 为空的新用户行，需求 1.7），惰性补齐路径保持原值并返回错误
     * （需求 1.8）。</p>
     *
     * @param occupied 候选码占用判定，通常为 {@code users.invite_code} 的存在性查询
     * @return 未被占用的 8 位邀请码
     * @throws ApiException {@code INVITE_CODE_GEN_FAILED}，连续 10 次候选码均被占用
     */
    public String generateUnique(Predicate<String> occupied) {
        Objects.requireNonNull(occupied, "occupied");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!occupied.test(candidate)) {
                return candidate;
            }
        }
        log.error("邀请码生成失败：连续 {} 次抽取的候选码均已被占用", MAX_ATTEMPTS);
        throw ApiException.inviteCodeGenFailed();
    }

    /** 逐字符从字母表抽取一个候选码。 */
    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
