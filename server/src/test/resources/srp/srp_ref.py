"""Cognito SRP 參考實作 —— 只為了產生 Java 單元測試的對拍向量。

刻意用與 Java 版完全不同的寫法（Python 的無限精度 int、hex 字串操作），
這樣「padding 算錯」「byte 陣列少一位」這類實作錯誤不會同時出現在兩邊。

演算法來源：AWS Cognito USER_SRP_AUTH（RFC 5054 3072-bit group）。

注意這個「獨立」是有邊界的：兩邊各自寫演算法，但 N 是同一串抄來的常數，
抄漏就會兩邊一起錯、對拍照樣全綠。N 本身要靠 CognitoSrpTest 的
ModulusIsTheRealGroup 用數學性質驗，不是靠這裡。
"""
import base64
import hashlib
import hmac
import json

# RFC 3526 §4 的 3072-bit MODP Group（id 15）。768 個 hex 字元，見下面的 assert。
N_HEX = (
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
    "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
    "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
)
# 產生向量前先擋下「N 抄漏了」——那會安靜地生出一組看起來正常、對真實 Cognito
# 卻永遠失敗的向量。
assert len(N_HEX) == 768, f"N_HEX 必須是 768 個 hex 字元，實際 {len(N_HEX)}"
assert int(N_HEX, 16).bit_length() == 3072

G_HEX = "2"
INFO_BITS = b"Caldera Derived Key"


def hash_sha256(buf: bytes) -> str:
    a = hashlib.sha256(buf).hexdigest()
    return "0" * (64 - len(a)) + a


def hex_hash(hex_string: str) -> str:
    return hash_sha256(bytearray.fromhex(hex_string))


def pad_hex(value) -> str:
    """AWS 的 padding 規則：奇數長度補一個 0；最高位元為 1 時補 '00'（避免被當負數）。"""
    h = value if isinstance(value, str) else "%x" % value
    if len(h) % 2 == 1:
        h = "0" + h
    elif h[0] in "89ABCDEFabcdef":
        h = "00" + h
    return h


def compute_hkdf(ikm: bytes, salt: bytes) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, INFO_BITS + bytes([1]), hashlib.sha256).digest()[:16]


def calculate_u(big_a: int, big_b: int) -> int:
    return int(hex_hash(pad_hex(big_a) + pad_hex(big_b)), 16)


def authentication_key(pool_name, username, password, small_a, big_a, big_b, salt_hex):
    u = calculate_u(big_a, big_b)
    if u == 0:
        raise ValueError("u == 0")
    n = int(N_HEX, 16)
    g = int(G_HEX, 16)
    k = int(hex_hash("00" + N_HEX + "0" + G_HEX), 16)

    up = f"{pool_name}{username}:{password}"
    up_hash = hash_sha256(up.encode("utf-8"))
    x = int(hex_hash(pad_hex(salt_hex) + up_hash), 16)

    s = pow(big_b - k * pow(g, x, n), small_a + u * x, n)
    return compute_hkdf(bytearray.fromhex(pad_hex(s)), bytearray.fromhex(pad_hex("%x" % u))), u, x, s, k


def main():
    # 固定輸入 —— 不用亂數，Java 測試要能重現同一組數字
    pool_name = "FhQHPoX2z"
    username = "1a2b3c4d-0000-4444-8888-abcdefabcdef"
    password = "Test-Password-123!"
    salt_hex = "ee2d3f1b9eafc63ae7ff2b60cdd1f8c8"
    secret_block_b64 = base64.b64encode(bytes(range(64))).decode()
    timestamp = "Tue Sep 9 01:23:45 UTC 2026"

    n = int(N_HEX, 16)
    g = int(G_HEX, 16)
    small_a = int(
        "3b1f0e6a5c8d7492bafe0011223344556677889900aabbccddeeff0112233445"
        "66778899aabbccddeeff00112233445566778899aabbccddeeff001122334455", 16) % n
    big_a = pow(g, small_a, n)
    big_b = int(hex_hash("deadbeef" * 8) * 6, 16) % n  # 任意但固定的 B

    hkdf, u, x, s, k = authentication_key(
        pool_name, username, password, small_a, big_a, big_b, salt_hex)

    secret_block = base64.b64decode(secret_block_b64)
    msg = (pool_name.encode() + username.encode() + secret_block + timestamp.encode())
    signature = base64.b64encode(hmac.new(hkdf, msg, hashlib.sha256).digest()).decode()

    print(json.dumps({
        "poolName": pool_name,
        "username": username,
        "password": password,
        "saltHex": salt_hex,
        "secretBlockB64": secret_block_b64,
        "timestamp": timestamp,
        "smallAHex": "%x" % small_a,
        "bigAHex": "%x" % big_a,
        "bigBHex": "%x" % big_b,
        "kHex": "%x" % k,
        "uHex": "%x" % u,
        "xHex": "%x" % x,
        "sHex": "%x" % s,
        "hkdfHex": hkdf.hex(),
        "signatureB64": signature,
    }, indent=2))


if __name__ == "__main__":
    main()
