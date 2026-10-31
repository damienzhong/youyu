package com.damien.youyu.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;

/**
 * 自动分类的纯函数部分：商户名规整、内置商户库、按名称把候选落到用户自己的分类上。
 *
 * <p>无状态、无 IO，便于被 {@link CategorySuggestService} 复用。内置商户库只给出「候选分类名」，
 * 最终一律落到用户<b>已有</b>的分类上，从不新建分类；落不上就交给下一层。</p>
 */
public final class CategoryClassifier {

    private CategoryClassifier() {
    }

    /** 商户规整键最大长度（与 {@code category_rules.merchant_key} 一致）。 */
    public static final int KEY_MAX = 64;

    private static final Pattern BRACKETS = Pattern.compile("[(（【\\[][^)）】\\]]*[)）】\\]]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");

    /**
     * 商户规整键：去掉括号里的门店信息、去空白、转小写，截断到 {@value #KEY_MAX}。
     * 「瑞幸咖啡（望京店）」与「瑞幸咖啡(国贸店)」得到同一个键。空输入返回 null。
     */
    public static String merchantKey(String raw) {
        if (raw == null) {
            return null;
        }
        String s = BRACKETS.matcher(raw).replaceAll("");
        s = SPACES.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > KEY_MAX ? s.substring(0, KEY_MAX) : s;
    }

    /**
     * 从流水备注里取商户：账单导入的备注形如「交易对方 · 商品」，取「 · 」前的部分；
     * 手动备注没有分隔符时整段视为商户（与导入商户撞上的概率很低）。
     */
    public static String counterpartyFromNote(String note) {
        if (note == null) {
            return null;
        }
        int i = note.indexOf(" · ");
        return i >= 0 ? note.substring(0, i) : note;
    }

    /** 内置商户库的一条：命中任一关键字 → 依次尝试这些候选分类名。 */
    private record Entry(CategoryKind kind, String[] keywords, String[] categoryNames) {
    }

    private static Entry exp(String keywords, String names) {
        return new Entry(CategoryKind.EXPENSE, keywords.split("\\|"), names.split("\\|"));
    }

    private static Entry inc(String keywords, String names) {
        return new Entry(CategoryKind.INCOME, keywords.split("\\|"), names.split("\\|"));
    }

    /**
     * 内置商户库，按顺序匹配（越具体越靠前）。关键字一律小写比较。
     * 末尾几条是支付宝/微信账单自带的「交易分类」文案，作为商户都没命中时的补充。
     */
    private static final List<Entry> LIBRARY = List.of(
            exp("美团外卖|饿了么|拉扎斯|外卖", "外卖|餐饮|美食"),
            exp("瑞幸|luckin|星巴克|starbucks|库迪|cotti|manner|tims|咖啡", "咖啡|咖啡奶茶|饮品|饮料|餐饮"),
            exp("喜茶|奈雪|蜜雪冰城|茶百道|古茗|霸王茶姬|一点点|coco都可|沪上阿姨|奶茶", "奶茶|咖啡奶茶|饮品|饮料|餐饮"),
            exp("盒马|叮咚买菜|朴朴|美团买菜|多多买菜|兴盛优选|菜市场|生鲜|永辉|山姆|sam's|costco|大润发|沃尔玛|家乐福|物美|华润万家|超市",
                    "买菜|超市|日用|生活用品|餐饮"),
            exp("便利蜂|全家|familymart|罗森|lawson|7-eleven|7-11|美宜佳|天福|便利店", "零食|日用|超市|餐饮"),
            exp("麦当劳|肯德基|kfc|汉堡王|必胜客|德克士|海底捞|西贝|太二|老乡鸡|餐厅|饭店|火锅|面馆|烧烤",
                    "餐饮|美食"),
            exp("滴滴|高德打车|曹操出行|t3出行|首汽|花小猪|美团打车|出租车|打车", "打车|交通|出行"),
            exp("地铁|轨道交通", "地铁|交通|出行"),
            exp("公交|一卡通|交通卡|乘车码", "公交|交通|出行"),
            exp("哈啰|青桔|美团单车|共享单车", "共享单车|单车|交通|出行"),
            exp("12306|铁路|高铁|动车", "火车|交通|旅行"),
            exp("航空|机票|东方航空|南方航空|国航|春秋航空", "飞机|机票|交通|旅行"),
            exp("携程|去哪儿|飞猪|同程|途牛", "旅行|旅游|机票|酒店"),
            exp("酒店|宾馆|民宿|如家|汉庭|全季|亚朵|华住|锦江", "酒店|住宿|旅行|旅游"),
            exp("中石化|中石油|中国石化|中国石油|加油站|壳牌", "加油|汽车|交通"),
            exp("特来电|星星充电|充电桩", "充电|汽车|交通"),
            exp("停车", "停车|汽车|交通"),
            exp("etc|通行费|过路费|路桥费|高速公路", "过路费|路费|交通"),
            exp("中国移动|中国联通|中国电信|话费|手机充值|流量充值", "话费|通讯|通信"),
            exp("宽带", "宽带|通讯|通信"),
            exp("国家电网|电费", "电费|水电煤|生活缴费|居住|住房"),
            exp("水费|自来水", "水费|水电煤|生活缴费|居住|住房"),
            exp("燃气|天然气", "燃气|水电煤|生活缴费|居住|住房"),
            exp("物业", "物业|居住|住房"),
            exp("房租|自如|贝壳|链家|租金", "房租|居住|住房"),
            exp("淘宝|天猫|京东|拼多多|唯品会|得物|抖音商城|闲鱼|苏宁|当当", "购物|网购|日用"),
            exp("优衣库|zara|h&m|耐克|nike|阿迪达斯|adidas|安踏|李宁|服饰|服装", "服饰|衣服|购物"),
            exp("屈臣氏|丝芙兰|sephora|化妆品|美妆", "美妆|护肤|购物"),
            exp("宠物医院", "宠物医疗|宠物"),
            exp("猫粮|狗粮", "宠物粮|宠物"),
            exp("宠物", "宠物用品|宠物"),
            exp("药房|药店|大药房", "药品|医疗|医药|健康"),
            exp("体检", "体检|医疗|健康"),
            exp("医院|诊所|卫生院|口腔|牙科", "门诊|医疗|看病|健康"),
            exp("爱奇艺|腾讯视频|优酷|芒果tv|bilibili|哔哩哔哩|网易云音乐|qq音乐|酷狗|会员|app store|apple.com",
                    "订阅会员|会员|订阅|娱乐"),
            exp("电影|影城|影院|猫眼|淘票票", "电影|娱乐|休闲"),
            exp("ktv|唱吧", "唱歌|娱乐|休闲"),
            exp("steam|网易游戏|腾讯游戏|游戏", "游戏|娱乐|休闲"),
            exp("健身|瑜伽|游泳|球馆|keep|超级猩猩|乐刻", "运动健身|健身|运动|娱乐"),
            exp("学费", "学费|教育|学习"),
            exp("培训|课程|网课|得到", "培训|教育|学习"),
            exp("书店|图书|当当网", "书籍|教育|学习"),
            // 只认「红包」；个人转账可能是还钱、代付，不猜，留给用户确认
            exp("红包", "红包|人情|社交"),
            exp("餐饮美食", "餐饮|美食"),
            exp("交通出行", "交通|出行"),
            exp("爱车养车", "汽车|交通"),
            exp("日用百货", "日用|超市|购物"),
            exp("服饰装扮", "服饰|购物"),
            exp("充值缴费|生活缴费", "话费|生活缴费|水电煤|通讯"),
            exp("休闲娱乐|文化休闲", "娱乐|休闲"),
            exp("医疗健康", "医疗|健康"),
            exp("教育培训", "教育|学习"),
            exp("住房物业", "居住|住房|房租"),
            inc("工资|薪资|代发", "工资|薪资|月薪"),
            inc("奖金|绩效", "奖金|工资"),
            inc("余额宝|利息", "利息|理财|收益"),
            inc("基金", "基金|理财|收益"),
            inc("理财|收益|分红", "理财|收益|投资"),
            inc("红包", "红包|人情"),
            inc("退款", "退款|其他"),
            inc("报销", "报销|其他"));

