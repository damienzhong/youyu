package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 自定义提醒<b>不新增第二套凭证获取、不自行调 {@code cgi-bin/token}</b> 的<b>源码扫描断言</b>
 * （任务 10.4，需求 11.3）。
 *
 * <p>需求 11.3 要求：自定义提醒系统<b>复用 {@code WeChatAccessTokenProvider} 获取 {@code access_token}</b>，
 * <b>不新建第二套凭证获取或凭证缓存逻辑</b>，且<b>不自行调用 {@code cgi-bin/token}</b>。
 * 这条约束靠人工评审很难长期守住——将来任何人在提醒链路里图省事直接 new 一个 HTTP 客户端打
 * {@code cgi-bin/token}、或另起一份 token 缓存，都会让同 appid 的凭证互相踢掉，表现为随机
 * {@code errcode=40001}，排查成本极高（见 {@code WeChatAccessTokenProvider} 的类级说明）。
 * 本类把这条约束机器化，作为一道回归防线，随任务推进自动覆盖新增的提醒源文件。</p>
 *
 * <p>本类不需要 Spring 上下文、不连数据库，做两组断言：</p>
 * <ol>
 *   <li><b>提醒 spec 源文件洁净</b>：扫描本 spec 撰写的全部提醒服务端源文件（{@code Reminder*.java} /
 *       {@code CustomReminder*.java}，排除与本 spec 无关的既有借贷还款提醒 {@code RepayReminder*}），
 *       去注释后断言其中<b>既不出现任一微信 HTTP 端点</b>（{@code cgi-bin/...}）、<b>不构造任何 HTTP 客户端</b>
 *       （{@code RestClient} / {@code WebClient} / {@code RestTemplate} / {@code HttpClient} /
 *       {@code HttpURLConnection} / {@code OkHttpClient}）、也<b>不调用 {@code fetchAccessToken()}</b>
 *       （取凭证只经 {@code WeChatAccessTokenProvider}）。同时正向确认发送编排确实经
 *       {@code accessTokenProvider.getToken()} 取凭证（复用唯一网关）。</li>
 *   <li><b>全项目凭证入口收敛</b>：扫描 {@code src/main/java/com/damien/youyu} 下全部源文件，去注释后断言
 *       {@code cgi-bin/token} 端点常量只出现在 {@code WeChatClient} 一处、且对 {@code fetchAccessToken()}
 *       的<b>调用</b>只出现在 {@code WeChatAccessTokenProvider} 一处——即全项目<b>没有</b>第二套凭证获取路径。
 *       这道断言范围覆盖全部代码，因此提醒 spec（乃至任何后续 spec）一旦偷偷加了第二套凭证获取，都会被它逮住。</li>
 * </ol>
 *
 * <p>Feature: custom-reminder, 兼容边界回归（任务 10.4）。纯增量只读（Property 7）由
 * {@code ReminderPurelyReadOnlyPropertyTest}（任务 10.3）覆盖，本类不重复。</p>
 * <p>Validates: Requirements 11.3</p>
 */
class ReminderCredentialSourceScanTest {

