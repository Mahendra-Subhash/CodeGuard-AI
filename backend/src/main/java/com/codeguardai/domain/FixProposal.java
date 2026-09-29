package com.codeguardai.domain;

public record FixProposal(String originalCode, String proposedCode, String diff, String rationale) {}
