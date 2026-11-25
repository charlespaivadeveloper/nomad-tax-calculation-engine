package tax.domain.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class CanonicalHashUtil {

    private CanonicalHashUtil() {}

    /**
     * Produces the canonical SHA-256 input hash per SPEC-TAX-001 §4.
     * Keys are sorted by TreeMap natural order; no JSON library is used.
     * All rates and results are SYNTHETIC.
     */
    public static String computeInputHash(
            String classificationCode,
            String currency,
            String operationDate,
            String operationId,
            String simulationContext,
            String taxableBase,
            String tenantId) {

        var map = new TreeMap<String, String>();
        map.put("classificationCode", classificationCode);
        map.put("currency", currency);
        map.put("operationDate", operationDate);
        map.put("operationId", operationId);
        map.put("simulationContext", simulationContext);
        map.put("taxableBase", taxableBase);
        map.put("tenantId", tenantId);

        var body = map.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(Collectors.joining(","));

        return sha256Hex("{" + body + "}");
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
