package com.codeguardai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Controls CodeGuard's own optional heuristic layer.
 *
 * <p>The heuristic layer performs simple token based checks that are NOT produced by
 * PMD, Checkstyle or SpotBugs. Those findings are reported under the dedicated
 * {@code CodeGuard-Heuristics} analyzer name and are never attributed to a
 * third-party tool.
 *
 * <p>It is disabled by default so that a normal or judge-facing scan reports only
 * genuine PMD, Checkstyle and SpotBugs results. The educational demo configuration
 * can enable it explicitly with {@code codeguard.heuristics.enabled=true}.
 */
@Component
@ConfigurationProperties(prefix = "codeguard.heuristics")
public class HeuristicsProperties {

    private boolean enabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}