# Design Document

## Overview

自定义提醒（Custom Reminder）给「有余」补上第一条**主动**链路：在此之前，用户是否记账全靠自觉，
连续记账断了也只能事后发现。本设计让用户自己设定「每天 / 工作日 / 周末 + 某个时刻」的提醒，
到点由服务端定时任务经**微信一次性订阅消息**下发，文案随「今日是否已记账」二选一——
未记账「今天还没记账哦~」、已记账「今天已经完成啦~」。

三条贯穿全设计的既有约束（均已在需求文档裁定，本设计只落地、不重开）：

1. **投递只能用微信一次性订阅消息**。个人主体小程序（`wx58eeb3784f3d644f`）拿不到长期订阅，
   每发一条消耗一次用户授权额度。因此本设计围绕「额度」建模：上报授权累加、成功发送扣减、
   微信报额度不足则本地归零。
2. **「今日已记账」复用记账日历，不新开第二口径**。等价于
   `StreakJudgment.todayDone(user_growth.last_record_date, 判定日)`，只读 `user_growth.last_record_date`，
   绝不另查 `transactions`。这是与 streak/growth 同一事实源的刻意取舍。
3. **纯增量**。只新增三张表与一批只读查询，只读 `user_growth`、`users.wx_openid`；
   删掉这三张表其余功能原样成立。这是本项目**第一个定时任务**，因此要额外小心：调度故障、
   微信故障、额度耗尽一律就地吞掉，绝不回灌到记账、登录、注销、结算等主路径。

### 关键设计决策速览

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 触发机制 | `@EnableScheduling` + 每分钟 `@Scheduled` 扫描 | 项目首个定时任务；分钟粒度足够（提醒时间就是分钟粒度） |
| 幂等 | 发送记录唯一键 `(reminder_id, trigger_date)` | 构造性保证「同一提醒同一触发日至多一条 SENT」，不靠调度不重叠 |
| 追补 | 10 分钟有界窗口 | 容忍进程重启/调度抖动，又不发「几小时前」的陈旧提醒 |
| 「今日已记账」 | 触发当刻读 `user_growth.last_record_date` | 单一事实源；文案随发送那一刻的状态变化 |
| 额度 | 单独 `reminder_quota` 表，`user_id` 维度，`[0,50]` | 与提醒配置解耦；累积上限防无限增长；原子增减防丢更新 |
| 凭证 | 复用 `WeChatAccessTokenProvider` | 全项目唯一 access_token 网关，不建第二套 |
| 持久层 | Spring Data JPA（对齐 streak/growth） | 与既有分层一致，避免引入 MyBatis |
| 错误码 | `ApiException` 静态工厂新增 5 个域前缀码 | 对齐既有「每域自加码、错误体 `{code,message,field}`」约定 |

---

## Architecture

### 分层与包落点

对齐既有 streak-system 的垂直切片，全部落在 `com.damien.youyu` 下：

```
api/ReminderController.java            REST 入口（/api/reminders、/api/reminders/quota:grant），仅鉴权 + DTO 转发
service/ReminderService.java           CRUD + 上报授权 + 列表/概览（读写事务）
service/ReminderScheduler.java         @Scheduled 每分钟扫描（本项目首个定时任务）
service/ReminderDispatchService.java   单条提醒的一次发送尝试（选文案、查额度、发微信、写发送记录、扣额度）
service/ReminderMessageResolver.java   纯函数：由「今日已记账」映射到两条文案之一
domain/CustomReminder.java             @Entity custom_reminders
domain/ReminderQuota.java              @Entity reminder_quota
domain/ReminderSendLog.java            @Entity reminder_send_logs
domain/ReminderFrequency.java          枚举 DAILY/WEEKDAY/WEEKEND
domain/ReminderSendResult.java         枚举 SENT/SKIPPED_NO_QUOTA/SKIPPED_STALE/FAILED
repository/CustomReminderRepository.java
repository/ReminderQuotaRepository.java
repository/ReminderSendLogRepository.java
wechat/WeChatClient.java               新增 sendSubscribeMessage(...)（唯一新增到既有类的方法）
config/SchedulingConfig.java 或 YouyuApplication 上加 @EnableScheduling
```

miniapp 侧：

```
src/api/reminder.js                    对齐 api/streak.js，全部 noLedger:true
src/pages/reminder/reminder.vue        提醒设置页（新增/编辑/开关/删除/授权）
src/pages.json                         注册 pages/reminder/reminder
src/pages/me/me.vue                    「我的」页加入口
```

### 两条链路

**链路 A（用户操作，同步）**：miniapp → `ReminderController` → `ReminderService` → 三个 Repository。
纯 CRUD 与额度上报，与账本无关（`noLedger:true`），数据归属只认令牌用户 id。

**链路 B（定时触发，异步）**：`ReminderScheduler`（每分钟）→ 查「本分钟到点 + 追补窗口内」的启用提醒
→ 逐条交给 `ReminderDispatchService.dispatch(reminder, now)`。每条：
1. 幂等预检：`(reminder_id, trigger_date)` 是否已有发送记录 → 有则跳过。
2. 判定日今日已记账 → `ReminderMessageResolver` 选文案。
3. 取额度、取 `wx_openid`：任一不满足 → 写 `SKIPPED_NO_QUOTA`，不发。
4. 超出追补窗口 → 写 `SKIPPED_STALE`，不发。
5. 发微信 `subscribeMessage.send`：成功写 `SENT` 且额度 -1；失败写 `FAILED` 且额度不动。
6. **每条 dispatch 用独立 try/catch 包裹**，异常只记日志，绝不冒泡中断本轮扫描或其它提醒。

```
每分钟 tick (Asia/Shanghai)
        │
        ▼
ReminderScheduler.scan()
  查 enabled=1 且 remind_time ∈ [now-10min, now] 的提醒
        │  for each（隔离：单条异常不影响其余）
        ▼
ReminderDispatchService.dispatch(reminder, now)
  ① 幂等：send_logs 存在 (reminder_id, trigger_date)? ──有──▶ return（不重复）
  ② 今日已记账? → 选文案（今天已经完成啦~ / 今天还没记账哦~）
  ③ 超出追补窗口? ──是──▶ 写 SKIPPED_STALE
  ④ 额度=0 或 openid 空? ──是──▶ 写 SKIPPED_NO_QUOTA
  ⑤ WeChatClient.sendSubscribeMessage(openid, 文案)
        成功(errcode=0) ──▶ 写 SENT + 额度-1
        失败/异常       ──▶ 写 FAILED(记errcode) + 额度不动；43101 → 额度置0
```

