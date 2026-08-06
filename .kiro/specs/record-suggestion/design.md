# Design Document

## Overview

记账推荐给「有余」补上「少输入」的一环：打开首页时，基于当前账本**最近 30 天的历史流水**实时算出
2–3 条最可能要记的「候选」（如「午餐 35 元 餐饮」），用户点其一即**带预填数据跳进记账页**，
确认或微调后走既有流程保存。

三条贯穿全设计的边界（均已在需求裁定）：

1. **纯派生、纯只读**。候选每次由 `transactions` 实时算出，不落第二张表、不加迁移；
   只读 `transactions`/`categories`/`accounts`。删掉本功能，其余原样成立。
2. **不直接入账**。点候选只把字段带进记账页作初始值，落库仍由用户在既有
   `POST /api/transactions` 完成。推荐系统自身不调用任何写接口、不碰余额。
3. **与既有「记账模板」并存互不影响**。模板是手动收藏（`transaction_templates`），推荐是历史派生；
   两者各自预填，互不读写。

### 关键设计决策速览

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 候选来源 | 当前账本近 30 天、未删除、`expense`/`income` 流水 | 贴近真实重复记账；转账语义不同，排除 |
| 去重单位 | 形态 `(type, categoryId, accountId, amount, note规整)` | 同一笔组合只出现一次 |
| 排序 | 出现次数降序 → 近因降序 → 代表 id 降序 | 全序、确定、可复现；「最常记、最近记」优先 |
| 计算位置 | 拉窗口内行到内存分组排序 | 需 `note` 去空白规整，SQL 分组难保一致；窗口有界，成本可控 |
| 展示门槛 | 候选 < 2 条服务端直接返回空 | 满足「不足不硬猜」，前端只判空 |
| 落库 | 跳记账页预填，走既有创建接口 | 用户可改；不新建写链路 |
| 持久层 | Spring Data JPA 只读投影（对齐既有切片） | 与项目一致，无新表 |
| 账本隔离 | 复用 `CurrentLedger`（`X-Ledger-Id`） | 与流水/分类同一口径 |

### 与其它 spec 的关系

- **复用记账链路**：候选点击后走既有 `pages/record/record.vue` 与 `POST /api/transactions`，不改其契约。
- **复用账本/鉴权**：`CurrentLedger`（`X-Ledger-Id` + 默认账本兜底）、`CurrentUser`。
- **无迁移**：当前最大版本 `V35__custom_reminder.sql`，本 spec 不占号、不加脚本。

---

## Architecture

### 分层与包落点（对齐既有 transaction_templates 垂直切片）

```
api/RecordSuggestionController.java     GET /api/transactions/suggestions（只读，鉴权 + 账本解析 + 转发）
api/dto/RecordSuggestionResponse.java   record：{ suggestions: List<RecordSuggestionItem> }
api/dto/RecordSuggestionItem.java       record：{ type, amount, categoryId, accountId, note, categoryName, categoryIcon }
service/RecordSuggestionService.java    @Transactional(readOnly=true)：拉窗口行 → 分组 → 排序 → 取前 3
service/RecordSuggestionRanker.java     纯函数：List<行投影> → List<候选>（分组/去重/排序/截断），无 DB、无时钟副作用
repository/…                            复用/新增 TransactionRepository 只读窗口投影查询
```

miniapp 侧：

```
src/api/suggestion.js                   fetchSuggestions(ledgerId)，对齐 api/template.js
src/pages/index/index.vue               新增推荐卡（快捷入口下方），点候选 navigateTo 记账页带预填参数
src/pages/record/record.vue             扩展既有 onLoad(q)：读 amount/categoryId/note 预填（复用 applyTemplate 式逻辑）
```

**不新增后端写接口、不新增实体、不新增迁移、不改既有 DTO。**

### 两条链路

**链路 A（首页展示）**：首页 `onShow` → `fetchSuggestions(currentLedger)` →
`RecordSuggestionController` → `RecordSuggestionService.list(ledgerId)` →
`TransactionRepository.findWindowRows(...)`（近 30 天、本账本、未删除、expense/income）→
`RecordSuggestionRanker.rank(rows, clock)` → 前 3 条（<2 则空）→ 前端 <2 不展示卡。

**链路 B（点击预填）**：点候选 → `navigateTo('/pages/record/record?type=..&amount=..&categoryId=..&accountId=..&note=..')`
→ 记账页 `onLoad(q)` 读取并预填 → 用户改/确认 → 既有 `POST /api/transactions` 保存。推荐系统不参与落库。

