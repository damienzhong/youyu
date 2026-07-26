package com.damien.youyu.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 交易类型枚举 &lt;-&gt; 小写数据库编码(expense/income/transfer) 转换器。
 */
@Converter(autoApply = true)
public class TransactionTypeConverter implements AttributeConverter<TransactionType, String> {

    @Override
    public String convertToDatabaseColumn(TransactionType attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public TransactionType convertToEntityAttribute(String dbData) {
        return TransactionType.fromCode(dbData);
    }
}
