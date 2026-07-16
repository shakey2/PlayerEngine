package com.player2.playerengine.player2api;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * <p>Every code path that builds a {@code /v1/chat/completions} request MUST route the whole history through
 * {@link #cappedMessages(List, String)} (the egress gate), and every path that stores model-facing text MUST
 * route it through {@link #capForModel(String, String)} (defense-in-depth). There must be no other way for a
 * message to reach the model.
 */
public final class LogEgressGuard {
   private static final Logger LOGGER = LogManager.getLogger();

   /**
    * Maximum number of characters allowed for a single model-facing message's content (32 KB).
    *
    * <p>A SAFETY BACKSTOP set far above any legitimate single message — the largest real messages
    * (the full-command-list system prompt assembly and BuildStructureTask user prompts) are well under
    * this. It exists solely to make the gigabyte-log catastrophe impossible. Tunable: raise it if a real
    * message ever approaches it, but never remove the cap.
    */
   public static final int MAX_MODEL_MESSAGE_CHARS = 32768; // 32 KB
   // Keep prompt + completion within the small context windows used by local Player2 profiles. A
   // character cap alone is insufficient: a large prompt plus a large completion budget can be rejected as
   // HTTP 400 even though the prompt passed the egress gate.
   public static final int MAX_CHAT_COMPLETION_CONTENT_CHARS = 24 * 1024; // 24 KB across message content
   public static final int MAX_CHAT_COMPLETION_MESSAGES = 32;
   public static final int MAX_SAFE_CHAT_COMPLETION_OUTPUT_TOKENS = 2048;
   public static final int DEFAULT_CHAT_COMPLETION_OUTPUT_TOKENS = MAX_SAFE_CHAT_COMPLETION_OUTPUT_TOKENS;
   private static final int MIN_PARTIAL_MESSAGE_CHARS = 1024;
   private static final int OMISSION_NOTICE_RESERVE_CHARS = 160;
   /** Marker text is written inside the hard cap, never appended beyond it. */
   private static final int TRUNCATION_MARKER_RESERVE_CHARS = 256;

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
      int cut = Math.max(0, MAX_MODEL_MESSAGE_CHARS - TRUNCATION_MARKER_RESERVE_CHARS);
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
    * Egress gate for one request message. Rebuilds from the strict role/content allowlist so unknown
    * properties, oversized role values, and structured content can never bypass the content budget.
    * The persisted source object is never mutated.
    */
   public static JsonObject cappedMessage(JsonObject msg) {
      if (msg == null) {
         return malformedMessageNotice();
      }
      String role = roleOf(msg);
      JsonElement contentEl = msg.get("content");
      if (!isAllowedRole(role) || contentEl == null || !contentEl.isJsonPrimitive()
            || !contentEl.getAsJsonPrimitive().isString()) {
         LOGGER.warn("[LogEgressGuard] Malformed chat message replaced before model dispatch: role={}",
               role == null ? "invalid" : (isAllowedRole(role) ? role : "unsupported"));
         return malformedMessageNotice();
      }
      String content = contentEl.getAsString();
      JsonObject copy = new JsonObject();
      copy.addProperty("role", role);
      copy.addProperty("content", capForModel(content, role));
      return copy;
   }

   private static boolean isAllowedRole(String role) {
      return "system".equals(role) || "user".equals(role) || "assistant".equals(role);
   }

   private static JsonObject malformedMessageNotice() {
      JsonObject notice = new JsonObject();
      notice.addProperty("role", "system");
      notice.addProperty("content", "Context notice: one malformed conversation message was omitted.");
      return notice;
   }

   /**
    * Egress gate for an entire chat-completion request. Keeps the system prompt plus the newest turns first,
    * drops older middle history when necessary, and caps one oversized newest turn instead of letting the
    * request exceed the hard ceiling.
    */
   public static JsonArray cappedMessages(List<JsonObject> messages, String surface) {
      JsonArray result = new JsonArray();
      if (messages == null || messages.isEmpty()) {
         return result;
      }

      List<JsonObject> selectedSource = new ArrayList<>();
      int omittedByCount = 0;
      if (messages.size() > MAX_CHAT_COMPLETION_MESSAGES) {
         boolean preserveSystem = isRole(messages.get(0), "system");
         int originalTailStart = preserveSystem ? 1 : 0;
         int tailSlots = MAX_CHAT_COMPLETION_MESSAGES - 1 - (preserveSystem ? 1 : 0);
         int selectedTailStart = Math.max(originalTailStart, messages.size() - tailSlots);
         if (preserveSystem) {
            selectedSource.add(messages.get(0));
         }
         selectedSource.addAll(messages.subList(selectedTailStart, messages.size()));
         omittedByCount = selectedTailStart - originalTailStart;
      } else {
         selectedSource.addAll(messages);
      }

      List<JsonObject> cappedSource = new ArrayList<>(selectedSource.size());
      long sourceChars = 0L;
      for (JsonObject message : selectedSource) {
         JsonObject capped = cappedMessage(message);
         cappedSource.add(capped);
         sourceChars += contentChars(capped);
      }
      // Preserve every instruction and turn byte-for-byte whenever the request already fits. The
      // prior implementation truncated any system prompt over half the budget even when no culling
      // was needed.
      if (sourceChars <= MAX_CHAT_COMPLETION_CONTENT_CHARS && omittedByCount == 0) {
         for (JsonObject message : cappedSource) {
            result.add(message);
         }
         return result;
      }

      int used = 0;
      int start = 0;
      JsonObject first = cappedSource.get(0);
      if (isRole(first, "system")) {
         JsonObject system = first;
         int systemChars = contentChars(system);
         int maxSystemChars = cappedSource.size() > 1
               ? MAX_CHAT_COMPLETION_CONTENT_CHARS
                     - MIN_PARTIAL_MESSAGE_CHARS
                     - OMISSION_NOTICE_RESERVE_CHARS
               : MAX_CHAT_COMPLETION_CONTENT_CHARS;
         if (systemChars > maxSystemChars) {
            system = copyWithCappedContent(system, maxSystemChars, "system");
            systemChars = contentChars(system);
         }
         result.add(system);
         used += systemChars;
         start = 1;
      }

      List<JsonObject> suffix = new ArrayList<>();
      int omitted = omittedByCount;
      int remaining = Math.max(0, MAX_CHAT_COMPLETION_CONTENT_CHARS
            - used - OMISSION_NOTICE_RESERVE_CHARS);
      for (int i = cappedSource.size() - 1; i >= start; i--) {
         JsonObject capped = cappedSource.get(i);
         int chars = contentChars(capped);
         if (chars <= remaining) {
            suffix.add(capped);
            remaining -= chars;
         } else if (suffix.isEmpty() && remaining >= MIN_PARTIAL_MESSAGE_CHARS) {
            suffix.add(copyWithCappedContent(capped, remaining, roleOf(capped)));
            // The newest message is retained with its own visible truncation marker; only entries
            // older than it count as omitted transcript messages.
            omitted += i - start;
            break;
         } else {
            // Stop at the first non-fitting boundary. Continuing the reverse scan here would create
            // holes and mismatched user/assistant turns by admitting still-older small messages.
            omitted += i - start + 1;
            break;
         }
      }

      if (omitted > 0) {
         LOGGER.warn(
               "[LogEgressGuard] Chat-completion request capped (DESIGN.md §3 cost/egress backstop): surface={} sourceMessages={} omittedMessages={} contentCharsCap={}. Older context was dropped before dispatch.",
               surface, messages.size(), omitted, MAX_CHAT_COMPLETION_CONTENT_CHARS);
         JsonObject notice = new JsonObject();
         notice.addProperty("role", "system");
         notice.addProperty("content", "Context notice: " + omitted
               + " older message(s) were omitted to stay within the request limit.");
         result.add(notice);
      }

      Collections.reverse(suffix);
      for (JsonObject msg : suffix) {
         result.add(msg);
      }
      return result;
   }

   /**
    * Adds the configured completion-token ceiling to every mod-built chat-completion request. A lower caller-set
    * value is preserved; absent, null, non-positive, or higher values are clamped.
    */
   public static void applyChatCompletionRequestCaps(JsonObject requestBody, String surface) {
      if (requestBody == null) {
         return;
      }
      int cap = configuredOutputTokenCap();
      JsonElement maxTokens = requestBody.get("max_tokens");
      if (maxTokens != null && !maxTokens.isJsonNull() && maxTokens.isJsonPrimitive()
            && maxTokens.getAsJsonPrimitive().isNumber()) {
         int requested = maxTokens.getAsInt();
         if (requested > 0 && requested <= cap) {
            return;
         }
         LOGGER.warn(
               "[LogEgressGuard] Chat-completion max_tokens clamped: surface={} requested={} cap={}",
               surface, requested, cap);
      }
      requestBody.addProperty("max_tokens", cap);
   }

   private static int configuredOutputTokenCap() {
      try {
         return Math.min(Player2ServerConfigHolder.get().getChatCompletionMaxOutputTokensClamped(),
               MAX_SAFE_CHAT_COMPLETION_OUTPUT_TOKENS);
      } catch (Exception e) {
         return DEFAULT_CHAT_COMPLETION_OUTPUT_TOKENS;
      }
   }

   private static boolean isRole(JsonObject msg, String role) {
      return role.equals(roleOf(msg));
   }

   private static String roleOf(JsonObject msg) {
      if (msg == null || !msg.has("role") || msg.get("role").isJsonNull()
            || !msg.get("role").isJsonPrimitive()
            || !msg.get("role").getAsJsonPrimitive().isString()) {
         return null;
      }
      return msg.get("role").getAsString();
   }

   private static int contentChars(JsonObject msg) {
      if (msg == null) {
         return 0;
      }
      JsonElement contentEl = msg.get("content");
      if (contentEl == null || !contentEl.isJsonPrimitive() || !contentEl.getAsJsonPrimitive().isString()) {
         return 0;
      }
      return contentEl.getAsString().length();
   }

   private static JsonObject copyWithCappedContent(JsonObject msg, int maxChars, String role) {
      if (msg == null || maxChars <= 0) {
         return msg;
      }
      JsonElement contentEl = msg.get("content");
      if (contentEl == null || !contentEl.isJsonPrimitive() || !contentEl.getAsJsonPrimitive().isString()) {
         return msg;
      }
      String content = contentEl.getAsString();
      if (content.length() <= maxChars) {
         return msg;
      }
      int cut = Math.max(0, maxChars - TRUNCATION_MARKER_RESERVE_CHARS);
      if (cut > 0 && java.lang.Character.isHighSurrogate(content.charAt(cut - 1))) {
         cut--;
      }
      int overflow = content.length() - cut;
      JsonObject copy = new JsonObject();
      for (Map.Entry<String, JsonElement> entry : msg.entrySet()) {
         if ("content".equals(entry.getKey())) {
            copy.addProperty("content", content.substring(0, cut)
                  + "\n...[TRUNCATED " + overflow + " chars by LogEgressGuard request cap]");
         } else {
            copy.add(entry.getKey(), entry.getValue());
         }
      }
      LOGGER.warn(
            "[LogEgressGuard] Oversized model-facing message capped by request budget: role={} originalChars={} cap={} truncatedChars={}",
            role, content.length(), maxChars, overflow);
      return copy;
   }
}
