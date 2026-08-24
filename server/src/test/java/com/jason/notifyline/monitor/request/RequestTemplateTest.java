package com.jason.notifyline.monitor.request;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestTemplate} 的替換規則。見 {@code Docs/plan/12-API監控易用性升級.md} §2.5、§5。
 */
@DisplayName("RequestTemplate")
class RequestTemplateTest {

    // 2026-08-24T03:00:00Z = Asia/Taipei（UTC+8）08/24 11:00:00。
    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RequestTemplate template = new RequestTemplate(FIXED_CLOCK);

    @Nested
    @DisplayName("各佔位符")
    class Placeholders {

        @Test
        @DisplayName("{{now.epochSeconds}}")
        void epochSeconds() {
            assertThat(template.render("t={{now.epochSeconds}}")).isEqualTo("t=" + NOW.getEpochSecond());
        }

        @Test
        @DisplayName("{{now.epochMillis}}")
        void epochMillis() {
            assertThat(template.render("t={{now.epochMillis}}")).isEqualTo("t=" + NOW.toEpochMilli());
        }

        @Test
        @DisplayName("{{now.iso8601}}：UTC，含秒不含小數")
        void iso8601() {
            assertThat(template.render("{{now.iso8601}}")).isEqualTo("2026-08-24T03:00:00Z");
        }

        @Test
        @DisplayName("{{now.format:PATTERN}}：Asia/Taipei（UTC+8）")
        void formatPattern() {
            assertThat(template.render("{{now.format:yyyy-MM-dd HH:mm}}")).isEqualTo("2026-08-24 11:00");
        }

        @Test
        @DisplayName("{{uuid}}：合法 UUID 格式，且每次呼叫不同")
        void uuidPlaceholder() {
            String first = template.render("{{uuid}}");
            String second = template.render("{{uuid}}");

            Pattern uuidPattern = Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
            assertThat(uuidPattern.matcher(first).matches()).isTrue();
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("多個佔位符混在同一個字串裡")
        void multiplePlaceholdersInOneString() {
            String rendered = template.render("https://example.com/api?ts={{now.epochSeconds}}&id={{uuid}}");
            assertThat(rendered).startsWith("https://example.com/api?ts=" + NOW.getEpochSecond() + "&id=");
        }

        @Test
        @DisplayName("沒有佔位符的字串原樣傳回")
        void noPlaceholders_returnsUnchanged() {
            assertThat(template.render("https://example.com/api?x=1")).isEqualTo("https://example.com/api?x=1");
        }

        @Test
        @DisplayName("null 模板回傳 null（body 可能真的沒有內容）")
        void nullTemplate_returnsNull() {
            assertThat(template.render(null)).isNull();
        }
    }

    @Nested
    @DisplayName("位移")
    class Offsets {

        @Test
        @DisplayName("{{now-7d.format:yyyy-MM-dd}}")
        void minusDays() {
            assertThat(template.render("{{now-7d.format:yyyy-MM-dd}}")).isEqualTo("2026-08-17");
        }

        @Test
        @DisplayName("{{now+1h.epochSeconds}}")
        void plusHours() {
            assertThat(template.render("{{now+1h.epochSeconds}}"))
                    .isEqualTo(String.valueOf(NOW.getEpochSecond() + 3600));
        }

        @Test
        @DisplayName("{{now-30m.epochSeconds}}")
        void minusMinutes() {
            assertThat(template.render("{{now-30m.epochSeconds}}"))
                    .isEqualTo(String.valueOf(NOW.getEpochSecond() - 1800));
        }

        @Test
        @DisplayName("{{now+45s.epochSeconds}}")
        void plusSeconds() {
            assertThat(template.render("{{now+45s.epochSeconds}}"))
                    .isEqualTo(String.valueOf(NOW.getEpochSecond() + 45));
        }
    }

    @Nested
    @DisplayName("拒絕：畸形 pattern")
    class MalformedPattern {

