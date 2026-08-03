# Requirements Document

## Introduction

有余（youyu）目前没有用户增长通路：新用户从哪来、由谁带来，系统一无所知。本次新增**用户邀请系统**，
把「老用户分享 → 新用户注册」这条链路记录下来，为后续增长运营与奖励机制打好数据底座：

- **每个用户拥有一个稳定的个人邀请码**（`users.invite_code`，8 位、全局唯一、终身不变），
  并由邀请码派生出**邀请链接**（小程序页面路径 + 邀请码）与**邀请二维码**（微信小程序码）。
- **完整流程**：A 分享（卡片 / 二维码 / 复制邀请码）→ B 点击或扫码进入小程序 → 客户端暂存邀请码 →
  B 完成注册（邮箱验证码或微信一键，注册/登录合一）→ 服务端在建号的同一事务内写入邀请关系。
- **邀请关系表** `invite_relations`：`invite_id`、`inviter_id`、`invitee_id`、`register_time`、`status`。
- **本期只统计不发奖**：邀请人可在「邀请好友」页看到自己的邀请人数与被邀请人列表（昵称 / 注册时间 / 状态），
  奖励规则留待后续 spec 扩展，本期仅在 `status` 取值与表结构上预留扩展空间。

范围与前提约定（影响验收标准的关键决策）：

- **绑定时机唯一**：邀请关系**只在"新账号被创建的那一刻"**建立。已注册用户携带邀请码登录不绑定，
  因此不存在 A→B、B→A 互邀刷量，也不存在改绑。
- **仅一级邀请**：不做多级分销、不做邀请链路追溯。
- **注销不删除任何邀请关系行**：邀请人或被邀请人注销后该行均保留，`status` 仅反映被邀请人的账号状态。
  邀请关系表是「只追加 + 状态更新」的历史表，保留完整链路用于后台增长统计与对账。
- **邀请码问题绝不阻断注册**：邀请码缺失、无效、自邀等情形一律登录/注册照常成功，
  仅在响应中告知绑定结果，避免把增长功能的故障传导到登录主路径。
- **微信生态限制**：微信外的普通 HTTP 链接无法直达小程序，故「邀请链接」以**小程序页面路径 + 邀请码**
  形式提供（用于分享卡片 `path` 与小程序码 `page`），不引入 URL Link / H5 中转页。
- **二维码为微信小程序码**：服务端调用微信 `wxacode.getUnlimited`（需新增微信接口调用凭证的获取与缓存），
  以 base64 PNG 返回；系统当前无对象存储，故不落盘、只做服务端内存缓存。
- 与既有**账本邀请码**（`ledger_invites`，用于加入协作账本）是两套彼此独立的机制，本 spec 不修改账本邀请。

## Glossary

