package com.codeguardai.ai;

public record AiAnalysisRequest(
        String sourceCode,
        String fileName,
        String analyzer,
        String rule,
        String category,
        String severity,
        String message,
        String snippet,
        String context
) {}
