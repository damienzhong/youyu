# Requirements Document

## Introduction

有余（youyu）当前没有任何用户反馈通道：用户遇到 Bug、想提功能建议、或想联系开发者时，只能通过应用外的渠道，
开发者也无法在系统内跟踪处理进度。本次新增**用户反馈系统**，打通「用户提交 → 开发者处理 → 用户看到回复」的闭环：

- **用户端（miniapp「我的」页）**：新增「意见反馈」入口，可选择反馈类型（Bug 反馈 / 功能建议 / 使用体验 / 联系开发者）、
  输入文字、上传截图、填写可选联系方式；可在「我的反馈」列表查看自己历史反馈的状态与开发者回复。
- **管理端（web 后台）**：管理员可按类型/状态筛选查看全部反馈、逐条回复、标记为已解决。
- **服务端（Spring Boot）**：新增 `feedback` 表与反馈接口；新增**图片上传能力**（系统当前无任何文件上传/静态资源托管），
  截图以对象键数组形式存储在 `feedback.images`。

范围约定：反馈是**用户对产品的单向工单**，一条反馈至多一条开发者回复（不做多轮会话）；不做邮件/站内推送通知
（用户主动进入「我的反馈」查看回复）。

## Glossary

- **反馈（Feedback）**：用户提交的一条工单记录，对应 `feedback` 表一行，主键 `feedback_id`。
- **反馈类型（type）**：取值 `BUG`（Bug 反馈）、`FEATURE`（功能建议）、`EXPERIENCE`（使用体验）、`CONTACT`（联系开发者）。
- **反馈状态（status）**：取值 `PENDING`（待处理）、`REPLIED`（已回复）、`RESOLVED`（已解决）。
- **联系方式（contact）**：用户自愿填写的邮箱或其它联系文本，可空，仅供开发者回访使用。
- **截图（image）**：用户上传的图片附件；服务端返回**图片对象键**（相对存储路径），`feedback.images` 保存对象键的 JSON 数组。
- **图片对象键（objectKey）**：图片在服务端存储中的唯一相对标识（例如 `feedback/2026/03/{uuid}.png`），用于拼接访问地址。
- **管理员（Admin）**：`users.role = 'admin'` 的用户（沿用现有 `Role` 枚举）。
- **反馈中心**：用户端「我的反馈」列表页，展示当前用户自己提交的全部反馈及其状态与回复。
- **反馈后台**：web 管理端的反馈管理页面，仅管理员可访问。
- **反馈系统（Feedback_System）**：本 spec 涉及的服务端反馈接口与业务逻辑整体。
- **图片服务（Image_Service）**：本 spec 新增的图片上传、存储与访问鉴权组件。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌。

## Requirements

### 需求 1：提交反馈（类型 + 文字 + 截图 + 联系方式）

**用户故事：** 作为用户，我希望在「我的」页面找到「意见反馈」入口，选择反馈类型并写下问题、附上截图，这样开发者能理解我遇到的情况。

#### 验收标准

1. THE 反馈系统 SHALL 提供已登录用户提交反馈的接口，接收 `type`、`content`、`images`、`contact` 四个输入字段，其中 `type` 与 `content` 为必填字段，`images` 与 `contact` 为可选字段，`images` 缺失时按空列表处理、`contact` 缺失时按空值处理。
2. WHEN 提交反馈 AND `type` 属于 `BUG`/`FEATURE`/`EXPERIENCE`/`CONTACT` AND `content` 去空白后长度为 1 到 1000 个字符
   THEN THE 反馈系统 SHALL 创建一条反馈，写入 `user_id` 为当前会话用户、`status` 为 `PENDING`、`created_time` 为服务端当前时刻、`updated_time` 等于 `created_time`、`reply_content` 与 `replied_at` 为空值，并返回该反馈的 `feedback_id`（正整数）、`status` 与 `created_time`。
