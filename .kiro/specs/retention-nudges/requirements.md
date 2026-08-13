# Requirements Document

## Introduction

有余（youyu）是个人主体微信小程序，长期主动触达（push）受平台政策限制、只能用一次性订阅消息，且产品已决定不做邮件、暂不升级主体。因此本 spec 聚焦两件**完全自主、零后端改造**的留存轻触达优化：

1. **添加入口引导（Add_Entry_Guide）**：引导用户把有余「添加到我的小程序 / 添加到桌面」，沉淀一个长期免费的复访入口。
2. **订阅授权策略优化（Subscribe_Grant_Strategy）**：把一次性订阅授权用到极致——在用户高意愿时刻请求、一次批量请求多个模板、对已选「总是保持」的用户顺手续额而不再打扰，让稀缺的订阅额度尽量攒得住。

### 两条微信平台硬约束（构成本 spec 的骨架）

以下平台事实决定了本 spec「能做什么、不能做什么」，验收标准围绕它们展开：

- **「添加到我的小程序 / 桌面」无可编程接口**：小程序<b>不能</b>用代码把自己加入「我的小程序」或手机桌面，只能引导用户自行在微信右上角「···」胶囊菜单里操作。故 Add_Entry_Guide 本质是**「教育引导 + 合适时机 + 本地记忆、不重复打扰」的纯前端能力**，不承诺「一键添加」。
- **`wx.requestSubscribeMessage` 必须在用户点击手势的回调内调用**：<b>不能</b>在页面加载 / `onShow` 时静默发起授权。因此「续额」只能挂在用户本来就会触发的点击动作上；对已勾选「总是保持以上选择」的用户，该调用不再弹窗、可无感完成，但仍须由一次点击触发。

### 范围与前提约定

1. **纯前端、零后端改造。** 复用既有的两个授权上报接口（记账提醒 `POST /api/reminders/quota:grant`、预算提醒 `POST /api/budget-reminders/quota:grant`）。不新增 / 不改动任何后端接口、不新增表、不改后端发送与额度扣减逻辑。
2. **不改「发送侧」策略。** 本 spec 只优化「攒授权」这一侧（前端何时、如何请求授权）；「发得准」（只在高价值事件下发）由既有 budget-reminder / custom-reminder 的发送编排负责，不在本 spec 范围。
3. **绝不打扰过度。** 两类引导 / 请求都须有节制：本地记忆「已处理 / 已拒绝 / 上次展示时间」，不在同一会话或短期内反复弹。
4. **未登录、非微信环境安全降级。** 未登录不做任何引导与授权请求；H5 / 非微信环境无相关 API 时不报错、不进入错误态。
5. **时区口径** `Asia/Shanghai`（涉及「距上次展示天数」等判定时）。

### 与其它 spec 的关系

- **依赖 custom-reminder（已实现）**：复用记账提醒模板 id（`WX_REMINDER_TEMPLATE_ID`）与其授权上报接口。
- **依赖 subscribe-message-reminders（已实现）**：复用预算提醒模板 id（`WX_BUDGET_REMINDER_TEMPLATE_ID`）与其授权上报接口。
- **不改动上述两者的发送行为、额度扣减与数据。**

## Glossary

- **添加入口引导（Add_Entry_Guide）**：引导用户将小程序添加到「我的小程序」或手机桌面的前端提示与教学，含本地记忆的展示节流。
- **订阅授权策略（Subscribe_Grant_Strategy）**：前端在合适时机、以批量模板方式发起 `wx.requestSubscribeMessage`，并把用户点「允许」的结果按模板上报到对应授权接口的整体逻辑。
- **高意愿时刻（high-intent moment）**：用户对「接收提醒」意愿较高的自然操作节点，如记账保存成功、查看月度账单后、设置 / 修改预算后。
- **订阅模板（subscribe template）**：微信一次性订阅消息模板；本 spec 涉及记账提醒模板与预算提醒模板两个。
- **总是保持（always-keep）**：微信订阅授权弹窗中用户勾选「总是保持以上选择」后的状态；此后同模板的授权请求不再弹窗、直接返回上次选择。可经 `wx.getSetting({ withSubscriptions: true })` 的 `subscriptionsSetting` 探知。
- **授权结果（grant result）**：`wx.requestSubscribeMessage` 回调中各模板 id 对应的取值，`accept` 表示本次允许、`reject` / `ban` 表示未允许。
- **本地记忆（local memory）**：存于端上（如 `uni.setStorageSync`）的引导 / 授权节流状态，不落后端。
- **静默降级（silent degrade）**：能力不可用（非微信环境、未登录、接口失败）时不报错、不阻断当前页。

