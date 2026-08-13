# Implementation Plan: 留存轻触达（retention-nudges）

## Overview

纯前端（miniapp）增量功能，无后端改动。先落 `src/utils` 下不依赖 uni API 的纯逻辑（节流决策、accept 过滤、模板映射、总是保持解析）并配属性测试，再做 `requestSubscribe` 编排（uni.*），随后复用到提醒设置页、接到三个高意愿时刻，最后做添加入口引导（home 低打扰 + me 常驻入口）。属性/单元测试用 vitest + fast-check，只覆盖纯逻辑；`wx.*` 交互与页面跳转按既有约定归手工验收。每个任务引用其对应需求条款。

实现语言：**JavaScript（uni-app / Vue 3）**，与设计一致。不新增后端接口 / 表，不发送任何订阅消息。

## Tasks

- [x] 1. 基础纯逻辑与本地记忆键
  - [x] 1.1 在 `utils/config.js` 的 `STORAGE_KEYS` 扩充节流记忆键
    - 新增 `addGuideState`（添加引导记忆）与 `grantPromptState`（高意愿授权节流记忆），集中管理、不散落魔法字符串
    - _Requirements: 5.3_

  - [x] 1.2 新建 `utils/subscribe.js` 的纯函数与模板映射
    - `pickAcceptedTemplates(res, requestedTmplIds)`：只返回「结果为 `accept` 且属于请求集」的模板 id，输出 ⊆ 请求集，`reject`/`ban`/未请求一律排除
    - `isAlwaysKeep(subscriptionsSetting, tmplId)`：仅当该模板明确处于「总是保持已允许」时返回真，缺失 / 主开关关 / 项缺失 / 非 `accept` 一律返回假（安全默认）
    - `TEMPLATE_REPORTERS`：由 `WX_REMINDER_TEMPLATE_ID` / `WX_BUDGET_REMINDER_TEMPLATE_ID` 组装「模板 id → 授权上报函数」映射，未知模板无映射；上报器复用 `api/reminder.js` 的 `grantReminderQuota` 与 `api/budgetReminder.js` 的 `grantBudgetReminderQuota`
    - _Requirements: 2.3, 2.4, 3.1, 3.5, 4.3, 4.4_

  - [x] 1.3 新建 `utils/nudge.js` 的节流纯函数与记忆封装
    - `shouldShowAddGuide(state, nowMs)`：已永久关闭→假；从未展示→真；否则距上次展示达 `ADD_GUIDE_MIN_INTERVAL_DAYS` 才为真
    - `shouldShowGrantPrompt(state, nowMs)`：距上次明确拒绝未过 `GRANT_PROMPT_REJECT_COOLDOWN_DAYS`→假；否则（未关闭）真
    - `readNudgeState(key)` / `writeNudgeState(key, patch)`：`uni.getStorageSync`/`setStorageSync` 薄封装，异常吞掉返回默认
    - _Requirements: 1.3, 1.4, 2.6, 1.7, 5.4_

  - [ ]* 1.4 纯逻辑属性 / 单元测试（vitest + fast-check）
    - **Property 1: 添加引导节流单调正确** — **Validates: Requirements 1.3, 1.4**
    - **Property 2: 高意愿授权入口的拒绝冷却** — **Validates: Requirements 2.6**
    - **Property 3: 只上报 accept 且属于本次请求集** — **Validates: Requirements 2.4**
    - **Property 4: 模板→上报器映射一致** — **Validates: Requirements 2.4, 4.3**
    - **Property 5: 「总是保持」解析的安全默认** — **Validates: Requirements 3.1, 3.3, 3.5**
    - **Property 6: 每模板每次上报次数恒为 1 且合法** — **Validates: Requirements 4.4**
    - 每条属性单独 `fast-check` 用例、`numRuns` ≥ 200，注释标注 `// Feature: retention-nudges, Property N: ...`；生成器覆盖阈值边界、缺失记忆、accept/reject/ban/未请求混合、已知/未知模板、subscriptionsSetting 各缺失/异常形态；辅以边界值 EXAMPLE / EDGE_CASE 单测

