package com.javaee.common.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Parses the cross-origin allow list as exact browser origins.
 *
 * <p>An origin may contain only scheme, host and optional port. Paths,
 * queries, fragments, credentials and wildcard patterns are deliberately
 * rejected so that the MVC and gateway layers share the same boundary.</p>
 */
public final class CorsOriginPolicy {

    private static final String LOCAL_DEVELOPMENT_ORIGIN = "http://localhost:5173";

    private CorsOriginPolicy() {
    }

    public static List<String> parse(String configured, boolean production) {
        if (configured == null || configured.isBlank()) {
            if (production) {
                throw new IllegalStateException(
                        "security.cors.allowed-origins must be configured in production");
            }
            return List.of(LOCAL_DEVELOPMENT_ORIGIN);
        }

        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String candidate : configured.split(",")) {
            String origin = candidate.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if ("*".equals(origin) || origin.contains("*")) {
                throw new IllegalArgumentException("CORS wildcard origins are not allowed: " + origin);
            }
            origins.add(validateExactOrigin(origin));
        }

        if (origins.isEmpty()) {
            if (production) {
                throw new IllegalStateException(
                        "security.cors.allowed-origins must be configured in production");
            }
            return List.of(LOCAL_DEVELOPMENT_ORIGIN);
        }
        return List.copyOf(new ArrayList<>(origins));
    }

    private static String validateExactOrigin(String origin) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid CORS origin: " + origin, exception);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("CORS origin must use http or https: " + origin);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getPath() != null && !uri.getPath().isEmpty()
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("CORS origin must not contain path, query, fragment or credentials: " + origin);
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
    }
}