3. IF `type` 缺失、为空值、或其取值（区分大小写）不在 `BUG`/`FEATURE`/`EXPERIENCE`/`CONTACT` 四种类型之内 THEN THE 反馈系统 SHALL 拒绝提交并返回 `FEEDBACK_TYPE_INVALID`，且 SHALL 保持 `feedback` 表数据不变（不新增任何行）。
4. IF `content` 缺失、为空值、去空白后为空、或去空白后长度超过 1000 个字符（长度按 Unicode 码点计数，中文字符与 emoji 各计 1 个字符）THEN THE 反馈系统 SHALL 拒绝提交并返回 `FEEDBACK_CONTENT_INVALID`，且 SHALL 保持 `feedback` 表数据不变（不新增任何行）。
5. IF 提交反馈携带的 `contact` 去空白后长度超过 128 个字符 THEN THE 反馈系统 SHALL 拒绝提交并返回 `FEEDBACK_CONTACT_INVALID`，且 SHALL 保持 `feedback` 表数据不变（不新增任何行）。
6. WHERE `contact` 缺失、为空值或去空白后为空 THE 反馈系统 SHALL 以 `contact` 为空值创建反馈，且 SHALL 不返回任何联系方式校验错误（联系方式为可选项）。
7. WHEN 创建反馈 THEN THE 反馈系统 SHALL 对 `content` 与 `contact` 裁剪首尾空白字符（空格、制表符、换行、回车）后再持久化，持久化值 SHALL 不以空白字符开头或结尾。
8. IF 提交请求未携带令牌、或所携带令牌无效或已过期 THEN THE 反馈系统 SHALL 返回 `UNAUTHENTICATED`，且 SHALL 保持 `feedback` 表数据不变（不新增任何行）。
9. IF 提交请求携带的令牌校验通过但其标识的用户在 `users` 表中不存在（例如账号已注销）THEN THE 反馈系统 SHALL 返回 `UNAUTHENTICATED`，且 SHALL 保持 `feedback` 表数据不变（不新增任何行）。
10. WHEN 提交反馈 THEN THE 反馈系统 SHALL 在服务端处理耗时不超过 2000 毫秒内返回成功结果或错误标识（不含网络传输与截图上传耗时）。

### 需求 2：截图上传

**用户故事：** 作为用户，我希望把出问题的界面截图上传上去，这样开发者不用我用文字描述整个界面。

#### 验收标准

1. THE 图片服务 SHALL 提供已登录用户上传单张图片的接口，单次请求接收 `multipart/form-data` 中恰好 1 个文件，并在成功时返回该图片的**图片对象键**与可访问地址；该访问地址在通过需求 9 的访问权限校验后 SHALL 返回与上传字节完全一致的图片内容。
2. WHEN 上传图片 AND 请求声明的 MIME 类型属于 `image/jpeg`、`image/png`、`image/webp` AND 依据文件内容识别出的实际图片格式与该声明类型一致 AND 文件字节数为 1 到 5,242,880 字节（5 MB = 5 × 1024 × 1024）
   THEN THE 图片服务 SHALL 以随机生成、全局唯一的文件名（不沿用客户端提交的原始文件名，扩展名与校验通过的图片格式一致）将该文件持久化到服务端图片存储，记录其上传者为当前会话用户，并返回其图片对象键；同一用户连续上传内容完全相同的两个文件 SHALL 得到两个不同的对象键。