```
首页 onShow (具体账本, 已登录)
      │  GET /api/transactions/suggestions   (X-Ledger-Id)
      ▼
RecordSuggestionController → Service.list(ledgerId)
      │  findWindowRows: ledger_id=? and deleted_at is null
      │                  and type in ('expense','income')
      │                  and occurred_at >= 窗口起点
      ▼
RecordSuggestionRanker.rank(rows, clock)
      ① 按形态 (type,categoryId,accountId,amount,trim(note)) 分组
      ② 每组：frequency=组内行数, recency=max(occurred_at), rep=occurred_at最大(并列取id最大)
      ③ 排序：freq desc → recency desc → rep.id desc
      ④ 取前 3；若 <2 → 返回空
      ▼
List<RecordSuggestionItem>（join categories 取 name/icon 供展示）
```

### 时区

窗口起点按 `Asia/Shanghai`（全局默认时区已在 `YouyuApplication` 设定，且注入 `Clock`）：
`LocalDate today = LocalDate.now(clock)`；窗口起点 = `today.minusDays(29).atStartOfDay()`，
终点 = `today.atTime(LocalTime.MAX)`。判定不依赖 JVM 默认时区，`clock` 由既有 `TimeConfig` 提供。

---

## Components and Interfaces

### 1. REST 接口（`RecordSuggestionController`）

挂到既有交易路由族下，避免新增顶层路径：`GET /api/transactions/suggestions`。
对齐 `TransactionTemplateController`：注入 `CurrentLedger` + `CurrentUser`，鉴权与账本解析全部复用。

```java
@RestController
@RequestMapping("/api/transactions")
public class RecordSuggestionController {
    private final RecordSuggestionService suggestionService;
    private final CurrentLedger currentLedger;

    @GetMapping("/suggestions")
    public ResponseEntity<RecordSuggestionResponse> suggestions() {
        Long ledgerId = currentLedger.requireLedgerId();   // 无令牌→UNAUTHENTICATED；账本不可访问→既有错误
        return ResponseEntity.ok(suggestionService.list(ledgerId));
    }
}
```

- 鉴权：`SecurityConfig` 中 `/api/transactions/**` 已在 `authenticated()` 下；令牌无效/过期→既有 401 `UNAUTHENTICATED`（需求 6.2）。
- 账本：`currentLedger.requireLedgerId()` 复用 `X-Ledger-Id` 解析与成员校验，越权→既有账本不可访问错误（需求 6.3）。
- 数据归属只认令牌用户 + 解析出的账本，忽略任何请求入参指定的用户/账本（需求 6.4）。
- 「全部账本聚合视图」由前端不发起请求实现（前端在 `isAll` 时不请求，见 miniapp）；后端始终按单账本工作。

DTO（record，对齐既有 `TransactionTemplateResponse` 风格）：

```java
public record RecordSuggestionResponse(List<RecordSuggestionItem> suggestions) {}

public record RecordSuggestionItem(
    String type,            // expense/income
    BigDecimal amount,      // 恒为正，2 位小数
    Long categoryId,        // 代表流水的分类 id（可能已被删除）
    Long accountId,         // 代表流水的账户 id（可能已被删除）
    String note,            // 规整后备注（可空）
    String categoryName,    // 展示用：join categories 得；分类已删则 null
    String categoryIcon) {} // 展示用：分类 icon；缺省 null，前端回退
```

### 2. `RecordSuggestionService`（只读编排）

```java
@Service
public class RecordSuggestionService {
    static final int WINDOW_DAYS = 30;      // 含当日共 30 个自然日
    static final int MAX_SUGGESTIONS = 3;
    static final int MIN_SUGGESTIONS = 2;   // 不足 2 条返回空（需求 7.1）

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RecordSuggestionResponse list(Long ledgerId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = today.minusDays(WINDOW_DAYS - 1).atStartOfDay();
        LocalDateTime to = today.atTime(LocalTime.MAX);
        List<SuggestionRow> rows = transactionRepository.findSuggestionWindowRows(ledgerId, from, to);
        List<RankedShape> ranked = RecordSuggestionRanker.rank(rows);   // 分组+排序+截断到 3
        if (ranked.size() < MIN_SUGGESTIONS) {
            return new RecordSuggestionResponse(List.of());             // 不足不硬猜
        }
        // 批量取分类名/图标（一次 in 查询），分类已删→name/icon 留 null
        Map<Long, Category> cats = categoryRepository.findByIdIn(
            ranked.stream().map(RankedShape::categoryId).filter(Objects::nonNull).toList())
            .stream().collect(toMap(Category::getId, identity()));
        List<RecordSuggestionItem> items = ranked.stream()
            .map(r -> toItem(r, cats.get(r.categoryId()))).toList();
        return new RecordSuggestionResponse(items);
    }
}
```

