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

import com.damien.youyu.api.dto.TagResponse;
import com.damien.youyu.api.dto.TagSaveRequest;
import com.damien.youyu.domain.Tag;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.TagService;

/**
 * 标签接口。
 *
 * <p>身份由 Spring Security 统一鉴权，所有读写按会话 ledgerId 隔离（越权返回 404，需求 2.2-2.4）。</p>
 *
 * <ul>
 *   <li>GET {@code /api/tags} 列出本账本标签。</li>
 *   <li>POST {@code /api/tags} 新建（同名幂等，201）。</li>
 *   <li>PUT {@code /api/tags/{id}} 重命名。</li>
 *   <li>DELETE {@code /api/tags/{id}} 删除（连带清除交易关联，204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public TagController(TagService tagService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.tagService = tagService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 列出本账本标签。 */
    @GetMapping
    public ResponseEntity<List<TagResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        List<TagResponse> body = tagService.list(ledgerId).stream()
                .map(TagResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 新建标签：成功返回 201。 */
    @PostMapping
    public ResponseEntity<TagResponse> create(@RequestBody TagSaveRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        Tag t = tagService.create(userId, ledgerId, req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(TagResponse.from(t));
    }

    /** 重命名标签。 */
    @PutMapping("/{id}")
    public ResponseEntity<TagResponse> update(
            @PathVariable Long id, @RequestBody TagSaveRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Tag t = tagService.rename(ledgerId, id, req.name());
        return ResponseEntity.ok(TagResponse.from(t));
    }

    /** 删除标签：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        tagService.delete(ledgerId, id);
        return ResponseEntity.noContent().build();
    }
}