3. IF 请求声明的 MIME 类型不属于 `image/jpeg`、`image/png`、`image/webp`，或依据文件内容识别出的实际图片格式不属于这三者，或两者不一致 THEN THE 图片服务 SHALL 拒绝上传并返回 `IMAGE_TYPE_UNSUPPORTED`，且 SHALL 不写入任何文件、保持图片存储内容不变。
4. IF 上传文件字节数超过 5,242,880 字节 THEN THE 图片服务 SHALL 拒绝上传并返回 `IMAGE_TOO_LARGE`，且 SHALL 保持图片存储内容不变；字节数恰为 5,242,880 的文件 SHALL 视为合法。
5. IF 图片写入存储过程中失败，或单个文件的写入耗时超过 30 秒 THEN THE 图片服务 SHALL 返回 `IMAGE_UPLOAD_FAILED`，SHALL 删除本次已写入的部分文件，并使该对象键在后续反馈提交与图片访问中均视为不存在。
6. WHEN 提交反馈 AND `images` 中的对象键数量为 0 到 3 个 AND 这些对象键互不重复 THEN THE 反馈系统 SHALL 将这些对象键按用户提交顺序保存到 `feedback.images`，且后续读取该反馈时返回的对象键数量与顺序 SHALL 与提交时一致。
7. IF 提交反馈时 `images` 中的对象键数量超过 3 个 THEN THE 反馈系统 SHALL 拒绝提交并返回 `FEEDBACK_IMAGE_TOO_MANY`，且 SHALL 保持 `feedback` 表数据不变。
8. IF 提交反馈时 `images` 中存在图片存储中不存在、已被删除、或上传者记录不等于当前会话用户的对象键 THEN THE 反馈系统 SHALL 拒绝提交并返回 `IMAGE_NOT_FOUND`，且 SHALL 保持 `feedback` 表数据不变。
9. WHEN 上传请求未携带有效令牌 THEN THE 图片服务 SHALL 返回 `UNAUTHENTICATED`，且 SHALL 保持图片存储内容不变。
10. IF 上传请求未包含文件，或文件字节数为 0 THEN THE 图片服务 SHALL 拒绝上传并返回 `IMAGE_EMPTY`，且 SHALL 保持图片存储内容不变。
11. IF 提交反馈时 `images` 中存在重复的对象键 THEN THE 反馈系统 SHALL 拒绝提交并返回 `FEEDBACK_IMAGE_INVALID`，且 SHALL 保持 `feedback` 表数据不变。
12. WHERE 图片已上传但尚未被任何反馈引用 THE 图片服务 SHALL 自上传成功时刻起至少保留该图片 24 小时，在此期间该对象键 SHALL 可被同一用户的反馈提交成功引用。

### 需求 3：截图列表的存储与序列化

**用户故事：** 作为开发者，我希望截图列表在数据库里以稳定的结构存取，这样读出来的图片顺序和数量始终与用户提交的一致。

#### 验收标准

1. THE 反馈系统 SHALL 将截图对象键列表以 JSON 字符串数组形式序列化后存入 `feedback.images` 列，其中数组元素个数为 0 到 3，每个元素为去空白后长度 1 到 255 个字符的图片对象键字符串，元素顺序与用户提交顺序一致，且 SHALL 不去重、不重排、不写入 null 元素。
2. WHEN 读取某条 `feedback.images` 为验收标准 1 所写入内容的反馈 THEN THE 反馈系统 SHALL 返回元素个数、元素取值与元素顺序三者均与写入时完全一致的对象键列表。
3. WHERE 反馈没有截图 THE 反馈系统 SHALL 将 `feedback.images` 存为空 JSON 数组 `[]`，并在读取时返回长度为 0 的截图列表。
4. IF `feedback.images` 的内容不是合法 JSON、不是 JSON 数组、包含非字符串元素、包含去空白后为空的元素、或元素个数超过 3 THEN THE 反馈系统 SHALL 返回长度为 0 的截图列表、记录一条可定位到该反馈的告警日志、并正常返回该反馈的其余字段（读取不因脏数据而失败），且 SHALL 不修改 `feedback.images` 的原始内容。
5. WHEN 返回反馈详情或反馈列表项 THEN THE 反馈系统 SHALL 按对象键在 `feedback.images` 中的存储顺序，为每个对象键生成一个可访问的图片地址，返回的地址个数与对象键个数相同且顺序一致。
6. IF 提交反馈时 `images` 中存在非字符串元素、null 元素、去空白后为空的元素、或去空白后长度超过 255 个字符的元素 THEN THE 反馈系统 SHALL 拒绝提交并返回 `IMAGE_NOT_FOUND`，且 SHALL 保持 `feedback` 表数据不变。
7. IF 读取到的 `feedback.images` 为 NULL 或去空白后为空字符串 THEN THE 反馈系统 SHALL 返回长度为 0 的截图列表，且 SHALL 不记录告警日志（视同无截图，而非脏数据）。

### 需求 4：提交防刷

**用户故事：** 作为开发者，我希望反馈接口不被刷爆，这样存储和我的处理队列不会被无效数据淹没。

