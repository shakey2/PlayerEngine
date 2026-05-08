package com.player2.playerengine.player2api.status;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class ObjectStatus {
   protected final Map<String, String> fields = new HashMap<>();

   public ObjectStatus add(String key, String value) {
      this.fields.put(key, value);
      return this;
   }

   @Override
   public String toString() {
      JsonObject object = new JsonObject();

      for (Map.Entry<String, String> entry : this.fields.entrySet()) {
         object.addProperty(entry.getKey(), entry.getValue());
      }

      return object.toString();
   }
}
