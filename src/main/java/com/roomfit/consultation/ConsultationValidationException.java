package com.roomfit.consultation;

/** Fixed diagnostic rule only; never include request text, image data or provider content. */
final class ConsultationValidationException extends IllegalArgumentException {
    ConsultationValidationException(String rule) { super(rule); }
}
