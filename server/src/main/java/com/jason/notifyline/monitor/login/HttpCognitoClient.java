package com.jason.notifyline.monitor.login;

import com.jason.notifyline.monitor.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link CognitoClient} 的實作：兩個端點、純 JSON POST。
 *
 * <h2>為什麼不用 AWS SDK</h2>
 *
 * <p>{@code software.amazon.awssdk:cognitoidentityprovider} 是十幾 MB 的依賴，
 * 換來的是兩個不需要簽章的公開端點（{@code InitiateAuth} / {@code RespondToAuthChallenge}
 * 都是 unauthenticated API，不需要 SigV4）。而且 SDK 也<strong>不含</strong> SRP 的數學
 * ——那部分無論如何都要自己寫（見 {@link CognitoSrp}）。不划算。
 *
 * <h2>絕不記錄的東西</h2>
 *
 * <p>request body 含 {@code PASSWORD_CLAIM_SIGNATURE}，response body 含 token。
 * <strong>兩者都不可進 log</strong>，出錯時只記錄 Cognito 的錯誤型別與 HTTP 狀態。
 */
@Component
public class HttpCognitoClient implements CognitoClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCognitoClient.class);

    private static final String TARGET_HEADER = "X-Amz-Target";
    private static final String INITIATE_AUTH = "AWSCognitoIdentityProviderService.InitiateAuth";
    private static final String RESPOND_TO_CHALLENGE =
            "AWSCognitoIdentityProviderService.RespondToAuthChallenge";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final String PASSWORD_VERIFIER = "PASSWORD_VERIFIER";

    /** Cognito 回應不會大；超過這個大小代表拿到的不是預期的東西。 */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration readTimeout;

    public HttpCognitoClient(MonitorProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.readTimeout = properties.readTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // 與 ApiFetcher 相同的理由：重導向在這裡只可能是異常，不該自動跟隨
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public SrpChallenge initiateSrp(CognitoEndpoint endpoint, String username, String srpAHex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("AuthFlow", "USER_SRP_AUTH");
        body.put("ClientId", endpoint.clientId());
        body.put("AuthParameters", Map.of("USERNAME", username, "SRP_A", srpAHex));

        JsonNode response = post(endpoint, INITIATE_AUTH, body);

        String challengeName = text(response, "ChallengeName");
        if (!PASSWORD_VERIFIER.equals(challengeName)) {
            // MFA、強制改密碼之類。這條路徑無法自動完成，停用比反覆重試好。
            throw new CognitoAuthException(CognitoAuthException.Reason.MFA_REQUIRED,
                    "Cognito 要求的挑戰無法自動處理: " + (challengeName == null ? "（無）" : challengeName));
        }

        JsonNode params = response.get("ChallengeParameters");
        if (params == null) {
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT,
                    "回應缺少 ChallengeParameters");
        }
        return new SrpChallenge(
                required(params, "SALT"),
                required(params, "SRP_B"),
                required(params, "SECRET_BLOCK"),
                required(params, "USER_ID_FOR_SRP"));
    }

    @Override
    public AuthTokens respondToPasswordVerifier(CognitoEndpoint endpoint,
                                                String userIdForSrp,
                                                String secretBlock,
                                                String signature,
                                                String timestamp) {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("USERNAME", userIdForSrp);
        responses.put("PASSWORD_CLAIM_SECRET_BLOCK", secretBlock);
        responses.put("PASSWORD_CLAIM_SIGNATURE", signature);
        responses.put("TIMESTAMP", timestamp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ChallengeName", PASSWORD_VERIFIER);
        body.put("ClientId", endpoint.clientId());
        body.put("ChallengeResponses", responses);

        JsonNode response = post(endpoint, RESPOND_TO_CHALLENGE, body);
        return readTokens(response);
    }

    @Override
    public AuthTokens refresh(CognitoEndpoint endpoint, String refreshToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("AuthFlow", "REFRESH_TOKEN_AUTH");
        body.put("ClientId", endpoint.clientId());
        body.put("AuthParameters", Map.of("REFRESH_TOKEN", refreshToken));

        JsonNode response = post(endpoint, INITIATE_AUTH, body);
        return readTokens(response);
    }

    // ------------------------------------------------------------------ 內部

    /**
     * {@code REFRESH_TOKEN_AUTH} 的回應不含 {@code RefreshToken}（沿用舊的），
     * 所以那個欄位缺席是正常的，不是錯誤。
     */
    private AuthTokens readTokens(JsonNode response) {
        JsonNode result = response.get("AuthenticationResult");
        if (result == null) {
            String challenge = text(response, "ChallengeName");
            throw new CognitoAuthException(CognitoAuthException.Reason.MFA_REQUIRED,
                    "登入未完成，Cognito 要求額外挑戰: "
                            + (challenge == null ? "（回應缺少 AuthenticationResult）" : challenge));
        }
        String idToken = text(result, "IdToken");
        if (idToken == null || idToken.isBlank()) {
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT,
                    "回應缺少 IdToken");
        }
        JsonNode expiresIn = result.get("ExpiresIn");
        return new AuthTokens(idToken, text(result, "RefreshToken"),
                expiresIn == null ? 0 : expiresIn.asInt());
    }

    private JsonNode post(CognitoEndpoint endpoint, String target, Map<String, Object> body) {
        HttpRequest request = HttpRequest.newBuilder(endpoint.uri())
                .timeout(readTimeout)
                .header("Content-Type", CONTENT_TYPE)
                .header(TARGET_HEADER, target)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT,
                    "無法連線到 Cognito", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT, "登入被中斷", e);
        }

        String payload = response.body();
        if (payload != null && payload.length() > MAX_RESPONSE_BYTES) {
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT,
                    "Cognito 回應過大: " + payload.length() + " bytes");
        }
        if (response.statusCode() != 200) {
            throw mapError(response.statusCode(), payload);
        }
        try {
            return objectMapper.readTree(payload);
        } catch (RuntimeException e) {
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT,
                    "Cognito 回應不是合法 JSON", e);
        }
    }

    /**
     * 把 Cognito 的 {@code __type} 對應到我們的失敗類型。
     *
     * <p>對應的關鍵是「這個錯誤重試會不會鎖帳號」，不是 HTTP 狀態碼 ——
     * {@code NotAuthorizedException} 與 {@code TooManyRequestsException} 都是 400 系列，
     * 但一個必須停手、一個應該稍後再來。
     */
    private CognitoAuthException mapError(int status, String payload) {
        String type = "";
        String message = "";
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "{}" : payload);
            type = text(node, "__type") == null ? "" : text(node, "__type");
            message = text(node, "message") == null ? "" : text(node, "message");
        } catch (RuntimeException ignored) {
            // 非 JSON 的錯誤回應（例如 gateway 的 HTML）——當作暫時性
        }

        // 只記類型與狀態碼。message 可能含使用者名稱，body 可能含 token。
        log.warn("Cognito 登入失敗 status={} type={}", status, type.isEmpty() ? "（未知）" : type);

        String bare = type.contains("#") ? type.substring(type.indexOf('#') + 1) : type;
        CognitoAuthException.Reason reason = switch (bare) {
            case "NotAuthorizedException" -> CognitoAuthException.Reason.INVALID_CREDENTIALS;
            case "UserNotFoundException" -> CognitoAuthException.Reason.USER_NOT_FOUND;
            case "PasswordResetRequiredException", "UserNotConfirmedException" ->
                    CognitoAuthException.Reason.PASSWORD_RESET_REQUIRED;
            case "TooManyRequestsException", "LimitExceededException", "ThrottlingException" ->
                    CognitoAuthException.Reason.RATE_LIMITED;
            case "InvalidParameterException", "ResourceNotFoundException",
                 "InvalidUserPoolConfigurationException" -> CognitoAuthException.Reason.CONFIGURATION;
            // 未知型別與 5xx 都當暫時性：對未知錯誤停用登入，風險高於偶爾多重試幾次
            default -> CognitoAuthException.Reason.TRANSIENT;
        };
        return new CognitoAuthException(reason,
                (bare.isEmpty() ? "HTTP " + status : bare) + (message.isEmpty() ? "" : ": " + message));
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new CognitoAuthException(CognitoAuthException.Reason.TRANSIENT,
                    "挑戰缺少必要欄位: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child.asString();
    }
}
