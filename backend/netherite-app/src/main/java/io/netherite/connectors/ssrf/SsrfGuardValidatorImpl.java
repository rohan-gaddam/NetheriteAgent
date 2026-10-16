package io.netherite.connectors.ssrf;

import io.netherite.connectors.spi.SsrfGuardValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * SSRF & DNS Egress Guard.
 *
 * <p><b>Strict mode</b> ({@link #isUriPermitted}) is used for tenant-influenceable outbound
 * destinations (registered resource base URLs, health checks). It blocks RFC1918 networks,
 * loopback, link-local, cloud instance metadata endpoints, shared/CGNAT address space,
 * documentation &amp; benchmark ranges plus IPv6 special-purpose ranges. Fail-closed: DNS
 * resolution failures and malformed URIs are rejected.</p>
 *
 * <p><b>Operator-relaxed mode</b> ({@link #isUriPermittedForOperatorConfigured}) is used for
 * destinations that are configured by the control-plane operator (OIDC issuer/JWKS/token
 * endpoints, OPA, notification webhooks). An internal IdP or policy engine on a private
 * network is legitimate, so RFC1918/loopback are permitted when
 * {@code netherite.security.ssrf-guard.allow-private-operator-configured=true}. The explicit
 * {@code blocked-cidrs} deny list is still enforced, and cloud-metadata, link-local, special
 * purpose and IPv6 special-use ranges remain blocked.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "netherite.security.ssrf-guard.enabled", havingValue = "true")
public class SsrfGuardValidatorImpl implements SsrfGuardValidator {

    /** Implicit deny ranges always applied in strict mode. */
    private static final List<CidrBlock> STRICT_AUTO_BLOCKED = parseList(
            "127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,100.64.0.0/10,"
                    + "192.0.0.0/24,192.0.2.0/24,198.18.0.0/15,198.51.100.0/24,203.0.113.0/24,224.0.0.0/4,240.0.0.0/4");

    /** Implicit deny ranges always applied even in operator-relaxed mode. */
    private static final List<CidrBlock> OPERATOR_AUTO_BLOCKED = parseList(
            "169.254.0.0/16,0.0.0.0/8,100.64.0.0/10,192.0.0.0/24,192.0.2.0/24,198.18.0.0/15,"
                    + "198.51.100.0/24,203.0.113.0/24,224.0.0.0/4,240.0.0.0/4");

    /** Explicit deny ranges provided by configuration — enforced in both modes. */
    private static final List<Integer> DEFAULT_BLOCKED_PORTS = List.of(
            22, 23, 25, 53, 111, 135, 139, 445, 1433, 1521, 2049, 2375, 2376, 3306,
            3389, 5432, 5433, 5984, 6379, 6443, 9000, 9042, 9200, 9300, 11211, 15672, 27017);

    private final List<CidrBlock> configuredBlockedRanges;
    private final List<Integer> blockedPorts;
    private final boolean allowPrivateOperatorConfigured;

    public SsrfGuardValidatorImpl(List<String> blockedCidrs) {
        this(blockedCidrs != null ? blockedCidrs : List.of(), DEFAULT_BLOCKED_PORTS, false);
    }

    @Autowired
    public SsrfGuardValidatorImpl(
            @Value("${netherite.security.ssrf-guard.blocked-cidrs:}") List<String> blockedCidrs,
            @Value("${netherite.security.ssrf-guard.blocked-ports:}") List<Integer> blockedPorts,
            @Value("${netherite.security.ssrf-guard.allow-private-operator-configured:false}")
            boolean allowPrivateOperatorConfigured) {
        this.configuredBlockedRanges = (blockedCidrs != null ? blockedCidrs : List.<String>of()).stream()
                .map(CidrBlock::parse)
                .toList();
        this.blockedPorts = (blockedPorts != null && !blockedPorts.isEmpty()) ? blockedPorts : DEFAULT_BLOCKED_PORTS;
        this.allowPrivateOperatorConfigured = allowPrivateOperatorConfigured;
        log.info("SSRF guard initialized with {} configured deny ranges and {} blocked ports (operator-private-allowed={})",
                configuredBlockedRanges.size(), this.blockedPorts.size(), allowPrivateOperatorConfigured);
    }

    @Override
    public boolean isUriPermitted(URI targetUri) {
        return validateUri(targetUri, false);
    }

    @Override
    public boolean isUriPermittedForOperatorConfigured(URI targetUri) {
        return validateUri(targetUri, allowPrivateOperatorConfigured);
    }

    private boolean validateUri(URI targetUri, boolean operatorRelaxed) {
        if (targetUri == null) {
            return false;
        }

        String scheme = targetUri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            log.warn("SSRF guard rejecting non-HTTP scheme: {}", scheme);
            return false;
        }

        // Credentials in the URI are never permitted for outbound proxy-style requests
        if (targetUri.getUserInfo() != null && !targetUri.getUserInfo().isBlank()) {
            log.warn("SSRF guard rejecting URI with embedded userinfo");
            return false;
        }

        String host = targetUri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("SSRF guard rejecting URI with null/blank host");
            return false;
        }

        if (!isPortPermitted(targetUri)) {
            return false;
        }

        if (operatorRelaxed) {
            if (!validateResolvedAddresses(host, OPERATOR_AUTO_BLOCKED, "operator-configured"))
                return false;
        } else {
            // Block loopback by hostname before resolution
            if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) {
                log.warn("SSRF guard rejecting loopback host: {}", host);
                return false;
            }
            if (!validateResolvedAddresses(host, STRICT_AUTO_BLOCKED, "strict"))
                return false;
        }

        return true;
    }

    /**
     * Port policy: connections to well-known internal/management ports (SSH, database,
     * queue, cache, container-admin endpoints) are never legitimate egress targets for a
     * connector, health check or webhook dispatch. The list is configurable via
     * {@code netherite.security.ssrf-guard.blocked-ports} and enforced in both modes.
     * An explicit port of -1 means the scheme default (80 for http, 443 for https) and is
     * always permitted.
     */
    private boolean isPortPermitted(URI targetUri) {
        int port = targetUri.getPort();
        if (port <= 0) {
            return true;
        }
        if (blockedPorts.contains(port)) {
            log.warn("SSRF guard rejecting blocked port {} for host {}", port, targetUri.getHost());
            return false;
        }
        return true;
    }

    private boolean validateResolvedAddresses(String host, List<CidrBlock> implicitBlocks, String mode) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.warn("SSRF guard rejecting unresolvable host: {}", host);
            return false;
        }

        for (InetAddress address : addresses) {
            byte[] rawAddress = address.getAddress();

            if (rawAddress.length == 16) {
                // IPv4-mapped IPv6 (::ffff:a.b.c.d) — evaluate the embedded IPv4 address
                if (isIpv4Mapped(rawAddress)) {
                    int ipv4 = ((rawAddress[12] & 0xFF) << 24)
                            | ((rawAddress[13] & 0xFF) << 16)
                            | ((rawAddress[14] & 0xFF) << 8)
                            | (rawAddress[15] & 0xFF);
                    if (isInExplicitRange(ipv4) || isInImplicitRange(ipv4, implicitBlocks)) {
                        log.warn("SSRF guard blocking IPv4-mapped IPv6 address {} for host {}",
                                address.getHostAddress(), host);
                        return false;
                    }
                } else if (ipv6SpecialPurposeBlocked(rawAddress, implicitBlocks)) {
                    log.warn("SSRF guard blocking IPv6 address {} for host {}", address.getHostAddress(), host);
                    return false;
                }
            } else if (rawAddress.length == 4) {
                int ipv4 = ((rawAddress[0] & 0xFF) << 24)
                        | ((rawAddress[1] & 0xFF) << 16)
                        | ((rawAddress[2] & 0xFF) << 8)
                        | (rawAddress[3] & 0xFF);
                if (isInExplicitRange(ipv4) || isInImplicitRange(ipv4, implicitBlocks)) {
                    log.warn("SSRF guard blocking IPv4 address {} for host {}", address.getHostAddress(), host);
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isInExplicitRange(int ipv4) {
        for (CidrBlock block : configuredBlockedRanges) {
            if (block.containsIPv4(ipv4)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInImplicitRange(int ipv4, List<CidrBlock> implicitBlocks) {
        for (CidrBlock block : implicitBlocks) {
            if (block.containsIPv4(ipv4)) {
                return true;
            }
        }
        // Localhost hostname is already handled pre-resolution; any remaining loopback here
        // is covered by the implicit 127.0.0.0/8 list passed by the caller.
        return false;
    }

    private boolean isIpv4Mapped(byte[] address) {
        return address[0] == 0 && address[1] == 0 && address[2] == 0 && address[3] == 0
                && address[4] == 0 && address[5] == 0 && address[6] == 0 && address[7] == 0
                && address[8] == 0 && address[9] == 0 && address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
    }

    /**
     * IPv6 deny rules. Strict mode additionally blocks loopback, ULA and link-local.
     * Both modes block multicast, NAT64, 6to4, discard, documentation ranges and link-local.
     */
    private boolean ipv6SpecialPurposeBlocked(byte[] addr, List<CidrBlock> implicitBlocks) {
        // Multicast: ff00::/8
        if (addr[0] == (byte) 0xFF) {
            return true;
        }
        // Link-local: fe80::/10 (fe8x)
        if (addr[0] == (byte) 0xFE && (addr[1] & 0xC0) == 0x80) {
            return true;
        }
        // NAT64: 64:ff9b::/96
        if (addr[0] == 0x00 && addr[1] == 0x64 && addr[2] == (byte) 0xFF && addr[3] == (byte) 0x9B) {
            return true;
        }
        // 6to4: 2002::/16
        if (addr[0] == 0x20 && addr[1] == 0x02) {
            return true;
        }
        // Discard-only: 100::/64
        if (addr[0] == 0x01 && addr[1] == 0x00 && addr[2] == 0x00 && addr[3] == 0x00) {
            return true;
        }
        // Documentation: 2001:db8::/32
        if (addr[0] == 0x20 && addr[1] == 0x01 && addr[2] == 0x0D && addr[3] == (byte) 0xB8) {
            return true;
        }

        boolean hasImplicitIpv6 = implicitBlocks == STRICT_AUTO_BLOCKED;
        if (hasImplicitIpv6) {
            // Loopback ::1
            boolean isLoopback = addr[0] == 0 && addr[1] == 0 && addr[2] == 0 && addr[3] == 0
                    && addr[4] == 0 && addr[5] == 0 && addr[6] == 0 && addr[7] == 0
                    && addr[8] == 0 && addr[9] == 0 && addr[10] == 0 && addr[11] == 0
                    && addr[12] == 0 && addr[13] == 0 && addr[14] == 0 && addr[15] == 1;
            if (isLoopback) {
                return true;
            }
            // ULA: fc00::/7
            if ((addr[0] & 0xFE) == 0xFC) {
                return true;
            }
        }

        return false;
    }

    private static List<CidrBlock> parseList(String cidrs) {
        return Arrays.stream(cidrs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CidrBlock::parse)
                .toList();
    }

    /**
     * Represents an IPv4 CIDR block with parsed network address and prefix length.
     */
    static class CidrBlock {
        private final int networkAddress;
        private final int prefixLength;
        private final int subnetMask;

        private CidrBlock(int networkAddress, int prefixLength) {
            this.networkAddress = networkAddress;
            this.prefixLength = prefixLength;
            this.subnetMask = prefixLength == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLength));
        }

        static CidrBlock parse(String cidr) {
            String[] parts = cidr.trim().split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid CIDR notation: " + cidr);
            }

            String[] octets = parts[0].split("\\.");
            if (octets.length != 4) {
                throw new IllegalArgumentException("Invalid IPv4 address in CIDR: " + parts[0]);
            }

            int address = 0;
            for (String octet : octets) {
                address = (address << 8) | (Integer.parseInt(octet) & 0xFF);
            }

            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("Invalid prefix length: " + prefix);
            }

            return new CidrBlock(address, prefix);
        }

        boolean containsIPv4(int ipv4) {
            return (ipv4 & subnetMask) == (networkAddress & subnetMask);
        }

        // IPv6 containment is intentionally not supported by the config CIDR parser;
        // IPv6 special-purpose ranges are enforced directly in ipv6SpecialPurposeBlocked.
        boolean containsIPv6(byte[] ipv6Address) {
            return false;
        }
    }
}