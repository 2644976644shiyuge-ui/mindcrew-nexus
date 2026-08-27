package com.simon.MindCrew;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({
        "com.simon.MindCrew.mapper",
        "com.simon.MindCrew.crew.mapper",
        "com.simon.MindCrew.workflow.mapper",
        "com.simon.MindCrew.datasource.mapper",
        "com.simon.MindCrew.digitalemployee.mapper"
})
@EnableAsync
@EnableScheduling   // ⭐ 任务 13.7 BSS 对账每天 3:30 跑
public class MindCrewApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCrewApplication.class, args);
    }
}
