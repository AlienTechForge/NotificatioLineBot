package com.jason.notifyline.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link ApiMonitorScheduler} 只做一件事，但那件事極其重要：{@code @Scheduled}
 * 方法絕不能讓例外逃出去——逃出去會讓 Spring 停掉這個排程的後續執行，等於整個
 * 監控功能靜悄悄地永久停擺。抄 {@code DeliveryScheduler.poll()} 的同款規則，
 * 這裡驗證同款行為。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiMonitorScheduler")
class ApiMonitorSchedulerTest {

    @Mock
    private ApiMonitorRunner runner;

    @Test
    @DisplayName("runner.runOnce() 拋例外時，poll() 吞掉不往外拋")
    void poll_swallowsExceptionsFromRunner() {
        willThrow(new RuntimeException("目標 API 掛了")).given(runner).runOnce();
        ApiMonitorScheduler scheduler = new ApiMonitorScheduler(runner);

        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        verify(runner).runOnce();
    }

    @Test
    @DisplayName("正常情況下 poll() 就是呼叫 runner.runOnce()")
    void poll_delegatesToRunner() {
        ApiMonitorScheduler scheduler = new ApiMonitorScheduler(runner);

        scheduler.poll();

        verify(runner).runOnce();
    }
}
