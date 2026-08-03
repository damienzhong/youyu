package com.damien.youyu.service;

/**
 * 邀请信息视图：邀请码、邀请链接、已邀请人数。
 *
 * <p>字段<strong>是且仅是</strong>这三个（需求 1.10）：不含被邀请人明细、不含邀请人自身的
 * {@code email} / {@code wx_openid} / {@code wx_unionid}，也不含任何用于指定目标用户的字段
 * （需求 7.8、8.3）。</p>
 *
 * @param inviteCode   当前用户的 8 位邀请码，非空（为空时已在服务层惰性补齐）
 * @param inviteLink   {@code /pages/invitelanding/invitelanding?code={邀请码}}（需求 2.1）
 * @param invitedCount 已邀请人数：{@code inviter_id = 当前用户} 且 {@code status = REGISTERED}
 *                     的关系条数，≥ 0（需求 7.6）
 */
public record InviteInfoView(String inviteCode, String inviteLink, long invitedCount) {
}
