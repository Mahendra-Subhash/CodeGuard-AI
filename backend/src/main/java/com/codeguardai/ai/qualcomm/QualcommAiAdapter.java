package com.codeguardai.ai.qualcomm;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;

public interface QualcommAiAdapter {
    String adapterName();
    boolean isAvailable();
    QualcommRuntimeStatus runtimeStatus();
    AiAnalysisResponse analyze(AiAnalysisRequest request);
}
