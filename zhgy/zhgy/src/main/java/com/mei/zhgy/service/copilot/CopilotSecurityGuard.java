package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates boundaries shared by chat and parameterized tools. */
public final class CopilotSecurityGuard {
    private static final Pattern INJECTION = Pattern.compile(
            "忽略(之前|所有|系统)?指令|ignore (all|previous|system) instructions|reveal (the )?system prompt|泄露系统提示");
    private final FarmFieldService fields;

    public CopilotSecurityGuard(FarmFieldService fields) { this.fields = fields; }

    public static boolean isPromptOverride(String input) {
        return input != null && INJECTION.matcher(CopilotQueryNormalizer.forMatching(input)).find();
    }

    public record ToolScope(Integer farmId, String fieldId) {}

    public ToolScope authorize(CopilotStableContext active, Integer requestedFarm, String requestedField,
                               boolean useSelectedField, Long actor) {
        if (actor == null) throw denied();
        Integer currentFarm = null;
        if (active.getFarmId() != null && !active.getFarmId().isBlank()) {
            try { currentFarm = Integer.valueOf(active.getFarmId()); }
            catch (NumberFormatException invalid) { throw denied(); }
        }
        if (currentFarm == null && active.getFieldId() != null && fields != null) {
            currentFarm = fields.get(active.getFieldId(), actor).getFarmId();
        }
        if (currentFarm != null && requestedFarm != null && !currentFarm.equals(requestedFarm)) throw denied();
        Integer farm = requestedFarm == null ? currentFarm : requestedFarm;
        String field = requestedField != null ? requestedField : useSelectedField ? active.getFieldId() : null;
        if (fields == null && (farm != null || field != null)) throw denied();
        if (field != null) {
            FarmField match = matchField(farm, field, requestedField != null, actor);
            if (match == null || farm != null && !farm.equals(match.getFarmId())) throw denied();
            return new ToolScope(match.getFarmId(), match.getFieldId());
        }
        if (farm != null) fields.requireFarm(farm, actor);
        return new ToolScope(farm, null);
    }

    private FarmField matchField(Integer farm, String field, boolean explicit, Long actor) {
        try { return fields.get(field, actor); }
        catch (FieldAccessException missing) {
            if (missing.getHttpStatus() != 404 || farm == null || !explicit) throw missing;
            var matches = fields.list(farm, actor).stream().filter(item -> item.getName() != null
                    && (field.equalsIgnoreCase(item.getName().trim()) || item.getName().trim().startsWith(field + " "))).limit(2).toList();
            if (matches.size() != 1) throw missing;
            return matches.get(0);
        }
    }

    public static List<String> trustedImages(List<String> references, String uploadBase, String cosBase, String bucket, String region) {
        if (references == null || references.isEmpty()) return List.of();
        URI upload = parse(uploadBase);
        URI cos = parse(cosBase);
        if (cos == null && (cosBase == null || cosBase.isBlank()) && bucket != null && region != null
                && bucket.matches("[a-zA-Z0-9-]+") && region.matches("[a-zA-Z0-9-]+")) {
            cos = parse("https://" + bucket + ".cos." + region + ".myqcloud.com");
        }
        var trusted = new LinkedHashSet<String>();
        for (String value : references) {
            if (value == null || value.length() > 2048) continue;
            URI reference = parse(value);
            if (under(upload, reference) || under(cos, reference)) trusted.add(value);
            if (trusted.size() == 8) break;
        }
        return List.copyOf(trusted);
    }

    private static boolean under(URI base, URI reference) {
        if (base == null || reference == null || reference.getUserInfo() != null || reference.getFragment() != null
                || base.getHost() == null || reference.getHost() == null
                || !("http".equalsIgnoreCase(reference.getScheme()) || "https".equalsIgnoreCase(reference.getScheme()))
                || !reference.getScheme().equalsIgnoreCase(base.getScheme())
                || !base.getHost().equalsIgnoreCase(reference.getHost()) || port(base) != port(reference)) return false;
        String path = reference.getPath();
        String prefix = Objects.toString(base.getPath(), "").replaceAll("/+$", "");
        if (path == null || path.isBlank() || path.contains("\\") || path.contains("%")
                || path.chars().anyMatch(Character::isISOControl)) return false;
        for (String segment : path.split("/")) if (".".equals(segment) || "..".equals(segment)) return false;
        return path.startsWith(prefix + "/") && path.length() > prefix.length() + 1;
    }

    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }

    private static URI parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return URI.create(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static FieldAccessException denied() {
        return new FieldAccessException(403, "SCOPE_UNAVAILABLE", "Requested data is outside the authorized scope");
    }
}