## Requirements

### 需求 1：添加入口引导的呈现与节流

**用户故事：** 作为用户，我希望有个不烦人的提示教我把有余加到「我的小程序 / 桌面」，方便下次快速打开。

#### 验收标准

1. THE Add_Entry_Guide SHALL 提供引导用户将小程序添加到「我的小程序」与「添加到桌面」的说明性提示（含操作路径指引，如指向右上角「···」菜单），SHALL 不声称能一键自动添加。
2. WHERE 运行环境为微信小程序 AND 存在已登录状态 THE Add_Entry_Guide SHALL 在满足展示时机（需求 1.4）时呈现引导；WHERE 运行环境非微信小程序（如 H5）THE Add_Entry_Guide SHALL 不呈现该引导。
3. WHEN 用户对引导执行「知道了 / 关闭 / 不再提示」任一操作 THEN THE Add_Entry_Guide SHALL 记录本地记忆，并 SHALL 在后续满足「不再打扰」条件时不再自动弹出。
4. THE Add_Entry_Guide SHALL 依本地记忆做展示节流：SHALL 不在用户已选择「不再提示」后再自动弹出；SHALL 不在同一次会话内重复自动弹出；SHALL 使两次自动展示之间至少间隔一个可配置的最短天数。
5. WHEN 首次使用或使用尚浅（如尚未建立稳定记账习惯）THEN THE Add_Entry_Guide SHALL 不打断关键首用流程（登录 / 首次记账），SHALL 选择低打扰时机呈现。
6. THE Add_Entry_Guide SHALL 使引导可随时手动查看（如在「我的」页保留一个常驻入口），使关闭自动提示的用户仍能主动找到添加教程。
7. IF 获取或写入本地记忆失败 THEN THE Add_Entry_Guide SHALL 安全降级（至多按默认节流处理），SHALL 不报错、不阻断所在页面。

### 需求 2：高意愿时刻的订阅授权请求

**用户故事：** 作为刚记完一笔 / 刚看完月报的用户，这时候让我开启提醒我更愿意点「允许」。

#### 验收标准

1. THE Subscribe_Grant_Strategy SHALL 在高意愿时刻（记账保存成功、查看月度账单后、设置 / 修改预算后至少其一）提供一个由**用户点击触发**的「开启提醒」入口来发起订阅授权。
2. THE Subscribe_Grant_Strategy SHALL 仅在用户点击手势的回调内调用 `wx.requestSubscribeMessage`，SHALL 不在页面加载 / `onShow` 等非点击时机发起授权请求。
3. WHEN 在高意愿时刻发起授权 THEN THE Subscribe_Grant_Strategy SHALL 一次性请求所需的订阅模板集合（记账提醒模板与预算提醒模板可在一次请求内同时申请，`tmplIds` 至多 3 个，符合微信上限）。
4. WHEN `wx.requestSubscribeMessage` 回调返回 THEN THE Subscribe_Grant_Strategy SHALL 对结果为 `accept` 的每个模板，调用其对应的授权上报接口（记账提醒模板→记账提醒授权接口、预算提醒模板→预算提醒授权接口）上报本次授权；SHALL 不为 `reject` / `ban` 的模板上报。
5. IF `wx.requestSubscribeMessage` 调用失败或用户全部未允许 THEN THE Subscribe_Grant_Strategy SHALL 不上报任何授权、SHALL 不使所在页面进入错误态，且 SHALL 至多给出一次轻提示。
6. THE Subscribe_Grant_Strategy SHALL 对高意愿时刻的授权入口做节流：SHALL 不在用户已明确拒绝后短期内反复弹出该入口，节流状态以本地记忆维护。
7. WHERE 运行环境无 `wx.requestSubscribeMessage`（如 H5）THE Subscribe_Grant_Strategy SHALL 不展示授权入口或点击后给出「请在微信小程序内开启提醒」提示，且 SHALL 不进入错误态。

