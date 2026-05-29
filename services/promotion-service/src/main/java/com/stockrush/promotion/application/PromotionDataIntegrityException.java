package com.stockrush.promotion.application;
/**
 * 비즈니스 예외를 타입별로 분리해 상위 계층에서 정확한 분기 처리와 응답 매핑이 가능하게 합니다.
 */


public class PromotionDataIntegrityException extends RuntimeException {

    public PromotionDataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
