package com.codeguardai.service;

import com.codeguardai.ai.AiAnalysisRequest;
import com.codeguardai.ai.AiAnalysisResponse;
import com.codeguardai.ai.AiInferenceProvider;
import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixApplicationResult;
import com.codeguardai.domain.FixProposal;
import com.codeguardai.domain.Scan;
import com.codeguardai.domain.VerificationResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ScanService {

    private final StaticAnalysisService staticAnalysisService;
    private final AiInferenceProvider aiInferenceProvider;
    private final SafeFixApplier safeFixApplier;

    private final Map<Long, Scan> scans =
            new ConcurrentHashMap<>();

    private final AtomicLong sequence =
            new AtomicLong(1L);

    public ScanService(
            StaticAnalysisService staticAnalysisService,
            AiInferenceProvider aiInferenceProvider,
            SafeFixApplier safeFixApplier
    ) {
        this.staticAnalysisService =
                staticAnalysisService;

        this.aiInferenceProvider =
                aiInferenceProvider;

        this.safeFixApplier =
                safeFixApplier;
    }

    /*
     * ============================================================
     * CREATE SCAN
     * ============================================================
     */

    public Scan createScan(
            String sourceCode,
            String fileName
    ) {

        Long id =
                sequence.getAndIncrement();

        String resolvedFileName =
                fileName == null ||
                fileName.isBlank()
                        ? "Sample.java"
                        : fileName;

        String resolvedSourceCode =
                sourceCode == null
                        ? ""
                        : sourceCode;

        Scan scan =
                new Scan(
                        id,
                        resolvedFileName,
                        resolvedSourceCode
                );

        List<Finding> findings =
                staticAnalysisService.analyzeSource(
                        resolvedSourceCode,
                        resolvedFileName
                );

        /*
         * CURRENT FINDINGS
         */
        scan.setFindings(
                new ArrayList<>(findings)
        );

        /*
         * ORIGINAL BASELINE
         *
         * This is intentionally created only once.
         */
        scan.setBaselineFindings(
                new ArrayList<>(findings)
        );

        scan.setStatus(
                findings.isEmpty()
                        ? "completed-no-findings"
                        : "completed-with-findings"
        );

        scan.setUpdatedAt(
                LocalDateTime.now()
        );

        scans.put(
                id,
                scan
        );

        return scan;
    }

    /*
     * ============================================================
     * GET SCAN
     * ============================================================
     */

    public Scan getScan(Long id) {

        if (id == null) {
            return null;
        }

        return scans.get(id);
    }

    /*
     * ============================================================
     * GET FINDINGS
     * ============================================================
     */

    public List<Finding> getFindings(Long id) {

        Scan scan =
                scans.get(id);

        if (scan == null) {
            return List.of();
        }

        if (scan.getFindings() == null) {
            return List.of();
        }

        return scan.getFindings();
    }

    /*
     * ============================================================
     * RESCAN
     * ============================================================
     *
     * Re-analyzes the CURRENT source code.
     *
     * IMPORTANT:
     *
     * baselineFindings is NEVER changed here.
     * ============================================================
     */

    public Scan rescan(Long scanId) {
        return rescan(
                scanId,
                null,
                null
        );
    }

    /**
     * Re-analyzes a scan, optionally against source supplied by the caller.
     *
     * <p>When {@code sourceCode} is non blank the scan is re-pointed at that
     * source before analysis. The dashboard uses this so that "re-scan" always
     * analyzes the code currently in the editor instead of the source stored by
     * the previous scan. When it is null or blank the stored source is used,
     * which keeps the existing endpoint contract intact.
     *
     * <p>baselineFindings is NEVER changed here: verification must keep
     * comparing the current code against the original baseline.
     */
    public Scan rescan(
            Long scanId,
            String sourceCode,
            String fileName
    ) {

        if (scanId == null) {
            throw new IllegalArgumentException(
                    "Scan ID cannot be null."
            );
        }

        Scan scan =
                scans.get(scanId);

        if (scan == null) {
            throw new IllegalArgumentException(
                    "Scan not found: " + scanId
            );
        }

        /*
         * Current editor contents replace the stored current source. The
         * baseline is deliberately left alone.
         */
        if (sourceCode != null &&
                !sourceCode.isBlank()) {

            scan.setSourceCode(
                    sourceCode
            );
        }

        if (fileName != null &&
                !fileName.isBlank()) {

            scan.setFileName(
                    fileName
            );
        }

        String analyzedSource =
                scan.getSourceCode();

        List<Finding> refreshedFindings =
                staticAnalysisService.analyzeSource(
                        analyzedSource,
                        scan.getFileName()
                );

        /*
         * Update CURRENT findings only.
         */
        scan.setFindings(
                new ArrayList<>(
                        refreshedFindings
                )
        );

        scan.setStatus(
                refreshedFindings.isEmpty()
                        ? "rescanned-no-findings"
                        : "rescanned-with-findings"
        );

        scan.setUpdatedAt(
                LocalDateTime.now()
        );

        return scan;
    }

    /*
     * ============================================================
     * ANALYZE FINDING
     * ============================================================
     */

    public AiAnalysisResponse analyzeFinding(
            String findingId
    ) {

        Finding finding =
                findFinding(findingId);

        if (finding == null) {
            throw new IllegalArgumentException(
                    "Finding not found: " +
                            findingId
            );
        }

        Long scanId =
                findScanIdForFinding(
                        findingId
                );

        if (scanId == null) {
            throw new IllegalArgumentException(
                    "Scan not found for finding: " +
                            findingId
            );
        }

        Scan scan =
                scans.get(scanId);

        if (scan == null) {
            throw new IllegalArgumentException(
                    "Scan not found: " +
                            scanId
            );
        }

        AiAnalysisRequest request =
                buildAiAnalysisRequest(
                        scan,
                        finding
                );

        return aiInferenceProvider.analyze(
                request
        );
    }

    /*
     * ============================================================
     * PROPOSE FIX
     * ============================================================
     */

    public FixProposal proposeFix(
            String findingId
    ) {

        Finding finding =
                findFinding(findingId);

        if (finding == null) {
            throw new IllegalArgumentException(
                    "Finding not found: " +
                            findingId
            );
        }

        Long scanId =
                findScanIdForFinding(
                        findingId
                );

        if (scanId == null) {
            throw new IllegalArgumentException(
                    "Scan not found for finding: " +
                            findingId
            );
        }

        Scan scan =
                scans.get(scanId);

        if (scan == null) {
            throw new IllegalArgumentException(
                    "Scan not found: " +
                            scanId
            );
        }

        AiAnalysisRequest request =
                buildAiAnalysisRequest(
                        scan,
                        finding
                );

        return aiInferenceProvider.proposeFix(
                request
        );
    }

    /*
     * ============================================================
     * APPLY FIX TO SCAN
     * ============================================================
     *
     * A model generated correction is NEVER written over the whole file.
     *
     * The correction is scoped to the line range that the finding's own
     * metadata proves, reusing:
     *
     *     Finding.line
     *     Finding.codeSnippet
     *
     * When that range cannot be confirmed exactly, or when the stored source
     * no longer matches the finding metadata, the source code is left
     * untouched and a "requires-review" result is returned so a developer can
     * apply the change manually.
     * ============================================================
     */

    public FixApplicationResult applyFixToScan(
            Long scanId,
            String findingId,
            String correctedCode
    ) {

        if (scanId == null) {
            throw new IllegalArgumentException(
                    "Scan ID cannot be null."
            );
        }

        Scan scan =
                scans.get(scanId);

        if (scan == null) {
            throw new IllegalArgumentException(
                    "Scan not found: " + scanId
            );
        }

        if (findingId == null ||
                findingId.isBlank()) {

            throw new IllegalArgumentException(
                    "Finding ID cannot be empty."
            );
        }

        Finding finding =
                findCurrentFinding(
                        scan,
                        findingId
                );

        if (finding == null) {
            return FixApplicationResult.requiresReview(
                    "Finding " + findingId + " is not part of the current findings of scan "
                            + scanId + "."
                            + " Re-scan the file and review the finding before applying a fix.",
                    0,
                    0,
                    scan
            );
        }

        SafeFixApplier.PatchResult patch =
                safeFixApplier.apply(
                        scan.getSourceCode(),
                        finding,
                        correctedCode
                );

        /*
         * The stored source is NOT touched when the patch is refused.
         */
        if (patch.requiresReview()) {
            return FixApplicationResult.requiresReview(
                    patch.message(),
                    patch.startLine(),
                    patch.endLine(),
                    scan
            );
        }

        if (!patch.applied()) {
            /*
             * The requested correction is already present in the source.
             * Nothing is written and nothing is re-analyzed.
             */
            scan.setUpdatedAt(
                    LocalDateTime.now()
            );

            return FixApplicationResult.alreadyApplied(
                    patch.startLine(),
                    patch.endLine(),
                    patch.message(),
                    scan
            );
        }

        /*
         * The corrected code is analyzed BEFORE the scan is mutated.
         *
         * When the model output cannot even be analyzed it is not trusted, so
         * the stored source stays exactly as it was.
         */
        List<Finding> refreshedFindings;

        try {
            refreshedFindings =
                    staticAnalysisService.analyzeSource(
                            patch.sourceCode(),
                            scan.getFileName()
                    );

        } catch (RuntimeException exception) {
            scan.setUpdatedAt(
                    LocalDateTime.now()
            );

            return FixApplicationResult.requiresReview(
                    "The proposed correction could not be analyzed ("
                            + exception.getMessage() + "), so the source was left unchanged."
                            + " Review the change manually.",
                    patch.startLine(),
                    patch.endLine(),
                    scan
            );
        }

        /*
         * Update CURRENT source code with the scoped patch only.
         */
        scan.setSourceCode(
                patch.sourceCode()
        );

        /*
         * Update CURRENT findings.
         */
        scan.setFindings(
                new ArrayList<>(
                        refreshedFindings
                )
        );

        /*
         * NEVER modify baselineFindings.
         */
        scan.setStatus(
                refreshedFindings.isEmpty()
                        ? "fixed-no-findings"
                        : "fixed-with-findings"
        );

        scan.setUpdatedAt(
                LocalDateTime.now()
        );

        return FixApplicationResult.applied(
                patch.startLine(),
                patch.endLine(),
                patch.message(),
                scan
        );
    }
    /*
     * ============================================================
     * APPLY FIX TO FINDING
     * ============================================================
     */

    public FixApplicationResult applyFixToFinding(
            String findingId,
            String correctedCode
    ) {

        if (findingId == null ||
                findingId.isBlank()) {

            throw new IllegalArgumentException(
                    "Finding ID cannot be empty."
            );
        }

        Long scanId =
                findScanIdForFinding(
                        findingId
                );

        if (scanId == null) {
            throw new IllegalArgumentException(
                    "Scan not found for finding: " +
                            findingId
            );
        }

        return applyFixToScan(
                scanId,
                findingId,
                correctedCode
        );
    }

    /*
     * ============================================================
     * FIND CURRENT FINDING
     * ============================================================
     *
     * Baseline findings are intentionally excluded.
     *
     * Their line numbers describe the ORIGINAL source, so a fix may never be
     * scoped through them.
     * ============================================================
     */

    private Finding findCurrentFinding(
            Scan scan,
            String findingId
    ) {

        if (scan == null ||
                scan.getFindings() == null) {
            return null;
        }

        for (Finding finding :
                scan.getFindings()) {

            if (finding != null &&
                    findingId.equals(
                            finding.getId()
                    )) {

                return finding;
            }
        }

        return null;
    }

    /*
     * ============================================================
     * VERIFY
     * ============================================================
     *
     * ORIGINAL BASELINE
     *        VS
     * CURRENT CODE
     *
     * ============================================================
     */

    public VerificationResult verify(
            Long scanId
    ) {

        if (scanId == null) {
            throw new IllegalArgumentException(
                    "Scan ID cannot be null."
            );
        }

        Scan scan =
                scans.get(scanId);

        if (scan == null) {
            throw new IllegalArgumentException(
                    "Scan not found: " +
                            scanId
            );
        }

        /*
         * Re-run analysis against CURRENT source.
         */
        List<Finding> currentFindings =
                staticAnalysisService.analyzeSource(
                        scan.getSourceCode(),
                        scan.getFileName()
                );

        /*
         * Update CURRENT findings.
         */
        scan.setFindings(
                new ArrayList<>(
                        currentFindings
                )
        );

        /*
         * Load ORIGINAL baseline.
         */
        List<Finding> baselineFindings =
                scan.getBaselineFindings();

        if (baselineFindings == null) {
            baselineFindings =
                    new ArrayList<>();
        }

        VerificationResult result =
                new VerificationResult();

        result.setBaselineFindingCount(
                baselineFindings.size()
        );

        result.setCurrentFindingCount(
                currentFindings.size()
        );

        /*
         * IDs from original baseline.
         */
        List<String> baselineIds =
                baselineFindings.stream()
                        .map(Finding::getId)
                        .filter(id -> id != null)
                        .toList();

        /*
         * IDs from current analysis.
         */
        List<String> currentIds =
                currentFindings.stream()
                        .map(Finding::getId)
                        .filter(id -> id != null)
                        .toList();

        /*
         * Determine which original findings
         * were resolved or remain.
         */
        for (Finding baseline :
                baselineFindings) {

            if (baseline.getId() == null) {
                continue;
            }

            if (currentIds.contains(
                    baseline.getId()
            )) {

                result.getStillPresent()
                        .add(
                                baseline.getTitle()
                        );

            } else {

                result.getResolved()
                        .add(
                                baseline.getTitle()
                        );
            }
        }

        /*
         * Determine newly introduced findings.
         */
        for (Finding current :
                currentFindings) {

            if (current.getId() == null) {
                continue;
            }

            if (!baselineIds.contains(
                    current.getId()
            )) {

                result.getNewFindings()
                        .add(
                                current.getTitle()
                        );
            }
        }

        /*
         * Verification succeeds only when:
         *
         * 1. No baseline finding remains.
         * 2. No new finding appeared.
         */
        boolean verified =
                result.getStillPresent().isEmpty()
                        &&
                result.getNewFindings().isEmpty();

        result.setVerified(
                verified
        );

        scan.setStatus(
                verified
                        ? "verified"
                        : "verification-failed"
        );

        scan.setUpdatedAt(
                LocalDateTime.now()
        );

        return result;
    }

    /*
     * ============================================================
     * FIND FINDING
     * ============================================================
     */

    private Finding findFinding(
            String findingId
    ) {

        if (findingId == null) {
            return null;
        }

        /*
         * Finding ids are content derived, so the same id can be present in
         * several scans. Prefer a scan where the finding is still active,
         * and prefer the newest scan, so a re-scan always wins over an older
         * scan that may already have been patched.
         */
        Finding active =
                findFindingInScans(
                        findingId,
                        true
                );

        if (active != null) {
            return active;
        }

        return findFindingInScans(
                findingId,
                false
        );
    }

    private Finding findFindingInScans(
            String findingId,
            boolean active
    ) {

        for (Scan scan :
                scansNewestFirst()) {

            List<Finding> candidates =
                    active
                            ? scan.getFindings()
                            : scan.getBaselineFindings();

            if (candidates == null) {
                continue;
            }

            for (Finding finding :
                    candidates) {

                if (findingId.equals(
                        finding.getId()
                )) {

                    return finding;
                }
            }
        }

        return null;
    }

    /*
     * ============================================================
     * FIND SCAN ID FOR FINDING
     * ============================================================
     */

    public Long findScanIdForFinding(
            String findingId
    ) {

        if (findingId == null) {
            return null;
        }

        /*
         * Active findings first: applying a fix must resolve to the scan that
         * still owns the finding. Baseline findings are only consulted when no
         * scan reports the finding as active, which is what makes a repeated
         * correction detectable as already applied instead of being written
         * a second time.
         */
        Long active =
                findScanIdForFindingIn(
                        findingId,
                        true
                );

        if (active != null) {
            return active;
        }

        return findScanIdForFindingIn(
                findingId,
                false
        );
    }

    private Long findScanIdForFindingIn(
            String findingId,
            boolean active
    ) {

        for (Scan scan :
                scansNewestFirst()) {

            List<Finding> candidates =
                    active
                            ? scan.getFindings()
                            : scan.getBaselineFindings();

            if (candidates == null) {
                continue;
            }

            for (Finding finding :
                    candidates) {

                if (findingId.equals(
                        finding.getId()
                )) {

                    return scan.getId();
                }
            }
        }

        return null;
    }

    /*
     * ============================================================
     * SCAN ITERATION ORDER
     * ============================================================
     */

    private List<Scan> scansNewestFirst() {

        List<Scan> ordered =
                new ArrayList<>(scans.values());

        ordered.sort(
                Comparator.comparing(
                        Scan::getId
                ).reversed()
        );

        return ordered;
    }

    /*
     * ============================================================
     * BUILD AI REQUEST
     * ============================================================
     */

    private AiAnalysisRequest buildAiAnalysisRequest(
            Scan scan,
            Finding finding
    ) {

        return new AiAnalysisRequest(
                scan.getSourceCode(),
                scan.getFileName(),
                finding.getAnalyzer(),
                finding.getRule(),
                finding.getCategory(),
                finding.getSeverity(),
                finding.getMessage(),
                finding.getCodeSnippet(),
                finding.getSuggestedContext()
        );
    }
}
