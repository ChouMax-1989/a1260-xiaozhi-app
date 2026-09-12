package io.github.choumax.a1260xiaozhi.wake;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Longest phrase match preserves dictionary readings of polyphonic Chinese words. */
final class WakeKeywordLexicon {
    static String encode(Reader input, String keyword) throws IOException {
        if (!keyword.matches("[\\u4e00-\\u9fff]{2,12}")) throw new IllegalArgumentException("Invalid keyword");
        // Keep only entries that can occur in this word, not a 68,000-entry resident map.
        Map<String, String> relevant = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && keyword.contains(line.substring(0, tab)))
                    relevant.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < keyword.length();) {
            String value = null;
            int end;
            for (end = keyword.length(); end > offset; end--) {
                value = relevant.get(keyword.substring(offset, end));
                if (value != null) break;
            }
            if (value == null) throw new IllegalArgumentException("Unsupported keyword pronunciation");
            if (result.length() > 0) result.append(' ');
            result.append(value);
            offset = end;
        }
        return result.append(" @wake\n").toString();
    }
}
