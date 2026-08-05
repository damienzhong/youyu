package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 里程碑数值不写死的<b>源码扫描断言</b>（Property 10 的「源码扫描」子句，需求 3.5、10.10）。
 *
 * <p>这条是「里程碑数值不重复定义」的机器化防线。里程碑集合的唯一事实源是
 * {@link GrowthBadgeCatalog} 中统计口径为 {@link BadgeMetric#MAX_STREAK} 的成就门槛
 * （当前为 7 / 30 / 100 / 365）。{@link StreakMilestones} 在启动期从该常量派生里程碑集合，
 * 本 spec 的服务端代码<b>绝不写死这四个数值</b>——否则「库里的段规则」与「代码里的里程碑」
 * 会各自漂移，而漂移只在用户看到「还差 3 天」却拿不到里程碑的那一刻才暴露。</p>
 *
 * <p>本类做两件事，两件都不需要 Spring 上下文、不连数据库：
 * <ol>
 *   <li><b>源码扫描</b>：读 {@code src/main/java/com/damien/youyu} 下本 spec 撰写的全部
 *       {@code Streak*.java} 源文件（{@code StreakMilestones}、{@code StreakQueryService}、
 *       {@code StreakOverviewResponse} 等，随任务推进而增多），<b>排除注释与 import / package 行</b>后，
 *       断言其中不出现任一里程碑数值的裸整型字面量。禁止的数值不写死在测试里，而是从
 *       {@link GrowthBadgeCatalog} 的 {@code MAX_STREAK} 口径门槛动态派生——里程碑集合变了，
 *       这条防线自动跟随。</li>
 *   <li><b>集合恒等</b>：断言 {@link StreakMilestones} 派生出的里程碑集合，逐项等于
 *       {@link GrowthBadgeCatalog} 中 {@code MAX_STREAK} 口径门槛的升序去重结果。</li>
 * </ol>
 *
 * <p>Feature: streak-system, Property 10: 里程碑单调与边界、且里程碑数值不写死。</p>
 * <p>Validates: Requirements 3.5, 10.10</p>
 */
class StreakMilestoneSourceScanTest {

    /** 本 spec 服务端源码的包根目录（工作目录为项目根，与 MigrationDirectoryTest 的约定一致）。 */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "damien", "youyu");

    /** 本 spec 撰写的服务端源文件命名规律：Streak 前缀的 Java 文件。 */
    private static final Pattern STREAK_SOURCE = Pattern.compile("^Streak.*\\.java$");

    /** 块注释（含 Javadoc）：非贪婪跨行匹配，DOTALL 让 {@code .} 吃换行。 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private GrowthBadgeCatalog catalog() {
        return new GrowthBadgeCatalog();
    }

    /** 里程碑门槛：MAX_STREAK 口径、升序、去重（唯一事实源，禁止数值即由此派生）。 */
    private List<Integer> milestoneThresholds() {
        return catalog().badges().stream()
                .filter(b -> b.metric() == BadgeMetric.MAX_STREAK)
                .map(BadgeDef::target)
                .distinct()
                .sorted()
                .toList();
    }

    private static List<Path> streakSourceFiles() {
        assertThat(SOURCE_ROOT)
                .as("服务端源码包根目录须存在（测试的工作目录应为项目根目录）")
                .isDirectory();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> STREAK_SOURCE.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 去掉注释与 import / package 行后，逐行返回 {@code (行号, 有效代码)}。
     *
     * <p>先整文件抹掉块注释（Javadoc 里写「满 7 天」这类文案不应触发扫描），
     * 再逐行抹掉行注释、跳过 import 与 package 声明。</p>
     */
    private static List<String[]> effectiveLines(String source) {
        String withoutBlocks = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        String[] rawLines = source.split("\\R", -1);          // 原始行数用于报错定位
        String[] strippedLines = withoutBlocks.split("\\R", -1);
        List<String[]> out = new ArrayList<>();
        // 块注释替换为单个空格后行数可能与原文不一致，逐行扫描以「去块注释后」的文本为准
        for (int i = 0; i < strippedLines.length; i++) {
            String line = strippedLines[i];
            int lineComment = line.indexOf("//");
            if (lineComment >= 0) {
                line = line.substring(0, lineComment);
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                continue;
            }
            String display = (i < rawLines.length) ? rawLines[i].strip() : trimmed;
            out.add(new String[] {Integer.toString(i + 1), line, display});
        }
        return out;
    }

    /** 匹配某个里程碑数值的裸整型字面量：不被数字 / 标识符字符 / 点号包住。 */
    private static Pattern forbiddenLiteralPattern(List<Integer> thresholds) {
        // 长度降序排列，避免短值先匹配掉长值的前缀（如 "30" 抢在 "365" 之前）
        String alternation = thresholds.stream()
                .map(String::valueOf)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow();
        // (?<![\w.]) 前面不是标识符字符 / 数字 / 点号；(?![\d.]) 后面不是数字 / 点号
        return Pattern.compile("(?<![\\w.])(" + alternation + ")(?![\\d.])");
    }

    @Test
    void streakSourceFilesDoNotHardcodeMilestoneValues() {
        List<Integer> thresholds = milestoneThresholds();
        assertThat(thresholds)
                .as("里程碑门槛（MAX_STREAK 口径）须非空，否则本扫描无从校验")
                .isNotEmpty();

        List<Path> sources = streakSourceFiles();
        assertThat(sources)
                .as("须至少扫描到 StreakMilestones.java（源文件命名规律或目录结构可能已变）")
                .anySatisfy(p -> assertThat(p.getFileName().toString()).isEqualTo("StreakMilestones.java"));

        Pattern forbidden = forbiddenLiteralPattern(thresholds);
        List<String> violations = new ArrayList<>();

        for (Path file : sources) {
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String[] entry : effectiveLines(source)) {
                Matcher m = forbidden.matcher(entry[1]);
                if (m.find()) {
                    violations.add(SOURCE_ROOT.relativize(file) + ":" + entry[0]
                            + " 出现里程碑数值字面量「" + m.group(1) + "」 → " + entry[2]);
                }
            }
        }

        assertThat(violations)
                .as("本 spec 服务端源码不得写死里程碑数值 %s（须一律取自 GrowthBadgeCatalog 的 "
                        + "MAX_STREAK 口径门槛，见 StreakMilestones）；违规位置：%n%s",
                        thresholds, String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void streakMilestonesEqualCatalogMaxStreakThresholds() {
        List<Integer> expected = milestoneThresholds();

        StreakMilestones milestones = new StreakMilestones(catalog());
        milestones.derive();                                  // @PostConstruct 在无 Spring 上下文时须手动触发

        // 用 nextAfter 从最小值逐级向上重建里程碑集合，等价于读私有 thresholds
        List<Integer> reconstructed = new ArrayList<>();
        Integer cur = milestones.nextAfter(Integer.MIN_VALUE);
        while (cur != null) {
            reconstructed.add(cur);
            cur = milestones.nextAfter(cur);
        }

        assertThat(reconstructed)
                .as("StreakMilestones 派生的里程碑集合须逐项等于 GrowthBadgeCatalog 中 MAX_STREAK 口径门槛"
                        + "的升序去重结果（里程碑数值只有这一个事实源）")
                .isEqualTo(expected);
    }
}
