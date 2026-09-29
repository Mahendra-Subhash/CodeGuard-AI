package com.codeguardai.ai;

import com.codeguardai.domain.FixProposal;
import org.springframework.stereotype.Component;

@Component("localHeuristicAiProvider")
public class LocalAiInferenceProvider implements AiInferenceProvider {
    @Override
    public String providerName() {
        return "local-heuristic-ai";
    }

    @Override
    public AiAnalysisResponse analyze(AiAnalysisRequest request) {
        String lower = request.message().toLowerCase();
        String summary = "Deterministic finding matched a Java risk pattern.";
        String explanation = "The static analyzer identified a pattern consistent with a real defect that may affect reliability or security.";
        String rootCause = "Input values or resource cleanup are being handled without validation or proper lifecycle control.";
        String risk = "The defect can cause crashes, security exposure, or unstable resource handling depending on the exact code path.";
        String recommendedFix = "Use safer APIs and explicit validation to ensure data and resource handling are explicit and controlled.";
        String correctedCode = buildCorrectedCode(request);
        String confidence = "0.88";
        String verificationHint = "Re-run the static analyzer after the change to confirm the finding is removed or downgraded.";

        if (lower.contains("sql")) {
            summary = "Unsafe SQL composition creates injection risk.";
            explanation = "The code concatenates untrusted input directly into a SQL statement. A prepared statement separates data from the query itself and prevents injection.";
            rootCause = "User-controlled input is embedded directly into the SQL string.";
            risk = "Attackers can manipulate the query, expose data, or alter application behavior.";
            recommendedFix = "Replace string concatenation with a PreparedStatement and bind parameters.";
            correctedCode = "PreparedStatement preparedStatement = connection.prepareStatement(\"SELECT * FROM users WHERE username = ?\");\npreparedStatement.setString(1, username);\nResultSet rs = preparedStatement.executeQuery();";
        } else if (lower.contains("nullpointer")) {
            summary = "Null values are dereferenced without validation.";
            explanation = "A possibly null object is dereferenced before a null check, which can crash the application.";
            rootCause = "The code assumes a value is never null and calls methods without guarding the value.";
            risk = "The application can crash during runtime when input is absent or unset.";
            recommendedFix = "Guard against null before dereferencing and use safe comparisons.";
            correctedCode = "if (user != null && user.getName() != null) {\n    System.out.println(user.getName().trim());\n}";
        } else if (lower.contains("resource")) {
            summary = "A resource is opened without a guaranteed close.";
            explanation = "The file or stream remains open until garbage collection, which can leak descriptors and cause failures under load.";
            rootCause = "The resource is created without a try-with-resources block.";
            risk = "The process may exhaust file descriptors or memory resources.";
            recommendedFix = "Wrap the resource in try-with-resources so the close is guaranteed.";
            correctedCode = "try (FileInputStream fileInputStream = new FileInputStream(path)) {\n    // read bytes\n}\n";
        } else if (lower.contains("credential") || lower.contains("password")) {
            summary = "Sensitive credentials are embedded into source.";
            explanation = "Hard-coded secrets are difficult to rotate and easy to leak through source control or logs.";
            rootCause = "Credentials are stored directly in Java source instead of coming from protected configuration.";
            risk = "Secrets can be exposed accidentally or end up in logs or repositories.";
            recommendedFix = "Move credentials to environment variables or a secure configuration store.";
            correctedCode = "String password = System.getenv(\"DB_PASSWORD\");\n";
        } else if (lower.contains("equals") || lower.contains("hashcode")) {
            summary = "equals/hashCode contract is inconsistent.";
            explanation = "The equality logic and hash contract are not aligned, which can break collections and produce inconsistent lookups.";
            rootCause = "The class overrides one method without the other or returns values that do not match the same logical equality semantics.";
            risk = "Hash-based collections may not behave correctly, leading to missing entries or duplicate objects.";
            recommendedFix = "Implement both methods consistently and use the same fields in both.";
            correctedCode = "@Override\npublic boolean equals(Object obj) {\n    if (this == obj) return true;\n    if (obj == null || getClass() != obj.getClass()) return false;\n    User user = (User) obj;\n    return Objects.equals(id, user.id);\n}\n\n@Override\npublic int hashCode() {\n    return Objects.hash(id);\n}\n";
        } else if (lower.contains("swallowed") || lower.contains("exception")) {
            summary = "Exception handling suppresses the failure signal.";
            explanation = "The method catches an exception but discards it without logging or rethrowing, which hides the real problem.";
            rootCause = "The code handles the exception without preserving diagnostics or a clear recovery path.";
            risk = "The system may continue in a broken state without alerting operators.";
            recommendedFix = "Log the issue and either rethrow or handle it explicitly with a recovery strategy.";
            correctedCode = "catch (IOException ex) {\n    logger.error(\"Failed to read file\", ex);\n    throw new IllegalStateException(\"Read failed\", ex);\n}\n";
        }

        return new AiAnalysisResponse(summary, explanation, rootCause, risk, recommendedFix, correctedCode, confidence, verificationHint);
    }

    @Override
    public FixProposal proposeFix(AiAnalysisRequest request) {
        String originalCode = request.snippet() == null ? request.sourceCode() : request.snippet();
        String correctedCode = buildCorrectedCode(request);
        return new FixProposal(originalCode, correctedCode, buildDiff(originalCode, correctedCode), "AI recommendation generated for the selected finding.");
    }

    private String buildCorrectedCode(AiAnalysisRequest request) {
        String lower = request.message().toLowerCase();
        if (lower.contains("sql")) {
            return "PreparedStatement preparedStatement = connection.prepareStatement(\"SELECT * FROM users WHERE username = ?\");\npreparedStatement.setString(1, username);\nResultSet rs = preparedStatement.executeQuery();";
        }
        if (lower.contains("nullpointer")) {
            return "if (user != null && user.getName() != null) {\n    System.out.println(user.getName().trim());\n}";
        }
        if (lower.contains("resource")) {
            return "try (FileInputStream fileInputStream = new FileInputStream(path)) {\n    // read bytes\n}";
        }
        if (lower.contains("credential") || lower.contains("password")) {
            return "String password = System.getenv(\"DB_PASSWORD\");";
        }
        if (lower.contains("equals") || lower.contains("hashcode")) {
            return "@Override\npublic boolean equals(Object obj) {\n    if (this == obj) return true;\n    if (obj == null || getClass() != obj.getClass()) return false;\n    User user = (User) obj;\n    return Objects.equals(id, user.id);\n}\n\n@Override\npublic int hashCode() {\n    return Objects.hash(id);\n}";
        }
        if (lower.contains("swallowed") || lower.contains("exception")) {
            return "catch (IOException ex) {\n    logger.error(\"Failed to read file\", ex);\n    throw new IllegalStateException(\"Read failed\", ex);\n}";
        }
        return "// Recommended fix in context\n" + (request.snippet() == null ? "" : request.snippet());
    }

    private String buildDiff(String originalCode, String correctedCode) {
        if (originalCode == null || originalCode.isBlank()) {
            return "- no current code available\n+ " + correctedCode;
        }
        return "- " + originalCode.replace("\n", "\n- ") + "\n+ " + correctedCode.replace("\n", "\n+ ");
    }
}