- 只读事务；两次查询（窗口行 + 分类名批量）都是 `SELECT`，绝不写。
- 分类/账户是否仍存在**不影响**候选生成（代表流水的 id 照带），仅影响展示名与前端预填（需求 4.5）。

### 3. `RecordSuggestionRanker`（纯函数，对齐 `StreakJudgment` 静态工具风格）

```java
public final class RecordSuggestionRanker {
    private RecordSuggestionRanker() {}

    /** 分组去重 + 排序 + 截断到前 3。输入行假定已由仓库限定窗口/账本/类型/未删除。 */
    public static List<RankedShape> rank(List<SuggestionRow> rows) {
        Map<ShapeKey, Agg> byShape = new LinkedHashMap<>();
        for (SuggestionRow r : rows) {
            ShapeKey key = new ShapeKey(r.type(), r.categoryId(), r.accountId(),
                                        r.amount(), normalizeNote(r.note()));
            byShape.computeIfAbsent(key, k -> new Agg()).accept(r);   // 累加 frequency，维护代表行
        }
        return byShape.values().stream()
            .map(Agg::toRanked)
            .sorted(BY_FREQ_DESC.thenComparing(BY_RECENCY_DESC).thenComparing(BY_REP_ID_DESC))
            .limit(3)
            .toList();
    }

    /** 备注规整：null/空白 → 空串；否则去首尾空白。形态标识的一部分（需求 2.2 术语）。 */
    static String normalizeNote(String note) {
        return note == null ? "" : note.strip();
    }
}
```

- **形态标识**：`(type, categoryId, accountId, amount, normalizeNote(note))`。`amount` 用 `BigDecimal.compareTo` 语义比较（`35.00` 与 `35` 视为同额），`ShapeKey` 以规整后的 `amount`（`stripTrailingZeros`）参与 equals/hashCode，避免标度不同导致漏并。
- **代表行**：组内 `occurred_at` 最大者，并列取 `id` 最大者（确定性，需求 2.3）。
- **排序**：`frequency` 降序 → `recency`(=代表行 occurred_at) 降序 → 代表行 `id` 降序，构成全序（需求 3.2、3.3、3.5）。
- 纯函数、无 DB、无时钟：可被属性测试穷举（需求 3 全部）。

### 4. 仓库查询（`TransactionRepository` 新增只读投影方法）

```java
public interface SuggestionRow {   // 接口投影，避免整实体加载
    String getType(); BigDecimal getAmount();
    Long getCategoryId(); Long getAccountId();
    String getNote(); LocalDateTime getOccurredAt(); Long getId();
}

@Query("""
    select t.type as type, t.amount as amount, t.categoryId as categoryId,
           t.accountId as accountId, t.note as note, t.occurredAt as occurredAt, t.id as id
    from Transaction t
    where t.ledgerId = :ledgerId
      and t.type in ('expense','income')
      and t.occurredAt >= :from and t.occurredAt <= :to
    """)
List<SuggestionRow> findSuggestionWindowRows(@Param("ledgerId") Long ledgerId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);
```

- `Transaction` 实体带 `@SQLRestriction("deleted_at is null")`，JPQL 查询自动排除软删（需求 2.1）。
- 走既有 `idx_tx_user_time (user_id, occurred_at)`；本查询按 `ledger_id + occurred_at` 过滤，窗口 30 天单账本，行数有界。如后续量大，可加 `idx_tx_ledger_time`，但**本 spec 不加迁移**，先复用现有索引。
- `CategoryRepository.findByIdIn(Collection<Long>)`：若不存在则复用既有查询或新增一个只读派生方法。

---

## Data Models

**无新增表、无迁移脚本。** 仅新增只读投影 `SuggestionRow` 与内存排序中间类型 `ShapeKey`/`Agg`/`RankedShape`。

复用的既有列（只读）：
- `transactions`：`ledger_id`、`type`、`amount`、`category_id`、`account_id`、`note`、`occurred_at`、`id`、`deleted_at`（软删由 `@SQLRestriction` 排除）。
- `categories`：`id`、`name`、`icon`（展示用）。
- `accounts`：仅用于前端预填时校验账户是否仍存在（记账页既有可选账户集覆盖，无需后端额外查询）。

`reset-db.sql` 无需改动（本 spec 不建表）。账号注销无需改动（无本 spec 数据）。

---

## miniapp 设计

