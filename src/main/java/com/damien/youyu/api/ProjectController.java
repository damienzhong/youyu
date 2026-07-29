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

import com.damien.youyu.api.dto.ProjectResponse;
import com.damien.youyu.api.dto.ProjectSaveRequest;
import com.damien.youyu.domain.Project;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.ProjectService;

/**
 * 项目接口。
 *
 * <p>身份由 Spring Security 统一鉴权，所有读写按会话 ledgerId 隔离（越权返回 404，需求 2.2-2.4）。</p>
 *
 * <ul>
 *   <li>GET {@code /api/projects} 列出本账本项目（未归档优先）。</li>
 *   <li>POST {@code /api/projects} 新建（201）。</li>
 *   <li>PUT {@code /api/projects/{id}} 重命名/归档切换。</li>
 *   <li>DELETE {@code /api/projects/{id}} 删除（204）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projectService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.projectService = projectService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 列出本账本项目。 */
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        List<ProjectResponse> body = projectService.list(ledgerId).stream()
                .map(ProjectResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** 新建项目：成功返回 201。 */
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@RequestBody ProjectSaveRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        Project p = projectService.create(userId, ledgerId, req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(p));
    }

    /** 重命名/归档切换。 */
    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable Long id, @RequestBody ProjectSaveRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Project p = projectService.update(ledgerId, id, req.name(), req.archived());
        return ResponseEntity.ok(ProjectResponse.from(p));
    }

    /** 删除项目：成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        projectService.delete(ledgerId, id);
        return ResponseEntity.noContent().build();
    }
}