### 时区

全局默认时区在 `YouyuApplication.main` 已设为 `Asia/Shanghai`。本设计**不**依赖 JVM 默认时区，
统一注入 `Clock`（与 `WeChatAccessTokenProvider`、`StreakJudgment` 调用方同一做法）：
`LocalDate today = LocalDate.now(clock)`、`LocalTime nowHm = LocalTime.now(clock).truncatedTo(MINUTES)`。
`clock` 由既有 `TimeConfig` 提供 `Asia/Shanghai` 固定口径，保证星期几判定与到点判定不随环境默认时区漂移。

---

## Components and Interfaces

### 1. REST 接口（`ReminderController`，`@RequestMapping("/api/reminders")`）

对齐 `StreakController`：注入 `CurrentUser` + `UserRepository`，每个方法第一步
`requireExistingUserId()`（令牌合法且用户仍存在，否则 `UNAUTHENTICATED`），随后转发服务层。
全部端点落在 `anyRequest().authenticated()` 下，且都是 `noLedger`（不要求 `X-Ledger-Id`）。

| 方法 | 路径 | 请求体 | 成功返回 | 对应需求 |
| --- | --- | --- | --- | --- |
| GET | `/api/reminders` | — | `ReminderListResponse{ reminders:[...], remainingQuota }` | 5.7、7.1、7.2 |
| POST | `/api/reminders` | `{frequency, remindTime, enabled?}` | `ReminderItem{reminderId, frequency, remindTime, enabled}` | 1 |
| PUT | `/api/reminders/{reminderId}` | `{frequency?, remindTime?, enabled?}` | `ReminderItem` | 7.3、7.4、7.8 |
| DELETE | `/api/reminders/{reminderId}` | — | 204 无体 | 7.6 |
| POST | `/api/reminders/quota:grant` | `{grantedCount}` | `{remainingQuota}` | 5.1、5.2 |

DTO 全部用 `record`（对齐 streak 的 `StreakOverviewResponse` 等）。`ReminderItem` 字段恰为 4 项：
`reminderId`(Long)、`frequency`(String)、`remindTime`(String `HH:mm`)、`enabled`(boolean)。

**校验入口在服务层，不交给框架类型转换**：`frequency`、`remindTime`、`grantedCount` 以原文接收
（`remindTime`、`frequency` 为 String，`grantedCount` 以 String 或 Integer 接收后手工解析），
避免 `@Valid`/类型绑定把「取值非法」提前变成 `FIELD_REQUIRED`，从而能精确返回本域的五个错误码。
这与 `StreakController` 用原文 String 接分页参数、由服务层解析是同一取舍。

### 2. `ReminderService`（CRUD + 授权上报 + 列表）

```java
@Service
public class ReminderService {
    // 依赖：CustomReminderRepository, ReminderQuotaRepository, Clock
    // 每用户提醒上限
    static final int MAX_REMINDERS_PER_USER = 10;
    static final int QUOTA_CAP = 50;

    @Transactional
    ReminderItem create(Long userId, String frequency, String remindTime, Boolean enabled);
    @Transactional
    ReminderItem update(Long userId, Long reminderId, String frequency, String remindTime, Boolean enabled);
    @Transactional
    void delete(Long userId, Long reminderId);
    @Transactional(readOnly = true)
    ReminderListResponse list(Long userId);
    @Transactional
    int grantQuota(Long userId, String grantedCountRaw);
}
```

**校验顺序（需求 1.9 优先级：FREQUENCY > TIME > DUPLICATE > LIMIT）**，`create` 内部严格按此序短路：

```java
ReminderFrequency freq = parseFrequency(frequency);   // 非法 → REMINDER_FREQUENCY_INVALID
LocalTime time = parseHhmm(remindTime);               // 非法 → REMINDER_TIME_INVALID
if (repo.existsByUserIdAndFrequencyAndRemindTime(userId, freq, time))
    throw ApiException.reminderDuplicate();            // REMINDER_DUPLICATE
if (repo.countByUserId(userId) >= MAX_REMINDERS_PER_USER)
    throw ApiException.reminderLimitExceeded();        // REMINDER_LIMIT_EXCEEDED
```

- `parseHhmm`：正则 `^([01]\d|2[0-3]):[0-5]\d$`（零填充两位时:两位分，不含秒），非法即 `REMINDER_TIME_INVALID`。
- `frequency` 区分大小写精确匹配枚举名，非法即 `REMINDER_FREQUENCY_INVALID`。
- 唯一键 `uk_custom_reminders_user_freq_time` 在 DB 侧兜底并发下的重复插入（捕 `DataIntegrityViolationException` → `REMINDER_DUPLICATE`），应用层 `exists` 只是先行友好校验。
- `update`：先 `findByIdAndUserId`，不存在或不属于本人 → **统一 `NOT_FOUND`**（不泄漏他人提醒是否存在，需求 7.5、8.8）；只更新提交字段，未提交字段保持原值；校验失败保持整行不变（需求 7.3、7.4）；改动 `frequency/remindTime` 会撞他人本人另一条 → `REMINDER_DUPLICATE`（需求 7.8）。
- `grantQuota`：`grantedCount` 解析为整数且 ∈[1,5]，否则 `REMINDER_GRANT_INVALID`；累加后 `min(结果, 50)`；用**原子 UPSERT**（见 §Data Models 的 `ReminderQuotaRepository.addCapped`）避免并发丢更新。

### 3. `ReminderScheduler`（每分钟扫描，本项目首个定时任务）