#### 验收标准

1. IF 提交反馈的请求已通过身份认证 AND 该用户最近一次**成功创建**的反馈的 `created_time` 距服务端当前时刻不足 60 秒
   THEN THE 反馈系统 SHALL 拒绝本次提交并返回 `FEEDBACK_COOLDOWN`，且 SHALL 在响应中返回距可再次提交的剩余秒数（1 到 60 的整数）。
2. IF 提交反馈的请求已通过身份认证 AND 该用户在「服务端当前时刻往前 24 小时」滑动窗口内**成功创建**的反馈数已达到 20 条
   THEN THE 反馈系统 SHALL 拒绝本次提交（即第 21 条及以后）并返回 `FEEDBACK_RATE_LIMITED`。
3. IF 上传图片的请求已通过身份认证 AND 该用户在「服务端当前时刻往前 24 小时」滑动窗口内**成功上传**的图片数已达到 60 张
   THEN THE 图片服务 SHALL 拒绝本次上传（即第 61 张及以后）并返回 `IMAGE_RATE_LIMITED`，且 SHALL 保持图片存储内容不变。
4. IF 提交因冷却或 24 小时限额被拒绝 THEN THE 反馈系统 SHALL 保持 `feedback` 表数据不变，且 SHALL 不改变该用户的冷却起点与 24 小时窗口计数（被拒绝的尝试不消耗额度、不延长冷却）。
5. THE 反馈系统 SHALL 以 `user_id` 作为限流统计维度：同一用户在全部设备与会话共享同一冷却计时与同一 24 小时额度，窗口边界一律以服务端时刻计算；反馈行被删除 SHALL 不减少已统计的窗口计数。
6. WHEN 一次反馈提交请求同时违反认证、限流与字段校验中的多项约束 THEN THE 反馈系统 SHALL 按 `UNAUTHENTICATED` → `FEEDBACK_COOLDOWN` → `FEEDBACK_RATE_LIMITED` → 字段校验类错误码的固定顺序，只返回首个命中的错误码。

### 需求 5：用户查看自己的反馈与回复

**用户故事：** 作为用户，我希望能看到我提过的反馈现在处理到哪一步、开发者回了什么，这样我知道我的声音被听见了。

#### 验收标准

1. WHEN 已认证用户请求自己的反馈列表 THEN THE 反馈系统 SHALL 仅返回 `user_id` 等于当前会话用户的反馈，按 `created_time` 倒序排列、`created_time` 相同时按 `feedback_id` 倒序排列，支持分页参数 `page`（整数，从 0 起，缺省 0）与 `size`（整数，1 到 50，缺省 20），并在响应中返回当前用户反馈的总条数。
2. WHEN 返回反馈列表项或详情 THEN THE 反馈系统 SHALL 包含 `feedback_id`、`type`、`content`（完整内容，不截断）、截图地址列表（0 到 3 个，顺序与 `feedback.images` 存储顺序一致）、`status`、`created_time`、回复内容与回复时间，以及本人填写的 `contact`（未填写时为空值）。
3. IF 某条反馈的 `reply_content` 尚未被保存过 THEN THE 反馈系统 SHALL 以空值返回回复内容与回复时间两个字段，且 SHALL 不省略这两个字段。
4. WHEN 用户请求某条反馈详情 AND 该反馈的 `user_id` 等于当前会话用户 THEN THE 反馈系统 SHALL 返回验收标准 2 所列的全部字段。
5. IF 用户请求的 `feedback_id` 在 `feedback` 表中不存在，或其 `user_id` 不等于当前会话用户 THEN THE 反馈系统 SHALL 对两种情形返回完全相同的 `NOT_FOUND` 响应，且响应中 SHALL 不包含任何反馈字段值（不泄漏他人反馈是否存在）。
6. IF 请求的 `page` 小于 0，或 `size` 小于 1 或大于 50 THEN THE 反馈系统 SHALL 拒绝该请求并返回 `FEEDBACK_PAGE_PARAM_INVALID`，且 SHALL 不返回任何列表数据。
7. WHEN 当前用户没有任何反馈，或请求的页码超出已有数据范围 THEN THE 反馈系统 SHALL 返回空列表与真实总条数，且 SHALL 不返回错误。
8. WHEN 用户在 miniapp「我的」页面点击「意见反馈」入口 THEN THE miniapp SHALL 进入反馈中心，提供新建反馈的入口与当前用户的反馈列表。
9. WHILE 用户停留在反馈中心 THE miniapp SHALL 首屏展示最近 20 条反馈，支持上拉加载至已加载条数等于总条数为止，并为每条反馈展示与 `PENDING`/`REPLIED`/`RESOLVED` 一一对应的状态文案；有回复的反馈 SHALL 同时展示回复内容与回复时间，无回复的反馈 SHALL 不展示回复区域。

