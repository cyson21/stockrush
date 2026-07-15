package com.stockrush.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/**
 * Spring 부트 스트랩 진입점으로 서비스 실행과 기본 환경 구성을 담당하는 애플리케이션 클래스입니다.
 */


@SpringBootApplication
public class FulfillmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}
