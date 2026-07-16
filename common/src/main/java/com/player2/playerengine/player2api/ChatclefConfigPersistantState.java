package com.player2.playerengine.player2api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.player2.playerengine.PlayerEnginePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ChatclefConfigPersistantState {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = PlayerEnginePaths.userFile("chatclef_config.json");
   private static final Object LOCK = new Object();
   private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor((runnable) -> {
      Thread thread = new Thread(runnable, "playerengine-chatclef-config-io");
      thread.setDaemon(true);
      return thread;
   });

   private static ChatclefConfigPersistantState config = new ChatclefConfigPersistantState();
   private static boolean loadStarted;
   private static boolean loaded;
   private static boolean dirtySinceLastWrite;
   private static boolean sttHintDirty;
   private static boolean sttConsentDirty;
   private static boolean sttEnabledDirty;
   private static boolean ttsEnabledDirty;
   private static final List<Runnable> loadCallbacks = new ArrayList<>();

   private boolean sttHintEnabled = true;
   private boolean sttConsentGranted = false;
   private boolean sttEnabled = false;
   private boolean ttsEnabled = true;

   public static void preload() {
      ensureLoadStarted();
   }

   public static boolean isLoaded() {
      ensureLoadStarted();
      synchronized(LOCK) {
         return loaded;
      }
   }

   public static boolean runWhenLoaded(Runnable callback) {
      ensureLoadStarted();
      synchronized(LOCK) {
         if (loaded) {
            return true;
         }

         loadCallbacks.add(callback);
         return false;
      }
   }

   public static boolean isSttHintEnabled() {
      ensureLoadStarted();
      synchronized(LOCK) {
         return config.sttHintEnabled;
      }
   }

   public static void updateSttHint(boolean value) {
      ensureLoadStarted();
      synchronized(LOCK) {
         config.sttHintEnabled = value;
         sttHintDirty = true;
         dirtySinceLastWrite = true;
      }

      save();
   }

   public static boolean canUseStt() {
      ensureLoadStarted();
      synchronized(LOCK) {
         return config.sttConsentGranted && config.sttEnabled;
      }
   }

   public static boolean hasSttConsent() {
      ensureLoadStarted();
      synchronized(LOCK) {
         return config.sttConsentGranted;
      }
   }

   public static void grantConsentAndEnable() {
      ensureLoadStarted();
      synchronized(LOCK) {
         config.sttConsentGranted = true;
         config.sttEnabled = true;
         sttConsentDirty = true;
         sttEnabledDirty = true;
         dirtySinceLastWrite = true;
      }

      save();
   }

   public static void setSttEnabled(boolean value) {
      ensureLoadStarted();
      synchronized(LOCK) {
         config.sttEnabled = value;
         sttEnabledDirty = true;
         dirtySinceLastWrite = true;
      }

      save();
   }

   public static void revokeSttConsent() {
      ensureLoadStarted();
      synchronized(LOCK) {
         config.sttConsentGranted = false;
         config.sttEnabled = false;
         sttConsentDirty = true;
         sttEnabledDirty = true;
         dirtySinceLastWrite = true;
      }

      save();
   }

   public static boolean isTtsEnabled() {
      ensureLoadStarted();
      synchronized(LOCK) {
         return config.ttsEnabled;
      }
   }

   public static void setTtsEnabled(boolean value) {
      ensureLoadStarted();
      synchronized(LOCK) {
         config.ttsEnabled = value;
         ttsEnabledDirty = true;
         dirtySinceLastWrite = true;
      }

      save();
   }

   private static void ensureLoadStarted() {
      synchronized(LOCK) {
         if (loadStarted) {
            return;
         }

         loadStarted = true;
      }

      IO_EXECUTOR.execute(ChatclefConfigPersistantState::loadFromDisk);
   }

   private static void loadFromDisk() {
      ChatclefConfigPersistantState loadedConfig = readConfigFromDisk();
      List<Runnable> callbacks;
      synchronized(LOCK) {
         if (!sttHintDirty) {
            config.sttHintEnabled = loadedConfig.sttHintEnabled;
         }
         if (!sttConsentDirty) {
            config.sttConsentGranted = loadedConfig.sttConsentGranted;
         }
         if (!sttEnabledDirty) {
            config.sttEnabled = loadedConfig.sttEnabled;
         }
         if (!ttsEnabledDirty) {
            config.ttsEnabled = loadedConfig.ttsEnabled;
         }
         loaded = true;
         callbacks = new ArrayList<>(loadCallbacks);
         loadCallbacks.clear();
      }

      for (Runnable callback : callbacks) {
         try {
            callback.run();
         } catch (RuntimeException exception) {
            LOGGER.warn("Chatclef config: load callback failed ({})", exception.getClass().getSimpleName());
         }
      }
   }

   private static ChatclefConfigPersistantState readConfigFromDisk() {
      if (!Files.exists(CONFIG_PATH)) {
         LOGGER.debug("Chatclef config: using defaults");
         return new ChatclefConfigPersistantState();
      }

      try {
         String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
         ChatclefConfigPersistantState loadedConfig = GSON.fromJson(json, ChatclefConfigPersistantState.class);
         if (loadedConfig != null) {
            LOGGER.debug("Chatclef config: loaded from disk");
            return loadedConfig;
         }
      } catch (IOException | JsonParseException | IllegalStateException exception) {
         LOGGER.warn("Chatclef config: load failed ({})", exception.getClass().getSimpleName());
      }

      LOGGER.debug("Chatclef config: using defaults");
      return new ChatclefConfigPersistantState();
   }

   private static void save() {
      try {
         IO_EXECUTOR.execute(ChatclefConfigPersistantState::writeCurrentToDisk);
      } catch (RejectedExecutionException exception) {
         LOGGER.warn("Chatclef config: save rejected ({})", exception.getClass().getSimpleName());
      }
   }

   public static void shutdown() {
      ensureLoadStarted();
      Future<?> finalWrite = null;
      boolean shouldWrite;
      synchronized(LOCK) {
         shouldWrite = dirtySinceLastWrite;
      }

      try {
         if (shouldWrite) {
            finalWrite = IO_EXECUTOR.submit(ChatclefConfigPersistantState::writeCurrentToDisk);
         }
         IO_EXECUTOR.shutdown();
         if (finalWrite != null) {
            finalWrite.get(1L, TimeUnit.SECONDS);
         } else {
            IO_EXECUTOR.awaitTermination(1L, TimeUnit.SECONDS);
         }
      } catch (RejectedExecutionException exception) {
         LOGGER.warn("Chatclef config: shutdown save rejected ({})", exception.getClass().getSimpleName());
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         LOGGER.warn("Chatclef config: shutdown interrupted");
      } catch (ExecutionException | TimeoutException exception) {
         LOGGER.warn("Chatclef config: shutdown save did not complete ({})", exception.getClass().getSimpleName());
      }
   }

   private static void writeCurrentToDisk() {
      ChatclefConfigPersistantState snapshot;
      synchronized(LOCK) {
         snapshot = config.copy();
         dirtySinceLastWrite = false;
      }

      try {
         Files.createDirectories(CONFIG_PATH.getParent());
         Path tmp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
         Files.writeString(tmp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
         atomicReplace(tmp, CONFIG_PATH);
         LOGGER.debug("Chatclef config: wrote to disk");
      } catch (IOException exception) {
         synchronized(LOCK) {
            dirtySinceLastWrite = true;
         }
         LOGGER.warn("Chatclef config: write failed ({})", exception.getClass().getSimpleName());
      }
   }

   private static void atomicReplace(Path tmp, Path target) throws IOException {
      try {
         Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
         Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private ChatclefConfigPersistantState copy() {
      ChatclefConfigPersistantState copy = new ChatclefConfigPersistantState();
      copy.sttHintEnabled = this.sttHintEnabled;
      copy.sttConsentGranted = this.sttConsentGranted;
      copy.sttEnabled = this.sttEnabled;
      copy.ttsEnabled = this.ttsEnabled;
      return copy;
   }
}