### 需求 6：管理员查看反馈列表

**用户故事：** 作为开发者（管理员），我希望在后台按类型和状态筛选查看全部反馈，这样我能优先处理待处理的 Bug。

#### 验收标准

1. WHEN 管理员请求反馈列表 THEN THE 反馈系统 SHALL 返回全部用户的反馈，按 `created_time` 倒序排列、`created_time` 相同时按 `feedback_id` 倒序排列，支持分页参数 `page`（整数，从 0 起，缺省 0）与 `size`（整数，1 到 100，缺省 20）。
2. WHERE 请求携带合法的 `type` 筛选参数 THE 反馈系统 SHALL 仅返回该类型的反馈；未携带 `type` 时 SHALL 不按类型过滤。
3. WHERE 请求携带合法的 `status` 筛选参数 THE 反馈系统 SHALL 仅返回该状态的反馈；未携带 `status` 时 SHALL 不按状态过滤。
4. WHEN 返回管理端列表项 THEN THE 反馈系统 SHALL 在需求 5 验收标准 2 所列字段之外，额外包含提交者的 `user_id`、昵称与 `contact`，供开发者回访使用。
5. THE 反馈系统 SHALL 在管理端列表响应中返回符合筛选条件的总条数，该总条数 SHALL 不受 `page` 与 `size` 影响；无匹配数据时 SHALL 返回空列表与总条数 0，且不返回错误。
6. WHEN 管理员请求某条存在的反馈详情 THEN THE 反馈系统 SHALL 返回该反馈的 `feedback_id`、`type`、`content`、全部截图地址、`status`、`created_time`、回复内容与回复时间、提交者 `user_id`、昵称与 `contact`，不受提交者身份限制。
7. IF 管理端请求的 `feedback_id` 在 `feedback` 表中不存在 THEN THE 反馈系统 SHALL 返回 `NOT_FOUND`。
8. IF 请求携带的 `type` 取值不属于 `BUG`/`FEATURE`/`EXPERIENCE`/`CONTACT` THEN THE 反馈系统 SHALL 拒绝该请求并返回 `FEEDBACK_TYPE_INVALID`，且 SHALL 不返回任何列表数据。
9. IF 请求携带的 `status` 取值不属于 `PENDING`/`REPLIED`/`RESOLVED` THEN THE 反馈系统 SHALL 拒绝该请求并返回 `FEEDBACK_STATUS_INVALID`，且 SHALL 不返回任何列表数据。
10. WHERE 请求同时携带 `type` 与 `status` THE 反馈系统 SHALL 按两者的逻辑「与」关系过滤，且总条数与返回列表基于同一筛选条件计算。
11. WHERE 提交者昵称或 `contact` 未填写 THE 反馈系统 SHALL 以空值返回该字段，且 SHALL 不使用占位文本替代。
12. THE web 管理端 SHALL 提供反馈管理页面，包含类型筛选控件（全部 + 4 种类型）、状态筛选控件（全部 + 3 种状态）、分页控件、总条数展示，以及每条反馈进入详情执行回复与标记状态的入口；筛选结果为空时 SHALL 展示空状态提示。

### 需求 7：管理员回复反馈

**用户故事：** 作为开发者（管理员），我希望对一条反馈写回复，这样提交者能在应用里看到我的答复。

#### 验收标准

