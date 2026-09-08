package com.jason.notifyline.monitor.login;

/**
 * 站台登入的種類。目前只有一種，但刻意做成 enum 而不是 boolean——
 * 之後要加別的登入方式（例如某站台的表單登入）時不必改 schema，
 * {@code monitor_login.type} 已經是字串欄位。
 */
public enum LoginType {

    /** AWS Cognito user pool 的 {@code USER_SRP_AUTH} 流程。 */
    COGNITO_SRP
}
