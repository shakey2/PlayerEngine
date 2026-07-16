package com.player2.playerengine.player2api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Runtime backstop for the DESIGN.md section 3 model-egress invariant. */
public final class LogEgressGuard {
   private static final Logger LOGGER = LogManager.getLogger();

   public static final int MAX_MODEL_MESSAGE_CHARS = 32768;
   public static final int MAX_CHAT_COMPLETION_CONTENT_CHARS = 24 * 1024;
   public static final int MAX_CHAT_COMPLETION_MESSAGES = 32;
   public static final int MAX_SAFE_CHAT_COMPLETION_OUTPUT_TOKENS = 2048;
   public static final int DEFAULT_CHAT_COMPLETION_OUTPUT_TOKENS = MAX_SAFE_CHAT_COMPLETION_OUTPUT_TOKENS;
   private static final int MIN_PARTIAL_MESSAGE_CHARS = 1024;
   private static final int OMISSION_NOTICE_RESERVE_CHARS = 160;
   private static final int TRUNCATION_MARKER_RESERVE_CHARS = 256;

   private LogEgressGuard() {
   }

   public static String capForModel(String content, String role) {
      if (content == null || content.length() <= MAX_MODEL_MESSAGE_CHARS) {
         return content;
      }
      int cut = Math.max(0, MAX_MODEL_MESSAGE_CHARS - TRUNCATION_MARKER_RESERVE_CHARS);
      if (cut > 0 && java.lang.Character.isHighSurrogate(content.charAt(cut - 1))) {
         cut--;
      }
      int overflow = content.length() - cut;
      LOGGER.warn(
            "[LogEgressGuard] Oversized model-facing message capped: role={} originalChars={} cap={} truncatedChars={}",
            role, content.length(), MAX_MODEL_MESSAGE_CHARS, overflow);
      return content.substring(0, cut)
            + "\n…[TRUNCATED " + overflow + " chars by LogEgressGuard — possible log/diagnostic egress, see DESIGN.md §3]";
   }

   /** Rebuilds a request message from the strict role/content allowlist without mutating history. */
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
      JsonObject copy = new JsonObject();
      copy.addProperty("role", role);
      copy.addProperty("content", capForModel(contentEl.getAsString(), role));
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

   /** Keeps the system prompt and a contiguous newest suffix within message/content budgets. */
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
               ? MAX_CHAT_COMPLETION_CONTENT_CHARS - MIN_PARTIAL_MESSAGE_CHARS - OMISSION_NOTICE_RESERVE_CHARS
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
      int remaining = Math.max(0, MAX_CHAT_COMPLETION_CONTENT_CHARS - used - OMISSION_NOTICE_RESERVE_CHARS);
      for (int i = cappedSource.size() - 1; i >= start; i--) {
         JsonObject capped = cappedSource.get(i);
         int chars = contentChars(capped);
         if (chars <= remaining) {
            suffix.add(capped);
            remaining -= chars;
         } else if (suffix.isEmpty() && remaining >= MIN_PARTIAL_MESSAGE_CHARS) {
            suffix.add(copyWithCappedContent(capped, remaining, roleOf(capped)));
            omitted += i - start;
            break;
         } else {
            omitted += i - start + 1;
            break;
         }
      }

      if (omitted > 0) {
         LOGGER.warn(
               "[LogEgressGuard] Chat-completion request capped: surface={} sourceMessages={} omittedMessages={} contentCharsCap={}",
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

   /** Applies the bounded completion-token ceiling while preserving lower caller-set values. */
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