    /**
     * 内置商户库：在 {@code text}（商户 + 商品 + 账单自带分类）里找第一条命中的关键字，
     * 把它的候选名落到用户分类上；落不上返回 null。
     */
    public static Category matchLibrary(String text, CategoryKind kind, List<Category> categories) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = cleanText(text).toLowerCase(Locale.ROOT);
        for (Entry e : LIBRARY) {
            if (e.kind() != kind) {
                continue;
            }
            for (String k : e.keywords()) {
                if (t.contains(k)) {
                    Category c = resolveByNames(e.categoryNames(), kind, categories);
                    if (c != null) {
                        return c;
                    }
                    break; // 该条命中但用户没有对应分类，继续看后面更宽泛的条目
                }
            }
        }
        return null;
    }

    /**
     * 把候选分类名落到用户分类：候选名从具体到宽泛排列，逐个尝试——先找同名分类（子分类优先），
     * 再找「用户分类名包含候选名 / 候选名包含用户分类名」的（双方至少 2 个字，避免单字误配）。
     * 逐个候选先精确后模糊，是为了让「地铁」先落到子分类「地铁」，而不是被后面的「交通」抢走。
     */
    static Category resolveByNames(String[] names, CategoryKind kind, List<Category> categories) {
        List<Category> cands = ofKindChildFirst(kind, categories);
        for (String n : names) {
            for (Category c : cands) {
                if (n.equals(c.getName())) {
                    return c;
                }
            }
            for (Category c : cands) {
                String cn = c.getName();
                if (cn.length() >= 2 && n.length() >= 2 && (cn.contains(n) || n.contains(cn))) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * 分类名关键字：用户某个分类名是账单文本的子串即命中（子分类优先、名称更长优先）。
     * 沿用前端原有的匹配口径，作为商户库没命中时的兜底。
     */
    public static Category matchByCategoryName(String text, CategoryKind kind, List<Category> categories) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = cleanText(text);
        List<Category> cands = ofKindChildFirst(kind, categories);
        cands.sort(Comparator.comparing((Category c) -> c.getParentId() == null ? 1 : 0)
                .thenComparing(c -> -c.getName().length()));
        for (Category c : cands) {
            if (clean.contains(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /**
     * 匹配前清理账单文本：支付宝把个人转账统一标成「转账红包」，转账可能是还钱、代付，
     * 不能据此猜成红包，先剔掉这几个字（真正的「微信红包」「红包」仍可命中）。
     */
    private static String cleanText(String text) {
        return text.replace("转账红包", " ");
    }

    private static List<Category> ofKindChildFirst(CategoryKind kind, List<Category> categories) {
        List<Category> out = new ArrayList<>();
        for (Category c : categories) {
            if (c.getKind() == kind && c.getName() != null && !c.getName().isBlank() && c.getParentId() != null) {
                out.add(c);
            }
        }
        for (Category c : categories) {
            if (c.getKind() == kind && c.getName() != null && !c.getName().isBlank() && c.getParentId() == null) {
                out.add(c);
            }
        }
        return out;
    }
}