```java
@Component
public class ReminderScheduler {
    private final CustomReminderRepository reminderRepository;
    private final ReminderDispatchService dispatchService;
    private final Clock clock;

    /** 每分钟第 5 秒触发，错开整分峰值；分钟粒度足够（提醒时间就是分钟粒度）。 */
    @Scheduled(cron = "5 * * * * *", zone = "Asia/Shanghai")
    public void scan() {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        LocalTime windowStart = now.minusMinutes(10);   // 追补窗口下界
        List<CustomReminder> due = reminderRepository
            .findDue(today.getDayOfWeek(), windowStart, now);   // enabled=1 且频率命中今天 且 remind_time∈[windowStart, now]
        for (CustomReminder r : due) {
            try {
                dispatchService.dispatch(r, today, now);
            } catch (RuntimeException ex) {
                // 需求 6.7：单条故障就地隔离，记告警日志，继续下一条，绝不中断整轮。
                log.warn("reminder dispatch failed, reminderId={}, userId={}", r.getId(), r.getUserId(), ex);
            }
        }
    }
}
```

- **追补窗口的边界**：`remind_time ∈ [now-10min, now]` 闭区间即需求 3.3/3.7。跨自然日边界（如 00:03 扫描、追补 23:55）在本设计中**不追补跨天**：`windowStart` 若因 `minusMinutes` 回卷到前一天则夹到 `00:00`，超窗口的昨日提醒由需求 3.4 视作 `SKIPPED_STALE`——但注意 `SKIPPED_STALE` 记录只在「命中今日为触发日且已过窗口」时写；纯粹的「昨天该发没发」不补记，避免每次重启回灌历史。（见 §已知取舍）
- `DayOfWeek` → 频率命中：`DAILY` 恒真；`WEEKDAY` = MON..FRI；`WEEKEND` = SAT/SUN。判定放在 SQL（`findDue` 按频率集合过滤）还是内存均可，本设计放 SQL 以走 `idx_custom_reminders_enabled_time` 缩小候选集，星期几→频率集合的映射在 Java 侧算好后作为 `IN` 参数传入。

### 4. `ReminderDispatchService`（单条一次发送尝试）

```java
@Service
public class ReminderDispatchService {
    @Transactional
    public void dispatch(CustomReminder r, LocalDate today, LocalTime now) {
        // ① 幂等预检
        if (sendLogRepository.existsByReminderIdAndTriggerDate(r.getId(), today)) return;

        // ② 选文案（触发当刻判定，只读 user_growth.last_record_date）
        LocalDate lastRecord = userGrowthRepository.findLastRecordDate(r.getUserId()).orElse(null);
        boolean done = StreakJudgment.todayDone(lastRecord, today);
        String message = ReminderMessageResolver.pick(done);   // 两条之一

        // ③ 超窗口
        if (r.getRemindTime().isBefore(now.minusMinutes(10))) {
            writeLog(r, today, SKIPPED_STALE, done, null); return;
        }
        // ④ 额度 / openid
        int remaining = quotaRepository.findRemaining(r.getUserId()).orElse(0);
        String openid = userRepository.findWxOpenid(r.getUserId()).orElse(null);
        if (remaining <= 0 || openid == null || openid.isBlank()) {
            writeLog(r, today, SKIPPED_NO_QUOTA, done, null); return;
        }
        // ⑤ 发送
        try {
            String token = accessTokenProvider.getToken();
            int errcode = weChatClient.sendSubscribeMessage(token, openid, message);
            if (errcode == 0) {
                writeLog(r, today, SENT, done, 0);
                quotaRepository.decrementFloorZero(r.getUserId());   // -1 且不小于 0
            } else {
                writeLog(r, today, FAILED, done, errcode);
                if (errcode == 43101) quotaRepository.zero(r.getUserId());  // 用户拒收/无额度 → 归零对齐
            }
        } catch (RuntimeException ex) {
            writeLog(r, today, FAILED, done, null);
            log.warn("subscribeMessage.send failed, reminderId={}, userId={}", r.getId(), r.getUserId());
        }
    }
}
```

- `writeLog` 插入 `reminder_send_logs`；唯一键 `(reminder_id, trigger_date)` 冲突（并发触发）→ 捕 `DataIntegrityViolationException` 后**静默放弃本次**（需求 6.6，不重复发、不报错）。
- **发送与写记录/扣额度的一致性**：`dispatch` 标 `@Transactional`。但「已调微信成功」是不可回滚的外部副作用，故顺序上**先写 SENT 记录并扣额度、后不再有可能抛错的操作**；若写记录本身唯一键冲突（说明另一线程已发），本次视为重复放弃。发送前的幂等预检 + 唯一键是双保险。
- 判定失败兜底（需求 4.8）：`findLastRecordDate` 抛错或返回不可解析值时，`done=false`、选「今天还没记账哦~」，不写 `user_growth`。
- **只读既有表**：`userGrowthRepository.findLastRecordDate`、`userRepository.findWxOpenid` 均为投影只读查询，不 `save`。

### 5. `ReminderMessageResolver`（纯函数，对齐 `StreakJudgment` 的静态工具风格）

```java
public final class ReminderMessageResolver {
    public static final String MSG_DONE = "今天已经完成啦~";
    public static final String MSG_NOT_YET = "今天还没记账哦~";
    private ReminderMessageResolver() {}
    public static String pick(boolean todayRecorded) {
        return todayRecorded ? MSG_DONE : MSG_NOT_YET;
    }
}
```

取值集合恰为两条、互斥、逐字符相等（需求 4.1、4.4）。

### 6. `WeChatClient.sendSubscribeMessage`（唯一新增到既有类的方法）

对齐既有 `jscode2session` / `fetchUnlimitedQrCode`：独立 `RestClient`、独立超时（建议 3000ms）、
`POST https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=...`。

```java
/**
 * 下发一次性订阅消息。返回微信 errcode（0 表示成功）。
 * 40001（凭证无效）时强制刷新凭证重试一次（对齐既有 access_token 失效重试约定）。
 */
public int sendSubscribeMessage(String accessToken, String openid, String message) { ... }
```

