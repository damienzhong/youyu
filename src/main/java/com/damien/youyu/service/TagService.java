package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Tag;
import com.damien.youyu.domain.TransactionTag;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionTagRepository;

/**
 * 标签服务：自由标签的增删改查、归属校验，以及交易-标签关联的读写（多对多）。
 *
 * <p>校验：标签名去空白后 1-30（否则 {@code TAG_NAME_INVALID}）。所有操作按会话 {@code ledgerId}
 * 隔离：读取/修改/删除他人标签一律 {@code NOT_FOUND}（需求 2.3、2.4）。</p>
 */
@Service
public class TagService {

    static final int NAME_MAX = 30;

    private final TagRepository tagRepository;
    private final TransactionTagRepository transactionTagRepository;
    private final Clock clock;

    public TagService(TagRepository tagRepository,
            TransactionTagRepository transactionTagRepository, Clock clock) {
        this.tagRepository = tagRepository;
        this.transactionTagRepository = transactionTagRepository;
        this.clock = clock;
    }

    /** 列出某账本全部标签。 */
    @Transactional(readOnly = true)
    public List<Tag> list(Long ledgerId) {
        return tagRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId);
    }

    /**
     * 新建标签（同名幂等复用）。
     *
     * @throws ApiException TAG_NAME_INVALID
     */
    @Transactional
    public Tag create(Long userId, Long ledgerId, String rawName) {
        String name = validateName(rawName);
        return tagRepository.findFirstByLedgerIdAndName(ledgerId, name)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    Tag t = new Tag();
                    t.setUserId(userId);
                    t.setLedgerId(ledgerId);
                    t.setName(name);
                    t.setSortOrder((int) tagRepository.countByLedgerId(ledgerId));
                    t.setCreatedAt(now);
                    t.setUpdatedAt(now);
                    return tagRepository.save(t);
                });
    }

    /**
     * 重命名标签。
     *
     * @throws ApiException NOT_FOUND / TAG_NAME_INVALID
     */
    @Transactional
    public Tag rename(Long ledgerId, Long id, String rawName) {
        Tag t = tagRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("标签不存在"));
        t.setName(validateName(rawName));
        t.setUpdatedAt(LocalDateTime.now(clock));
        return tagRepository.save(t);
    }

    /**
     * 删除标签：同时清除其在所有交易上的关联。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long ledgerId, Long id) {
        Tag t = tagRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("标签不存在"));
        // 清除该标签在所有交易上的关联。
        transactionTagRepository.deleteByTagId(id);
        tagRepository.delete(t);
    }

    /**
     * 校验一组标签 id 均属于该账本，返回去重后的有效 id 列表；空/ null 返回空列表。
     * 任一 id 不属于该账本即抛 NOT_FOUND。
     */
    @Transactional(readOnly = true)
    public List<Long> validateTagIds(Long ledgerId, Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>(tagIds);
        List<Tag> found = tagRepository.findByLedgerIdAndIdIn(ledgerId, unique);
        if (found.size() != unique.size()) {
            throw ApiException.notFound("标签不存在");
        }
        return new ArrayList<>(unique);
    }

    /**
     * 覆盖设置某交易的标签关联为给定集合（先清后建）。调用方须已校验 tagIds 归属本账本。
     */
    @Transactional
    public void setTransactionTags(Long transactionId, Collection<Long> tagIds) {
        transactionTagRepository.deleteByTransactionId(transactionId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>(tagIds);
        List<TransactionTag> rows = new ArrayList<>(unique.size());
        for (Long tagId : unique) {
            rows.add(new TransactionTag(transactionId, tagId));
        }
        transactionTagRepository.saveAll(rows);
    }

    /** 清除某交易的全部标签关联（删除交易时调用）。 */
    @Transactional
    public void clearTransactionTags(Long transactionId) {
        transactionTagRepository.deleteByTransactionId(transactionId);
    }

    /** 某交易的标签 id 列表。 */
    @Transactional(readOnly = true)
    public List<Long> tagIdsOf(Long transactionId) {
        return transactionTagRepository.findByTransactionId(transactionId).stream()
                .map(TransactionTag::getTagId)
                .toList();
    }

    /** 一批交易的 交易id→标签id列表 映射（列表批量取标签，避免 N+1）。 */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> tagIdsMap(Collection<Long> transactionIds) {
        Map<Long, List<Long>> map = new LinkedHashMap<>();
        if (transactionIds == null || transactionIds.isEmpty()) {
            return map;
        }
        for (TransactionTag tt : transactionTagRepository.findByTransactionIdIn(transactionIds)) {
            map.computeIfAbsent(tt.getTransactionId(), k -> new ArrayList<>()).add(tt.getTagId());
        }
        return map;
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new ApiException("TAG_NAME_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "标签名长度需为 1 到 30 个字符", "name");
        }
        return name;
    }
}
