package com.jason.notifyline.monitor.login;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * AWS Cognito {@code USER_SRP_AUTH} 的密碼學核心。純計算，不碰網路、不碰資料庫。
 * 見 {@code Docs/plan/15-監控站台登入設計.md} §7。
 *
 * <h2>為什麼是 SRP，不是帳密直傳</h2>
 *
 * <p>目標站台的 app client 明確停用了 {@code USER_PASSWORD_AUTH}
 * （{@code InvalidParameterException: USER_PASSWORD_AUTH flow not enabled for this client}），
 * 只留 {@code USER_SRP_AUTH}。密碼永遠不會離開這台機器 —— 送出去的是一個以密碼
 * 推導出的證明值，這正是 SRP 的重點。
 *
 * <h2>演算法（RFC 5054 3072-bit group）</h2>
 *
 * <pre>
 * x   = H(padHex(salt) ‖ H(poolName ‖ username ‖ ":" ‖ password))
 * u   = H(padHex(A) ‖ padHex(B))
 * s   = (B − k·g^x)^(a + u·x) mod N
 * key = HKDF-SHA256(ikm = padHex(s), salt = padHex(u), info = "Caldera Derived Key")[0..16]
 * sig = HMAC-SHA256(key, poolName ‖ username ‖ secretBlock ‖ timestamp)
 * </pre>
 *
 * <h2>三個一定會踩的坑</h2>
 *
 * <ol>
 *   <li><strong>{@code username} 必須是 {@code USER_ID_FOR_SRP}</strong>（Cognito 內部
 *       id），不是登入用的 email。用 email 會拿到看起來像密碼錯誤的
 *       {@code NotAuthorizedException}，而密碼其實是對的。</li>
 *   <li><strong>{@code poolName} 是去掉 region 前綴的部分</strong>：
 *       {@code eu-west-2_FhQHPoX2z} → {@code FhQHPoX2z}。整串丟進去簽章一定對不上。</li>
 *   <li><strong>timestamp 格式固定</strong>：{@code EEE MMM d HH:mm:ss 'UTC' yyyy}、
 *       {@code Locale.US}、UTC 時區、<strong>日期不補零</strong>。用系統預設 locale
 *       會在非英文環境產生 "週二"，簽章直接失敗。</li>
 * </ol>
 *
 * <h2>{@link #padHex(String)} 的 salt 歧義</h2>
 *
 * <p>AWS 自家的 Java 範例用 {@code new BigInteger(saltHex, 16).toByteArray()}，pycognito
 * 則對 <strong>hex 字串本身</strong>做 padding。兩者只在「Cognito 送來的 salt hex 以
 * {@code 00} 開頭」時不同（約 1/256 的帳號）—— BigInteger 會吃掉那個前導零位元組，
 * 字串版會保留。這裡預設用字串版（{@link SaltEncoding#PRESERVE}），並讓呼叫端在
 * 遇到歧義時可以改用 {@link SaltEncoding#MINIMAL} 重試一次，見
 * {@code SiteLoginService}。不猜哪個對，而是讓那 1/256 的帳號也能自動走通。
 */
public final class CognitoSrp {

    /** RFC 5054 3072-bit group 的 N。 */
    private static final String N_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D040A99D9"
            + "556AE0F19B9F42B54BC9F9BB1F60FEB1EDCEB9D69B70F2ED8B0AE4C0D2AABB48"
            + "0C34C0F3D5E9C2CD9A8F44F1F1B2B8CE1CE6BD8FF67F1CDC1EFDD5C8B4D0DA0F"
            + "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

    private static final String G_HEX = "2";

    private static final BigInteger N = new BigInteger(N_HEX, 16);
    private static final BigInteger G = new BigInteger(G_HEX, 16);

    /** k = H(00 ‖ N ‖ 0 ‖ g)。AWS 用的就是這個很特別的補零方式，不是一般的 PAD()。 */
    private static final BigInteger K = new BigInteger(hexHash("00" + N_HEX + "0" + G_HEX), 16);

    private static final byte[] INFO_BITS = "Caldera Derived Key".getBytes(StandardCharsets.US_ASCII);

    private static final int DERIVED_KEY_BYTES = 16;

    /** 私鑰 a 的長度。AWS 各版實作一致用 128 bytes。 */
    private static final int SMALL_A_BYTES = 128;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss 'UTC' yyyy", Locale.US)
                    .withZone(ZoneOffset.UTC);

    private CognitoSrp() {
    }

    /** salt hex 轉 byte 的兩種解讀，見類別註解「salt 歧義」。 */
    public enum SaltEncoding {

        /** 保留 Cognito 送來的前導零位元組（pycognito 的行為）。 */
        PRESERVE,

        /** 以 {@code BigInteger} 解讀，丟掉前導零位元組（AWS Java 範例的行為）。 */
        MINIMAL
    }

    /**
     * 一次登入用的臨時金鑰對。{@code a} 是私鑰，只活在這次登入的呼叫堆疊裡。
     *
     * @param smallA 私鑰 a
     * @param bigA   公開值 A = g^a mod N，要送給 Cognito
     */
    public record KeyPair(BigInteger smallA, BigInteger bigA) {

        /** Cognito 的 {@code SRP_A} 參數格式：不補零的 hex。 */
        public String bigAHex() {
            return bigA.toString(16);
        }
    }

    /**
     * 產生這次登入的臨時金鑰對。
     *
     * <p>{@code A mod N == 0} 會讓伺服器端的計算退化，SRP 規格要求檢查；機率低到
     * 實務上不會發生，但這是規格明文要求的安全檢查，不是可省略的防禦性程式碼。
     */
    public static KeyPair generateKeyPair(SecureRandom random) {
        byte[] bytes = new byte[SMALL_A_BYTES];
        while (true) {
            random.nextBytes(bytes);
            BigInteger smallA = new BigInteger(1, bytes).mod(N);
            BigInteger bigA = G.modPow(smallA, N);
            if (bigA.mod(N).signum() != 0) {
                return new KeyPair(smallA, bigA);
            }
        }
    }

    /**
     * 推導出簽章用的金鑰（16 bytes）。
     *
     * @param poolName     user pool id 去掉 region 前綴的部分，見類別註解坑 2
     * @param username     {@code USER_ID_FOR_SRP}，見類別註解坑 1
     * @param password     使用者密碼；只在這個方法的堆疊內存在，不外傳、不記錄
     * @param keyPair      {@link #generateKeyPair}
     * @param bigB         Cognito 回的 {@code SRP_B}（hex）
     * @param saltHex      Cognito 回的 {@code SALT}（hex）
     * @param saltEncoding 見類別註解「salt 歧義」
     */
    public static byte[] passwordAuthenticationKey(String poolName,
                                                   String username,
                                                   String password,
                                                   KeyPair keyPair,
                                                   BigInteger bigB,
                                                   String saltHex,
                                                   SaltEncoding saltEncoding) {
        if (bigB.mod(N).signum() == 0) {
            throw new IllegalArgumentException("SRP_B mod N is zero");
        }
        BigInteger u = new BigInteger(hexHash(padHex(keyPair.bigA()) + padHex(bigB)), 16);
        if (u.signum() == 0) {
            throw new IllegalArgumentException("u is zero");
        }

        String credentialsHash = hashSha256(
                (poolName + username + ":" + password).getBytes(StandardCharsets.UTF_8));
        BigInteger x = new BigInteger(hexHash(encodeSalt(saltHex, saltEncoding) + credentialsHash), 16);

        // s = (B − k·g^x)^(a + u·x) mod N
        // 括號內可能是負數；modPow 對負底數仍回傳非負結果，與參考實作的 pow(b, e, n) 一致。
        BigInteger base = bigB.subtract(K.multiply(G.modPow(x, N)));
        BigInteger exponent = keyPair.smallA().add(u.multiply(x));
        BigInteger s = base.modPow(exponent, N);

        return hkdf(hexToBytes(padHex(s)), hexToBytes(padHex(u)));
    }

    /**
     * 算出 {@code PASSWORD_CLAIM_SIGNATURE}。
     *
     * @param derivedKey  {@link #passwordAuthenticationKey}
     * @param secretBlock Cognito 回的 {@code SECRET_BLOCK}，<strong>base64 解碼後</strong>的
     *                    原始位元組——用 base64 字串本身去簽是最常見的錯法
     * @param timestamp   必須與送出的 {@code TIMESTAMP} 參數逐字相同，見 {@link #timestamp}
     */
    public static String passwordClaimSignature(byte[] derivedKey,
                                                String poolName,
                                                String username,
                                                byte[] secretBlock,
                                                String timestamp) {
        Mac mac = hmac(derivedKey);
        mac.update(poolName.getBytes(StandardCharsets.UTF_8));
        mac.update(username.getBytes(StandardCharsets.UTF_8));
        mac.update(secretBlock);
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }

    /**
     * Cognito 要求的時間戳格式。{@code Locale.US} 與 UTC 都是簽章的一部分，不可改。
     *
     * <p>{@code d}（非 {@code dd}）是刻意的：Cognito 要的是不補零的日期，
     * {@code "Sep 9"} 而不是 {@code "Sep 09"}。
     */
    public static String timestamp(Instant now) {
        return TIMESTAMP_FORMAT.format(now);
    }

    /** {@code eu-west-2_FhQHPoX2z} → {@code FhQHPoX2z}，見類別註解坑 2。 */
    public static String poolNameOf(String userPoolId) {
        int underscore = userPoolId.indexOf('_');
        if (underscore < 0 || underscore == userPoolId.length() - 1) {
            throw new IllegalArgumentException("userPoolId must look like <region>_<poolName>");
        }
        return userPoolId.substring(underscore + 1);
    }

    // ------------------------------------------------------------------ 內部

    private static String encodeSalt(String saltHex, SaltEncoding encoding) {
        return switch (encoding) {
            case PRESERVE -> padHex(saltHex);
            case MINIMAL -> padHex(new BigInteger(saltHex, 16));
        };
    }

    /**
     * AWS 的 padding 規則：奇數長度補一個 {@code 0}；最高位元為 1 時補 {@code 00}
     * （否則會被當成負數）。等價於「正整數的最小 big-endian 位元組表示」。
     */
    static String padHex(String hex) {
        if (hex.length() % 2 == 1) {
            return "0" + hex;
        }
        if ("89ABCDEFabcdef".indexOf(hex.charAt(0)) >= 0) {
            return "00" + hex;
        }
        return hex;
    }

    static String padHex(BigInteger value) {
        return padHex(value.toString(16));
    }

    /** HKDF-SHA256，輸出截到 16 bytes —— Cognito 用的就是這個非標準長度。 */
    private static byte[] hkdf(byte[] ikm, byte[] salt) {
        byte[] prk = hmac(salt).doFinal(ikm);
        Mac mac = hmac(prk);
        mac.update(INFO_BITS);
        mac.update((byte) 1);
        byte[] full = mac.doFinal();
        byte[] out = new byte[DERIVED_KEY_BYTES];
        System.arraycopy(full, 0, out, 0, DERIVED_KEY_BYTES);
        return out;
    }

    private static Mac hmac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** SHA-256 後回傳 64 字元小寫 hex。 */
    static String hashSha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 把 hex 字串解碼成位元組後做 SHA-256，回傳 64 字元小寫 hex。 */
    static String hexHash(String hex) {
        return hashSha256(hexToBytes(hex));
    }

    private static byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex.length() % 2 == 1 ? "0" + hex : hex);
    }
}