### 需求 3：对「总是保持」用户的无感续额

**用户故事：** 作为已经勾了「总是保持」的用户，我希望应用能在我正常使用时顺手把提醒额度续上，别再反复弹窗问我。

#### 验收标准

1. THE Subscribe_Grant_Strategy SHALL 能经 `wx.getSetting({ withSubscriptions: true })` 的 `subscriptionsSetting` 判定某订阅模板是否处于「总是保持」的已允许状态。
2. WHEN 用户在某个点击动作中触发续额 AND 目标模板处于「总是保持」已允许状态 THEN THE Subscribe_Grant_Strategy SHALL 通过 `wx.requestSubscribeMessage` 完成无弹窗续额，并 SHALL 按需求 2.4 上报本次授权。
3. THE Subscribe_Grant_Strategy SHALL 不因续额尝试而向「未勾选总是保持」的用户额外弹出授权弹窗（即无感续额只作用于已「总是保持」的模板）。
4. THE Subscribe_Grant_Strategy SHALL 使续额仍受微信「一次点击触发」约束：SHALL 不在无用户点击的情况下发起续额，SHALL 不在页面加载时自动续额。
5. IF `wx.getSetting` 或 `withSubscriptions` 在当前环境不可用 THEN THE Subscribe_Grant_Strategy SHALL 安全降级为「按普通授权入口处理」，SHALL 不报错、不阻断页面。
6. THE Subscribe_Grant_Strategy SHALL 不在页面上向用户暴露其订阅授权的内部状态明细（如各模板 accept/reject 原始值），仅以「已开启 / 去开启」等用户可理解的表述呈现。

### 需求 4：与既有提醒设置页的一致与不回归

**用户故事：** 作为用户，我在「记账提醒」设置页里的开关与授权行为不应因为这次优化而变乱。

#### 验收标准

1. THE Subscribe_Grant_Strategy SHALL 保留提醒设置页既有的授权入口与行为（记账提醒、预算提醒各自的开关与「去授权 / 再次授权」），SHALL 不改变其既有交互结果。
2. WHERE 在提醒设置页发起授权 THE Subscribe_Grant_Strategy SHALL 可复用批量请求与按模板上报的统一逻辑，但 SHALL 不改变用户可见的每个区块的语义（记账提醒区块仍对应记账提醒、预算提醒区块仍对应预算提醒）。
3. THE Subscribe_Grant_Strategy SHALL 复用既有的授权上报 API 模块（`api/reminder.js`、`api/budgetReminder.js`），SHALL 不新增第二套授权上报通道。
4. THE Subscribe_Grant_Strategy SHALL 不改变后端两个授权接口的请求 / 响应结构与语义（仍上报 `grantedCount` 且取值 1~5）。

### 需求 5：兼容与降级边界

**用户故事：** 作为开发者，我要确认这次优化是纯增量的：移除后一切照旧，且不误触发任何后端副作用。

#### 验收标准

1. THE retention-nudges SHALL 不新增 / 不改动任何后端接口、数据库表与列，SHALL 不触发任何微信订阅消息的**发送**（本 spec 只请求授权与上报，不发送消息）。
2. WHEN 引导与授权策略整体从前端移除 THEN THE 记账、提醒设置、预算提醒、记账提醒系统 SHALL 保持其响应与行为不变。
3. THE retention-nudges SHALL 使所有新增本地记忆键集中管理（如并入既有 `STORAGE_KEYS`），SHALL 不散落魔法字符串。
4. THE retention-nudges SHALL 在未登录、非微信环境、接口失败、本地存储失败等任一异常路径下静默降级，SHALL 不阻断任何所在页面的其余功能。
5. THE retention-nudges SHALL 不在任何引导或授权提示中展示金额、账本名、邮箱与邀请码。
