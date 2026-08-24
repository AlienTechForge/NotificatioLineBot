package com.jason.notifyline.monitor.domain;

/**
 * {@link ComputedStep} 一段雜湊輸出（同時也是下一段輸入，或整個計算欄位最終值）的
 * 編碼方式。見 {@code Docs/plan/13-監控計算欄位設計.md} §2.2。
 */
public enum HashEncoding {
    HEX_UPPER,
    HEX_LOWER,
    BASE64
}