### 文件清单

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `src/api/suggestion.js` | 新增 | `fetchSuggestions(ledgerId)`，对齐 `api/template.js` 的 `opts(ledgerId)` |
| `src/pages/index/index.vue` | 改 | 快捷入口下方加推荐卡；`onShow` 拉取；点候选跳记账页 |
| `src/pages/record/record.vue` | 改 | 扩展既有 `onLoad(q)`，读 `amount/categoryId/note` 预填 |

### `src/api/suggestion.js`

```js
import { http } from '../utils/request'
function opts(ledgerId) { return ledgerId != null ? { ledgerId } : undefined }
/** 当前账本的记账推荐（至多 3 条；<2 条后端返回空）。 */
export function fetchSuggestions(ledgerId) {
  return http.get('/transactions/suggestions', opts(ledgerId))
}
```

### 首页推荐卡（`index.vue`）

- **触发**：`onShow` 且已登录且**非全部账本聚合视图**（`!ledgerStore.isAll`）时调 `fetchSuggestions(当前账本)`；聚合视图或未登录不请求（需求 5.3、7.4）。
- **展示位置**：快捷入口 `quick-wrap` 之下、预算卡之上（对齐原型图 `design/auto-suggest-prototype.html` 方案 2）。
- **渲染**：返回 `suggestions.length >= 2` 才渲染卡片，列出每条的金额、分类名、方向、图标（图标缺省走既有 `AppIcon` 名称回退）；`< 2` 或请求失败/超时（3000ms）→ 不渲染卡、不占位、其余模块不受影响（需求 1、7）。
- **切换账本**：账本切换会触发首页重载/`onShow`，据切换后账本重新拉取（需求 5.2）。
- **点候选**：`uni.navigateTo({ url: '/pages/record/record?type=${type}&amount=${amount}&categoryId=${categoryId}&accountId=${accountId}&note=${encodeURIComponent(note)}' })`；不做任何写请求（需求 4.2）。跳转失败 `fail` 回调提示、停留原页（需求 4.7）。
- **降级**：请求失败/超时静默隐藏卡，自动重试 0 次（需求 7.2、7.5）。

### 记账页预填（`record.vue`）

扩展既有 `onLoad(q)`（已解析 `q.type`/`q.accountId`/`q.ledgerId`），在 `load()`（分类/账户就绪）后应用预填，复用既有 `applyTemplate` 的赋值方式：

```js
// onLoad 内记录预填参数
prefill.value = q ? {
  amount: q.amount, categoryId: q.categoryId ? Number(q.categoryId) : null, note: q.note
} : null

// load() 末尾（accounts/tree 就绪后）
if (prefill.value && !isEditing.value) {
  if (prefill.value.amount) expr.value = String(prefill.value.amount)
  if (prefill.value.note) note.value = decodeURIComponent(prefill.value.note)
  if (prefill.value.categoryId && categoryExists(prefill.value.categoryId)) categoryId.value = prefill.value.categoryId
  if (preAccountId.value && accountById(preAccountId.value)) accountId.value = preAccountId.value
  // 分类/账户已删 → 留空由用户重选（需求 4.5）；金额缺失/非正 → 留空（需求 4.6）
}
```

- 预填只设初始值，用户可改任意字段，保存仍走既有 `POST /api/transactions`（需求 4.3、4.4）。
- 不展示任何金额/账本名以外的敏感信息；推荐卡与预填不含其它账本数据、邮箱、令牌（需求 8.5）。

---

## Error Handling

推荐是「读」，一切失败降级为「不展示推荐卡」，不新增任何错误码（复用既有 `UNAUTHENTICATED` 与账本不可访问错误）。

| 情形 | 处理 | 对外表现 |
| --- | --- | --- |
| 令牌缺失/过期/用户不存在 | `currentLedger.requireLedgerId()` 内既有链路 | 401 `UNAUTHENTICATED`，无候选 |
| `X-Ledger-Id` 越权 | `ledgerService.requireAccessible` 抛既有错误 | 既有账本不可访问错误，无候选 |
| 窗口内合格历史 < 2 组 | 服务端返回空列表 | `{suggestions: []}`，前端不展示卡 |
| 查询异常/超时 | 前端 3000ms 无响应或 reject | 静默隐藏卡、重试 0 次、首页其余正常 |
| 分类/账户已删 | 候选照出，展示名 null / 预填留空 | 前端回退图标、留空字段，不报错 |
| 跳转记账页失败 | `navigateTo` fail 回调 | 提示 + 停留原页，不写任何数据 |
| 聚合视图 / 未登录 | 前端不发起请求 | 无卡、无请求 |

---

## Correctness Properties

属性测试用 jqwik（服务端，仓库根已有 `.jqwik-database`），聚焦纯函数 `RecordSuggestionRanker`。

