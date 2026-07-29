package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.TransactionTemplate;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.TransactionTemplateRepository;

/**
 * 记账模板服务：常用记账形态的保存、列出与删除。
 *
 * <p>模板保存类型/分类/账户/金额/备注，记一笔时一键套用预填表单，本身不产生流水、不影响余额。</p>
 *
 * <ul>
 *   <li>校验：模板名去空白后 1-50（否则 {@code TEMPLATE_FIELD_INVALID}）；类型须为
 *       expense/income/transfer；金额若给出则最多两位小数且 [0.01, 上限]；备注 <=200。</li>
 *   <li>账户/分类引用不做存在性强校验（被引用对象删除后模板仍存在，套用时前端兜底）。</li>
 * </ul>
 *
 * <p>所有操作按会话 {@code ledgerId} 隔离：读取/删除他人模板一律 {@code NOT_FOUND}（需求 2.3、2.4）。</p>
 */
@Service
public class TransactionTemplateService {

    static final int NAME_MAX = 50;
    static final int NOTE_MAX = 200;
    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    private final TransactionTemplateRepository templateRepository;
    private final Clock clock;

    public TransactionTemplateService(TransactionTemplateRepository templateRepository, Clock clock) {
        this.templateRepository = templateRepository;
        this.clock = clock;
    }

    /** 列出某账本全部模板（按排序、id 升序）。 */
    @Transactional(readOnly = true)
    public List<TransactionTemplate> list(Long ledgerId) {
        return templateRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId);
    }

    /**
     * 新建一个记账模板。
     *
     * @throws ApiException TEMPLATE_FIELD_INVALID（模板名/类型/金额/备注任一非法）
     */
    @Transactional
    public TransactionTemplate create(
            Long userId,
            Long ledgerId,
            String rawName,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            String rawNote) {
        String name = validateName(rawName);
        String type = validateType(rawType);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);

        LocalDateTime now = LocalDateTime.now(clock);
        TransactionTemplate t = new TransactionTemplate();
        t.setUserId(userId);
        t.setLedgerId(ledgerId);
        t.setName(name);
        t.setType(type);
        t.setAmount(amount);
        t.setNote(note);
        // 按类型保留相关引用，清空无关引用，保证套用形态干净。
        if (TransactionType.TRANSFER.getCode().equals(type)) {
            t.setSourceAccountId(sourceAccountId);
            t.setDestinationAccountId(destinationAccountId);
        } else {
            t.setAccountId(accountId);
            t.setCategoryId(categoryId);
        }
        t.setSortOrder((int) templateRepository.countByLedgerId(ledgerId));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return templateRepository.save(t);
    }

    /**
     * 删除一个模板。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long ledgerId, Long id) {
        TransactionTemplate t = templateRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("模板不存在"));
        templateRepository.delete(t);
    }

    // ---------------- 校验 ----------------

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw ApiException.templateFieldInvalid("name", "模板名长度需为 1 到 50 个字符");
        }
        return name;
    }

    private String validateType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw ApiException.templateFieldInvalid("type", "类型不能为空");
        }
        try {
            return TransactionType.fromCode(rawType.trim()).getCode();
        } catch (IllegalArgumentException ex) {
            throw ApiException.templateFieldInvalid("type", "不支持的交易类型");
        }
    }

    private BigDecimal validateAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            return null; // 金额可空
        }
        BigDecimal normalized;
        try {
            normalized = rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.templateFieldInvalid("amount", "金额最多两位小数");
        }
        if (normalized.compareTo(AMOUNT_MIN) < 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.templateFieldInvalid("amount",
                    "金额需在 0.01 至 9,999,999,999,999,999.99 之间");
        }
        return normalized;
    }

    private String validateNote(String rawNote) {
        if (rawNote == null) {
            return null;
        }
        String note = rawNote.trim();
        if (note.isEmpty()) {
            return null;
        }
        if (note.length() > NOTE_MAX) {
            throw ApiException.templateFieldInvalid("note", "备注最多 200 个字符");
        }
        return note;
    }
}