- **邀请码（invite_code）**：用户的个人邀请标识，8 个字符，取自字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`
  （大写字母与数字，排除易混字符 `I`/`O`/`0`/`1`），全局唯一，一经生成终身不变，存于 `users.invite_code`。
- **邀请人（inviter）**：分享出邀请码的已注册用户，对应 `invite_relations.inviter_id`。
- **被邀请人（invitee）**：携带邀请码完成注册的新用户，对应 `invite_relations.invitee_id`。
- **邀请关系（invite relation）**：`invite_relations` 表的一行，主键 `invite_id`。
- **邀请关系状态（status）**：**仅描述被邀请人的账号状态**，取值 `REGISTERED`（被邀请人账号存在）、
  `INVALID`（被邀请人已注销）。`status` 不表达邀请人的账号状态：邀请人注销不改变任何行的 `status`。
- **悬空 id**：`invite_relations.inviter_id` 与 `invite_relations.invitee_id` 均可能指向已被删除的 `users` 行。
  该表不含任何指向 `users(id)` 的外键，是「只追加 + 状态更新」的历史表，任何一方注销都不删除行。
- **邀请链接**：小程序页面路径与邀请码拼成的字符串 `/pages/invitelanding/invitelanding?code={邀请码}`，
  用于分享卡片的 `path` 与小程序码的 `page` 参数。
- **邀请二维码**：微信小程序码（`wxacode.getUnlimited` 生成，`scene` 为邀请码），微信扫码后进入**邀请落地页**。
- **邀请落地页**：miniapp 新增页面 `pages/invitelanding/invitelanding`，展示邀请人昵称与登录/注册入口。
- **邀请好友页**：miniapp 新增页面 `pages/invite/invite`，展示当前用户的邀请码、邀请链接、二维码与邀请统计。
- **待绑定邀请码**：客户端本地存储中暂存的邀请码（键 `youyu_pending_invite_code`），随下一次登录/注册请求上报。
- **待绑定邀请码写入时刻**：客户端把待绑定邀请码写入本地存储时记录的客户端本地时刻
  （键 `youyu_pending_invite_code_at`），用于判定该邀请码是否已满 7 天（604800000 毫秒）而失效。
- **待匹配邀请码**：登录/注册请求携带的邀请码输入字段取值，经裁剪首尾空白并转为大写后的结果。
- **未绑定原因**：登录/注册响应中说明本次未建立邀请关系的单一枚举取值，取自
  `NO_CODE`、`NOT_NEW_USER`、`CODE_NOT_FOUND`、`SELF_INVITE`、`ALREADY_BOUND`。
- **邀请关系总条数**：`inviter_id` 等于当前会话用户的 `invite_relations` 行数，含 `REGISTERED` 与 `INVALID` 两种状态。
- **已邀请人数**：`inviter_id` 等于当前会话用户且 `status` 为 `REGISTERED` 的 `invite_relations` 行数。
- **来源 IP**：反向代理（nginx）追加在 `X-Forwarded-For` 头末位的地址；该头缺失或末位去空白后为空时，
  取 TCP 连接的远端地址。客户端自带的 `X-Forwarded-For` 前序取值不作为来源 IP。
- **邀请系统（Invite_System）**：本 spec 涉及的服务端邀请码、邀请关系与邀请统计的接口与业务逻辑整体。
- **二维码服务（QRCode_Service）**：本 spec 新增的微信小程序码生成、凭证管理与缓存组件。
- **miniapp**：微信小程序端（uni-app / Vue 3）。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌。
- **新建用户**：本次登录/注册请求中由服务端在 `users` 表新插入的一行（注册/登录合一语义下的"注册"）。

## Requirements

### 需求 1：个人邀请码

**用户故事：** 作为用户，我希望有一个属于我的固定邀请码，这样我随时分享出去都是同一个码，别人报这个码就能算到我名下。

#### 验收标准

1. THE 邀请系统 SHALL 为每个用户维护至多一个邀请码，存于 `users.invite_code`，其取值 SHALL 为 NULL 或长度恰为 8 个字符、全部字符为大写且无首尾空白、每个字符取自字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` 的字符串。
2. WHEN 服务端创建新建用户 THEN THE 邀请系统 SHALL 在同一事务内为该用户生成并写入一个全局唯一的邀请码，且该事务提交后读取该用户行的 `invite_code` SHALL 得到一个长度为 8 个字符的非空取值。
3. WHEN 已认证用户请求邀请信息 AND 该用户的 `invite_code` 为 NULL 或去空白后为空 THEN THE 邀请系统 SHALL 生成并持久化一个全局唯一的邀请码后返回该邀请码（存量用户的惰性补齐）。
4. WHEN 同一用户连续两次请求邀请信息 THEN THE 邀请系统 SHALL 两次返回相同的邀请码，且 SHALL 保持 `users.invite_code` 的取值不变（邀请码稳定、请求幂等）。
5. THE 邀请系统 SHALL 保证任意两个用户的 `invite_code` 不相同。
6. WHEN 生成邀请码 THEN THE 邀请系统 SHALL 使用密码学安全随机源逐字符抽取候选码，SHALL 以「`users.invite_code` 中已存在同一取值」作为该候选码被占用的判定依据，SHALL 在候选码被占用时重新抽取且最多尝试 10 次，并 SHALL 采用首个未被占用的候选码作为该用户的邀请码。
7. IF 在创建新建用户的事务内连续 10 次生成的候选邀请码均已被占用 THEN THE 邀请系统 SHALL 回滚该登录/注册事务、SHALL 不签发令牌，且 SHALL 使 `users` 表保持请求前的状态（不留下 `invite_code` 为空的新用户行）。
8. IF 在惰性补齐邀请码时连续 10 次生成的候选邀请码均已被占用 THEN THE 邀请系统 SHALL 返回 `INVITE_CODE_GEN_FAILED`、SHALL 使该用户 `users.invite_code` 保持原有取值，且响应中 SHALL 不包含邀请码、邀请链接与已邀请人数中任何字段的值。
9. WHEN 邀请系统接收外部传入的邀请码 THEN THE 邀请系统 SHALL 先裁剪首尾空白字符再转为大写后进行匹配（邀请码大小写不敏感）；IF 规整后的取值长度不等于 8 个字符或含字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` 之外的字符 THEN THE 邀请系统 SHALL 直接判定为不匹配并继续处理本次请求，且 SHALL 不抛出异常、SHALL 不返回服务端错误。
10. THE 邀请系统 SHALL 提供已认证用户获取自身邀请信息的接口，其成功响应字段是且仅是邀请码、邀请链接、已邀请人数三个；其中邀请码 SHALL 等于该用户 `users.invite_code` 的当前取值，已邀请人数 SHALL 为大于或等于 0 的整数且在该用户没有任何邀请关系时取值为 0。
11. WHEN 已认证用户请求邀请信息 THEN THE 邀请系统 SHALL 在服务端处理耗时不超过 2000 毫秒内返回成功结果或错误标识（不含网络传输耗时）。
12. WHEN 同一用户的两个及以上请求并发触发邀请码惰性补齐 THEN THE 邀请系统 SHALL 使该用户 `users.invite_code` 的终态为恰好一个非空取值，且 SHALL 使全部返回成功的响应中的邀请码取值相同。
13. THE 邀请系统 SHALL 不提供修改或重置邀请码的操作；WHEN 用户的昵称、邮箱或微信绑定发生变更，或同一用户重复登录 THEN THE 邀请系统 SHALL 保持该用户 `users.invite_code` 的取值不变；THE 邀请系统 SHALL 仅在账号注销随 `users` 行删除时释放该邀请码（见需求 10）。

### 需求 2：邀请链接与分享卡片

**用户故事：** 作为用户，我希望直接把邀请卡片转发给微信好友，这样对方点一下就进来了，不用手抄邀请码。

#### 验收标准

1. THE 邀请系统 SHALL 在邀请信息响应中返回邀请链接，其取值为 `/pages/invitelanding/invitelanding?code={邀请码}`，其中 `{邀请码}` 为该用户 `users.invite_code` 中的 8 个字符原文（大写、无首尾空白、不做额外转义）。
2. WHEN 用户在邀请好友页触发转发 AND 邀请信息已加载成功 THEN THE miniapp SHALL 生成一张分享卡片，其 `path` 等于该用户的邀请链接（含取值等于该用户邀请码的 `code` 查询参数），其标题包含产品名「有余」且标题长度不超过 30 个字符。
3. THE miniapp SHALL 在邀请好友页提供「复制邀请码」与「复制邀请链接」两个操作；WHEN 用户触发其中一个操作 AND 写入系统剪贴板成功 THEN THE miniapp SHALL 使剪贴板内容分别等于邀请码原文或邀请链接原文（不含首尾空白、不含其它说明文字），并展示时长 1500 毫秒的复制成功提示。
4. WHEN 用户点击分享卡片进入 miniapp THEN THE miniapp SHALL 打开邀请落地页，并把地址参数 `code` 经 URL 解码并裁剪首尾空白后的取值作为邀请码传入该页。
5. IF 邀请落地页启动参数中不含 `code`，或 `code` 去空白后为空，或 `code` 去空白转大写后长度不等于 8 个字符，或含字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` 之外的字符 THEN THE miniapp SHALL 展示不含邀请人信息的默认登录引导，SHALL 不写入待绑定邀请码，且 SHALL 不发起邀请人展示信息查询。
6. WHILE 用户处于已登录状态 THE miniapp SHALL 在「我的」页面展示进入邀请好友页的入口；WHEN 用户点击该入口 THEN THE miniapp SHALL 打开邀请好友页 `pages/invite/invite`。
7. WHEN 邀请好友页加载完成 AND 邀请信息请求返回成功 THEN THE miniapp SHALL 在同一屏内展示当前用户的 8 个字符邀请码、邀请二维码与已邀请人数，其中已邀请人数取值等于邀请信息响应中的已邀请人数字段。
8. IF 邀请好友页的邀请信息请求返回错误标识，或 10 秒内无响应 THEN THE miniapp SHALL 展示邀请信息加载失败的提示文案与重试操作，且 SHALL 不展示邀请码、邀请链接与转发入口。
9. IF 用户在邀请好友页触发转发 AND 邀请信息尚未加载成功（邀请链接为空）THEN THE miniapp SHALL 生成 `path` 为 `/pages/invitelanding/invitelanding` 且不含 `code` 参数的分享卡片，并展示邀请码尚未就绪的提示文案。
10. IF 复制邀请码或复制邀请链接时写入系统剪贴板失败 THEN THE miniapp SHALL 展示复制失败的提示文案，并 SHALL 保持停留在邀请好友页且继续展示邀请码与邀请链接文本供用户手动选取。

