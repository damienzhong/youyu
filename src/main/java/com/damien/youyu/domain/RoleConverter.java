package com.damien.youyu.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 角色枚举 &lt;-&gt; 小写数据库编码(user/admin) 转换器。
 */
@Converter(autoApply = true)
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        return Role.fromCode(dbData);
    }
}