1. WHEN 管理员对 `status` 为 `PENDING` 或 `REPLIED` 的反馈提交回复 AND 回复文本去空白后长度为 1 到 1000 个 Unicode 字符
   THEN THE 反馈系统 SHALL 在同一事务内写入首尾空白裁剪后的回复文本到 `reply_content`、写入 `replied_at` 为服务端当前时刻、写入 `replied_by` 为当前会话管理员的 `user_id`、更新 `updated_time` 为服务端当前时刻，并将该反馈 `status` 置为 `REPLIED`。
2. IF 回复文本去空白后为空或长度超过 1000 个 Unicode 字符 THEN THE 反馈系统 SHALL 拒绝该回复并返回 `FEEDBACK_REPLY_INVALID`，且 SHALL 保持该反馈的 `content`、`reply_content`、`replied_at`、`replied_by` 与 `status` 全部不变。
3. WHEN 管理员对已有回复的反馈再次提交回复 THEN THE 反馈系统 SHALL 以新回复文本覆盖 `reply_content`、以本次服务端当前时刻覆盖 `replied_at`、以当前会话管理员的 `user_id` 覆盖 `replied_by`（一条反馈至多一条回复），且 SHALL 保持该反馈的 `type`、`content`、`images`、`contact` 与 `created_time` 不变。
4. WHEN 管理员对 `status` 为 `RESOLVED` 的反馈提交回复 AND 回复文本去空白后长度为 1 到 1000 个 Unicode 字符 THEN THE 反馈系统 SHALL 按验收标准 1 写入 `reply_content`、`replied_at`、`replied_by` 与 `updated_time`，并保持 `status` 为 `RESOLVED`（已解决不因回复而回退）。
5. IF 回复目标 `feedback_id` 在 `feedback` 表中不存在 THEN THE 反馈系统 SHALL 返回 `NOT_FOUND`，且 SHALL 保持 `feedback` 表数据不变。
6. THE 反馈系统 SHALL 提供仅管理员可调用的回复接口，接收 `feedback_id` 与回复文本两个输入字段，并在保存成功时返回该反馈保存后的回复内容、回复时间与 `status`。
7. WHEN 回复保存成功后提交者请求该反馈的详情或自己的反馈列表 THEN THE 反馈系统 SHALL 返回与本次保存值一致的回复内容与回复时间。
8. IF 回复写入持久化过程中失败 THEN THE 反馈系统 SHALL 回滚本次事务并返回表明回复保存失败的错误响应，且 SHALL 保持该反馈的 `reply_content`、`replied_at`、`replied_by` 与 `status` 为写入前的取值。

### 需求 8：标记已解决

**用户故事：** 作为开发者（管理员），我希望把处理完的反馈标记为已解决，这样我的待处理队列只剩下真正要做的事。

#### 验收标准

1. WHEN 管理员对 `status` 为 `PENDING` 或 `REPLIED` 的反馈执行标记已解决 THEN THE 反馈系统 SHALL 在同一事务内把该反馈 `status` 置为 `RESOLVED`、把 `updated_time` 置为服务端当前时刻，并保持 `content`、`images`、`contact`、`reply_content`、`replied_at`、`replied_by`、`created_time` 不变，且在响应中返回该反馈更新后的 `status`。
2. WHEN 管理员对 `status` 已为 `RESOLVED` 的反馈再次执行标记已解决 THEN THE 反馈系统 SHALL 返回成功且响应中 `status` 为 `RESOLVED`，并保持该反馈的全部列（含 `updated_time`、`reply_content`、`replied_at`、`replied_by`）与本次操作前完全一致（幂等）。
3. WHEN 管理员对 `status` 为 `RESOLVED` 的反馈执行重新打开 THEN THE 反馈系统 SHALL 在 `reply_content` 去空白后非空时把 `status` 置为 `REPLIED`、在 `reply_content` 为空或空白时把 `status` 置为 `PENDING`，同时把 `updated_time` 置为服务端当前时刻，并保持 `reply_content`、`replied_at`、`replied_by`、`content`、`images`、`contact`、`created_time` 不变。
4. THE 反馈系统 SHALL 把 `status` 的可写入取值限制为 `PENDING`、`REPLIED`、`RESOLVED` 三者之一。
5. IF 标记已解决或重新打开的目标 `feedback_id` 在 `feedback` 表中不存在 THEN THE 反馈系统 SHALL 返回 `NOT_FOUND`，且 SHALL 保持 `feedback` 表数据不变。
6. WHEN 管理员对 `status` 已为 `PENDING` 或 `REPLIED` 的反馈执行重新打开 THEN THE 反馈系统 SHALL 保持该反馈的全部列与本次操作前完全一致，并在响应中返回其当前 `status`（幂等）。
7. IF 状态变更请求携带的目标状态不属于 `PENDING`、`REPLIED`、`RESOLVED` THEN THE 反馈系统 SHALL 拒绝该请求并返回 `FEEDBACK_STATUS_INVALID`，且 SHALL 保持该反馈的全部列不变。
8. THE web 管理端 SHALL 在反馈详情中提供「标记已解决」与「重新打开」两个操作入口，并在操作返回成功后展示该反馈更新后的 `status`。