- 请求体：`{ touser: openid, template_id: <app.wechat.subscribe.reminder-template-id>, data: {...} }`。模板 id 与字段名（如 `thing1`/`time2`）走配置 `app.wechat.subscribe.*`，因为提醒模板由运营在微信后台申请、字段随模板而定，不写死在代码里。
- `message` 映射到模板的文案字段；模板字段 20 字以内，两条文案均满足。
- **凭证获取只经 `WeChatAccessTokenProvider`**（需求 11.3）：`dispatch` 里 `getToken()`；若 `sendSubscribeMessage` 内部识别 `errcode=40001`，调 `accessTokenProvider.forceRefresh(token)` 重试一次。

---

## Data Models

### 迁移 `V35__custom_reminder.sql`

当前最大版本号为 `V34__streak.sql`，`V30` 由 user-feedback-system 预占；本 spec 取 `V35`。
三张表均无外键（与 `user_growth`/`growth_events`/`streak_segments` 同一取舍：注销时由
`AccountDeletionService` 在同一事务显式删除）。注释风格对齐 V34：表首 `-- ===` 说明块 + 每列内联 `COMMENT`。

```sql
-- ============================================================================
-- 有余(youyu) 自定义提醒(Custom Reminder)：三表
--  custom_reminders    提醒配置（频率+时间+开关）
--  reminder_quota      每用户一次性订阅剩余额度（[0,50]）
--  reminder_send_logs  每次发送尝试的落表结果（(reminder_id,trigger_date)唯一 → 幂等）
-- 均无外键：注销时由 AccountDeletionService 在同一事务内显式删除。
-- 不接入法定节假日/调休：工作日恒为周一至周五、周末恒为周六与周日（需求 2.4）。
-- ============================================================================

CREATE TABLE custom_reminders (
    reminder_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '用户id,无外键(注销时由服务层显式删除)',
    frequency   VARCHAR(16)  NOT NULL COMMENT '频率:DAILY/WEEKDAY/WEEKEND(区分大小写)',
    remind_time TIME         NOT NULL COMMENT '每日触发时刻(分钟粒度,Asia/Shanghai)',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用:1启用0停用',
    created_at  DATETIME     NOT NULL COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (reminder_id),
    UNIQUE KEY uk_custom_reminders_user_freq_time (user_id, frequency, remind_time),
    KEY idx_custom_reminders_enabled_time (enabled, remind_time),
    CONSTRAINT ck_custom_reminders_frequency CHECK (frequency IN ('DAILY','WEEKDAY','WEEKEND'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '自定义提醒配置((user_id,frequency,remind_time)唯一,同一频率同一时间不重复)';

CREATE TABLE reminder_quota (
    user_id    BIGINT   NOT NULL COMMENT '用户id(主键,一人一行),无外键',
    remaining  INT      NOT NULL DEFAULT 0 COMMENT '剩余一次性订阅额度,[0,50]',
    created_at DATETIME NOT NULL COMMENT '首次授权上报时间',
    updated_at DATETIME NOT NULL COMMENT '最后一次增减时间',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_reminder_quota_remaining CHECK (remaining >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '每用户订阅剩余额度(授权累加,成功发送扣减,43101归零)';

CREATE TABLE reminder_send_logs (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    reminder_id     BIGINT      NOT NULL COMMENT '来源提醒id,无外键',
    user_id         BIGINT      NOT NULL COMMENT '用户id,无外键',
    trigger_date    DATE        NOT NULL COMMENT '触发日(Asia/Shanghai自然日)',
    result          VARCHAR(24) NOT NULL COMMENT '结果:SENT/SKIPPED_NO_QUOTA/SKIPPED_STALE/FAILED',
    message_variant VARCHAR(16) NOT NULL COMMENT '文案变体:DONE/NOT_YET',
    wx_errcode      INT         NULL     COMMENT '微信errcode(SENT为0,SKIPPED为空,FAILED为微信码或空)',
    created_at      DATETIME    NOT NULL COMMENT '发送尝试时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reminder_send_logs_reminder_date (reminder_id, trigger_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '提醒发送记录((reminder_id,trigger_date)唯一,构造性保证同日至多一条→幂等)';
```

约束点名与需求 9 逐条对齐：`uk_custom_reminders_user_freq_time`(9.3)、`idx_custom_reminders_enabled_time`(9.4)、
`ck_custom_reminders_frequency`(9.5)、`ck_reminder_quota_remaining`(9.6)、`uk_reminder_send_logs_reminder_date`(9.8)。
不使用窗口函数/`CONVERT_TZ`/存储过程/触发器，MySQL 与 H2 `MODE=MySQL` 同构（9.12）。

### 实体（Spring Data JPA，对齐 `StreakSegment`）

```java
@Entity @Table(name = "custom_reminders")
class CustomReminder { @Id @GeneratedValue(IDENTITY) Long id;  // 映射 reminder_id
    Long userId; @Enumerated(STRING) ReminderFrequency frequency; LocalTime remindTime;
    boolean enabled; LocalDateTime createdAt, updatedAt; }

@Entity @Table(name = "reminder_quota")
class ReminderQuota { @Id Long userId; int remaining; LocalDateTime createdAt, updatedAt; }

@Entity @Table(name = "reminder_send_logs")
class ReminderSendLog { @Id @GeneratedValue(IDENTITY) Long id;
    Long reminderId, userId; LocalDate triggerDate;
    @Enumerated(STRING) ReminderSendResult result; String messageVariant; Integer wxErrcode; LocalDateTime createdAt; }
```

### 仓库关键方法

