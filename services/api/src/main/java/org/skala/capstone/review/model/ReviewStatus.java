package org.skala.capstone.review.model;

public enum ReviewStatus {
    QUEUED("queued"), ANALYZING("analyzing"), DONE("done"), FAILED("failed"), UNKNOWN("");

    private final String value;

    ReviewStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean running() {
        return this == QUEUED || this == ANALYZING;
    }

    public static ReviewStatus from(String value) {
        for (ReviewStatus status : values()) if (status.value.equals(value)) return status;
        return UNKNOWN;
    }
}