- [x] 2. 订阅授权编排（uni.*）
  - [x] 2.1 在 `utils/subscribe.js` 实现 `requestSubscribe(tmplIds)`
    - 须在调用方点击回调内调用；`tmplIds` 去空去重、最多 3 个
    - 无 `wx.requestSubscribeMessage`（H5/非微信）→ 直接 resolve `{accepted:[]}`，不报错
    - 调 `wx.requestSubscribeMessage({tmplIds})`，回调里对 `pickAcceptedTemplates` 结果逐模板调 `TEMPLATE_REPORTERS[id](1)` 上报（每模板 grantedCount 恒 1）；`reject`/`ban` 不报；单模板上报失败只吞不影响其它；全程 try/catch 静默、不进错误态
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.7, 4.4, 5.1_

- [x] 3. Checkpoint - 纯逻辑与编排就绪
  - Ensure unit/property tests pass and `npx uni build -p mp-weixin` compiles; ask the user if questions arise.

- [x] 4. 提醒设置页复用统一编排
  - [x] 4.1 重构 `pages/reminder/reminder.vue` 复用 `utils/subscribe.js`
    - 把既有记账提醒、预算提醒两处 `wx.requestSubscribeMessage`+上报替换为统一 `requestSubscribe`，保留两个区块各自的用户可见语义、开关与「去授权/再次授权」交互不回归
    - 对「总是保持」模板可无感续额（点击触发），不改设置页展示语义
    - _Requirements: 4.1, 4.2, 4.3, 3.2, 3.3, 3.4_

- [ ] 5. 高意愿时刻授权入口接入
  - [ ] 5.1 记账保存成功后的「开启提醒」入口
    - 在记账保存成功反馈处提供由点击触发的「开启提醒」入口；`shouldShowGrantPrompt` 控制是否出现；点击调 `requestSubscribe([REMINDER, BUDGET])`；拒绝写冷却记忆、至多一次轻提示、不进错误态；未登录/H5 安全降级
    - _Requirements: 2.1, 2.2, 2.5, 2.6, 2.7_

  - [x] 5.2 预算设置成功 / 月报查看后的「开启提醒」入口（已在预算页落地）
    - 在预算设置成功、月度账单查看后至少其一处接同款点击触发入口，复用 `requestSubscribe` 与 `shouldShowGrantPrompt` 节流
    - _Requirements: 2.1, 2.2, 2.6_

- [x] 6. 添加入口引导
  - [x] 6.1 home 低打扰添加引导
    - `onShow` 且已登录且微信环境且 `shouldShowAddGuide` 为真时，以低打扰卡片/浮层展示「添加到我的小程序 / 桌面」说明性引导（含操作路径指引，不承诺一键添加）；「知道了/不再提示」写 `addGuideState` 记忆；不打断首用关键流程；H5 不展示
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.7_

  - [x] 6.2 me 常驻教程入口
    - 在「我的」页新增常驻「添加到桌面 / 我的小程序」教程入口，关闭自动提示的用户仍可主动查看；入口不展示任何金额/账本名/邮箱/邀请码
    - _Requirements: 1.6, 5.5_

- [x] 7. Final checkpoint - 两端构建与手工验收
  - `npx uni build -p mp-weixin` 与 `VITE_API_BASE=/api npm run build:h5` 均通过；对照设计「手工验收清单」自查添加引导/高意愿授权/总是保持/设置页不回归；ask the user if questions arise.

## Notes

- 标 `*` 的子任务为可选（属性 / 单元测试）；纯逻辑实现任务不标可选。
- 每个任务引用其对应需求条款，便于追溯。
- 属性测试用 vitest + fast-check，逐条对齐设计的 6 条正确性属性；`wx.*` 交互、弹窗、跳转按既有约定归手工验收，不自动化。
- 纯增量、零后端改造：不新增/改动任何后端接口与表，不发送任何微信订阅消息（只请求授权 + 上报），全路径静默降级。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["1.4", "2.1"] },
    { "id": 2, "tasks": ["3"] },
    { "id": 3, "tasks": ["4.1", "5.1", "5.2", "6.1", "6.2"] },
    { "id": 4, "tasks": ["7"] }
  ]
}
```
