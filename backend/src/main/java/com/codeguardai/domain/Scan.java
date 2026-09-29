package com.codeguardai.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Scan {

    private Long id;
    private String fileName;
    private String sourceCode;

    private List<Finding> findings = new ArrayList<>();

    /*
     * Original findings from the first scan.
     *
     * This is kept separately from the current findings so that verification
     * can determine which original findings were resolved.
     */
    private List<Finding> baselineFindings = new ArrayList<>();

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Scan() {
    }

    public Scan(Long id, String fileName, String sourceCode) {
        this.id = id;
        this.fileName = fileName;
        this.sourceCode = sourceCode;
        this.status = "completed";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public void setFindings(List<Finding> findings) {
        this.findings = findings;
    }

    public List<Finding> getBaselineFindings() {
        return baselineFindings;
    }

    public void setBaselineFindings(List<Finding> baselineFindings) {
        this.baselineFindings = baselineFindings;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}