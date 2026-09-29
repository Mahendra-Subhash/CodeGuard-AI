package com.codeguardai.ai;

public record AiAnalysisResponse(
        String summary,
        String explanation,
        String rootCause,
        String risk,
        String recommendedFix,
        String correctedCode,
        String confidence,
        String verificationHint
) {}
