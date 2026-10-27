package com.damien.youyu.vision;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;

/**
 * 把截图上的付款方式（「零钱」「花呗」「招商银行信用卡(1234)」）匹配到用户自己的账户。纯函数。
 * 匹配不上返回 null，由用户在核对页自己选。
 */
public final class PayAccountMatcher {

    private PayAccountMatcher() {
    }

    private static final Pattern TAIL = Pattern.compile("(\\d{4})(?!\\d)");
    private static final List<String> BANKS = List.of("招商", "工商", "建设", "农业", "中国银行", "交通", "邮储", "邮政",
            "浦发", "中信", "光大", "民生", "兴业", "平安", "华夏", "广发", "北京银行", "上海银行", "宁波银行", "网商");

    public static Long match(String payAccount, String app, List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        String p = payAccount == null ? "" : payAccount;
        String a = app == null ? "" : app;

        // 1) 卡号尾号
        Matcher m = TAIL.matcher(p);
        if (m.find()) {
            String tail = m.group(1);
            for (Account acc : accounts) {
                if (acc.getCardNo() != null && acc.getCardNo().endsWith(tail)) {
                    return acc.getId();
                }
            }
        }
        // 2) 明确的信用 / 钱包产品
        if (p.contains("花呗")) {
            return byType(accounts, Set.of(AccountType.HUABEI));
        }
        if (p.contains("白条")) {
            return byType(accounts, Set.of(AccountType.JD_BAITIAO));
        }
        // 3) 银行名（区分信用卡 / 储蓄卡）
        for (String bank : BANKS) {
            if (p.contains(bank)) {
                boolean credit = p.contains("信用");
                for (Account acc : accounts) {
                    boolean typeOk = credit ? acc.getType() == AccountType.CREDIT_CARD
                            : acc.getType() == AccountType.BANK_CARD;
                    String names = (acc.getName() == null ? "" : acc.getName())
                            + (acc.getIssuingBank() == null ? "" : acc.getIssuingBank());
                    if (typeOk && names.contains(bank)) {
                        return acc.getId();
                    }
                }
                return null;
            }
        }
        // 4) 微信 / 支付宝余额
        if (p.contains("零钱") || (p.isBlank() && a.contains("微信"))) {
            return byType(accounts, Set.of(AccountType.WECHAT));
        }
        if (p.contains("余额宝") || p.contains("账户余额") || (p.isBlank() && a.contains("支付宝"))) {
            return byType(accounts, Set.of(AccountType.ALIPAY));
        }
        return null;
    }

    /** 该类型的账户恰好一个（或多个时取排序最前）且未隐藏。 */
    private static Long byType(List<Account> accounts, Set<AccountType> types) {
        for (Account acc : accounts) {
            if (types.contains(acc.getType()) && !acc.isHidden()) {
                return acc.getId();
            }
        }
        return null;
    }
}
