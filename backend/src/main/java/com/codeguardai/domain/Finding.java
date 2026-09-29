package com.codeguardai.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Finding {

    private String id;
    private String analyzer;
    private String rule;
    private String category;
    private String severity;
    private String title;
    private String message;
    private String file;
    private int line;
    private int column;
    private String codeSnippet;
    private String suggestedContext;

    public Finding() {
    }

    public Finding(
            String analyzer,
            String rule,
            String category,
            String severity,
            String title,
            String message,
            String file,
            int line,
            int column,
            String codeSnippet,
            String suggestedContext
    ) {
        this.analyzer = analyzer;
        this.rule = rule;
        this.category = category;
        this.severity = severity;
        this.title = title;
        this.message = message;
        this.file = file;
        this.line = line;
        this.column = column;
        this.codeSnippet = codeSnippet;
        this.suggestedContext = suggestedContext;
        this.id = generateStableId();
    }

    /**
     * Generates a deterministic identifier.
     *
     * Line and column are deliberately excluded because a fix can move
     * code without changing the underlying issue.
     */
    public String generateStableId() {
        String identity = String.join(
                "|",
                safe(analyzer),
                safe(rule),
                safe(category),
                safe(title),
                safe(message),
                safe(file),
                normalize(codeSnippet)
        );

        return sha256(identity).substring(0, 16);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();

            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public void setAnalyzer(String analyzer) {
        this.analyzer = analyzer;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public String getCodeSnippet() {
        return codeSnippet;
    }

    public void setCodeSnippet(String codeSnippet) {
        this.codeSnippet = codeSnippet;
    }

    public String getSuggestedContext() {
        return suggestedContext;
    }

    public void setSuggestedContext(String suggestedContext) {
        this.suggestedContext = suggestedContext;
    }
}