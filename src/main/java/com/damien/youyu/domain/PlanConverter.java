package com.damien.youyu.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 套餐枚举 &lt;-&gt; 小写数据库编码(free/pro/lifetime) 转换器。
 */
@Converter(autoApply = true)
public class PlanConverter implements AttributeConverter<Plan, String> {

    @Override
    public String convertToDatabaseColumn(Plan attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public Plan convertToEntityAttribute(String dbData) {
        return Plan.fromCode(dbData);
    }
}
