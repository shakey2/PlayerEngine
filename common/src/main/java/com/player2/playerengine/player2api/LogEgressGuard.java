package com.player2.playerengine.player2api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DESIGN.md §3 "Logs must NEVER reach a prompt or the model (data-egress hard rule)" runtime backstop.
 *
 * <p>This is a <b>safety backstop</b>, not a normal-path transform: it makes it impossible for a giant /
 * unbounded string (a leaked log, a gigabyte {@code latest.log}, a full stack trace, etc.) to be sent to
 * the Player2 model, even if a future careless caller passes one into an egress surface. Truncation should
 * <b>NEVER</b> fire in normal operation — every legitimate model-facing message (system prompt + full
 * command list, build-structure prompts, per-turn status JSON, reminders, RAG text) is well under the cap.
 * If truncation ever fires, something tried to send unbounded content to the model and it must be
 * investigated (see DESIGN.md §3).
 *
 * <p>Every code path that builds a {@code /v1/chat/completions} request MUST route each message through
 * {@link #cappedMessage(JsonObject)} (the egress gate), and every path that stores model-facing text MUST
 * route it through {@link #capForModel(String, String)} (defense-in-depth). There must be no other way for a
 * message to reach the model.
 */
public final class LogEgressGuard {
   private static final Logger LOGGER = LogManager.getLogger();

   /**
    * Maximum number of characters allowed for a single model-facing message's content (256 KB).
    *
    * <p>A SAFETY BACKSTOP set far above any legitimate single message — the largest real messages
    * (the full-command-list system prompt assembly and BuildStructureTask user prompts) are well under
    * this. It exists solely to make the gigabyte-log catastrophe impossible. Tunable: raise it if a real
    * message ever approaches it, but never remove the cap.
    */
   public static final int MAX_MODEL_MESSAGE_CHARS = 262144; // 256 KB

   private LogEgressGuard() {
   }

   /**
    * Caps a model-facing message's content at {@link #MAX_MODEL_MESSAGE_CHARS}. Returns the input unchanged
    * when it is null or within the cap. When it exceeds the cap, logs a console WARNING (role + sizes only,
    * never the content itself) and returns a truncated string carrying a visible egress marker. The cut never
    * splits a UTF-16 surrogate pair, so the result is always valid for JSON serialization.
    *
    * @param content the message content about to be sent to the model (may be null)
    * @param role    the message role (e.g. "system", "user", "assistant") — used only for the warning
    * @return the original content, or a truncated, marked copy if it exceeded the cap
    */
   public static String capForModel(String content, String role) {
      if (content == null) {
         return null;
      }
      if (content.length() <= MAX_MODEL_MESSAGE_CHARS) {
         return content;
      }
      // Don't split a surrogate pair at the boundary (would emit an unpaired surrogate -> invalid JSON).
      // NOTE: fully-qualified java.lang.Character — this package has its own `Character` class
      // (com.player2.playerengine.player2api.Character) that otherwise shadows it (same package, no import).
      int cut = MAX_MODEL_MESSAGE_CHARS;
      if (java.lang.Character.isHighSurrogate(content.charAt(cut - 1))) {
         cut--;
      }
      int overflow = content.length() - cut;
      LOGGER.warn(
            "[LogEgressGuard] Oversized model-facing message capped (DESIGN.md §3 data-egress backstop): role={} originalChars={} cap={} truncatedChars={}. This should NEVER happen in normal operation — something tried to send unbounded content (e.g. a log) to the model.",
            role, content.length(), MAX_MODEL_MESSAGE_CHARS, overflow);
      return content.substring(0, cut)
            + "\n…[TRUNCATED " + overflow + " chars by LogEgressGuard — possible log/diagnostic egress, see DESIGN.md §3]";
   }

   /**
    * Egress gate for a request message: returns the original {@code msg} when its {@code "content"} is within
    * {@link #MAX_MODEL_MESSAGE_CHARS}, otherwise a shallow COPY whose content is capped. The original
    * (often a persisted-history {@link JsonObject}) is NEVER mutated — mutating it would corrupt stored
    * history and risk double-truncation — so capping only affects what is placed into the request body.
    *
    * <p>Call this for EVERY message added to a {@code /v1/chat/completions} "messages" array, at every code
    * site that builds such a request.
    *
    * @param msg a chat message object (expects a "content" string property; other shapes pass through)
    * @return the original msg, or a capped shallow copy
    */
   public static JsonObject cappedMessage(JsonObject msg) {
      if (msg == null) {
         return null;
      }
      String role = msg.has("role") && !msg.get("role").isJsonNull() ? msg.get("role").getAsString() : null;
      JsonElement contentEl = msg.get("content");
      if (contentEl == null || !contentEl.isJsonPrimitive() || !contentEl.getAsJsonPrimitive().isString()) {
         return msg;
      }
      String content = contentEl.getAsString();
      if (content.length() <= MAX_MODEL_MESSAGE_CHARS) {
         return msg;
      }
      JsonObject copy = new JsonObject();
      for (Map.Entry<String, JsonElement> entry : msg.entrySet()) {
         if ("content".equals(entry.getKey())) {
            copy.addProperty("content", capForModel(content, role));
         } else {
            copy.add(entry.getKey(), entry.getValue());
         }
      }
      return copy;
   }
}
