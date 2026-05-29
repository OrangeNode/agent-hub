package com.agenthub.common.exception;

public record ValidationError(String field, String message) {
}
