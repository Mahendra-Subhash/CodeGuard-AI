package com.codeguardai.api;

import com.codeguardai.domain.Finding;
import com.codeguardai.domain.FixApplicationResult;
import com.codeguardai.domain.Scan;
import com.codeguardai.domain.VerificationResult;
import com.codeguardai.service.ScanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping("/scans")
    public ResponseEntity<Scan> createScan(@RequestBody ScanRequest request) {
        return ResponseEntity.ok(
                scanService.createScan(
                        request.getSourceCode(),
                        request.getFileName()
                )
        );
    }

    @GetMapping("/scans/{id}")
    public ResponseEntity<Scan> getScan(@PathVariable Long id) {
        return ResponseEntity.ok(scanService.getScan(id));
    }

    @GetMapping("/scans/{id}/findings")
    public ResponseEntity<List<Finding>> getFindings(@PathVariable Long id) {
        return ResponseEntity.ok(scanService.getFindings(id));
    }

    @PostMapping("/findings/{findingId}/analyze")
    public ResponseEntity<?> analyzeFinding(@PathVariable String findingId) {
        return ResponseEntity.ok(
                scanService.analyzeFinding(findingId)
        );
    }

    @PostMapping("/findings/{findingId}/propose-fix")
    public ResponseEntity<?> proposeFix(@PathVariable String findingId) {
        return ResponseEntity.ok(
                scanService.proposeFix(findingId)
        );
    }

    @PostMapping("/findings/{findingId}/apply-fix")
    public ResponseEntity<?> applyFix(
            @PathVariable String findingId,
            @RequestBody Map<String, String> request
    ) {
        String correctedCode = request.get("correctedCode");

        if (correctedCode == null || correctedCode.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "correctedCode is required"));
        }

        FixApplicationResult result =
                scanService.applyFixToFinding(
                        findingId,
                        correctedCode
                );

        if (result.requiresReview()) {
            /*
             * The stored source code was NOT modified.
             *
             * The correction could not be scoped to a verified line range, so a
             * developer has to review and apply it manually.
             */
            Map<String, Object> failure =
                    Map.of(
                            "status", result.status(),
                            "error", result.message(),
                            "findingId", findingId,
                            "startLine", result.startLine(),
                            "endLine", result.endLine(),
                            "sourceModified", false
                    );

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(failure);
        }

        return ResponseEntity.ok(
                result.scan()
        );
    }

    /**
     * Re-analyzes a scan.
     *
     * <p>The request body is optional. When it carries {@code sourceCode}, the
     * scan analyzes the code the developer currently has open in the editor
     * instead of the source stored by the previous scan. Without a body the
     * behavior is unchanged, so existing callers keep working.
     */
    @PostMapping("/scans/{id}/rescan")
    public ResponseEntity<Scan> rescan(
            @PathVariable Long id,
            @RequestBody(required = false) ScanRequest request
    ) {
        return ResponseEntity.ok(
                scanService.rescan(
                        id,
                        request == null
                                ? null
                                : request.getSourceCode(),
                        request == null
                                ? null
                                : request.getFileName()
                )
        );
    }

    @PostMapping("/scans/{id}/verify")
    public ResponseEntity<VerificationResult> verify(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(
                scanService.verify(id)
        );
    }
}
