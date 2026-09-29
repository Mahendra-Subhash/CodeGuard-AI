package com.codeguardai.ai;

public interface AiInferenceProvider {
    String providerName();
    AiAnalysisResponse analyze(AiAnalysisRequest request);
    com.codeguardai.domain.FixProposal proposeFix(AiAnalysisRequest request);
}
