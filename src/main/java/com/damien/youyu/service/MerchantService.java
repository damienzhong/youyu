package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Merchant;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.MerchantRepository;

/**
 * 商家服务：交易对方/商户（如「星巴克」「盒马」）的增删改查与归属校验。
 *
 * <p>校验：商家名去空白后 1-50（否则 {@code MERCHANT_NAME_INVALID}）。所有操作按会话 {@code ledgerId}
 * 隔离：读取/修改/删除他人商家一律 {@code NOT_FOUND}（需求 2.3、2.4）。</p>
 */
@Service
public class MerchantService {

    static final int NAME_MAX = 50;

    private final MerchantRepository merchantRepository;
    private final Clock clock;

    public MerchantService(MerchantRepository merchantRepository, Clock clock) {
        this.merchantRepository = merchantRepository;
        this.clock = clock;
    }

    /** 列出某账本全部商家。 */
    @Transactional(readOnly = true)
    public List<Merchant> list(Long ledgerId) {
        return merchantRepository.findByLedgerIdOrderBySortOrderAscIdAsc(ledgerId);
    }

    /**
     * 校验某商家属于该账本并返回；不匹配抛 NOT_FOUND。merchantId 为 null 直接返回 null（无商家）。
     */
    @Transactional(readOnly = true)
    public Merchant requireInLedgerOrNull(Long ledgerId, Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        return merchantRepository.findByIdAndLedgerId(merchantId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("商家不存在"));
    }

    /**
     * 新建商家（同名幂等复用：已存在同名则直接返回）。
     *
     * @throws ApiException MERCHANT_NAME_INVALID
     */
    @Transactional
    public Merchant create(Long userId, Long ledgerId, String rawName) {
        String name = validateName(rawName);
        return merchantRepository.findFirstByLedgerIdAndName(ledgerId, name)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    Merchant m = new Merchant();
                    m.setUserId(userId);
                    m.setLedgerId(ledgerId);
                    m.setName(name);
                    m.setSortOrder((int) merchantRepository.countByLedgerId(ledgerId));
                    m.setCreatedAt(now);
                    m.setUpdatedAt(now);
                    return merchantRepository.save(m);
                });
    }

    /**
     * 重命名商家。
     *
     * @throws ApiException NOT_FOUND / MERCHANT_NAME_INVALID
     */
    @Transactional
    public Merchant rename(Long ledgerId, Long id, String rawName) {
        Merchant m = merchantRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("商家不存在"));
        m.setName(validateName(rawName));
        m.setUpdatedAt(LocalDateTime.now(clock));
        return merchantRepository.save(m);
    }

    /**
     * 删除商家。关联流水的 {@code merchant_id} 不会自动清空（前端按空值兜底展示）。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long ledgerId, Long id) {
        Merchant m = merchantRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("商家不存在"));
        merchantRepository.delete(m);
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new ApiException("MERCHANT_NAME_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "商家名长度需为 1 到 50 个字符", "name");
        }
        return name;
    }
}
