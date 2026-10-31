package com.damien.youyu.vision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 截图识别结果的确定性解析：把模型输出的 JSON 规整成金额 / 时间 / 方向都可信的流水。
 *
 * <p>模型只负责「照抄截图上的字」，金额、日期、收支方向的解析与校验全部在这里用代码完成：
 * 金额必须是最多两位小数的正数，否则整笔丢弃；日期没写年份时按「不晚于今天」推断年份；
 * 「今天 / 昨天 / 前天」按参考日期换算。无状态、无 IO。</p>
 */
public final class ScreenshotParser {

    private ScreenshotParser() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 截图类型。 */
    public enum Screen { PAYMENT_RESULT, BILL_LIST, ORDER_DETAIL, RECEIPT, OTHER_BILL, NOT_BILL }

    /** 收支方向：TRANSFER 含信用卡还款、转到自己的账户、余额宝转入转出等非消费流水。 */
    public enum Direction { EXPENSE, INCOME, REFUND, TRANSFER }

    /** 一张截图的解析结果。{@code dropped} 为金额无法解析而丢弃的行数。 */
    public record Shot(Screen screen, String app, String payAccount, List<Entry> entries, int dropped) {
    }

    /**
     * 一笔规整后的流水。
     *
     * @param occurredAt      交易时间；截图上看不出时间为 null
     * @param dateOnly        只看得出日期、看不出时分（时分取 12:00）
     * @param amountUncertain 金额需要用户核对（模型表示看不清，或小数位异常、正负号与方向矛盾）
     * @param categoryGuess   模型从用户分类表里挑的分类（原文，可能为空）
     */
    public record Entry(Direction direction, BigDecimal amount, LocalDateTime occurredAt, boolean dateOnly,
            boolean amountUncertain, String merchant, String item, String billCategory, String categoryGuess) {
    }

    /** 内部哨兵：交易关闭 / 失败等无资金变动的行。 */
    private static final Entry SKIP = new Entry(Direction.EXPENSE, BigDecimal.ONE, null, false, false, "", "", "", "");

