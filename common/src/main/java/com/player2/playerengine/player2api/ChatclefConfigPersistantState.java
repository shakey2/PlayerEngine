package com.player2.playerengine.player2api;

import com.player2.playerengine.automaton.utils.DirUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChatclefConfigPersistantState {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = DirUtil.getConfigDir().resolve("chatclef_config.json");
   private static ChatclefConfigPersistantState config = load();
   private boolean sttHintEnabled = true;
   // Both default false — Gson leaves missing fields at their Java-declared default,
   // so old configs that lack these fields will correctly load as opt-out.
   private boolean sttConsentGranted = false;
   private boolean sttEnabled = false;
   // Default true — missing key in old configs keeps TTS on.
   private boolean ttsEnabled = true;

   public static boolean isSttHintEnabled() {
      return instance().sttHintEnabled;
   }

   public static void updateSttHint(boolean value) {
      System.out.println("[ChatclefConfigPersistantState]: updateSttHint called with: " + value);
      instance().sttHintEnabled = value;
      save();
   }

   /** Returns true only when the user has granted consent AND enabled STT. */
   public static boolean canUseStt() {
      return instance().sttConsentGranted && instance().sttEnabled;
   }

   /** Returns true if the user has previously granted consent (regardless of enabled state). */
   public static boolean hasSttConsent() {
      return instance().sttConsentGranted;
   }

   /** Grants consent and enables STT in one step (called from the consent UI). */
   public static void grantConsentAndEnable() {
      instance().sttConsentGranted = true;
      instance().sttEnabled = true;
      save();
   }

   /** Enables or disables STT without affecting consent. Requires prior consent. */
   public static void setSttEnabled(boolean v) {
      instance().sttEnabled = v;
      save();
   }

   /** Clears consent and disables STT. The user must re-consent to use STT again. */
   public static void revokeSttConsent() {
      instance().sttConsentGranted = false;
      instance().sttEnabled = false;
      save();
   }

   public static boolean isTtsEnabled() {
      return instance().ttsEnabled;
   }

   public static void setTtsEnabled(boolean v) {
      instance().ttsEnabled = v;
      save();
   }

   private static ChatclefConfigPersistantState load() {
      if (Files.exists(CONFIG_PATH)) {
         try {
            String json = Files.readString(CONFIG_PATH);
            System.out.println("[ChatclefConfigPersistantState]: Reading from file...");
            return (ChatclefConfigPersistantState)GSON.fromJson(json, ChatclefConfigPersistantState.class);
         } catch (IOException var1) {
            var1.printStackTrace();
         }
      }

      System.out.println("[ChatclefConfigPersistantState]: Could not load file, using default.");
      return new ChatclefConfigPersistantState();
   }

   private static void save() {
      System.out.println("[ChatclefConfigPersistantState]: save() called");

      try {
         Files.writeString(CONFIG_PATH, GSON.toJson(config));
         System.out.println("[ChatclefConfigPersistantState]: Writing to file...");
      } catch (IOException var1) {
         System.err.println("[ChatclefConfigPersistantState]: Writing to file FAILED");
         var1.printStackTrace();
      }
   }

   private static ChatclefConfigPersistantState instance() {
      return config;
   }
}
