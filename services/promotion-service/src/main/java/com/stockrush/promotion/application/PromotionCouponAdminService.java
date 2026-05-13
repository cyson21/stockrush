package com.stockrush.promotion.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionCouponAdminService {

    private final CouponQueryRepository couponQueryRepository;
    private final CouponCommandRepository couponCommandRepository;
    private final CouponAdminCommandIdempotencyRepository idempotencyRepository;

    PromotionCouponAdminService(
        CouponQueryRepository couponQueryRepository,
        CouponCommandRepository couponCommandRepository,
        CouponAdminCommandIdempotencyRepository idempotencyRepository
    ) {
        this.couponQueryRepository = couponQueryRepository;
        this.couponCommandRepository = couponCommandRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    @Transactional
    public CouponCreateResult create(
        String idempotencyKey,
        String couponCode,
        String name,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
        validateCreate(couponCode, discountType, discountValue, minOrderAmount, maxDiscountAmount, status, startsAt, endsAt);
        String requestHash = requestHash(couponCode, name, discountType, discountValue, minOrderAmount, maxDiscountAmount, status, startsAt, endsAt);
        var existingCommand = idempotencyRepository.findByKey(idempotencyKey);
        if (existingCommand.isPresent()) {
            IdempotencySnapshot snapshot = existingCommand.get();
            if (!snapshot.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            CouponSnapshot coupon = couponQueryRepository.findByCouponCode(snapshot.couponCode())
                .orElseThrow(() -> new PromotionDataIntegrityException("Promotion idempotency record points to a missing coupon.", null));
            return new CouponCreateResult(coupon, true);
        }
        if (couponQueryRepository.findByCouponCode(couponCode).isPresent()) {
            throw new DuplicateCouponException(couponCode);
        }

        CouponSnapshot coupon = new CouponSnapshot(
            couponCode,
            name,
            discountType,
            discountValue,
            minOrderAmount,
            maxDiscountAmount,
            status,
            startsAt,
            endsAt
        );

        try {
            CouponSnapshot created = couponCommandRepository.create(coupon);
            idempotencyRepository.record(idempotencyKey, requestHash, created.couponCode());
            return new CouponCreateResult(created, false);
        } catch (DataIntegrityViolationException exception) {
            if (isCouponCodeUniqueViolation(exception)) {
                throw new DuplicateCouponException(couponCode);
            }
            throw new PromotionDataIntegrityException("Promotion coupon data integrity error.", exception);
        }
    }

    private void validateCreate(
        String couponCode,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
        if (couponCode == null || couponCode.isBlank()) {
            throw new IllegalArgumentException("couponCode must not be blank");
        }
        validateDiscount(discountType, discountValue, maxDiscountAmount);
        if (minOrderAmount == null || minOrderAmount.signum() < 0) {
            throw new IllegalArgumentException("minOrderAmount must be zero or positive");
        }
        if (!"ACTIVE".equals(status) && !"PAUSED".equals(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or PAUSED");
        }
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
    }

    private void validateDiscount(String discountType, BigDecimal discountValue, BigDecimal maxDiscountAmount) {
        if (!"PERCENTAGE".equals(discountType) && !"FIXED_AMOUNT".equals(discountType)) {
            throw new IllegalArgumentException("discountType must be PERCENTAGE or FIXED_AMOUNT");
        }
        if (discountValue == null || discountValue.signum() <= 0) {
            throw new IllegalArgumentException("discountValue must be positive");
        }
        if ("PERCENTAGE".equals(discountType) && discountValue.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("percentage discountValue must be 100 or less");
        }
        if (maxDiscountAmount != null && maxDiscountAmount.signum() < 0) {
            throw new IllegalArgumentException("maxDiscountAmount must be zero or positive");
        }
    }

    private boolean isCouponCodeUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String message = sqlException.getMessage();
                return "23505".equals(sqlException.getSQLState())
                    && message != null
                    && message.contains("coupon_code");
            }
            current = current.getCause();
        }
        return false;
    }

    private String requestHash(
        String couponCode,
        String name,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
        String raw = String.join("|",
            couponCode,
            name,
            discountType,
            money(discountValue),
            money(minOrderAmount),
            maxDiscountAmount == null ? "" : money(maxDiscountAmount),
            status,
            startsAt.toInstant().toString(),
            endsAt.toInstant().toString()
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }

    private String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN).toPlainString();
    }
}