```java
interface CustomReminderRepository extends JpaRepository<CustomReminder, Long> {
    boolean existsByUserIdAndFrequencyAndRemindTime(Long uid, ReminderFrequency f, LocalTime t);
    int countByUserId(Long uid);
    List<CustomReminder> findByUserIdOrderByCreatedAtAsc(Long uid);
    Optional<CustomReminder> findByIdAndUserId(Long id, Long uid);

    @Query("select r from CustomReminder r where r.enabled = true and r.frequency in :freqs " +
           "and r.remindTime between :start and :end")
    List<CustomReminder> findDue(@Param("freqs") Collection<ReminderFrequency> freqs,
                                 @Param("start") LocalTime start, @Param("end") LocalTime end);
    @Modifying int deleteByUserId(Long uid);   // 注销用
}

interface ReminderQuotaRepository extends JpaRepository<ReminderQuota, Long> {
    @Query("select q.remaining from ReminderQuota q where q.userId = :uid")
    Optional<Integer> findRemaining(@Param("uid") Long uid);

    /** 原子上限累加：不存在则插入 min(delta,50)，存在则 remaining=min(remaining+delta,50)。 */
    @Modifying @Query(nativeQuery = true, value =
        "INSERT INTO reminder_quota(user_id,remaining,created_at,updated_at) VALUES(:uid,LEAST(:delta,50),:now,:now) " +
        "ON DUPLICATE KEY UPDATE remaining=LEAST(remaining+:delta,50), updated_at=:now")
    void addCapped(@Param("uid") Long uid, @Param("delta") int delta, @Param("now") LocalDateTime now);

    @Modifying @Query("update ReminderQuota q set q.remaining = q.remaining - 1, q.updatedAt = :now " +
                      "where q.userId = :uid and q.remaining > 0")
    int decrementFloorZero(@Param("uid") Long uid, @Param("now") LocalDateTime now);

    @Modifying @Query("update ReminderQuota q set q.remaining = 0, q.updatedAt = :now where q.userId = :uid")
    int zero(@Param("uid") Long uid, @Param("now") LocalDateTime now);

    @Modifying int deleteByUserId(Long uid);   // 注销用
}

interface ReminderSendLogRepository extends JpaRepository<ReminderSendLog, Long> {
    boolean existsByReminderIdAndTriggerDate(Long reminderId, LocalDate triggerDate);
    @Modifying int deleteByUserId(Long uid);   // 注销用
}
```

> `ON DUPLICATE KEY UPDATE` 在 H2 `MODE=MySQL` 下受支持；若测试环境不便，退化为「先 `findRemaining` 再 `save`」并在方法上加乐观锁 `@Version` 或 `SELECT ... FOR UPDATE`。原子性是需求 5.8 的硬要求，实现二选一但必须防丢更新。

### 注销级联（`AccountDeletionService`）

在既有 `streakSegmentRepository.deleteByUserId(userId)` 之后、`userRepository.delete(user)` 之前，
于**同一事务**追加三行（构造函数注入三个新仓库）：

```java
// N) 自定义提醒三表（无外键，注销时显式删除）。
reminderSendLogRepository.deleteByUserId(userId);
customReminderRepository.deleteByUserId(userId);
reminderQuotaRepository.deleteByUserId(userId);
```

任一删除失败 → 整个注销事务回滚，不产生部分删除（需求 9.11、9.12）。注销接口的响应字段集、
状态码、错误码不变（需求 11 兼容边界）。

### 清库脚本 `deploy/reset-db.sql`

在 `TRUNCATE TABLE streak_segments;` 之后、`TRUNCATE TABLE users;` 之前插入：

```sql
-- 自定义提醒三表：同样无外键（注销时由应用显式删除），清空不依赖 FOREIGN_KEY_CHECKS 取值
TRUNCATE TABLE reminder_send_logs;
TRUNCATE TABLE custom_reminders;
TRUNCATE TABLE reminder_quota;
```

保留表结构与 `flyway_schema_history`（需求 9.14）。

### 迁移 `V35__custom_reminder.sql` 验证结论（任务 1.4）

