package com.jason.notifyline.monitor.domain;

/**
 * {@code computed_fields[].steps} 陣列裡的一段：演算法 + 這一段自己的輸出編碼。
 *
 * <p><strong>{@code encoding} 是每一段各自的屬性，不是整體最後才套用一次</strong>——
 * 這是從真實站台反推出來的規格：雙重 MD5，且中間那次也要轉大寫，大寫發生在第一次
 * 雜湊「之後」、第二次「之前」，會改變第二次雜湊的輸入。單一演算法 + 單一輸出編碼
 * 的設計沒辦法表達這件事。見 {@code Docs/plan/13-監控計算欄位設計.md} §1、§2.2。
 *
 * @param algorithm 這一段使用的演算法
 * @param encoding  這一段輸出的編碼；串接時就是下一段的輸入，最後一段就是整個計算
 *                  欄位的值
 * @param keySecret {@code algorithm} 是 {@code HMAC_*} 時必填：{@code monitor_secret.name}，
 *                  指定用哪個 secret 當 HMAC 金鑰。非 HMAC 演算法必須是 {@code null}，
 *                  驗證由使用端（{@code ComputedFieldValidator}）負責
 */
public record ComputedStep(HashAlgorithm algorithm, HashEncoding encoding, String keySecret) {
}
