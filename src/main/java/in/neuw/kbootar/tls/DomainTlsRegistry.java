package in.neuw.kbootar.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resolves per-domain TLS configuration by matching the outbound request hostname
 * against the entries declared in application.yml under kbootar.tls.domains.
 *
 * Matching order per lookup:
 *   1. Exact match          — "api.example.com" == "api.example.com"
 *   2. Wildcard match       — "*.example.com"   matches any single-level subdomain
 * First match in declaration order wins.
 *
 * All cert/key paths resolve on the proxy host filesystem.
 * Nothing TLS-related is accepted from API callers.
 */
@Service
public class DomainTlsRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainTlsRegistry.class);

    private final List<DomainTlsProperties.DomainEntry> entries;

    public DomainTlsRegistry(DomainTlsProperties properties) {
        this.entries = properties.domains();
        if (!entries.isEmpty()) {
            log.info("Domain TLS registry: {} domain(s) configured", entries.size());
            entries.forEach(e -> log.debug("  {} — mTLS={} customCA={}", e.hostname(), e.hasMtls(), e.hasCustomCa()));
        }
    }

    /**
     * Returns the TLS config for the given hostname, or empty if none is configured.
     */
    public Optional<DomainTlsProperties.DomainEntry> lookup(String hostname) {
        if (hostname == null || entries.isEmpty()) return Optional.empty();
        String host = hostname.toLowerCase();

        // 1. exact match
        Optional<DomainTlsProperties.DomainEntry> exact = entries.stream()
                .filter(e -> e.hostname().equalsIgnoreCase(host))
                .findFirst();
        if (exact.isPresent()) return exact;

        // 2. wildcard match — "*.example.com" matches "api.example.com", "gw.example.com", etc.
        //    does NOT match "api.sub.example.com" (only one level)
        return entries.stream()
                .filter(e -> e.hostname().startsWith("*."))
                .filter(e -> {
                    String suffix = e.hostname().substring(1); // ".example.com"
                    return host.endsWith(suffix) && host.indexOf('.') == host.length() - suffix.length();
                })
                .findFirst();
    }
}