### 需求 3：邀请二维码

**用户故事：** 作为用户，我希望有一张可以保存到相册、贴到群里或线下海报上的二维码，这样别人扫一下就能进小程序并算到我名下。

#### 验收标准

1. THE 二维码服务 SHALL 提供已认证用户获取自身邀请二维码的接口，返回一张 PNG 图片的 base64 编码字符串，且该字符串 SHALL 不含 `data:image/png;base64,` 等 data URI 前缀。
2. WHEN 已认证用户请求邀请二维码 AND 该用户邀请码的小程序码未命中服务端缓存或命中的缓存项写入时刻距服务端当前时刻已满 7 天 THEN THE 二维码服务 SHALL 以该用户的邀请码作为小程序码的 `scene` 参数、以 `pages/invitelanding/invitelanding` 作为 `page` 参数、以 430 像素作为图片边长调用微信小程序码接口，并在该接口返回成功时返回其 PNG 字节的 base64 编码。
3. WHEN 用户用微信扫描该邀请二维码 THEN THE miniapp SHALL 打开邀请落地页，并把启动参数 `scene` 经 URL 解码、裁剪首尾空白并转为大写后的取值作为邀请码传入该页。
4. IF 同一邀请码的小程序码已在服务端缓存中 AND 该缓存项写入时刻距服务端当前时刻不足 7 天 THEN THE 二维码服务 SHALL 返回缓存中的图片数据，且 SHALL 不调用微信小程序码接口与微信凭证接口。
5. THE 二维码服务 SHALL 缓存微信接口调用凭证，SHALL 在凭证剩余有效期不足 300 秒时重新获取凭证，且 SHALL 使单次凭证获取调用的超时上限为 2000 毫秒。
6. IF 服务端未配置微信小程序 appid 或 secret，或其配置取值去空白后为空 THEN THE 二维码服务 SHALL 返回 `INVITE_QRCODE_FAILED`、SHALL 不返回图片数据，且 SHALL 不调用任何微信接口。
7. IF 微信小程序码接口返回非零错误码、超过 3000 毫秒未返回响应、或调用抛出异常 THEN THE 二维码服务 SHALL 返回 `INVITE_QRCODE_FAILED`、SHALL 记录一条包含微信错误码的告警日志、SHALL 把本次请求计入该用户未命中缓存的请求计数，且 SHALL 不写入图片缓存。
8. IF 邀请二维码获取失败 THEN THE miniapp SHALL 在邀请好友页以文案提示二维码暂不可用，并 SHALL 继续展示邀请码与邀请链接、SHALL 保持复制邀请码、复制邀请链接与转发三个操作可用（二维码故障不阻断其余分享方式）。
9. IF 同一用户在「服务端当前时刻往前 24 小时」滑动窗口内的邀请二维码请求中未命中缓存的次数已达到 20 次 THEN THE 二维码服务 SHALL 拒绝本次请求并返回 `INVITE_RATE_LIMITED`，且 SHALL 不调用微信小程序码接口；THE 二维码服务 SHALL 在缓存命中判定之后再执行该限流判定，使命中缓存的请求 SHALL 不计入该计数且 SHALL 不被限流拒绝。
10. WHEN 已认证用户请求邀请二维码 THEN THE 二维码服务 SHALL 在服务端处理耗时不超过 5000 毫秒内返回成功结果或错误标识（含微信凭证获取与小程序码接口调用耗时）。
11. THE miniapp SHALL 在邀请好友页提供把邀请二维码保存到相册的操作；WHEN 保存到相册成功 THEN THE miniapp SHALL 展示保存成功的提示文案；IF 用户拒绝相册写入授权 THEN THE miniapp SHALL 展示需要授权的提示文案、SHALL 保持停留在邀请好友页，且 SHALL 保持已展示的二维码、邀请码与邀请链接不变。
12. WHEN 已认证用户请求邀请二维码 AND 该用户的 `invite_code` 为 NULL 或去空白后为空 THEN THE 邀请系统 SHALL 先按需求 1 第 3 条惰性生成并持久化该用户的邀请码，THE 二维码服务 SHALL 再以该邀请码作为 `scene` 参数生成小程序码。
13. WHEN 微信小程序码接口返回成功 THEN THE 二维码服务 SHALL 以邀请码为键把该 PNG 的 base64 编码与服务端当前时刻写入内存缓存，SHALL 使缓存项总数不超过 1000 项，SHALL 在写入将使总数超过 1000 项时淘汰写入时刻最早的缓存项，且 SHALL 不把图片数据写入磁盘文件。
14. IF 微信接口调用凭证获取失败（返回非零错误码、超过 2000 毫秒未返回响应、或调用抛出异常）THEN THE 二维码服务 SHALL 返回 `INVITE_QRCODE_FAILED`、SHALL 保持已缓存的凭证取值与其到期时刻不变，且 SHALL 不调用微信小程序码接口。

### 需求 4：邀请落地与邀请码暂存

**用户故事：** 作为被邀请的新用户，我点进来时还没有账号，我希望我看到是谁邀请我的，并且我注册完之后这层关系自动就记上了，不用我做额外操作。

#### 验收标准