### 需求 9：权限边界

**用户故事：** 作为用户，我希望我的反馈内容和联系方式只有我和开发者能看到；作为开发者，我希望管理端接口不被普通用户调用。

#### 验收标准

1. THE 反馈系统 SHALL 要求反馈提交、用户端列表与详情、管理端列表与详情、回复、状态变更、图片上传与图片访问全部接口携带**有效令牌**。
2. IF 请求未携带令牌、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在 THEN THE 反馈系统 SHALL 返回 `UNAUTHENTICATED`（优先于任何权限或字段校验错误），且 SHALL 保持 `feedback` 表数据与图片存储内容不变，响应中 SHALL 不包含任何反馈字段值。
3. IF 已认证用户的 `users.role` 不等于 `admin` AND 该用户调用管理端反馈接口（列表、详情、回复、标记状态）THEN THE 反馈系统 SHALL 返回 `ADMIN_FORBIDDEN`，且 SHALL 保持 `feedback` 表数据不变，响应中 SHALL 不包含任何反馈内容。
4. THE 反馈系统 SHALL 在每次请求时以 `users` 表中该用户的当前 `role` 取值作为管理员判定依据；令牌中的角色声明与数据库取值不一致时 SHALL 以数据库取值为准（沿用现有 `Role` 枚举）。
5. WHEN 用户访问某个图片对象键 AND 该对象键已被某条反馈引用 AND 该反馈的 `user_id` 等于当前会话用户或当前会话用户为管理员 THEN THE 图片服务 SHALL 返回该图片内容。
6. WHEN 用户访问某个图片对象键 AND 该对象键尚未被任何反馈引用 AND 其上传者为当前会话用户或当前会话用户为管理员 THEN THE 图片服务 SHALL 返回该图片内容。
7. IF 访问的图片对象键在图片存储中不存在，或当前会话用户既不是该图片的可见主体也不是管理员 THEN THE 图片服务 SHALL 对两种情形返回完全相同的 `NOT_FOUND` 响应（不泄漏对象键是否存在）。
8. THE 反馈系统 SHALL 仅在管理端响应与提交者本人的响应中返回 `contact` 字段，其余响应 SHALL 不包含该字段；`contact` 未填写时 SHALL 以空值返回。
9. THE 反馈系统 SHALL 把用户端列表与详情接口的数据范围硬性限定为 `user_id` 等于当前会话用户的反馈，管理员调用用户端接口时亦仅返回其本人的反馈。

### 需求 10：数据模型与迁移

**用户故事：** 作为开发者，我需要一张结构清晰的反馈表和对应的迁移脚本，这样反馈数据能与既有 Flyway 迁移体系一致地演进。

#### 验收标准