### Property 1: 去重
任意历史行集合经 `rank` 后，结果中不存在两条形态相同的候选。

**Validates: Requirements 3.1**

### Property 2: 排序全序且确定
`rank` 结果按 `(frequency desc, recency desc, repId desc)` 全序排列；对同一输入多次调用返回相同集合与相同次序（与输入行的传入顺序无关）。

**Validates: Requirements 3.2, 3.3, 3.5**

### Property 3: 截断至多 3、代表选取确定
`rank` 结果条数 ≤ 3；每条候选的代表字段取该形态 `occurred_at` 最大（并列 `id` 最大）的那一行。

**Validates: Requirements 2.3, 3.4**

### Property 4: 展示门槛
合格候选去重后 < 2 组时，服务层返回空列表；≥ 2 时返回 2 或 3 条。

**Validates: Requirements 1.1, 6.6, 7.1**

### Property 5: 只含支出/收入且窗口内
`rank` 只对 `type ∈ {expense, income}` 生效（仓库查询已过滤 transfer 与软删），且所有代表行 `occurred_at` 落在窗口区间内。

**Validates: Requirements 2.1, 2.4**

### Property 6: 备注规整并额
`note` 仅首尾空白差异视为同一形态；`amount` 数值相等（标度不同如 `35` 与 `35.00`）视为同一形态。

**Validates: Requirements 2.2**

---

## Testing Strategy

### 单元测试（`RecordSuggestionRanker`）
- 去重、排序三级键、并列决胜、截断 3、<2 空、note 规整、amount 标度并额、空输入。

### 服务层（`@DataJpaTest` / `@SpringBootTest`，H2 MODE=MySQL）
- `findSuggestionWindowRows` 只返回本账本、未删除、expense/income、窗口内的行；软删除行被 `@SQLRestriction` 排除；转账被排除；跨账本不串。
- `list`：<2 组返回空；分类已删时 `categoryName` 为 null；耗时（构造较多历史）在阈值内。

### 接口与安全（MockMvc）
- 无/过期令牌→`UNAUTHENTICATED`；`X-Ledger-Id` 越权→既有账本不可访问错误；只返回当前账本候选；不接受入参指定用户/账本。

### 兼容性回归
- 源码扫描：本 spec 代码路径无对 `transactions`/`categories`/`accounts` 的写语句、无新迁移、不引用 `transaction_templates`。
- 移除推荐接口后，交易/账本/分类/模板/预算/报表接口契约不变（既有测试全绿）。

### miniapp
- 聚合视图/未登录不请求；<2 或失败/超时不展示卡且不影响首页；点候选跳记账页且预填正确；分类/账户已删留空；`npm run test` 与 H5 构建通过。

---

## 需求覆盖矩阵

| 需求 | 设计落点 |
| --- | --- |
| 1 首页卡与展示 | `index.vue` 推荐卡 + 服务端 <2 返回空 |
| 2 来源与构成 | `findSuggestionWindowRows`（本账本/未删/收支/窗口）+ `ShapeKey` + 代表选取 |
| 3 排序去重数量 | `RecordSuggestionRanker`（三级全序、去重、截断 3） |
| 4 预填不入账 | `navigateTo` 带参 + `record.vue` `onLoad` 预填；不调写接口 |
| 5 账本隔离与刷新 | `CurrentLedger` + 前端 `onShow`/切换重拉 + 聚合视图不请求 |
| 6 查询接口与权限 | `RecordSuggestionController` + `requireLedgerId` + 只读 + 2s |
| 7 历史不足与降级 | 服务端空列表 + 前端 3000ms 超时静默隐藏 |
| 8 兼容边界 | 纯只读、无迁移、不改既有契约、不碰模板 |

---

## 已知取舍与残留风险

1. **无专用索引**：复用 `idx_tx_user_time`，按 `ledger_id + occurred_at` 过滤。窗口 30 天单账本行数有限；若某账本单月流水极多，查询成本上升——本 spec 不加迁移，留作后续 `idx_tx_ledger_time` 优化点（不影响正确性）。
2. **实时派生不缓存**：每次进首页/切账本都算一次。窗口有界 + 内存分组，2s 内可完成；不引入缓存以避免「已知过期候选」（需求 5.6）与失效复杂度。
3. **不记「忽略」状态**：本期不做「忽略某条当天不再出现」（那需要落表）。若后续要，可加一张 `suggestion_dismissals` 表，届时另起增量 spec，不影响当前纯只读边界。
4. **代表流水的账户可能已删**：候选仍带原 `accountId`，前端预填时若账户不在可选集则留空由用户选（需求 4.5），不影响候选生成。