1. WHEN 邀请落地页从 `code` 或 `scene` 启动参数接收到邀请码 AND 该邀请码去首尾空白并转为大写后长度为 8 个字符且每个字符均属于字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` THEN THE miniapp SHALL 把规整后的邀请码写入本地存储的待绑定邀请码（覆盖本地已存在的待绑定邀请码，以最近一次写入为准），并把客户端当前本地时刻记录为该待绑定邀请码的写入时刻。
2. THE 邀请系统 SHALL 提供无需令牌即可调用的邀请人展示信息查询接口，接收一个邀请码输入字段，成功时仅返回邀请人昵称一个字段（邀请人昵称为 NULL 或去空白后为空时以空值返回该字段），且 SHALL 在服务端处理耗时不超过 500 毫秒内返回成功结果或错误标识（不含网络传输耗时，与需求 8 第 10 条同一口径）。
3. WHEN 邀请落地页写入待绑定邀请码 THEN THE miniapp SHALL 以该待绑定邀请码发起邀请人展示信息查询，并在查询成功时展示邀请人昵称（返回昵称为空值时展示不含昵称的通用邀请提示）与微信一键登录、邮箱验证码登录/注册两个入口。
4. IF 查询邀请人展示信息时邀请码在 `users.invite_code` 中不存在 THEN THE 邀请系统 SHALL 返回 `NOT_FOUND`，且响应中 SHALL 不包含任何用户字段值。
5. IF 邀请人展示信息查询返回 `NOT_FOUND`、返回 `INVITE_RATE_LIMITED`、发生网络错误、或客户端等待响应超过 5000 毫秒 THEN THE miniapp SHALL 展示不含邀请人信息的默认登录引导，且 SHALL 保留已写入的待绑定邀请码及其写入时刻不变。
6. WHEN miniapp 发起邮箱验证码登录或微信一键登录请求 AND 本地存储中存在去空白后非空的待绑定邀请码 AND 该待绑定邀请码的写入时刻距客户端当前时刻不足 7 天（604800000 毫秒）THEN THE miniapp SHALL 在该登录请求中携带该邀请码。
7. IF 本地存储中待绑定邀请码的写入时刻距客户端当前时刻已满 7 天（604800000 毫秒），或该写入时刻缺失、不可解析为时刻 THEN THE miniapp SHALL 删除该待绑定邀请码与其写入时刻，且 SHALL 不在登录请求中携带邀请码。
8. WHEN 登录/注册请求返回成功 THEN THE miniapp SHALL 删除本地存储中的待绑定邀请码与其写入时刻（无论响应中的绑定结果标识为已绑定或未绑定），且此后发起的登录/注册请求 SHALL 不携带邀请码。
9. WHILE 用户处于已登录状态 THE miniapp SHALL 在邀请落地页展示已登录提示与进入首页的入口，且 SHALL 不写入、不修改本地存储中的待绑定邀请码。
10. THE miniapp SHALL 在邀请落地页提供且仅提供原有的两种登录方式入口（微信一键登录、邮箱验证码登录/注册合一），且 SHALL 不新增其它注册方式。
11. IF 邀请落地页接收到的邀请码去空白并转为大写后长度不等于 8 个字符，或含字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` 之外的字符 THEN THE miniapp SHALL 展示不含邀请人信息的默认登录引导，SHALL 不发起邀请人展示信息查询，且 SHALL 不写入、不修改本地存储中已有的待绑定邀请码。
12. IF 登录/注册请求未返回成功（返回错误响应、发生网络错误、或客户端等待响应超时）THEN THE miniapp SHALL 保留本地存储中的待绑定邀请码及其写入时刻不变，使用户重试登录时仍能携带该邀请码。
13. IF 待绑定邀请码的本地存储读取或写入操作失败 THEN THE miniapp SHALL 继续展示邀请落地页的两个登录入口并允许用户完成登录/注册，且 SHALL 不因该失败中断或阻断登录/注册流程（邀请码暂存故障不阻断注册主路径）。

### 需求 5：注册时自动绑定邀请关系

**用户故事：** 作为运营者，我希望被邀请人一注册成功，数据库里就落下一条准确的邀请关系，这样我的统计口径不会有歧义。

#### 验收标准

