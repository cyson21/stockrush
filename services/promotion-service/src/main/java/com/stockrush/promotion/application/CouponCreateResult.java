package com.stockrush.promotion.application;
/**
 * 쿼리/결과/조건 값을 전달하기 위한 값 객체로, API와 영속 사이 계약을 정합성 있게 유지합니다.
 */


public record CouponCreateResult(CouponSnapshot coupon, boolean replayed) {
}
