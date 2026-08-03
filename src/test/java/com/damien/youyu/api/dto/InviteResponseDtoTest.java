package com.damien.youyu.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.damien.youyu.service.InviteInfoView;
import com.damien.youyu.service.InviteeItemView;
import com.damien.youyu.service.InviteeListView;

/**
 * 邀请接口响应 DTO 的映射与字段集单元测试（关联需求 1.10、3.1、7.4、7.8、8.3、8.5）。
 *
 * <p>字段集断言用的是<strong>相等</strong>而非包含：多一个字段就可能把「指定目标用户」或
 * 「邀请人账号标识」这类不该出的东西带到线上。</p>
 */
class InviteResponseDtoTest {

    /** 任何邀请响应 DTO 都不得出现的字段名（需求 7.8、8.3、8.5）。 */
    private static final List<String> FORBIDDEN = List.of(
            "userId", "inviterId", "inviteeId", "targetUserId", "id",
            "email", "wxOpenid", "wxUnionid", "inviteCode");

    private static List<String> componentsOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    @Test
    void inviteInfoResponseHasExactlyThreeFieldsAndMapsView() {
        assertThat(componentsOf(InviteInfoResponse.class))
                .containsExactly("inviteCode", "inviteLink", "invitedCount");

        InviteInfoResponse response = InviteInfoResponse.from(
                new InviteInfoView("K7M2Q9XT", "/pages/invitelanding/invitelanding?code=K7M2Q9XT", 3L));
        assertThat(response.inviteCode()).isEqualTo("K7M2Q9XT");
        assertThat(response.inviteLink()).isEqualTo("/pages/invitelanding/invitelanding?code=K7M2Q9XT");
        assertThat(response.invitedCount()).isEqualTo(3L);
    }

    @Test
    void qrCodeResponseHasOnlyImageBase64() {
        assertThat(componentsOf(InviteQrCodeResponse.class)).containsExactly("imageBase64");
        assertThat(InviteQrCodeResponse.of("iVBORw0KG").imageBase64()).isEqualTo("iVBORw0KG");
    }

    @Test
    void inviteeListResponseMapsItemsAndKeepsBothCounts() {
        assertThat(componentsOf(InviteeListResponse.class))
                .containsExactly("items", "total", "invitedCount");
        assertThat(componentsOf(InviteeItemResponse.class))
                .containsExactly("inviteId", "nickname", "registerTime", "status");

        LocalDateTime registerTime = LocalDateTime.of(2025, 3, 1, 10, 30);
        InviteeListResponse response = InviteeListResponse.from(new InviteeListView(
                List.of(new InviteeItemView(7L, "小林同学", registerTime, "REGISTERED"),
                        new InviteeItemView(6L, null, registerTime.minusDays(1), "INVALID")),
                2L, 1L));

        assertThat(response.total()).isEqualTo(2L);
        assertThat(response.invitedCount()).isEqualTo(1L);
        assertThat(response.items()).containsExactly(
                new InviteeItemResponse(7L, "小林同学", registerTime, "REGISTERED"),
                new InviteeItemResponse(6L, null, registerTime.minusDays(1), "INVALID"));
    }

    @Test
    void inviterBriefResponseHasOnlyNicknameAndKeepsNull() {
        assertThat(componentsOf(InviterBriefResponse.class)).containsExactly("nickname");
        assertThat(InviterBriefResponse.of(null).nickname()).isNull();
        assertThat(InviterBriefResponse.of("小林同学").nickname()).isEqualTo("小林同学");
    }

    @Test
    void noInviteResponseDtoExposesForbiddenFields() {
        List<Class<?>> dtos = List.of(
                InviteQrCodeResponse.class,
                InviteeListResponse.class,
                InviteeItemResponse.class,
                InviterBriefResponse.class);
        for (Class<?> dto : dtos) {
            assertThat(componentsOf(dto))
                    .as("%s 不得暴露指定目标用户或账号标识的字段", dto.getSimpleName())
                    .doesNotContainAnyElementsOf(FORBIDDEN);
        }
        // InviteInfoResponse 是唯一允许出现 inviteCode 的 DTO（那是当前用户自己的码，需求 1.10）。
        assertThat(componentsOf(InviteInfoResponse.class))
                .doesNotContainAnyElementsOf(FORBIDDEN.stream()
                        .filter(name -> !"inviteCode".equals(name))
                        .toList());
    }
}