1. THE 邀请系统 SHALL 允许邮箱验证码登录接口与微信一键登录接口接收一个可选的邀请码输入字段，该字段接受的取值长度上限为 64 个字符；THE 邀请系统 SHALL 把该字段取值去除首尾空白后转为大写，作为本次请求的待匹配邀请码，并把字段缺失、取值为 NULL、或去空白后长度为 0 的情形一律按未携带邀请码处理。
2. WHEN 登录/注册请求的待匹配邀请码长度为 8 且全部字符取自邀请码字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` AND 本次请求在 `users` 表新插入了一行（新建用户）AND 该邀请码在 `users.invite_code` 中存在且其持有者不是该新建用户 THEN THE 邀请系统 SHALL 在创建该用户的同一数据库事务内插入恰好 1 条邀请关系，写入 `inviter_id` 为该邀请码持有者的用户 id、`invitee_id` 为新建用户的 id、`register_time` 为该用户的创建时刻、`status` 为 `REGISTERED`，且 SHALL 在响应中返回绑定结果为已绑定。
3. WHEN 登录/注册请求携带待匹配邀请码 AND 本次请求未在 `users` 表新插入行（已注册用户登录）THEN THE 邀请系统 SHALL 不对 `invite_relations` 表执行任何插入或更新语句，且 SHALL 以未绑定原因 `NOT_NEW_USER` 完成本次登录/注册并签发令牌。
4. THE 邀请系统 SHALL 在登录/注册成功响应中返回恰好 1 个绑定结果标识（已绑定 / 未绑定）与至多 1 个未绑定原因：结果为已绑定时未绑定原因 SHALL 为空值，结果为未绑定时未绑定原因 SHALL 取自 `NO_CODE`、`CODE_NOT_FOUND`、`NOT_NEW_USER`、`SELF_INVITE`、`ALREADY_BOUND` 五个取值之一。
5. IF 本次请求创建了新建用户 AND 待匹配邀请码长度为 8 且字符全部合法但在 `users.invite_code` 中不存在 THEN THE 邀请系统 SHALL 以未绑定原因 `CODE_NOT_FOUND` 完成本次登录/注册并签发令牌，且 SHALL 保持 `invite_relations` 表数据不变。
6. IF 待匹配邀请码的长度不等于 8、或包含邀请码字母表以外的字符、或原始取值长度超过 64 个字符 THEN THE 邀请系统 SHALL 不对 `invite_relations` 表执行任何插入语句、SHALL 不回滚登录/注册事务，且 SHALL 以未绑定原因 `CODE_NOT_FOUND` 完成本次登录/注册并签发令牌（邀请码格式问题不进入插入、不阻断注册主路径）。
7. IF 邀请关系插入语句因 `invitee_id` 唯一约束冲突以外的数据库故障而失败 THEN THE 邀请系统 SHALL 回滚整个登录/注册事务、SHALL 不签发令牌并返回表明登录/注册失败的错误响应，且 SHALL 保持 `users` 表与 `invite_relations` 表为请求前的状态。
8. WHEN 邀请关系插入成功 THEN THE 邀请系统 SHALL 使该关系的 `register_time` 与被邀请人 `users.created_at` 的取值完全相等（同一服务端时刻，时间差为 0 毫秒）。
9. THE 邀请系统 SHALL 仅记录一级邀请关系，且 SHALL 不因被邀请人后续邀请他人而修改任何已存在邀请关系行的 `inviter_id`、`invitee_id`、`register_time` 与 `status`。
10. IF 邀请关系插入语句因 `invitee_id` 唯一约束冲突而失败 THEN THE 邀请系统 SHALL 保留本次新建用户并正常签发令牌（不回滚登录/注册事务）、SHALL 以未绑定原因 `ALREADY_BOUND` 完成本次登录/注册，且 SHALL 保持已存在那一行的 `inviter_id`、`register_time` 与 `status` 不变。
11. WHEN 单次登录/注册请求同时满足多个未绑定情形 THEN THE 邀请系统 SHALL 按 `NO_CODE` → `NOT_NEW_USER` → `CODE_NOT_FOUND` → `SELF_INVITE` → `ALREADY_BOUND` 的固定优先顺序取首个成立的原因作为唯一未绑定原因返回。
12. WHEN 处理一次携带邀请码的登录/注册请求 THEN THE 邀请系统 SHALL 对 `invite_relations` 表最多执行 1 次插入尝试，且 SHALL 不在插入失败后重试。

### 需求 6：邀请关系的唯一性与拒绝条件

**用户故事：** 作为运营者，我希望一个新用户只可能算在一个人名下，而且自己邀请自己这种情况被挡掉，这样统计数据是干净的。

#### 验收标准

1. THE 邀请系统 SHALL 依靠数据库层 `invite_relations.invitee_id` 上的唯一索引（需求 9 第 3 条的 `uk_invite_relations_invitee`）保证同一 `invitee_id` 在该表中至多存在一行，且 SHALL 不以应用层的先查询后写入作为该唯一性的保证手段。
2. IF 待匹配邀请码持有者的用户 id 与本次新建用户的 id 相等 THEN THE 邀请系统 SHALL 以未绑定原因 `SELF_INVITE` 完成本次登录/注册并签发令牌、SHALL 保留本次新建的 `users` 行，且 SHALL 保持 `invite_relations` 表的行数与全部列取值不变。
3. IF 插入邀请关系时 `invitee_id` 唯一约束冲突（该被邀请人已有邀请关系）THEN THE 邀请系统 SHALL 以未绑定原因 `ALREADY_BOUND` 完成本次登录/注册并签发令牌、SHALL 保持该已有行的 `inviter_id`、`register_time` 与 `status` 不变，且 SHALL 不在 `invite_relations` 表新增或删除任何行（邀请关系一次写定、不可改绑）。
4. IF 向 `invite_relations` 表写入的 `invitee_id` 已存在 THEN THE 数据库 SHALL 以唯一约束违例拒绝该写入语句，且 SHALL 保持 `invite_relations` 表的行数与全部列取值为该语句执行前的状态、SHALL 不产生部分写入。
5. THE 邀请系统 SHALL 允许同一 `inviter_id` 在 `invite_relations` 表中存在多行（一名邀请人可邀请多名用户）；WHEN 同一 `inviter_id` 名下已有 10000 行邀请关系 THEN THE 邀请系统 SHALL 仍允许为该 `inviter_id` 插入新的邀请关系行，且 SHALL 不因已有行数而拒绝该写入。
6. WHEN 同一被邀请人使用同一邀请码连续触发 2 至 10 次登录 THEN THE 邀请系统 SHALL 使 `invite_relations` 表中以该被邀请人为 `invitee_id` 的行数保持为 1（重复登录幂等），且 SHALL 使第 2 次及其后各次的未绑定原因为 `NOT_NEW_USER`（对齐需求 5 第 3 条，区别于本需求第 3 条的 `ALREADY_BOUND`）。
7. THE 邀请系统 SHALL 保证 `invite_relations` 表中每一行的 `inviter_id` 与 `invitee_id` 取值不相等，且 SHALL 不向该表执行使这两列取值相等的插入或更新语句。
8. WHEN 插入邀请关系遇到 `invitee_id` 唯一约束冲突 THEN THE 邀请系统 SHALL 在插入语句之前设置的事务保存点处捕获该冲突、SHALL 仅回滚至该保存点并继续提交登录/注册事务，SHALL 不把该冲突视为需求 5 第 7 条所指的「插入失败」、SHALL 不回滚整个登录/注册事务；本次请求完成后该新建用户的 `users` 行与其 `invite_code` SHALL 仍存在。
9. WHEN 两个及以上请求在 1000 毫秒内并发以同一 `invitee_id` 插入邀请关系 THEN THE 邀请系统 SHALL 使该 `invitee_id` 的终态行数为 1，且 SHALL 使落败方以未绑定原因 `ALREADY_BOUND` 完成登录并签发令牌、SHALL 不向落败方返回服务端错误响应。
10. WHEN 单次登录/注册请求同时满足多个未绑定情形 THEN THE 邀请系统 SHALL 按 `NO_CODE` → `NOT_NEW_USER` → `CODE_NOT_FOUND` → `SELF_INVITE` → `ALREADY_BOUND` 的固定优先顺序取首个成立的原因作为唯一未绑定原因返回（与需求 5 第 11 条完全一致）。

### 需求 7：邀请统计与被邀请人列表

**用户故事：** 作为用户，我希望看到我一共邀请了多少人、都是谁、什么时候注册的，这样我知道我的分享有没有效果。

#### 验收标准

1. THE 邀请系统 SHALL 提供已认证用户查询自身被邀请人列表的接口，支持分页参数 `page`（整数，取值范围 0 到 100000，缺省 0）与 `size`（整数，取值范围 1 到 50，缺省 20）。
2. WHEN 已认证用户请求被邀请人列表 THEN THE 邀请系统 SHALL 仅返回 `inviter_id` 等于当前会话用户的邀请关系，按 `register_time` 倒序排列、`register_time` 相同时按 `invite_id` 倒序排列。
3. WHEN 已认证用户以生效取值为 `page` 与 `size` 的分页参数请求被邀请人列表 THEN THE 邀请系统 SHALL 自该排序序列的第 `page × size + 1` 条起返回列表项，且单次请求返回的列表项条数 SHALL 不超过生效的 `size`。
4. WHEN 返回被邀请人列表项 THEN THE 邀请系统 SHALL 包含 `invite_id`、被邀请人昵称、`register_time` 与 `status` 四个字段。
5. THE 邀请系统 SHALL 把被邀请人列表响应中的邀请关系总条数定义为 `inviter_id` 等于当前会话用户的 `invite_relations` 行数（含 `status` 为 `REGISTERED` 与 `INVALID` 的行），该总条数 SHALL 不受 `page` 与 `size` 影响；WHEN 客户端以同一 `size` 逐页取完全部页 THEN 各页返回的列表项条数之和 SHALL 等于该总条数。
6. THE 邀请系统 SHALL 把邀请信息接口与被邀请人列表接口返回的「已邀请人数」定义为 `inviter_id` 等于当前会话用户且 `status` 为 `REGISTERED` 的邀请关系行数，SHALL 使该已邀请人数小于或等于邀请关系总条数，且 SHALL 使邀请关系总条数减去已邀请人数的差等于该用户名下 `status` 为 `INVALID` 的行数。
7. IF 被邀请人昵称为 NULL、去空白后为空、或该被邀请人的 `users` 行已因注销而不存在 THEN THE 邀请系统 SHALL 以空值返回昵称字段、SHALL 仍返回该列表项的 `invite_id`、`register_time` 与 `status` 三个字段的真实取值、SHALL 不使用占位文本替代，且 SHALL 不使本次请求失败。
8. THE 邀请系统 SHALL 在被邀请人列表与邀请信息的响应中排除被邀请人的 `email`、`wx_openid`、`wx_unionid` 与 `invite_code` 四个字段。
9. IF 请求的 `page` 或 `size` 无法解析为整数，或 `page` 小于 0，或 `page` 大于 100000，或 `size` 小于 1，或 `size` 大于 50 THEN THE 邀请系统 SHALL 拒绝该请求并返回 `INVITE_PAGE_PARAM_INVALID`，且响应中 SHALL 不包含任何列表项与任何计数值。
10. WHEN 当前用户没有任何邀请关系，或请求的页码超出已有数据范围 THEN THE 邀请系统 SHALL 返回空列表与真实的邀请关系总条数，且 SHALL 不返回错误。
11. WHEN 已认证用户请求被邀请人列表 THEN THE 邀请系统 SHALL 在服务端处理耗时不超过 2000 毫秒内返回成功结果或错误标识（不含网络传输耗时，与需求 1 第 11 条同一口径）。
12. IF 被邀请人列表请求返回错误标识，或客户端等待响应超过 2000 毫秒未收到响应 THEN THE miniapp SHALL 展示列表加载失败的提示文案与重试操作，并 SHALL 保留已加载的被邀请人记录与已展示的邀请码、邀请链接不变。
13. WHILE 用户停留在邀请好友页 THE miniapp SHALL 展示已邀请人数与首屏至多 20 条被邀请人记录，SHALL 在每次上拉加载时追加至多 20 条，SHALL 在已加载条数等于邀请关系总条数后停止发起后续列表请求，并 SHALL 为每条记录展示与 `REGISTERED`/`INVALID` 两个取值一一对应的状态文案。
14. WHEN 当前用户没有任何邀请关系 THEN THE miniapp SHALL 在邀请好友页展示空状态提示与分享引导文案，且 SHALL 不展示被邀请人列表区域。

### 需求 8：权限与防刷

**用户故事：** 作为开发者，我希望邀请接口只能拿到自己的数据，也不被用来批量探测别人的信息。

#### 验收标准

1. THE 邀请系统 SHALL 要求邀请信息接口、邀请二维码接口与被邀请人列表接口携带**有效令牌**。
2. IF 请求未携带令牌、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在 THEN THE 邀请系统 SHALL 返回 `UNAUTHENTICATED`（优先于任何字段校验与限流错误），且 SHALL 保持 `users` 表与 `invite_relations` 表数据不变。
3. THE 邀请系统 SHALL 把邀请信息接口、邀请二维码接口与被邀请人列表接口的数据范围硬性限定为当前会话用户本人的邀请码与邀请关系，SHALL 以有效令牌所标识的用户 id 作为唯一的数据归属依据，且 SHALL 忽略请求中任何用于指定目标用户身份的输入字段（不允许通过入参越权读取他人数据）。
4. THE 邀请系统 SHALL 把邀请人展示信息查询接口与两个登录/注册接口设为公开端点（无需令牌）；WHERE 上述公开端点的请求携带了无效或已过期令牌 THE 邀请系统 SHALL 忽略该令牌并按未认证的公开请求继续处理，且 SHALL 不返回 `UNAUTHENTICATED`。
5. THE 邀请系统 SHALL 使邀请人展示信息查询接口的成功响应仅包含邀请人昵称一个字段，SHALL 排除邀请人的 `id`、`email`、`wx_openid`、`plan` 与 `role` 五个字段，且 SHALL 不包含任何表明该邀请人已邀请人数、注册时刻或账号状态的字段。
6. IF 同一来源 IP 在「服务端当前时刻往前 60 秒」滑动窗口内对邀请人展示信息查询接口的已计数请求数已达到 30 次 THEN THE 邀请系统 SHALL 拒绝本次请求并返回 `INVITE_RATE_LIMITED`（该判定优先于邀请码的格式校验与存在性查询），且响应中 SHALL 不包含任何用户字段值；其中「来源 IP」定义为反向代理（nginx）追加在 `X-Forwarded-For` 头末位的地址，该头缺失或末位去空白后为空时取 TCP 连接的远端地址，且 SHALL 不把客户端自带的 `X-Forwarded-For` 前序取值作为计数键。
7. WHEN 邀请人展示信息查询接口的请求被限流拒绝 THEN THE 邀请系统 SHALL 保持 `users` 表与 `invite_relations` 表数据不变。
8. THE 邀请系统 SHALL 以 `user_id` 作为邀请二维码接口的限流统计维度：同一用户在全部设备与会话共享同一 24 小时额度（额度按未命中缓存的请求次数计数，上限 20 次），窗口边界一律以服务端时刻计算，且被限流拒绝的请求 SHALL 不消耗该额度。
9. IF 邀请人展示信息查询接口收到的邀请码在裁剪首尾空白并转为大写后长度不等于 8 个字符、含字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` 之外的字符、或在 `users.invite_code` 中不存在 THEN THE 邀请系统 SHALL 对这三种情形返回同一错误码 `NOT_FOUND` 与同一响应字段集，且 SHALL 不返回任何可区分「格式非法」与「邀请码不存在」的附加标识（避免通过响应差异枚举邀请码）。
10. THE 邀请系统 SHALL 使邀请人展示信息查询接口在邀请码存在与不存在两种情形下的服务端处理耗时均不超过 500 毫秒（不含网络传输耗时），SHALL 不在邀请码不存在时追加等待或重试，且 SHALL 使两种情形同等计入该来源 IP 的 60 秒窗口计数。
11. THE 邀请系统 SHALL 把邀请人展示信息查询接口的 60 秒 IP 限流计数与邀请二维码接口的 24 小时用户限流计数保存在应用实例进程内的内存计数器中，SHALL 使额度按应用实例各自独立累计（当前部署为单实例；多实例部署时同一来源 IP 或同一用户在各实例上分别享有独立额度），且 SHALL 在应用实例进程启动后以 0 作为两类计数的初始值。