        @Test
        @DisplayName("pattern 含不在白名單內的字元 → 拒絕，不呼叫 DateTimeFormatter.ofPattern")
        void disallowedCharacter_rejected() {
            assertThatThrownBy(() -> template.render("{{now.format:yyyy-MM-dd'T'HH:mm:ssXXX}}"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        @Test
        @DisplayName("pattern 超過 32 字 → 拒絕")
        void tooLong_rejected() {
            String longPattern = "y".repeat(33);
            assertThatThrownBy(() -> template.render("{{now.format:" + longPattern + "}}"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("32 字剛好在上限內 → 允許")
        void exactlyMaxLength_allowed() {
            // 剛好 32 字元、字元集合合法，且每個連續同字母欄位的寬度都在
            // DateTimeFormatter 允許的範圍內（不可單純重複同一個字母湊長度——
            // 例如 32 個 'y' 會因為欄位寬度超過 19 而在語法層被拒，見
            // charsetAllowedButSyntaxInvalid_rejected 那類案例）。
            String pattern = "yyyy/MM/dd HH:mm:ss.SSS a ------";
            assertThat(pattern).hasSize(32);
            assertThat(template.render("{{now.format:" + pattern + "}}")).isNotBlank();
        }

        @Test
        @DisplayName("字元都合法但語法不合法（單引號沒有成對）→ 仍要拒絕，不能讓例外原樣往上拋")
        void charsetAllowedButSyntaxInvalid_rejected() {
            assertThatThrownBy(() -> template.render("{{now.format:yyyy'MM}}"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }

    @Nested
    @DisplayName("拒絕：未知佔位符")
    class UnknownPlaceholder {

        @Test
        @DisplayName("完全不認識的佔位符 → 拒絕（不是替換成 —，跟 MessageTemplate 相反）")
        void unknownKey_rejected() {
            assertThatThrownBy(() -> template.render("{{totally.unknown}}"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                    .hasMessageContaining("totally.unknown");
        }

        @Test
        @DisplayName("now 的未知子指令 → 拒絕")
        void unknownNowSubcommand_rejected() {
            assertThatThrownBy(() -> template.render("{{now.unknownThing}}"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("未知佔位符的例外訊息不可原樣輸出整個 {{ }}，也不能是空字串（可辨識用於除錯）")
        void unknownPlaceholder_messageIsUseful() {
            assertThatThrownBy(() -> template.render("{{bogus}}"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("bogus");
        }
    }

    @Nested
    @DisplayName("固定 Clock")
    class FixedClockUsage {

        @Test
        @DisplayName("同一個固定 Clock 下，兩次呼叫的 now.epochSeconds 相同")
        void sameClock_sameEpochSeconds() {
            assertThat(template.render("{{now.epochSeconds}}")).isEqualTo(template.render("{{now.epochSeconds}}"));
        }

        @Test
        @DisplayName("不同 Clock 產生不同的 now 值——證明真的有注入 Clock，不是呼叫系統時間")
        void differentClock_differentValue() {
            RequestTemplate other = new RequestTemplate(
                    Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC));

            assertThat(other.render("{{now.epochSeconds}}"))
                    .isNotEqualTo(template.render("{{now.epochSeconds}}"));
        }
    }

    @Nested
    @DisplayName("renderHeaders")
    class Headers {

        @Test
        @DisplayName("逐一替換每個 header value，保留鍵與插入順序")
        void rendersEachValue() {
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("accept", "application/json");
            headers.put("x-ts", "{{now.epochSeconds}}");

            Map<String, String> rendered = template.renderHeaders(headers);

            assertThat(rendered.get("accept")).isEqualTo("application/json");
            assertThat(rendered.get("x-ts")).isEqualTo(String.valueOf(NOW.getEpochSecond()));
            assertThat(rendered.keySet()).containsExactly("accept", "x-ts");
        }

        @Test
        @DisplayName("null/空 map 回傳空 map")
        void nullOrEmpty_returnsEmpty() {
            assertThat(template.renderHeaders(null)).isEmpty();
            assertThat(template.renderHeaders(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("其中一個 header 的佔位符未知 → 整個呼叫拋錯（不是部分成功）")
        void oneUnknownPlaceholder_wholeCallFails() {
            Map<String, String> headers = Map.of("x-bad", "{{nope}}");
            assertThatThrownBy(() -> template.renderHeaders(headers)).isInstanceOf(ApiException.class);
        }
    }

    /**
     * 凍結時間戳（Docs/plan/13-監控計算欄位設計.md §3，本波次必修的 bug）：一次請求只取
     * 一次 {@code Instant}，URL、headers、body、計算欄位共用同一份 {@link RequestTemplate.Session}。
     *
     * <p>用一個<strong>每次呼叫都往前走的 Clock</strong> 測，不是固定 Clock——固定 Clock
     * 就算實作有 bug（每個佔位符各自呼叫一次 {@code Instant.now(clock)}）也測不出來，
     * 因為每次呼叫回傳的值都碰巧一樣。這正是 doc §3 描述的那個「幾百次輪詢才發生一次」
     * 的 bug 之所以難查的原因。
     */
    @Nested
    @DisplayName("凍結時間戳（Session）")
    class FrozenSession {

        /** 每次呼叫 {@code instant()} 都回傳往前推一秒的值，讓「共用同一個 Session」與「沒有共用」的差異必然可觀察。 */
        private static final class TickingClock extends Clock {
            private Instant next;

            TickingClock(Instant start) {
                this.next = start;
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                Instant current = next;
                next = next.plusSeconds(1);
                return current;
            }
        }

        @Test
        @DisplayName("同一個 Session：URL、header、body 的 now.epochSeconds 全部相同，即使 Clock 會跳秒")
        void sameSession_sameInstantAcrossUrlHeaderBody() {
            RequestTemplate ticking = new RequestTemplate(new TickingClock(NOW));
            RequestTemplate.Session session = ticking.newSession();

            String url = ticking.render("t={{now.epochSeconds}}", session, Map.of());
            Map<String, String> headers = ticking.renderHeaders(
                    Map.of("x-ts", "{{now.epochSeconds}}"), session, Map.of());
            String body = ticking.render("{\"ts\":{{now.epochSeconds}}}", session, Map.of());

            assertThat(url).isEqualTo("t=" + session.instant().getEpochSecond());
            assertThat(headers.get("x-ts")).isEqualTo(String.valueOf(session.instant().getEpochSecond()));
            assertThat(body).isEqualTo("{\"ts\":" + session.instant().getEpochSecond() + "}");
        }

        @Test
        @DisplayName("同一個 Session：同一個 {{uuid}} 在多次 render 呼叫間回傳同一個值")
        void sameSession_sameUuidAcrossCalls() {
            RequestTemplate.Session session = template.newSession();

            String first = template.render("{{uuid}}", session, Map.of());
            String second = template.render("id2={{uuid}}", session, Map.of());

            assertThat(second).isEqualTo("id2=" + first);
        }

        @Test
        @DisplayName("兩個不同 Session（未共用）：now.epochSeconds 不同——證明真的是 Session 在凍結時間，不是巧合")
        void differentSessions_differentInstant() {
            RequestTemplate ticking = new RequestTemplate(new TickingClock(NOW));
            RequestTemplate.Session first = ticking.newSession();
            RequestTemplate.Session second = ticking.newSession();

            String a = ticking.render("{{now.epochSeconds}}", first, Map.of());
            String b = ticking.render("{{now.epochSeconds}}", second, Map.of());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("無參數 render(String)：每次呼叫各自建立一個新 session，只適合語法驗證，不共用時間戳")
        void adHocRender_doesNotShareInstant() {
            RequestTemplate ticking = new RequestTemplate(new TickingClock(NOW));

            String first = ticking.render("{{now.epochSeconds}}");
            String second = ticking.render("{{now.epochSeconds}}");

            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("{{computed.NAME}}")
    class ComputedScope {

        @Test
        @DisplayName("已求值的計算欄位可以被 URL/header/body 引用")
        void resolvesFromComputedValues() {
            RequestTemplate.Session session = template.newSession();
            Map<String, String> computed = Map.of("sign", "ABC123");

            assertThat(template.render("sign={{computed.sign}}", session, computed)).isEqualTo("sign=ABC123");
        }

        @Test
        @DisplayName("引用不存在的計算欄位名稱 → 拒絕")
        void unknownComputedName_rejected() {
            RequestTemplate.Session session = template.newSession();

            assertThatThrownBy(() -> template.render("{{computed.missing}}", session, Map.of("sign", "ABC")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        @Test
        @DisplayName("{{secret.*}} 不是請求模板的合法 scope——secret 只能透過計算欄位間接使用")
        void secretScope_rejected() {
            RequestTemplate.Session session = template.newSession();

            assertThatThrownBy(() -> template.render("{{secret.appsecret}}", session, Map.of()))
                    .isInstanceOf(ApiException.class);
        }
    }
}
