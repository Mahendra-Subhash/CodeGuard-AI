package com.codeguardai.domain;

/**
 * Outcome of an attempt to apply a model proposed correction to scanned source.
 *
 * A correction is only ever written to the finding's verified line range.
 * When no exact, safe range can be confirmed, the scan is returned untouched
 * and the status is {@link #STATUS_REQUIRES_REVIEW} so that a developer can
 * review and apply the proposal manually.
 */
public record FixApplicationResult(
        boolean applied,
        String status,
        String message,
        int startLine,
        int endLine,
        Scan scan
) {

    /** The correction was written to the finding's verified line range. */
    public static final String STATUS_APPLIED = "applied";

    /** The requested correction is already present, nothing was written. */
    public static final String STATUS_ALREADY_APPLIED = "already-applied";

    /** No exact safe range was confirmed, the source was NOT modified. */
    public static final String STATUS_REQUIRES_REVIEW = "requires-review";

    /**
     * True when the source was left untouched and developer review is required.
     */
    public boolean requiresReview() {
        return STATUS_REQUIRES_REVIEW.equals(status);
    }

    public static FixApplicationResult applied(
            int startLine,
            int endLine,
            String message,
            Scan scan
    ) {
        return new FixApplicationResult(
                true,
                STATUS_APPLIED,
                message,
                startLine,
                endLine,
                scan
        );
    }

    public static FixApplicationResult alreadyApplied(
            int startLine,
            int endLine,
            String message,
            Scan scan
    ) {
        return new FixApplicationResult(
                false,
                STATUS_ALREADY_APPLIED,
                message,
                startLine,
                endLine,
                scan
        );
    }

    public static FixApplicationResult requiresReview(
            String message,
            int startLine,
            int endLine,
            Scan scan
    ) {
        return new FixApplicationResult(
                false,
                STATUS_REQUIRES_REVIEW,
                message,
                startLine,
                endLine,
                scan
        );
    }
}
