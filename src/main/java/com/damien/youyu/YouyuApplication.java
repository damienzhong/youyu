package com.damien.youyu;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 「有余」个人记账后端应用入口。
 *
 * <p>Slogan：记好每一笔，日子有余。</p>
 */
@SpringBootApplication
public class YouyuApplication {

    public static void main(String[] args) {
        // 全局统一时区，自然月/报表边界均按 UTC+8 计算
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(YouyuApplication.class, args);
    }
}
