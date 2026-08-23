package com.jason.notifyline.monitor.domain;

/**
 * {@code extract_rules} 陣列裡的一條規則：JsonPointer 位置 + 模板引用用的名稱。
 *
 * <p>純值物件，供之後波次（W2b 的 {@code JsonExtractor} / {@code MessageTemplate}）使用。
 * {@link ApiMonitor#getExtractRules()} 本波次仍是 JSONB 原文的 {@code String}
 * ——見該欄位註解：反序列化與驗證留給實際使用它的波次負責，這裡先把型別定出來
 * 讓後續波次不用重新設計格式。
 *
 * @param name    模板佔位符引用的鍵，如 {@code {{value.status}}} 中的 {@code status}
 *                （限 {@code [A-Za-z0-9_]{1,32}}，驗證由使用端負責）
 * @param pointer RFC 6901 JsonPointer，例如 {@code /data/0/status}
 */
public record ExtractRule(String name, String pointer) {
}
