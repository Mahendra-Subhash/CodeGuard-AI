package com.codeguardai.ai.qualcomm;

/**
 * Detection seam for Qualcomm runtime capability reporting.
 *
 * The default implementation, {@link SystemQualcommRuntimeDetector}, inspects
 * the local machine and reports observations only.
 *
 * A Snapdragon deployment can supply a detector that additionally probes
 * QAIRT/QNN installation paths and verified hardware execution, without
 * changing {@link QualcommAiAdapter} or the application core.
 */
public interface QualcommRuntimeDetector {

    /**
     * @return factual observations about the host and the Qualcomm runtime
     *         components that are actually present
     */
    QualcommHostFacts detect();
}