> **实测状态（任务 1.4 迁移验证清单，回写日期 2026-08-05）：真实 MySQL 结构/约束核对本轮尚未执行、
> 待人工在 MySQL ≥ 8.0.16 上补测；自动化可核对项已通过。刻意不把未产出的结果写成已通过。**
>
> 本 spec 沿用 streak `V34` 的分工：测试套件**不执行 Flyway**（H2 表由 Hibernate 依实体生成，
> 见 `MigrationDirectoryTest` 与 `src/test/resources/application.yml` 的 `flyway.enabled=false`），
> `information_schema` 元数据与 MySQL 侧 CHECK/唯一键行为归**真实 MySQL 人工清单**（本节）。
>
> **① 自动化环境已核对（本轮已执行，绿）**：
> - `MigrationDirectoryTest` 3 项全绿——`V35__custom_reminder.sql` 存在、版本号 35 严格大于目录内全部既有版本
>   （未占用 user-feedback-system 预占的缺号 `V30`）、目录内版本号无重复、历史迁移文件 sha-256 未变动且
>   `V35` 已纳入 `migration-baseline.sha256`。
> - 迁移脚本文本与本设计「Data Models」逐字一致：三表列集/类型/可空/缺省/中文注释、两个 `DATETIME` 均未声明
>   `ON UPDATE`、约束点名 `uk_custom_reminders_user_freq_time` / `idx_custom_reminders_enabled_time` /
>   `ck_custom_reminders_frequency` / `ck_reminder_quota_remaining` / `uk_reminder_send_logs_reminder_date`、
>   `ENGINE=InnoDB` + `utf8mb4` + `utf8mb4_unicode_ci`、无任何指向 `users(id)` 的外键、未用窗口函数 /
>   `CONVERT_TZ` / 存储过程 / 触发器 / `IF NOT EXISTS`。
>
> **② 真实测试库 MySQL 结构/约束验证：本轮环境无法执行，DEFERRED 至人工补测。**
> 本执行环境**无 `mysql` 客户端**，且不得对共享业务库 `47.120.65.57/youyu` 施加 schema 变更（Flyway 会直接改该库），
> 亦无法像 `V34` 那样以 `youyu` 账号 `CREATE DATABASE` 建一次性探针库 → 跑 `V35` → 核对 → `DROP DATABASE`。
> 端口 `47.120.65.57:3306` 经 TCP 探测**可达**，测试服务器 MySQL 版本此前在 streak `V34` 实测块记录为 **8.0.46**
> （≥ 8.0.16，故 CHECK 真实生效）；**发布前须在该库上按下列清单逐项人工核对**，核对方式对齐 `V34`
> （一次性探针库，完全不触碰共享业务库）：
> - `information_schema.columns`：`custom_reminders` 恰 **7 列**、`reminder_quota` 恰 **4 列**、
>   `reminder_send_logs` 恰 **8 列**；逐列类型/可空性/缺省/中文注释非空均符；`custom_reminders.reminder_id`
>   与 `reminder_send_logs.id` 的 `EXTRA` 含 `auto_increment` 而其余列不含；所有 `DATETIME` 列 `EXTRA`
>   均不含 `on update`。
> - `information_schema.statistics`：`custom_reminders` 恰三组索引 `PRIMARY` /
>   `uk_custom_reminders_user_freq_time`（`NON_UNIQUE=0`，列序 `(user_id, frequency, remind_time)`）/
>   `idx_custom_reminders_enabled_time`（`NON_UNIQUE=1`，列序 `(enabled, remind_time)`）；
>   `reminder_send_logs` 有 `PRIMARY` 与 `uk_reminder_send_logs_reminder_date`（`NON_UNIQUE=0`，
>   列序 `(reminder_id, trigger_date)`）；全部索引列 `COLLATION='A'`（升序）。
> - `check_constraints`（join `table_constraints`）：`ck_custom_reminders_frequency` 落库为
>   `frequency in ('DAILY','WEEKDAY','WEEKEND')`、`ck_reminder_quota_remaining` 落库为 `(`remaining` >= 0)`。
> - **CHECK 实测**：`frequency='X'`、`frequency='daily'`（小写）各被 `ck_custom_reminders_frequency` 以
>   `ERROR 3819` 拒绝；`remaining=-1` 被 `ck_reminder_quota_remaining` 拒绝；被拒后各表行数不变。
> - **唯一键实测**：同 `(user_id,frequency,remind_time)` 二次直插以 `ERROR 1062` 被拒；
>   同 `(reminder_id,trigger_date)` 二次直插以 `ERROR 1062` 被拒（行数不变）。
> - `referential_constraints`：三表外键数均为 **0**。
> - `tables`：三表 `ENGINE=InnoDB`、`TABLE_COLLATION=utf8mb4_unicode_ci`、表注释非空含中文。
> - 迁移后三表行数为 **0**；迁移前后 `user_growth` / `users` 若干行快照逐列不变。
> - **`deploy/reset-db.sql` 语义**：`TRUNCATE TABLE reminder_send_logs/custom_reminders/reminder_quota` 后
>   三表行数 0、表仍存在、列定义不变、`flyway_schema_history` 保留。
>
> **③ 本地启动连库的运行时验证（Flyway 应用/幂等/`ddl-auto=validate`）：本轮环境无法执行，DEFERRED。**
> 需经 `bash deploy/dev-remote-db.sh` 以生产默认 profile（`application.yml` → `ddl-auto: validate`）启动应用连
> 迁移完成的库，人工核对：连启两次后 `flyway_schema_history` 中 `V35` 记录数为 **1**（幂等）；
> `ddl-auto=validate` 启动成功、无针对三表的 schema 校验异常（需求 9.13、9.15）。
> 本执行环境无 `mysql` 客户端且不得改共享业务库，故此项与 ② 一并留待发布前人工补测。
> **前置确认**：生产 MySQL 版本须 ≥ 8.0.16，否则 CHECK 被解析后忽略、`ck_custom_reminders_frequency` 与
> `ck_reminder_quota_remaining` 形同虚设（服务层 `parseFrequency` 与 `addCapped` 的应用层校验仍是第一道闸）。

---

## miniapp 设计

### 文件清单

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `src/api/reminder.js` | 新增 | 5 个函数，全部 `noLedger:true`，对齐 `api/streak.js` |
| `src/pages/reminder/reminder.vue` | 新增 | 提醒设置页 |
| `src/pages.json` | 改 | 注册 `pages/reminder/reminder`，标题「记账提醒」 |
| `src/pages/me/me.vue` | 改 | 「我的」页加一行入口 → `navigateTo('/pages/reminder/reminder')` |

### `src/api/reminder.js`

```js
import { http } from '../utils/request'
// 提醒与账本无关：全部 noLedger:true，不发送 X-Ledger-Id。
export function fetchReminders()            { return http.get('/reminders', { noLedger: true }) }
export function createReminder(body)        { return http.post('/reminders', body, { noLedger: true }) }
export function updateReminder(id, body)    { return http.put(`/reminders/${id}`, body, { noLedger: true }) }
export function deleteReminder(id)          { return http.del(`/reminders/${id}`, { noLedger: true }) }
export function grantReminderQuota(count)   { return http.post('/reminders/quota:grant', { grantedCount: count }, { noLedger: true }) }
```

### 页面行为（`reminder.vue`）

- **进入**：未登录（无 token）→ 不发任何请求、只显示登录入口（需求 10.10）；已登录 → `onShow` 调 `fetchReminders()`，返回前显示占位骨架、不渲染任何提醒项，返回后渲染列表 + 剩余订阅次数（需求 10.2）。
- **新增/编辑**：频率三选一（每天/工作日/周末）+ `<picker mode="time">` 选时:分；提交前本地校验频率已选、小时 0–23、分钟 0–59；不合法则就地提示、不发请求、保留已填内容（需求 10.3、10.4）。
- **开关/改时间/删除**：调对应接口，成功后就地更新列表（需求 10.8）。
- **订阅授权**：点「开启提醒」→ `wx.requestSubscribeMessage({ tmplIds:[模板id] })`；结果为「允许」→ 调 `grantReminderQuota(n)` 上报；拒绝/失败 → 不上报、提示未授权 + 再次授权入口、页面不进错误态（需求 10.5、10.6）。
- **额度为 0**：显著提示「授权后才能继续收到提醒」+ 再次授权入口（需求 10.7）。
- **错误/超时**：任一接口报错或 3000ms 无响应 → 失败提示 + 重试入口，自动重试 0 次，其余已加载内容不变（需求 10.9）。
- **展示**：复用 `utils/format` 呈现时间（`Asia/Shanghai`），不展示任何金额/账本名/邮箱/邀请码（需求 10.11）。`remindTime` 后端已是 `HH:mm`，直接展示。

---

## Error Handling

新增 `ApiException` 静态工厂（对齐既有「每域自加码」约定，错误码为工厂内字符串字面量），均 400：