1. THE 迁移脚本 SHALL 新建 `feedback` 表，包含列 `feedback_id`（BIGINT NOT NULL AUTO_INCREMENT，主键）、`user_id`（BIGINT NOT NULL）、`type`（VARCHAR(16) NOT NULL）、`content`（VARCHAR(1000) NOT NULL）、`images`（VARCHAR(1024) NOT NULL，缺省 `[]`）、`contact`（VARCHAR(128) NULL）、`status`（VARCHAR(16) NOT NULL，缺省 `PENDING`）、`reply_content`（VARCHAR(1000) NULL）、`replied_at`（DATETIME NULL）、`replied_by`（BIGINT NULL）、`created_time`（DATETIME NOT NULL）、`updated_time`（DATETIME NOT NULL）。
2. THE 迁移脚本 SHALL 为 `feedback.user_id` 建立指向 `users(id)` 的具名外键（命名沿用 `fk_<表名>_<关联>` 约定）并设置 ON DELETE CASCADE；删除某个用户行后该 `user_id` 对应的 `feedback` 行数 SHALL 为 0。
3. THE 迁移脚本 SHALL 为 `feedback` 建立两个非唯一复合索引：首列 `status`、次列 `created_time`（支撑管理端按状态筛选后按时间倒序翻页）；首列 `user_id`、次列 `created_time`（支撑用户端本人反馈按时间倒序翻页）。
4. THE 迁移脚本 SHALL 建立具名 CHECK 约束，把 `type` 限制为区分大小写的 `BUG`/`FEATURE`/`EXPERIENCE`/`CONTACT`，把 `status` 限制为区分大小写的 `PENDING`/`REPLIED`/`RESOLVED`。
5. IF 向 `feedback` 写入的 `type` 或 `status` 取值不在上述取值集合内 THEN THE 数据库 SHALL 拒绝该写入语句，且 SHALL 保持 `feedback` 表数据不变。
6. THE 迁移脚本 SHALL 命名为 `V30__feedback.sql`（现有最新版本为 `V29`），且 SHALL 不修改任何已存在的历史迁移文件内容。
7. THE `feedback` 表 SHALL 使用 InnoDB 引擎、`utf8mb4` 字符集与 `utf8mb4_unicode_ci` 排序规则，且 12 个列全部带中文列注释、表本身带中文表注释（对齐 `V27__loan_repayments.sql` 的既有写法）。
8. THE 迁移脚本 SHALL 不为 `replied_by` 建立外键，以便管理员账号被删除后仍保留历史回复人标识。
9. WHEN 创建反馈 THEN THE 反馈系统 SHALL 写入 `created_time` 与 `updated_time` 为同一服务端时刻；WHEN 反馈的任一业务列被更新 THEN THE 反馈系统 SHALL 把 `updated_time` 更新为该次操作的服务端时刻，且 SHALL 不修改 `created_time`。
10. WHEN 应用在已执行 V1 至 V29 的数据库上启动 THEN THE Flyway 迁移 SHALL 成功执行 `V30__feedback.sql` 并在迁移历史中新增版本 30 的成功记录；再次启动 SHALL 不重复执行该脚本。
11. IF `V30__feedback.sql` 执行失败 THEN THE 应用 SHALL 中止启动，且数据库 SHALL 保持迁移前的状态。
12. WHEN 应用以 Hibernate `ddl-auto=validate` 在迁移完成的数据库上启动 THEN THE 应用 SHALL 启动成功且 SHALL 不抛出针对 `feedback` 表的 schema 校验异常。
13. THE 清库脚本 `deploy/reset-db.sql` SHALL 在 `SET FOREIGN_KEY_CHECKS = 0` 与 `SET FOREIGN_KEY_CHECKS = 1` 之间、且在 `TRUNCATE TABLE users` 之前清空 `feedback` 表；脚本执行后 `feedback` 表行数 SHALL 为 0，表结构与 `flyway_schema_history` SHALL 保留。
14. WHEN 用户注销账号 THEN THE 反馈系统 SHALL 在同一事务内删除该用户的全部反馈行，并在事务提交后删除这些反馈引用的截图文件（与既有注销硬删除语义一致）。
15. IF 注销过程中反馈行删除失败 THEN THE 反馈系统 SHALL 回滚整个注销事务并返回表明注销失败的错误；IF 反馈行已删除但截图文件删除失败 THEN THE 反馈系统 SHALL 不回滚已删除的反馈行，SHALL 记录一条包含失败对象键的告警日志，且注销 SHALL 视为成功。