    /** 解析模型输出。不是合法 JSON 时按 NOT_BILL 处理（不抛异常）。 */
    public static Shot parse(String raw, LocalDateTime now) {
        JsonNode root = readJson(raw);
        if (root == null || !root.isObject()) {
            return new Shot(Screen.NOT_BILL, "", "", List.of(), 0);
        }
        Screen screen = parseScreen(text(root, "screen"));
        List<Entry> entries = new ArrayList<>();
        int dropped = 0;
        JsonNode arr = root.path("entries");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                Entry e = parseEntry(n, now);
                if (e == null) {
                    dropped++;
                } else if (e != SKIP) {
                    entries.add(e);
                }
            }
        }
        if (screen == Screen.NOT_BILL && !entries.isEmpty()) {
            screen = Screen.OTHER_BILL;
        }
        return new Shot(screen, text(root, "app"), text(root, "payAccount"), entries, dropped);
    }

    /** 没有真正发生资金变动的状态：这类行不是流水，直接跳过（不算丢弃）。 */
    private static final Pattern NO_MONEY_STATUS = Pattern.compile("交易关闭|已关闭|支付失败|交易失败|已撤销|已取消|未支付|待支付");

    private static Entry parseEntry(JsonNode n, LocalDateTime now) {
        if (NO_MONEY_STATUS.matcher(text(n, "status")).find()) {
            return SKIP;
        }
        String amountText = text(n, "amount");
        Amount a = parseAmount(amountText);
        if (a == null) {
            return null;
        }
        Direction dir = parseDirection(text(n, "direction"), a.sign);
        boolean uncertain = n.path("uncertain").asBoolean(false) || a.roughScale;
        // 正负号与方向矛盾（如方向是支出却带「+」）时，让用户看一眼
        if ((a.sign > 0 && dir == Direction.EXPENSE) || (a.sign < 0 && (dir == Direction.INCOME || dir == Direction.REFUND))) {
            uncertain = true;
        }
        Time t = parseTime(text(n, "time"), now);
        String[] mi = normalizeMerchant(text(n, "merchant"), text(n, "item"));
        return new Entry(dir, a.value, t == null ? null : t.at, t != null && t.dateOnly, uncertain,
                clip(mi[0], 50), clip(mi[1], 100), clip(text(n, "billCategory"), 20),
                clip(text(n, "category"), 60));
    }

    // ---------------- 商户 ----------------

    /** 微信 / 支付宝列表标题里的付款方式前缀，如「扫二维码付款-给张三」「群收款-转给李四」。 */
    private static final Pattern PAY_PREFIX = Pattern.compile(
            "^(扫二维码付款|二维码付款|付款码付款|扫码付款|群收款|微信转账|转账|二维码收款|收款)\\s*[-—－:：]?\\s*");
    private static final Pattern TO_FROM = Pattern.compile("^(转给|付给|给|来自)\\s*");
    private static final Pattern ELLIPSIS = Pattern.compile("\\s*(\\.{2,}|…+|⋯+)\\s*$");

    /**
     * 规整商户：把「扫二维码付款-给张三」拆成商户「张三」+ 说明「扫二维码付款」，去掉列表截断留下的省略号。
     * 模型对同一行时而输出「给张三」时而输出「张三」，规整后保持一致，「以后都这样记」的规则才对得上。
     *
     * @return {商户, 说明}
     */
    static String[] normalizeMerchant(String merchant, String item) {
        String m = merchant == null ? "" : merchant.trim();
        String it = item == null ? "" : item.trim();
        m = ELLIPSIS.matcher(m).replaceAll("");
        it = ELLIPSIS.matcher(it).replaceAll("");
        Matcher p = PAY_PREFIX.matcher(m);
        boolean payLike = PAY_PREFIX.matcher(it).find() && it.length() <= 8;
        if (p.find()) {
            String prefix = p.group(1);
            m = m.substring(p.end());
            payLike = true;
            if (it.isEmpty()) {
                it = prefix;
            }
        }
        if (payLike) {
            m = TO_FROM.matcher(m).replaceFirst("");
        }
        if (m.isEmpty()) {
            m = it;
        }
        return new String[] {m, it.equals(m) ? "" : it};
    }

    // ---------------- 金额 ----------------

    private record Amount(BigDecimal value, int sign, boolean roughScale) {
    }

    private static final Pattern AMOUNT = Pattern.compile("^([+-]?)(\\d{1,13})(?:\\.(\\d{1,4}))?$");
    private static final BigDecimal MAX = new BigDecimal("9999999999999.99");

    /** 金额：去掉货币符号、千分位与空白，最多两位小数；小数超过两位时四舍五入并标记待核对。 */
    static Amount parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[¥￥,，\\s元]", "").replace('−', '-').replace('－', '-').replace('＋', '+');
        Matcher m = AMOUNT.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String frac = m.group(3);
        BigDecimal v = new BigDecimal(m.group(2) + (frac == null ? "" : "." + frac));
        boolean rough = frac != null && frac.length() > 2;
        v = v.setScale(2, RoundingMode.HALF_UP);
        if (v.signum() <= 0 || v.compareTo(MAX) > 0) {
            return null;
        }
        int sign = "-".equals(m.group(1)) ? -1 : "+".equals(m.group(1)) ? 1 : 0;
        return new Amount(v, sign, rough);
    }

    private static Direction parseDirection(String raw, int sign) {
        String d = raw == null ? "" : raw.trim().toLowerCase();
        return switch (d) {
            case "expense" -> Direction.EXPENSE;
            case "income" -> Direction.INCOME;
            case "refund" -> Direction.REFUND;
            case "transfer" -> Direction.TRANSFER;
            default -> sign > 0 ? Direction.INCOME : Direction.EXPENSE;
        };
    }

    // ---------------- 时间 ----------------

    private record Time(LocalDateTime at, boolean dateOnly) {
    }

    private static final Pattern FULL = Pattern.compile(
            "(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?(?:\\D{0,4}?(\\d{1,2})[:：](\\d{2}))?");
    private static final Pattern NO_YEAR = Pattern.compile(
            "(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?(?:\\D{0,4}?(\\d{1,2})[:：](\\d{2}))?");
    private static final Pattern RELATIVE = Pattern.compile("(今天|昨天|前天)(?:\\D{0,4}?(\\d{1,2})[:：](\\d{2}))?");
    private static final Pattern TIME_ONLY = Pattern.compile("^\\s*(\\d{1,2})[:：](\\d{2})(?::\\d{2})?\\s*$");

    /** 解析截图上的时间原文；看不出日期返回 null。没写年份时取「不晚于今天」的最近一年。 */
    static Time parseTime(String raw, LocalDateTime now) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        LocalDate today = now.toLocalDate();
        Matcher m = RELATIVE.matcher(s);
        if (m.find()) {
            LocalDate d = switch (m.group(1)) {
                case "昨天" -> today.minusDays(1);
                case "前天" -> today.minusDays(2);
                default -> today;
            };
            return at(d, m.group(2), m.group(3));
        }
        m = TIME_ONLY.matcher(s);
        if (m.matches()) {
            // 只有时分（列表页「今天」分组下常见）：按今天
            return at(today, m.group(1), m.group(2));
        }
        m = FULL.matcher(s);
        if (m.find()) {
            LocalDate d = safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            return d == null ? null : at(d, m.group(4), m.group(5));
        }
        m = NO_YEAR.matcher(s);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            // 没写年份：账单只会是过去的，取「不晚于今天 + 7 天」的最近一年。
            // 留 7 天余量是为了手机与服务器时钟、时区稍有出入时不把今天的账推到去年。
            LocalDate latest = today.plusDays(7);
            LocalDate d = null;
            for (int y = today.getYear() + 1; y >= today.getYear() - 1 && d == null; y--) {
                LocalDate c = safeDate(y, mo, day);
                if (c != null && !c.isAfter(latest)) {
                    d = c;
                }
            }
            return d == null ? null : at(d, m.group(3), m.group(4));
        }
        return null;
    }

    private static Time at(LocalDate d, String hh, String mm) {
        if (hh == null || mm == null) {
            return new Time(d.atTime(12, 0), true);
        }
        int h = Integer.parseInt(hh);
        int mi = Integer.parseInt(mm);
        if (h > 23 || mi > 59) {
            return new Time(d.atTime(12, 0), true);
        }
        return new Time(d.atTime(LocalTime.of(h, mi)), false);
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------- 工具 ----------------

    private static Screen parseScreen(String raw) {
        try {
            return raw == null || raw.isBlank() ? Screen.OTHER_BILL : Screen.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Screen.OTHER_BILL;
        }
    }

    /** 读 JSON：容忍模型偶尔包一层 ```json 代码块。 */
    private static JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            s = a >= 0 && b > a ? s.substring(a, b + 1) : s;
        }
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").trim();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