### 需求 9：数据模型与迁移

**用户故事：** 作为开发者，我需要邀请码与邀请关系有清晰的表结构和迁移脚本，这样邀请数据能与既有 Flyway 迁移体系一致地演进。

#### 验收标准

1. THE 迁移脚本 SHALL 为 `users` 表新增列 `invite_code`（VARCHAR(8) NULL，带中文列注释，沿用 `users` 表既有的 `utf8mb4` 字符集与 `utf8mb4_unicode_ci` 排序规则），并为该列建立名为 `uk_users_invite_code` 的具名唯一约束；该唯一约束 SHALL 允许多行 `invite_code` 取值为 NULL、SHALL 使任意两行的非 NULL 取值不相同，且因该排序规则大小写不敏感 SHALL 把仅大小写不同的两个邀请码判定为重复。
2. THE 迁移脚本 SHALL 新建 `invite_relations` 表，该表 SHALL 恰好包含以下 7 列：`invite_id`（BIGINT NOT NULL AUTO_INCREMENT，主键）、`inviter_id`（BIGINT NOT NULL）、`invitee_id`（BIGINT NOT NULL）、`register_time`（DATETIME NOT NULL，无缺省值）、`status`（VARCHAR(16) NOT NULL，缺省 `REGISTERED`）、`created_at`（DATETIME NOT NULL）、`updated_at`（DATETIME NOT NULL）。
3. THE 迁移脚本 SHALL 为 `invite_relations.invitee_id` 建立名为 `uk_invite_relations_invitee` 的具名唯一约束（一名被邀请人至多一条邀请关系）。
4. THE 迁移脚本 SHALL 为 `invite_relations` 建立名为 `idx_invite_relations_inviter_time` 的非唯一复合索引，其首列为 `inviter_id`、次列为 `register_time`（支撑邀请人按注册时间倒序翻页）。
5. THE 迁移脚本 SHALL 不为 `invite_relations.inviter_id` 建立外键，以便邀请人注销后仍保留其名下的历史邀请记录；删除某个 `users` 行后以该用户 id 为 `inviter_id` 的 `invite_relations` 行数 SHALL 与删除前相同，且这些行的 `invite_id`、`inviter_id`、`invitee_id`、`register_time` 与 `status` 取值 SHALL 不变。
6. THE `invite_relations` 表 SHALL 不含任何指向 `users(id)` 的外键（`inviter_id` 与 `invitee_id` 均不建外键），以便任一方注销后仍保留该条历史邀请记录；删除某个 `users` 行后以该用户 id 为 `invitee_id` 的 `invite_relations` 行 SHALL 仍存在，且其 `invite_id`、`inviter_id`、`invitee_id`、`register_time` 取值 SHALL 不变。
7. THE 迁移脚本 SHALL 建立名为 `ck_invite_relations_status` 的具名 CHECK 约束，把 `status` 限制为区分大小写的 `REGISTERED`/`INVALID` 两个取值（小写或混合大小写的同名取值 SHALL 被视为非法）。
8. IF 向 `invite_relations` 插入或更新的 `status` 取值不属于 `REGISTERED`/`INVALID` 集合 THEN THE 数据库 SHALL 以约束违例错误拒绝该写入语句，且 SHALL 保持 `invite_relations` 表的行数与全部列取值不变。
9. THE `invite_relations` 表 SHALL 使用 InnoDB 引擎、`utf8mb4` 字符集与 `utf8mb4_unicode_ci` 排序规则，且 7 个列全部带中文列注释、表本身带中文表注释（对齐 `V27__loan_repayments.sql` 的既有写法）。
10. THE 迁移脚本 SHALL 命名为 `V<N>__user_invite.sql`，其中 `<N>` 为实现本 spec 时 `src/main/resources/db/migration` 目录中已存在的最大版本号加 1（撰写本文档时最大版本号为 29，故预期文件名为 `V30__user_invite.sql`），且 SHALL 不修改、不重命名任何已存在的历史迁移文件。
11. WHEN 应用在已执行全部历史迁移的数据库上启动 THEN THE Flyway 迁移 SHALL 成功执行本 spec 新增的迁移脚本，并在 `flyway_schema_history` 中新增一条版本号等于该脚本版本号、状态为成功的记录，且该脚本执行耗时 SHALL 不超过 60 秒。
12. IF 本 spec 新增的迁移脚本执行失败 THEN THE 应用 SHALL 中止启动并输出表明迁移失败的错误，`flyway_schema_history` SHALL 不含该版本号的成功记录，且 `users` 表与其余既有表的业务数据行数与列取值 SHALL 保持迁移前的状态。
13. WHEN 应用以 Hibernate `ddl-auto=validate` 在迁移完成的数据库上启动 THEN THE 应用 SHALL 启动成功且 SHALL 不抛出针对 `users.invite_code` 列与 `invite_relations` 表 7 个列的 schema 校验异常。
14. THE 清库脚本 `deploy/reset-db.sql` SHALL 在 `TRUNCATE TABLE users` 之前清空 `invite_relations` 表（因该表不含外键，该清空 SHALL 不依赖 `FOREIGN_KEY_CHECKS` 的取值）；脚本执行后 `invite_relations` 表行数 SHALL 为 0，`invite_relations` 表结构与 `flyway_schema_history` 的全部记录 SHALL 保留（脚本 SHALL 不含针对 `flyway_schema_history` 的删除或清空语句）。
15. WHEN 创建邀请关系 THEN THE 邀请系统 SHALL 写入 `created_at` 与 `updated_at` 为同一服务端时刻（两列取值相等）；WHEN 邀请关系的 `status` 被更新 THEN THE 邀请系统 SHALL 把 `updated_at` 更新为该次操作的服务端时刻，且 SHALL 不修改 `invite_id`、`created_at` 与 `register_time`。
16. IF 实现本 spec 时目标版本号 `<N>` 已被其它 spec 的迁移文件占用（例如 user-feedback-system spec 预占的 `V30__feedback.sql`）THEN THE 迁移脚本 SHALL 改用大于目录内全部已存在版本号且未被占用的最小版本号（如 `V31__user_invite.sql`），且 SHALL 不与任何已存在迁移文件的版本号相同、SHALL 不修改其它 spec 的迁移文件内容。
17. THE 迁移脚本 SHALL 不回填任何存量用户的 `users.invite_code`（存量用户的邀请码依需求 1 在首次请求邀请信息时惰性生成）；迁移执行完成后，迁移前已存在的全部 `users` 行的 `invite_code` SHALL 均为 NULL，且 `invite_relations` 表行数 SHALL 为 0。
18. WHEN 应用在已成功执行本 spec 迁移脚本的数据库上再次启动 THEN THE Flyway 迁移 SHALL 不重复执行该脚本，`flyway_schema_history` 中该版本号的记录数 SHALL 保持为 1，且 `users.invite_code` 与 `invite_relations` 的数据 SHALL 不被修改。
19. THE 邀请系统 SHALL 在插入邀请关系前于应用层校验 `inviter_id` 在 `users` 表中存在（因 `invite_relations` 已无外键兜底）；IF 该校验未通过 THEN THE 邀请系统 SHALL 不执行该插入语句，且 SHALL 以未绑定原因 `CODE_NOT_FOUND` 完成本次登录/注册并签发令牌。

