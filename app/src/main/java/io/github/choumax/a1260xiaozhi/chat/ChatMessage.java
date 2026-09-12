package io.github.choumax.a1260xiaozhi.chat;

public final class ChatMessage {
    public enum Role { USER, ASSISTANT }
    public final Role role;
    public final String text;
    public ChatMessage(Role role, String text) { this.role = role; this.text = text == null ? "" : text; }
}
