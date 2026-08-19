package com.jason.notifyline.client;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link Scope} ↔ 資料庫字串。
 *
 * <p>不能用 {@code @Enumerated(STRING)} —— 資料庫存的是 {@code notify:self}
 * 這種帶冒號的形式，與 enum 常數名 {@code NOTIFY_SELF} 不同。
 * schema 的 CHECK 約束也是照字串形式寫的。
 */
@Converter(autoApply = true)
public class ScopeConverter implements AttributeConverter<Scope, String> {

    @Override
    public String convertToDatabaseColumn(Scope scope) {
        return scope == null ? null : scope.value();
    }

    @Override
    public Scope convertToEntityAttribute(String value) {
        return value == null ? null : Scope.fromValue(value);
    }
}