    /** 本 spec 服务端源码的包根目录（工作目录为项目根，与 MigrationDirectoryTest / StreakMilestoneSourceScanTest 一致）。 */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "damien", "youyu");

    /** 本 spec 撰写的提醒源文件命名规律：Reminder / CustomReminder 前缀。 */
    private static final Pattern REMINDER_SOURCE = Pattern.compile("^(Reminder|CustomReminder).*\\.java$");

    /** 与本 spec 无关的既有源文件（借贷还款提醒），须从提醒 spec 扫描集中排除。 */
    private static final Pattern UNRELATED_SOURCE = Pattern.compile("^RepayReminder.*\\.java$");

    /** 全项目唯一允许出现 {@code cgi-bin/token} 端点常量的源文件。 */
    private static final String TOKEN_ENDPOINT_OWNER = "WeChatClient.java";

    /** 全项目唯一允许<b>调用</b> {@code fetchAccessToken()} 的源文件（其声明在 WeChatClient，调用只在此）。 */
    private static final String TOKEN_FETCH_CALLER = "WeChatAccessTokenProvider.java";

    /** 块注释（含 Javadoc）：非贪婪跨行匹配，DOTALL 让 {@code .} 吃换行。 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 微信 HTTP 端点：任何 {@code cgi-bin/...} 路径（token、消息发送等）。提醒源文件一律不得出现。 */
    private static final Pattern WECHAT_ENDPOINT = Pattern.compile("cgi-bin/");

    /** 自建 HTTP 客户端 / 连接：第二套凭证获取的典型形态，提醒源文件一律不得出现。 */
    private static final Pattern HTTP_CLIENT =
            Pattern.compile("\\b(RestClient|WebClient|RestTemplate|HttpClient|HttpURLConnection|OkHttpClient)\\b");

    /** 对 {@code fetchAccessToken(} 的<b>调用</b>（含前导点，排除方法声明 {@code public ... fetchAccessToken()}）。 */
    private static final Pattern FETCH_ACCESS_TOKEN_CALL = Pattern.compile("\\.fetchAccessToken\\s*\\(");

    /** {@code cgi-bin/token} 端点常量（带前导斜杠，只会匹配 WeChatClient 里的 TOKEN_PATH，不匹配 Javadoc 的无斜杠写法）。 */
    private static final Pattern TOKEN_ENDPOINT = Pattern.compile("/cgi-bin/token");

    // ============================================================================
    // 1) 提醒 spec 源文件洁净：无微信端点、无自建 HTTP 客户端、不调 fetchAccessToken
    // ============================================================================

    @Test
    void reminderSourceFilesHaveNoSecondCredentialPathAndNoDirectWeChatEndpoint() {
        List<Path> sources = reminderSourceFiles();
        assertThat(sources)
                .as("须至少扫描到 ReminderDispatchService.java（发送编排是唯一取凭证的提醒源文件）；"
                        + "源文件命名规律或目录结构可能已变")
                .anySatisfy(p -> assertThat(p.getFileName().toString())
                        .isEqualTo("ReminderDispatchService.java"));

        List<String> violations = new ArrayList<>();
        for (Path file : sources) {
            String relative = SOURCE_ROOT.relativize(file).toString();
            for (String[] entry : effectiveLines(readSource(file))) {
                scanForbidden(WECHAT_ENDPOINT, entry, relative,
                        "自定义提醒不得自行访问任何微信 HTTP 端点（cgi-bin/...），发送须经 WeChatClient", violations);
                scanForbidden(HTTP_CLIENT, entry, relative,
                        "自定义提醒不得自建 HTTP 客户端/连接（第二套凭证获取的典型形态）", violations);
                scanForbidden(FETCH_ACCESS_TOKEN_CALL, entry, relative,
                        "自定义提醒不得直接调用 fetchAccessToken()，取凭证只经 WeChatAccessTokenProvider", violations);
            }
        }

        assertThat(violations)
                .as("自定义提醒 spec 源文件不得新增第二套凭证获取、不得自行调微信端点（需求 11.3）；违规位置：%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void reminderDispatchAcquiresTokenOnlyViaAccessTokenProvider() {
        Path dispatch = reminderSourceFiles().stream()
                .filter(p -> p.getFileName().toString().equals("ReminderDispatchService.java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 ReminderDispatchService.java"));

        String effective = effectiveLines(readSource(dispatch)).stream()
                .map(entry -> entry[1])
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(effective)
                .as("发送编排须经 accessTokenProvider.getToken() 复用全项目唯一凭证网关（需求 11.3）")
                .contains("accessTokenProvider.getToken()");
    }

    // ============================================================================
    // 2) 全项目凭证入口收敛：token 端点只在 WeChatClient、fetchAccessToken 调用只在 Provider
    // ============================================================================

    @Test
    void tokenEndpointConstantIsConfinedToWeChatClient() {
        List<String> offenders = new ArrayList<>();
        for (Path file : allSourceFiles()) {
            String name = file.getFileName().toString();
            if (name.equals(TOKEN_ENDPOINT_OWNER)) {
                continue;   // 唯一允许持有 cgi-bin/token 端点常量的地方
            }
            for (String[] entry : effectiveLines(readSource(file))) {
                if (TOKEN_ENDPOINT.matcher(entry[1]).find()) {
                    offenders.add(SOURCE_ROOT.relativize(file) + ":" + entry[0] + " → " + entry[2]);
                }
            }
        }
        assertThat(offenders)
                .as("cgi-bin/token 端点常量只允许出现在 %s（全项目唯一凭证获取入口，需求 11.3）；越界位置：%n%s",
                        TOKEN_ENDPOINT_OWNER, String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void fetchAccessTokenIsInvokedOnlyByAccessTokenProvider() {
        List<String> offenders = new ArrayList<>();
        for (Path file : allSourceFiles()) {
            String name = file.getFileName().toString();
            if (name.equals(TOKEN_FETCH_CALLER)) {
                continue;   // 唯一允许调用 fetchAccessToken() 的地方
            }
            for (String[] entry : effectiveLines(readSource(file))) {
                if (FETCH_ACCESS_TOKEN_CALL.matcher(entry[1]).find()) {
                    offenders.add(SOURCE_ROOT.relativize(file) + ":" + entry[0] + " → " + entry[2]);
                }
            }
        }
        assertThat(offenders)
                .as("对 fetchAccessToken() 的调用只允许出现在 %s（凭证获取收敛到唯一网关，需求 11.3）；越界位置：%n%s",
                        TOKEN_FETCH_CALLER, String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    // ---------------------------------- 辅助 ----------------------------------

    /** 记录一条违规：{@code pattern} 命中 {@code entry} 的有效代码时，追加 {@code 文件:行 说明 → 原文}。 */
    private static void scanForbidden(Pattern pattern, String[] entry, String relative,
                                      String reason, List<String> violations) {
        Matcher m = pattern.matcher(entry[1]);
        if (m.find()) {
            violations.add(relative + ":" + entry[0] + "「" + m.group() + "」" + reason + " → " + entry[2]);
        }
    }

    /** 本 spec 撰写的提醒服务端源文件（排除既有借贷还款提醒 {@code RepayReminder*}）。 */
    private static List<Path> reminderSourceFiles() {
        assertRootExists();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return REMINDER_SOURCE.matcher(n).matches() && !UNRELATED_SOURCE.matcher(n).matches();
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 全项目服务端源文件。 */
    private static List<Path> allSourceFiles() {
        assertRootExists();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertRootExists() {
        assertThat(SOURCE_ROOT)
                .as("服务端源码包根目录须存在（测试的工作目录应为项目根目录）")
                .isDirectory();
    }

    private static String readSource(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 去掉块注释（含 Javadoc）与行注释、跳过 import / package 行后，逐行返回 {@code (行号, 有效代码, 原文)}。
     *
     * <p>先整文件抹掉块注释（Javadoc 里写 {@code cgi-bin/token}、{@code RestClient} 等文案不应触发扫描），
     * 再逐行抹掉行注释并跳过 import / package 声明（{@code import ...WeChatAccessTokenProvider} 本身合法）。</p>
     */
    private static List<String[]> effectiveLines(String source) {
        String withoutBlocks = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        String[] rawLines = source.split("\\R", -1);
        String[] strippedLines = withoutBlocks.split("\\R", -1);
        List<String[]> out = new ArrayList<>();
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
}