| 工厂方法 | code | field | 触发 |
| --- | --- | --- | --- |
| `reminderFrequencyInvalid()` | `REMINDER_FREQUENCY_INVALID` | `frequency` | 频率缺失/非枚举 |
| `reminderTimeInvalid()` | `REMINDER_TIME_INVALID` | `remindTime` | 时间格式/范围非法 |
| `reminderDuplicate()` | `REMINDER_DUPLICATE` | `frequency` | 同用户同频率同时间已存在 |
| `reminderLimitExceeded()` | `REMINDER_LIMIT_EXCEEDED` | null | 已达 10 条 |
| `reminderGrantInvalid()` | `REMINDER_GRANT_INVALID` | `grantedCount` | 授权次数非 [1,5] 整数 |

复用既有 `UNAUTHENTICATED`（令牌无效/过期/用户已注销，优先于一切字段校验）与 `NOT_FOUND`
（提醒不存在或不属于本人，两种情形同一响应）。错误体恒为 `{code, message, field}`，
`field` 无关时取空（`@JsonInclude(NON_NULL)` 自动省略），`message` ≤100 中文字符、不含 id/邮箱/令牌。

| 情形 | 处理 | 对外表现 |
| --- | --- | --- |
| 令牌缺失/非法/过期/用户已注销 | Controller `requireExistingUserId` 抛 | 401 `UNAUTHENTICATED` |
| 多个校验同时失败 | 服务层按 FREQUENCY>TIME>DUPLICATE>LIMIT 短路 | 单一最高优先级码（需求 1.9） |
| 并发插入撞唯一键 | 捕 `DataIntegrityViolationException` | `REMINDER_DUPLICATE` |
| 访问他人/不存在提醒 | `findByIdAndUserId` 空 | 统一 `NOT_FOUND`（不区分） |
| 微信发送非零/异常/超时 | 写 `FAILED`，额度不动，记告警日志 | 无（异步，不影响任何同步接口） |
| 微信 43101（拒收/无额度） | 写 `FAILED` + 额度归零 | 无 |
| 调度扫描单条异常 | scan 内 try/catch 隔离 | 无（其余提醒照发） |
| 读 `user_growth` 失败 | `done=false` 兜底选「今天还没记账哦~」 | 仍发提醒，不写 user_growth |

**故障隔离总纲（需求 6、11）**：链路 B 的任何异常都不得进入链路 A 或记账/登录/注销/结算路径。
`ReminderScheduler.scan` 是异步入口，本身不被任何同步请求调用；`dispatch` 的异常在 scan 内被吞。

---

## Correctness Properties

属性测试用 jqwik（服务端，仓库根已有 `.jqwik-database`）。核心不变式：

### Property 1: 发送幂等
对同一 `(reminder_id, trigger_date)` 任意次数、任意交错的 `dispatch`，`SENT` 记录至多 1 条、微信 `subscribeMessage.send` 至多被调用 1 次。

**Validates: Requirements 3.5, 6.5, 6.6**

### Property 2: 额度守恒且有界
任意「授权上报 / 成功发送扣减」序列执行后，`remaining` 恒 ∈ [0,50]，且等于 `clamp(Σ授权 − ΣSENT, 0, 50)`，并发下不丢更新。

**Validates: Requirements 5.3, 5.5, 5.8**

### Property 3: 文案二选一
`ReminderMessageResolver.pick` 的值域恰为 `{「今天已经完成啦~」,「今天还没记账哦~」}`，且 `pick(true) ≠ pick(false)`，两者逐字符固定。

**Validates: Requirements 4.1, 4.4**

### Property 4: 频率↔星期几且时区稳定
对任意日期，`DAILY` 恒为触发日、`WEEKDAY ⟺ MON..FRI`、`WEEKEND ⟺ SAT/SUN`；把 JVM 默认时区改为任一其它时区，同一提醒对同一自然日的判定结果不变。

**Validates: Requirements 2.1, 2.2, 2.3, 2.7**

### Property 5: 追补窗口单调
`remind_time` 落在 `[now−10min, now]` 且当日无记录 → 补发一次；早于 `now−10min` → `SKIPPED_STALE` 不发；晚于 `now` → 本轮不处理。

**Validates: Requirements 3.3, 3.4, 3.7**

### Property 6: 校验优先级确定
对同时命中多条拒绝条件的创建输入，返回码恒为 `FREQUENCY > TIME > DUPLICATE > LIMIT` 中优先级最高者，且表数据不变。

**Validates: Requirements 1.9**

### Property 7: 纯增量只读
任意 `dispatch` 或 CRUD 前后，`user_growth` 六项与 `users` 各列逐行相等。

**Validates: Requirements 6.8, 11.1, 11.2**

---

## Testing Strategy

### 单元测试
- `ReminderMessageResolver`：两条文案、互斥、逐字符相等。
- `parseHhmm` / `parseFrequency`：合法/非法边界（`24:00`、`8:00`、`08:60`、空、小写 `daily`）。
- 频率→星期几映射：7 天全覆盖 × 三频率。
- `StreakJudgment.todayDone` 复用既有测试，不重测。

### 服务层（`@DataJpaTest` / `@SpringBootTest` + H2 MODE=MySQL）
- 唯一键/CHECK 约束落地（重复插入、`frequency='X'`、`remaining=-1` 应被拒）。
- `create` 校验优先级、10 条上限、`update` 部分更新与 `NOT_FOUND` 归属、`grantQuota` 上限与非法值。
- `addCapped`/`decrementFloorZero`/`zero` 的边界与并发（多线程累加不丢）。
- `findDue` 的窗口边界（含 `now` 边界、`now-10` 边界、停用不入选）。

### 调度与发送（Mock `WeChatClient`）
- 幂等：重复 `dispatch` 只发一次。
- 各 `SendResult` 分支：无额度、无 openid、超窗口、成功扣减、失败不扣、43101 归零。
- 单条抛异常不中断整轮 scan。

### 迁移
- `V35` 在 H2 MODE=MySQL 与（若 CI 具备）MySQL 均成功；`ddl-auto=validate` 启动不报 schema 异常。
- `AccountDeletionService` 删除三表；`reset-db.sql` 执行后三表行数为 0、结构与 flyway 历史保留。

