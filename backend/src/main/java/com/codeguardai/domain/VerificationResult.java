package com.codeguardai.domain;

import java.util.ArrayList;
import java.util.List;

public class VerificationResult {

    private boolean verified;

    private List<String> resolved = new ArrayList<>();

    private List<String> stillPresent = new ArrayList<>();

    private List<String> newFindings = new ArrayList<>();

    private int baselineFindingCount;

    private int currentFindingCount;

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public List<String> getResolved() {
        return resolved;
    }

    public void setResolved(List<String> resolved) {
        this.resolved = resolved;
    }

    public List<String> getStillPresent() {
        return stillPresent;
    }

    public void setStillPresent(List<String> stillPresent) {
        this.stillPresent = stillPresent;
    }

    public List<String> getNewFindings() {
        return newFindings;
    }

    public void setNewFindings(List<String> newFindings) {
        this.newFindings = newFindings;
    }

    public int getBaselineFindingCount() {
        return baselineFindingCount;
    }

    public void setBaselineFindingCount(int baselineFindingCount) {
        this.baselineFindingCount = baselineFindingCount;
    }

    public int getCurrentFindingCount() {
        return currentFindingCount;
    }

    public void setCurrentFindingCount(int currentFindingCount) {
        this.currentFindingCount = currentFindingCount;
    }
}