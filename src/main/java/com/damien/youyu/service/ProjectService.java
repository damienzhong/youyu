package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Project;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.ProjectRepository;

/**
 * 项目服务：把若干流水归到一个「项目/事件」下（如装修、旅行）。提供增删改查与归属校验。
 *
 * <p>校验：项目名去空白后 1-50（否则 {@code PROJECT_NAME_INVALID}）。所有操作按会话 {@code ledgerId}
 * 隔离：读取/修改/删除他人项目一律 {@code NOT_FOUND}（需求 2.3、2.4）。</p>
 */
@Service
public class ProjectService {

    static final int NAME_MAX = 50;

    private final ProjectRepository projectRepository;
    private final Clock clock;

    public ProjectService(ProjectRepository projectRepository, Clock clock) {
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    /** 列出某账本全部项目（未归档优先）。 */
    @Transactional(readOnly = true)
    public List<Project> list(Long ledgerId) {
        return projectRepository.findByLedgerIdOrderByArchivedAscSortOrderAscIdAsc(ledgerId);
    }

    /**
     * 校验某项目属于该账本并返回；不匹配抛 NOT_FOUND。projectId 为 null 直接返回 null（无项目）。
     * 供交易创建/修改时校验 project_id 归属。
     */
    @Transactional(readOnly = true)
    public Project requireInLedgerOrNull(Long ledgerId, Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findByIdAndLedgerId(projectId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
    }

    /**
     * 新建项目。
     *
     * @throws ApiException PROJECT_NAME_INVALID
     */
    @Transactional
    public Project create(Long userId, Long ledgerId, String rawName) {
        String name = validateName(rawName);
        LocalDateTime now = LocalDateTime.now(clock);
        Project p = new Project();
        p.setUserId(userId);
        p.setLedgerId(ledgerId);
        p.setName(name);
        p.setArchived(false);
        p.setSortOrder((int) projectRepository.countByLedgerId(ledgerId));
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return projectRepository.save(p);
    }

    /**
     * 重命名 / 归档切换。{@code archived} 为 null 时不改归档状态。
     *
     * @throws ApiException NOT_FOUND / PROJECT_NAME_INVALID
     */
    @Transactional
    public Project update(Long ledgerId, Long id, String rawName, Boolean archived) {
        Project p = projectRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (rawName != null) {
            p.setName(validateName(rawName));
        }
        if (archived != null) {
            p.setArchived(archived);
        }
        p.setUpdatedAt(LocalDateTime.now(clock));
        return projectRepository.save(p);
    }

    /**
     * 删除项目。关联流水的 {@code project_id} 不会自动清空（历史流水仍带原 id，但项目已不存在，
     * 前端按空值兜底展示）；如需保留一致性可先归档。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long ledgerId, Long id) {
        Project p = projectRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        projectRepository.delete(p);
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new ApiException("PROJECT_NAME_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "项目名长度需为 1 到 50 个字符", "name");
        }
        return name;
    }
}
