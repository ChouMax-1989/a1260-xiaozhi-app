package io.github.choumax.a1260xiaozhi.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded simple-LRC parser. Positive [offset] advances lyrics; no HTML or word-level markup. */
public final class LrcLyrics {
    public static final int MAX_CHARS = 131072, MAX_ENTRIES = 4096;
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d{1,7})\\]\\s*$");
    public static final class Line {
        public final long timeMs;
        public final String text;
        Line(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }
    public final List<Line> lines;
    private LrcLyrics(List<Line> lines) { this.lines = Collections.unmodifiableList(lines); }
    public static LrcLyrics empty() { return new LrcLyrics(new ArrayList<>()); }
    public static LrcLyrics parse(String input) {
        if (input == null || input.length() > MAX_CHARS) throw new IllegalArgumentException("LRC size limit exceeded");
        String value = input.startsWith("\ufeff") ? input.substring(1) : input;
        long offset = 0;
        Matcher offsets = OFFSET.matcher(value);
        while (offsets.find()) offset = Long.parseLong(offsets.group(1));
        List<Line> result = new ArrayList<>();
        int expandedChars = 0;
        for (String row : value.split("\\r?\\n", -1)) {
            if (row.length() > 4096) throw new IllegalArgumentException("LRC line too long");
            Matcher times = TIME.matcher(row);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (times.find()) {
                // Only contiguous timestamp prefixes belong to a lyric line.
                if (!row.substring(end, times.start()).trim().isEmpty()) break;
                String fraction = times.group(3);
                long millis = fraction == null ? 0 : Integer.parseInt(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                stamps.add(Math.max(0, Long.parseLong(times.group(1)) * 60000 + Integer.parseInt(times.group(2)) * 1000L + millis - offset));
                end = times.end();
                if (stamps.size() + result.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many LRC entries");
            }
            if (!stamps.isEmpty()) {
                String text = row.substring(end).trim();
                expandedChars += stamps.size() * text.length();
                if (expandedChars > MAX_CHARS) throw new IllegalArgumentException("Expanded lyrics exceed limit");
                for (long time : stamps) result.add(new Line(time, text));
            }
        }
        result.sort(Comparator.comparingLong(line -> line.timeMs));
        // Preserve simultaneous bilingual lines as one display block.
        List<Line> merged = new ArrayList<>();
        for (Line line : result) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).timeMs == line.timeMs) {
                Line previous = merged.remove(merged.size() - 1);
                String combined = previous.text.equals(line.text) ? previous.text : previous.text + "\n" + line.text;
                merged.add(new Line(line.timeMs, combined));
            } else merged.add(line);
        }
        return new LrcLyrics(merged);
    }
    public int indexAt(long positionMs) {
        int low = 0, high = lines.size() - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs <= positionMs) { found = middle; low = middle + 1; }
            else high = middle - 1;
        }
        return found;
    }
    public String textAt(long positionMs) { int index = indexAt(positionMs); return index < 0 ? "" : lines.get(index).text; }
}
