package io.github.choumax.a1260xiaozhi.features;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Read-only manifest import boundary; never enables tools or downloads/executes anything.
 * Schema: {"schemaVersion":1,"pluginId":"audio","tools":["self.audio_speaker.set_volume"]}.
 * expectedSha256 must be obtained independently by the caller. A matching hash is integrity,
 * not publisher authentication; the closed schema and built-in allowlist remain mandatory.
 */
public final class BuiltinPluginManifest {
    public static final int MAX_BYTES = 4096;
    public final String pluginId;
    public final List<String> tools;

    private BuiltinPluginManifest(String pluginId, List<String> tools) {
        this.pluginId = pluginId;
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public static BuiltinPluginManifest verify(byte[] bytes, String expectedSha256) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) throw invalid();
        try {
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(expectedSha256.substring(i * 2, i * 2 + 2), 16);
            if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(bytes))) throw invalid();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            // This schema has only one object and one string array. Reject nesting before parsing.
            int depth = 0; boolean quote = false, escape = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote) {
                    if (escape) escape = false;
                    else if (c == '\\') escape = true;
                    else if (c == '"') quote = false;
                } else if (c == '"') quote = true;
                else if (c == '{' || c == '[') { if (++depth > 2) throw invalid(); }
                else if (c == '}' || c == ']') { if (--depth < 0) throw invalid(); }
            }
            if (quote || depth != 0) throw invalid();
            JSONTokener tokener = new JSONTokener(text);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) throw invalid();
            JSONObject value = (JSONObject) parsed;
            if (value.length() != 3) throw invalid();
            Iterator<String> keys = value.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"schemaVersion".equals(key) && !"pluginId".equals(key) && !"tools".equals(key)) throw invalid();
            }
            Object version = value.opt("schemaVersion");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1) throw invalid();
            Object id = value.opt("pluginId");
            if (!(id instanceof String) || !BuiltinPlugins.IDS.contains(id)) throw invalid();
            JSONArray entries = value.optJSONArray("tools");
            if (entries == null || entries.length() == 0 || entries.length() > 3) throw invalid();
            Set<String> unique = new HashSet<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                Object name = entries.opt(i);
                if (!(name instanceof String) || !BuiltinPlugins.toolsForPlugin((String) id).contains(name)
                        || !unique.add((String) name)) throw invalid();
                names.add((String) name);
            }
            return new BuiltinPluginManifest((String) id, names);
        } catch (Exception rejected) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Manifest must pass size, SHA-256, versioned schema and built-in tool checks");
    }
}
