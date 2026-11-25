package tax.domain.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Deterministic SHA-256 hash of a TaxRuleSnapshot's identifying fields.
 * 7-key alphabetical TreeMap → compact JSON → SHA-256 hex (SPEC-TAX-002 §5).
 */
public final class SnapshotHashUtil {

    private SnapshotHashUtil() {}

    /**
     * @param classificationCode raw code string
     * @param rateDecimal        rate as stored string (e.g. "0.088000000")
     * @param status             rule status name (e.g. "PUBLISHED")
     * @param taxType            TaxType name (e.g. "CBS")
     * @param tenantId           UUID as string
     * @param validFrom          ISO date string (e.g. "2026-01-01")
     * @param validTo            ISO date string or "open" when null
     * @return 64-char lowercase hex SHA-256
     */
    public static String computeSnapshotHash(String classificationCode,
                                              String rateDecimal,
                                              String status,
                                              String taxType,
                                              String tenantId,
                                              String validFrom,
                                              String validTo) {
        var map = new TreeMap<String, String>();
        map.put("classificationCode", classificationCode);
        map.put("rateDecimal",        rateDecimal);
        map.put("status",             status);
        map.put("taxType",            taxType);
        map.put("tenantId",           tenantId);
        map.put("validFrom",          validFrom);
        map.put("validTo",            validTo);

        var json = buildCompactJson(map);
        return sha256Hex(json);
    }

    private static String buildCompactJson(TreeMap<String, String> map) {
        var body = map.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(Collectors.joining(","));
        return "{" + body + "}";
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
