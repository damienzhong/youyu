package com.damien.youyu.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.LedgerCreateRequest;
import com.damien.youyu.api.dto.LedgerInviteResponse;
import com.damien.youyu.api.dto.LedgerJoinRequest;
import com.damien.youyu.api.dto.LedgerMemberResponse;
import com.damien.youyu.api.dto.LedgerResponse;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.LedgerService;

/**
 * 账本管理接口。账本访问按成员关系隔离：任一成员(OWNER/EDITOR)可读写业务数据，仅 OWNER 可改名/删除/邀请。
 *
 * <ul>
 *   <li>GET    {@code /api/ledgers} 列出可访问账本（自己拥有 + 已加入的协作账本）。</li>
 *   <li>POST   {@code /api/ledgers} 新建账本（INDEPENDENT/COLLABORATIVE）。</li>
 *   <li>PUT    {@code /api/ledgers/{id}} 重命名账本（OWNER）。</li>
 *   <li>DELETE {@code /api/ledgers/{id}} 删除账本（OWNER，级联清空数据）。</li>
 *   <li>POST   {@code /api/ledgers/{id}/invite} 生成邀请码（OWNER，协作账本）。</li>
 *   <li>POST   {@code /api/ledgers/join} 凭邀请码加入协作账本。</li>
 *   <li>GET    {@code /api/ledgers/{id}/members} 列出成员（成员）。</li>
 *   <li>DELETE {@code /api/ledgers/{id}/members/{userId}} 移除成员/退出。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ledgers")
public class LedgerController {

    private final LedgerService ledgerService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    public LedgerController(LedgerService ledgerService, CurrentUser currentUser,
            UserRepository userRepository) {
        this.ledgerService = ledgerService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<LedgerResponse>> list() {
        Long userId = currentUser.requireUserId();
        List<LedgerResponse> body = ledgerService.list(userId).stream()
                .map(l -> LedgerResponse.from(l, ledgerService.roleOf(userId, l.getId())))
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<LedgerResponse> create(@RequestBody LedgerCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Ledger ledger = ledgerService.create(userId, req.name(), req.type());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(LedgerResponse.from(ledger, LedgerMember.ROLE_OWNER));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LedgerResponse> rename(
            @PathVariable Long id, @RequestBody LedgerCreateRequest req) {
        Long userId = currentUser.requireUserId();
        Ledger ledger = ledgerService.rename(userId, id, req.name());
        return ResponseEntity.ok(LedgerResponse.from(ledger, ledgerService.roleOf(userId, id)));
    }

    /** 删除账本（OWNER，级联清空其数据；至少保留一个自己拥有的账本）：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        ledgerService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** OWNER 为协作账本生成邀请码。 */
    @PostMapping("/{id}/invite")
    public ResponseEntity<LedgerInviteResponse> invite(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        return ResponseEntity.ok(LedgerInviteResponse.from(ledgerService.createInvite(userId, id)));
    }

    /** 凭邀请码加入协作账本。 */
    @PostMapping("/join")
    public ResponseEntity<LedgerResponse> join(@RequestBody LedgerJoinRequest req) {
        Long userId = currentUser.requireUserId();
        Ledger ledger = ledgerService.join(userId, req.code());
        return ResponseEntity.ok(LedgerResponse.from(ledger, ledgerService.roleOf(userId, ledger.getId())));
    }

    /** 列出账本成员（成员可见）。 */
    @GetMapping("/{id}/members")
    public ResponseEntity<List<LedgerMemberResponse>> members(@PathVariable Long id) {
        Long userId = currentUser.requireUserId();
        List<LedgerMemberResponse> body = ledgerService.members(userId, id).stream()
                .map(m -> new LedgerMemberResponse(m.getUserId(), displayName(m.getUserId()), m.getRole()))
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 移除成员（OWNER 移除他人）或退出（成员移除自己）。 */
    @DeleteMapping("/{id}/members/{memberUserId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long id, @PathVariable Long memberUserId) {
        Long userId = currentUser.requireUserId();
        ledgerService.removeMember(userId, id, memberUserId);
        return ResponseEntity.noContent().build();
    }

    private String displayName(Long userId) {
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }
}
