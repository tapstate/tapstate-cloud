package io.tapstate.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for legal-agreement tracking.
 * <p>
 * Bound to the {@code tapstate.legal} prefix. The {@code currentVersion} string
 * identifies the legal document set the backend considers up-to-date; new
 * registrations must agree to this version, and existing users are auto-upgraded
 * on next login when their recorded version is stale.
 */
@Component
@ConfigurationProperties(prefix = "tapstate.legal")
public class LegalConfig {

    /** Current legal document version string, e.g. "legal-v1.0-2026-10-27". */
    private String currentVersion = "legal-v1.0-2026-10-27";

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }
}
