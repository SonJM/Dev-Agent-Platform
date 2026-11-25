package io.github.lib0823.devagent;

import io.github.lib0823.devagent.boot.Bootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DevAgentApplication {

    public static void main(String[] args) throws Exception {
        Bootstrap.prepare();          // .env 틀 · 메신저 없음 모드 · 내장 DB · 초기 스키마
        SpringApplication.run(DevAgentApplication.class, args);
    }
}
