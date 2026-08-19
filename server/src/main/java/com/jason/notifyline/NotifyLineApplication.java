package com.jason.notifyline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

/**
 * 統一 LINE Notification Service.
 *
 * <p>對外只暴露一個通知端點，讓所有內部服務共用同一套 LINE 通知管道。
 * 設計文件見 {@code Docs/plan/}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NotifyLineApplication {

    /** 帶此參數時只跑一次性的維運指令，不啟動 web server。 */
    private static final String BOOTSTRAP_FLAG = "--create-client";

    public static void main(String[] args) {
        boolean bootstrapMode = Arrays.stream(args).anyMatch(arg -> arg.startsWith(BOOTSTRAP_FLAG));

        SpringApplication application = new SpringApplication(NotifyLineApplication.class);
        if (bootstrapMode) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }

        ConfigurableApplicationContext context = application.run(args);

        if (bootstrapMode) {
            // 光是 WebApplicationType.NONE 不夠：run() 返回後 main 就結束了，
            // 但排程器、@Async 執行緒池與 Hikari 都是「非 daemon」執行緒，
            // JVM 會一直活著 —— 一次性的維運指令卻永遠不退出。
            //
            // SpringApplication.exit() 會先跑完 ExitCodeGenerator 並「正常關閉
            // context」（交易與連線池乾淨收尾），之後才 System.exit()。
            // 直接呼叫 System.exit() 會跳過關閉流程。
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
