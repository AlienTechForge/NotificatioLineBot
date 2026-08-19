package com.jason.notifyline.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 讓 request body 可以被讀兩次：一次驗簽，一次反序列化。
 *
 * <p><strong>不能用 Spring 的 {@code ContentCachingRequestWrapper}</strong> ——
 * 它是在資料「被讀取的當下」順便記錄一份，所以 filter 讀完整個 stream 之後，
 * controller 再讀就是空的。它適合「處理完之後才想看 body」（例如記錄存取日誌），
 * 不適合「讀完還要再讓別人讀」。
 *
 * <p>這裡是先把整個 body 讀進記憶體，之後每次 {@code getInputStream()} 都給一份
 * 新的 stream。因此<strong>必須有大小上限</strong>，否則一個超大 body 就能吃光記憶體。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        this.body = readFully(request, maxBytes);
    }

    /** 驗簽用的原始位元組。回傳副本，避免被下游改動。 */
    public byte[] getCachedBody() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("非同步讀取不適用於已快取的 body");
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    private static byte[] readFully(HttpServletRequest request, int maxBytes) throws IOException {
        // 邊讀邊計數，不能先 new byte[contentLength] —— Content-Length 是呼叫端說的，
        // 可能與實際不符，也可能根本沒有（chunked）。
        var out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        try (var in = request.getInputStream()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new RequestBodyTooLargeException(maxBytes);
                }
                out.write(chunk, 0, read);
            }
        }
        return out.toByteArray();
    }

    /** body 超過上限。 */
    public static class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException(int maxBytes) {
            super("request body exceeds " + maxBytes + " bytes");
        }
    }
}
