package com.jason.notifyline.monitor.login;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 從 JWT 讀出 {@code exp}，只為了決定快取何時失效。
 *
 * <h2>刻意不驗簽</h2>
 *
 * <p>這裡不做任何授權判斷——token 拿去給目標 API，簽章由<strong>對方</strong>驗。
 * 我們唯一關心的是「什麼時候該去換一個新的」。為此下載 JWKS、做簽章驗證，
 * 是拿密碼學複雜度換一個我們本來就不依賴的保證。
 *
 * <p>最壞情況也只是快取時間算錯：算太長 → 拿過期 token 撞一次 401 → 下一輪重登；
 * 算太短 → 多登入幾次。兩者都不是安全問題。
 */
public final class JwtExpiry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtExpiry() {
    }

    /** 解析失敗一律回 empty，由呼叫端套用保守的退路壽命。 */
    public static Optional<Instant> parse(String jwt) {
        if (jwt == null) {
            return Optional.empty();
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode exp = node.get("exp");
            if (exp == null || !exp.isNumber()) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochSecond(exp.asLong()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
