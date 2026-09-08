package com.jason.notifyline.monitor.login;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 一組 Cognito user pool 的連線座標。見 {@code Docs/plan/15-監控站台登入設計.md} §6.2。
 *
 * <h2>使用者填不出 host —— 這是安全邊界</h2>
 *
 * <p>端點<strong>由這個類別自己組出來</strong>（{@code https://cognito-idp.{region}.amazonaws.com/}），
 * 不接受使用者填任何 URL。三個欄位都以嚴格的正規式驗證，所以無論使用者在後台輸入
 * 什麼，都組不出指向別處的 host。
 *
 * <p>這也是為什麼這條路徑<strong>不需要</strong>經過
 * {@link com.jason.notifyline.monitor.fetch.OutboundUrlGuard}：那道防線擋的是「使用者
 * 可控的目標網址」，而這裡根本不存在使用者可控的 host。反過來說，<strong>絕不可</strong>
 * 因為方便就加一個「自訂端點」欄位——那會把這個保證整個拆掉。
 *
 * @param region     AWS region，如 {@code eu-west-2}
 * @param userPoolId 如 {@code eu-west-2_FhQHPoX2z}
 * @param clientId   app client id，如 {@code 1h3khfsa958g8qa0gge2dnqvka}
 */
public record CognitoEndpoint(String region, String userPoolId, String clientId) {

    private static final Pattern REGION = Pattern.compile("^[a-z]{2}-[a-z]+-[1-9]$");
    private static final Pattern USER_POOL_ID = Pattern.compile("^[a-z]{2}-[a-z]+-[1-9]_[A-Za-z0-9]+$");
    private static final Pattern CLIENT_ID = Pattern.compile("^[a-z0-9]{1,64}$");

    public CognitoEndpoint {
        require(REGION, region, "region");
        require(USER_POOL_ID, userPoolId, "userPoolId");
        require(CLIENT_ID, clientId, "clientId");

        // user pool id 自帶 region 前綴，兩者不一致代表設定填錯了。放過去只會在
        // 登入時得到看不懂的 ResourceNotFoundException，不如在這裡就講清楚。
        if (!userPoolId.startsWith(region + "_")) {
            throw new IllegalArgumentException(
                    "userPoolId 的 region 前綴與 region 不一致: " + userPoolId + " vs " + region);
        }
    }

    private static void require(Pattern pattern, String value, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 格式不正確: " + value);
        }
    }

    /** {@code eu-west-2_FhQHPoX2z} → {@code FhQHPoX2z}，簽章要用的就是這一段。 */
    public String poolName() {
        return CognitoSrp.poolNameOf(userPoolId);
    }

    public URI uri() {
        return URI.create("https://cognito-idp." + region + ".amazonaws.com/");
    }
}
