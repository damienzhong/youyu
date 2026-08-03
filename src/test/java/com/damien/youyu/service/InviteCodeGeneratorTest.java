package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.damien.youyu.error.ApiException;

/**
 * {@link InviteCodeGenerator} 的示例/边界单元测试（关联需求 1.1、1.6、1.9）。
 *
 * <p>不使用 mock 框架：随机源用真实的 {@link Random} 子类（把 {@code nextInt} 压到极小取值范围以
 * 压缩码空间、稳定制造碰撞），占用判定用真实的 {@link Predicate} 并顺手记账，
 * 以便断言「尝试恰好 10 次」这类调用次数约束。</p>
 */
class InviteCodeGeneratorTest {

    // ---- 字母表（需求 1.1） ----

    @Test
    void alphabetExcludesConfusableCharacters() {
        assertThat(InviteCodeGenerator.ALPHABET)
                .hasSize(32)
                .doesNotContain("I")
                .doesNotContain("O")
                .doesNotContain("0")
                .doesNotContain("1");
        assertThat(InviteCodeGenerator.LENGTH).isEqualTo(8);
        // 字母表内不得有重复字符，否则抽取概率不均。
        assertThat(InviteCodeGenerator.ALPHABET.chars().distinct().count()).isEqualTo(32);
    }

    // ---- normalize（需求 1.9） ----

    @Test
    void normalizeMapsNullToEmptyString() {
        assertThat(generator().normalize(null)).isEmpty();
    }

    @Test
    void normalizeTrimsAndUppercases() {
        assertThat(generator().normalize("  k7m2q9xt ")).isEqualTo("K7M2Q9XT");
        assertThat(generator().normalize("   ")).isEmpty();
        assertThat(generator().normalize("K7M2Q9XT")).isEqualTo("K7M2Q9XT");
    }

    // ---- isWellFormed（需求 1.1、1.9） ----

    @Test
    void isWellFormedAcceptsOnlyEightCharsFromAlphabet() {
        InviteCodeGenerator gen = generator();

        // 长度恰为 8 且字符全部取自字母表 → 合法
        assertThat(gen.isWellFormed("K7M2Q9XT")).isTrue();

        // 7 位 / 9 位 → 非法
        assertThat(gen.isWellFormed("K7M2Q9X")).isFalse();
        assertThat(gen.isWellFormed("K7M2Q9XTA")).isFalse();

        // 含被剔除的易混字符 → 非法
        assertThat(gen.isWellFormed("K7M2Q9XI")).isFalse();
        assertThat(gen.isWellFormed("IIIIIIII")).isFalse();
        assertThat(gen.isWellFormed("K7M2Q9X0")).isFalse();

        // null / 空串 / 小写 / 带空白（入参应为 normalize 的结果，本方法不再规整）→ 非法
        assertThat(gen.isWellFormed(null)).isFalse();
        assertThat(gen.isWellFormed("")).isFalse();
        assertThat(gen.isWellFormed("k7m2q9xt")).isFalse();
        assertThat(gen.isWellFormed(" K7M2Q9X")).isFalse();
    }

    @Test
    void isWellFormedAcceptsEveryAlphabetCharacter() {
        InviteCodeGenerator gen = generator();
        for (char c : InviteCodeGenerator.ALPHABET.toCharArray()) {
            assertThat(gen.isWellFormed(String.valueOf(c).repeat(InviteCodeGenerator.LENGTH)))
                    .as("字母表字符 %s 组成的 8 位码应判为合法", c)
                    .isTrue();
        }
    }

    // ---- generateUnique（需求 1.6） ----

    @Test
    void generateUniqueProducesWellFormedCode() {
        InviteCodeGenerator gen = generator();
        String code = gen.generateUnique(candidate -> false);
        assertThat(gen.isWellFormed(code)).isTrue();
    }

    @Test
    void generateUniqueFailsAfterExactlyTenAttemptsWhenAlwaysOccupied() {
        AtomicInteger attempts = new AtomicInteger();
        Predicate<String> alwaysOccupied = candidate -> {
            attempts.incrementAndGet();
            return true;
        };

        assertThatThrownBy(() -> generator().generateUnique(alwaysOccupied))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("INVITE_CODE_GEN_FAILED");

        // 需求 1.6：最多尝试 10 次，不无限重试。
        assertThat(attempts.get()).isEqualTo(InviteCodeGenerator.MAX_ATTEMPTS).isEqualTo(10);
    }

    @Test
    void generateUniqueReturnsFirstUnoccupiedCandidate() {
        // 压缩码空间：随机源只在 {0, 1} 间交替，8 位一码 → 候选序列为 AAAAAAAA、BBBBBBBB、AAAAAAAA...
        InviteCodeGenerator gen = new InviteCodeGenerator(new CyclingRandom(0, 1));
        char first = InviteCodeGenerator.ALPHABET.charAt(0);
        char second = InviteCodeGenerator.ALPHABET.charAt(1);
        String firstCode = String.valueOf(first).repeat(InviteCodeGenerator.LENGTH);
        String secondCode = String.valueOf(second).repeat(InviteCodeGenerator.LENGTH);

        List<String> seen = new ArrayList<>();
        // 第 1 个候选被占用，第 2 个未被占用 → 返回第 2 个。
        String code = gen.generateUnique(candidate -> {
            seen.add(candidate);
            return candidate.equals(firstCode);
        });

        assertThat(code).isEqualTo(secondCode);
        assertThat(seen).containsExactly(firstCode, secondCode);
    }

    private static InviteCodeGenerator generator() {
        return new InviteCodeGenerator(new Random(20240601L));
    }

    /** 受控随机源：按给定序列循环返回下标，用于压缩码空间、稳定制造碰撞。 */
    private static final class CyclingRandom extends Random {
        private final int[] values;
        private int cursor;

        private CyclingRandom(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            int value = values[(cursor / InviteCodeGenerator.LENGTH) % values.length];
            cursor++;
            return value % bound;
        }
    }
}