### 需求 10：账号注销与邀请数据

**用户故事：** 作为运营者，我希望注销不丢历史：谁带来谁一律留痕，注销只影响能不能登录，不影响这条邀请链路还在不在。

#### 验收标准

1. WHEN 用户注销账号 THEN THE 邀请系统 SHALL 保留以该用户 id 为 `inviter_id` 的全部邀请关系行、SHALL 不对这些行执行任何删除语句；注销事务提交后这些行的行数 SHALL 与注销前相同，且其 `invite_id`、`inviter_id`、`invitee_id`、`register_time`、`status` 与 `created_at` 取值 SHALL 与注销前相同（邀请人注销 SHALL 不改变任何行的 `status`）。
2. WHEN 用户注销账号 AND `invite_relations` 中存在以该用户 id 为 `invitee_id` 的行 THEN THE 邀请系统 SHALL 在同一注销事务内、且在删除该用户的 `users` 行之前把该行的 `status` 置为 `INVALID`、把 `updated_at` 置为服务端当前时刻，SHALL 使该更新语句的影响行数至多为 1，SHALL 不删除该行（依需求 9 第 6 条 `invitee_id` 不建外键的约定），且 SHALL 保持该行的 `invite_id`、`inviter_id`、`invitee_id`、`register_time` 与 `created_at` 取值不变。
3. WHEN 注销用户同时是若干行的 `inviter_id` 与某一行的 `invitee_id` THEN THE 邀请系统 SHALL 在同一注销事务内按「把以其为 `invitee_id` 的行置为 `INVALID` → 删除该 `users` 行」的顺序执行；事务提交后以该用户 id 为 `invitee_id` 的行数 SHALL 为 1 且该行 `status` 为 `INVALID`，以该用户 id 为 `inviter_id` 的行数 SHALL 与注销前相同且这些行的 `status` SHALL 均不变。
4. WHEN 用户注销账号 THEN THE 邀请系统 SHALL 在注销事务内随 `users` 行删除释放该用户的 `invite_code`：事务提交后 `users` 表中 `invite_code` 等于该邀请码的行数 SHALL 为 0；WHEN 后续生成邀请码时抽取到的候选码等于该已释放的邀请码 THEN THE 邀请系统 SHALL 成功写入该候选码、SHALL 不触发 `uk_users_invite_code` 唯一约束冲突，且 SHALL 不返回 `INVITE_CODE_GEN_FAILED`。
5. IF 注销过程中邀请数据的删除或状态更新失败 THEN THE 邀请系统 SHALL 回滚整个注销事务并返回表明注销失败的错误；回滚后该用户 `users` 行的 `id`、`email`、`wx_openid`、`nickname` 与 `invite_code` SHALL 与注销前相同，其名下每一行 `invite_relations` 的 `invite_id`、`inviter_id`、`invitee_id`、`register_time`、`status`、`created_at` 与 `updated_at` SHALL 与注销前相同，且该用户在注销前持有的有效令牌 SHALL 仍可成功请求邀请信息接口。
6. IF 注销的前置校验未通过（`AccountDeletionService.requireDeletable` 因协作牵连抛出 `DELETE_BLOCKED_COLLAB`，或 `AccountDeletionService.verifySecondFactor` 的二次验证未通过）THEN THE 邀请系统 SHALL 不对 `invite_relations` 表执行任何删除或更新语句，且 SHALL 使该用户的 `users.invite_code` 与 `invite_relations` 表全部行的列取值保持请求前的状态。
7. WHEN 某邀请人的一个被邀请人注销后该邀请人请求邀请信息与被邀请人列表 THEN THE 邀请系统 SHALL 返回等于该被邀请人注销前已邀请人数减 1 的已邀请人数、SHALL 返回与注销前相同的邀请关系总条数，并 SHALL 在被邀请人列表中仍返回该行且其 `status` 为 `INVALID`。
8. IF 被邀请人昵称因其账号已注销而无法取得 THEN THE 邀请系统 SHALL 以空值返回该昵称字段、SHALL 返回成功结果、SHALL 返回该列表项 `invite_id`、`register_time` 与 `status` 三个字段的真实取值，且 SHALL 不使用占位文本替代。
9. WHEN 某邀请人注销后有请求以其原邀请码查询邀请人展示信息 THEN THE 邀请系统 SHALL 返回 `NOT_FOUND`；WHEN 登录/注册请求携带该已释放且尚未被重新占用的邀请码 THEN THE 邀请系统 SHALL 以未绑定原因 `CODE_NOT_FOUND` 完成本次登录/注册并签发令牌。
10. WHEN 邀请人注销后 THEN THE 邀请系统 SHALL 使以该用户 id 为 `inviter_id` 的邀请关系行不再可通过任何已认证接口被读取（该邀请人已无法登录，且需求 8 第 3 条把数据范围限定为令牌用户本人），且 SHALL 使这些行仅供后台统计使用；WHEN 该邀请码被后续新用户重新占用 THEN THE 邀请系统 SHALL 不使这些历史行出现在该新用户的邀请信息与被邀请人列表响应中（数据归属以 `inviter_id` 判定，与邀请码取值无关）。
