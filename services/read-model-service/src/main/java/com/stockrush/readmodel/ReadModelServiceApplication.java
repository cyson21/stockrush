// ReadModelServiceApplication: 서비스 부트스트랩 진입 클래스이며 실행 주기를 관리합니다.

package com.stockrush.readmodel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReadModelServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadModelServiceApplication.class, args);
    }
}
