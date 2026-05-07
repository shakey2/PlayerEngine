package com.player2.playerengine.multiversion;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatType.Bound;

public class MessageTypeVer {
   public static ChatType getMessageType(Bound parameters) {
      return parameters.chatType();
   }
}