### miniapp
- 未登录不发请求；空列表渲染；本地校验拦截非法提交；授权拒绝不上报且不进错误态；额度 0 提示；3000ms 超时不重试。

---

## 需求覆盖矩阵

| 需求 | 设计落点 |
| --- | --- |
| 1 创建 | `ReminderController.create` + `ReminderService.create`（校验优先级 1.9） |
| 2 频率/触发日 | `findDue` 频率集合过滤 + `Clock(Asia/Shanghai)`，不接节假日 |
| 3 触发/调度 | `ReminderScheduler`（每分钟 cron）+ 10 分钟追补窗口 + 唯一键幂等 |
| 4 文案自适应 | `ReminderMessageResolver.pick(StreakJudgment.todayDone(...))` + 4.8 读失败兜底 |
| 5 额度 | `reminder_quota` + `addCapped`/`decrementFloorZero`/`zero`（原子、[0,50]） |
| 6 发送/幂等/隔离 | `ReminderDispatchService` + scan try/catch + 唯一键 + 只读六表 |
| 7 查询/改/删 | `ReminderService.list/update/delete`，`NOT_FOUND` 归属统一 |
| 8 权限边界 | `requireExistingUserId` + `findByIdAndUserId` + `noLedger` + 错误体 `{code,message,field}` |
| 9 数据模型/迁移 | `V35__custom_reminder.sql` + 注销级联 + `reset-db.sql` |
| 10 提醒设置页 | `pages/reminder/reminder.vue` + `api/reminder.js` + `me.vue` 入口 |
| 11 兼容边界 | 纯增量、只读 `user_growth`/`users.wx_openid`、复用凭证网关、5 新码 |

---

## 已知取舍与残留风险

1. **不追补跨自然日**：00:00–00:10 扫描时，前一日 23:5x 的提醒不补发（`windowStart` 夹到当日 `00:00`）。理由：追补是为容忍进程重启/抖动，不是为补昨天的漏发；跨天追补会引出「昨天是否为触发日」「昨天的已记账状态」等二义。可接受。
2. **单实例调度假设**：`@Scheduled` 在多实例部署下会多次触发，但发送记录唯一键 `(reminder_id, trigger_date)` 保证仍只发一条。若未来多实例，无需加分布式锁即安全，仅有重复扫描的空转成本。
3. **额度累积上限 50**：纯防御性上限，正常用户远达不到；微信侧真实额度以 43101 归零对齐为准。
4. **模板字段依赖运营配置**：`app.wechat.subscribe.reminder-template-id` 与字段映射需在微信后台申请提醒模板后填入配置；模板未配置时 `dispatch` 走 `FAILED` 分支、不影响任何主路径。

---

## 发布前手工验收清单（任务 12.2）

> **状态（回写日期 2026-08-05）：真机端到端走查本轮尚未执行、DEFERRED 至发布前人工在真实微信小程序上补测。**
> 该走查依赖真实设备、已在微信后台申请并配置的一次性订阅模板、以及真实的墙钟时间到点，
> 无法在自动化环境中产出；刻意不把未执行的结果写成已通过。自动化可覆盖的后端逻辑已由任务 6.2 / 7.2 /
> 10.1–10.3 的 dispatch/scheduler/属性测试锁定，前端本地逻辑已由任务 11.3 覆盖；本清单只承载**必须在真机上
> 用眼睛确认**的端到端验收项（需求 3.2、4.2、4.3、10.1）。
>
> **前置条件（缺一不可，否则整条链路无法验收）**：
> - `app.wechat.subscribe.reminder-template-id` 已在生产/预发配置为微信后台申请通过的**一次性订阅**提醒模板 id，
>   且模板字段名映射（`app.wechat.subscribe.*`）与该模板实际字段一致（模板未配置时 `dispatch` 只会走 `FAILED`，
>   收不到任何消息）。
> - 走查账号已完成登录、`users.wx_openid` 非空；调度任务已随应用启动（`@EnableScheduling` 生效）。
> - 走查设备为真实微信客户端（开发者工具的订阅弹窗与真机行为不完全一致，授权额度须以真机为准）。
>
> **逐项走查（全部标注为待人工执行 ☐，通过后由执行人勾选并记录设备/微信版本与实际到点时间）**：
>
> ☐ **(a) 订阅授权（需求 10.1、10.5）**：在「我的」页点入口进入提醒设置页 → 触发「开启提醒」→ 弹出
>   `wx.requestSubscribeMessage` 提醒模板授权弹窗 → 点「允许」→ 页面剩余订阅次数按上报的授权次数增加
>   （上报接口返回增加后的 `remainingQuota`）。点「拒绝」时不上报、页面显示未授权提示与再次授权入口且不进错误态。
>
> ☐ **(b) 到点收到提醒（需求 3.2）**：新建一条频率命中当日、`remindTime` 设为「当前时刻后 1–2 分钟」的启用提醒
>   → 等待到点 → 真机在该分钟内（含至多 10 分钟追补窗口）收到微信订阅消息推送。核对该提醒该触发日
>   `reminder_send_logs` 恰一条 `result=SENT`、`remaining` 相应减 1。
>
> ☐ **(c) 文案随「今日已记账」切换（需求 4.2、4.3）**：
>   - **未记账**场景：走查账号当日尚无记账 → 到点收到的文案为「今天还没记账哦~」（`message_variant=NOT_YET`）。
>   - **已记账**场景：走查账号当日已完成一笔记账后 → 另一到点提醒收到的文案为「今天已经完成啦~」
>     （`message_variant=DONE`）。两次分别用不同 `remindTime` 的提醒或跨日验证，确认文案随发送那一刻的记账状态切换。
>
> ☐ **(d) 「我的」页入口可达（需求 10.1）**：从「我的」页能进入 `pages/reminder/reminder` 提醒设置页，
>   页面正常展示提醒列表与剩余订阅次数，且不展示任何金额/账本名/邮箱/邀请码。
>
> **补充确认**：额度为 0 时页面显著提示「授权后才能继续收到提醒」并提供再次授权入口（需求 10.7）；
> 接口错误或 3000ms 超时时展示失败提示与重试入口、不自动重试（需求 10.9）。这两项在真机上顺带核对。
