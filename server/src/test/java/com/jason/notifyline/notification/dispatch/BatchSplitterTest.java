package com.jason.notifyline.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BatchSplitter")
class BatchSplitterTest {

    private static List<String> users(int count) {
        return IntStream.range(0, count).mapToObj("U%032d"::formatted).toList();
    }

    @ParameterizedTest(name = "{0} 人切成 {1} 批")
    @CsvSource({
            "0, 0",
            "1, 1",
            "499, 1",
            "500, 1",
            "501, 2",
            "1000, 2",
            "1200, 3",
            "1500, 3",
    })
    @DisplayName("切批數量：邊界在 500")
    void batchCount(int recipients, int expectedBatches) {
        assertThat(BatchSplitter.split(users(recipients))).hasSize(expectedBatches);
    }

    @Test
    @DisplayName("1200 人切成 500/500/200")
    void batchSizes() {
        assertThat(BatchSplitter.split(users(1200)))
                .extracting(List::size)
                .containsExactly(500, 500, 200);
    }

    @Test
    @DisplayName("每批都不超過 500，且加總等於總人數")
    void noBatchExceedsLimitAndNoneLost() {
        List<List<String>> batches = BatchSplitter.split(users(1501));

        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeBetween(1, 500));
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(1501);
        assertThat(batches.stream().flatMap(List::stream)).containsExactlyElementsOf(users(1501));
    }

    @Test
    @DisplayName("重複的收件人只留一份 —— 否則對方會收到兩則一樣的通知")
    void duplicatesRemoved() {
        List<String> withDupes = List.of("Ua", "Ub", "Ua", "Uc", "Ub");

        assertThat(BatchSplitter.split(withDupes))
                .containsExactly(List.of("Ua", "Ub", "Uc"));
    }

    @Test
    @DisplayName("去重後剛好落在邊界：501 人含 1 個重複 → 1 批")
    void duplicatesCountedAfterDedup() {
        List<String> list = new java.util.ArrayList<>(users(500));
        list.add(list.getFirst());

        assertThat(BatchSplitter.split(list)).hasSize(1);
        assertThat(BatchSplitter.split(list).getFirst()).hasSize(500);
    }

    @Test
    @DisplayName("空清單回空批次，不是一個空批")
    void emptyInput() {
        assertThat(BatchSplitter.split(List.of())).isEmpty();
    }
}
