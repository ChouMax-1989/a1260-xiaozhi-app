package io.github.choumax.a1260xiaozhi.chat;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-private, bounded transcript cache. It deliberately stores no credentials, activation data, or audio. */
public final class ChatHistoryStore {
    private static final String PREFS = "private_chat_history";
    private static final String ENTRIES = "entries";
    private static final int MAX_MESSAGES = 48;
    private static final int MAX_TEXT_CHARS = 1600;
    private final SharedPreferences prefs;
    public ChatHistoryStore(Context context) { prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public List<ChatMessage> load() {
        ArrayList<ChatMessage> messages = new ArrayList<>();
        try {
            JSONArray entries = new JSONArray(prefs.getString(ENTRIES, "[]"));
            int start = Math.max(0, entries.length() - MAX_MESSAGES);
            for (int i = start; i < entries.length(); i++) { JSONObject entry = entries.optJSONObject(i); if (entry == null) continue; String role = entry.optString("role", ""); String text = entry.optString("text", ""); if (text.contains("控制面板添加设备") || (text.contains("验证码") && text.matches("(?s).*[0-9]{6}.*"))) continue; if (("user".equals(role) || "assistant".equals(role)) && !text.isEmpty()) messages.add(new ChatMessage("user".equals(role) ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT, trim(text))); }
        } catch (Exception ignored) { prefs.edit().remove(ENTRIES).apply(); }
        return messages;
    }
    public void save(List<ChatMessage> messages) {
        JSONArray entries = new JSONArray(); int start = Math.max(0, messages.size() - MAX_MESSAGES);
        for (int i = start; i < messages.size(); i++) { ChatMessage message = messages.get(i); if (message.text.isEmpty()) continue; try { entries.put(new JSONObject().put("role", message.role == ChatMessage.Role.USER ? "user" : "assistant").put("text", trim(message.text))); } catch (Exception ignored) { } }
        prefs.edit().putString(ENTRIES, entries.toString()).apply();
    }
    public void clear() { prefs.edit().remove(ENTRIES).apply(); }
    public static String trim(String text) { String cleaned = text == null ? "" : text.trim(); return cleaned.length() <= MAX_TEXT_CHARS ? cleaned : cleaned.substring(0, MAX_TEXT_CHARS); }
}
