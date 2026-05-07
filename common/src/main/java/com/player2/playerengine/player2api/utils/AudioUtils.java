package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

public class AudioUtils {
    private static final String WEB_API_URL = "https://api.player2.game";

    public static void streamAudio(String clientId, String token, String text, double speed, String[] voiceIds) {
        // Check if local API is available
        String localApiUrl = LocalAPIDiscovery.getLocalApiUrl();
        if (localApiUrl != null) {
            // Local API uses /v1/tts/speak — get base64 audio data back and play it ourselves
            playViaLocalApi(localApiUrl, clientId, token, text, speed, voiceIds);
        } else {
            // Fall back to web API streaming
            streamViaWebApi(clientId, token, text, speed, voiceIds);
        }
    }

    /**
     * Uses the local Player2 API's /v1/tts/speak endpoint with play_in_app=false.
     * The response contains base64-encoded mp3 audio data that we decode and play.
     */
    private static void playViaLocalApi(String localApiUrl, String clientId, String token, String text, double speed, String[] voiceIds) {
        HttpURLConnection connection = null;
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("text", text);
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("play_in_app", false);
            requestBody.addProperty("audio_format", "wav");
            JsonArray voiceIdsArray = new JsonArray();
            for (String id : voiceIds) {
                voiceIdsArray.add(id);
            }
            requestBody.add("voice_ids", voiceIdsArray);

            URL url = new URL(localApiUrl + "/v1/tts/speak");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("player2-game-key", clientId);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                System.err.println("Local TTS /tts/speak returned HTTP " + responseCode + ", falling back to web API.");
                streamViaWebApi(clientId, token, text, speed, voiceIds);
                return;
            }

            // Read the JSON response containing base64-encoded audio data
            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            String responseStr = responseBuilder.toString();
            JsonObject responseJson = JsonParser.parseString(responseStr).getAsJsonObject();
            String audioBase64 = responseJson.get("data").getAsString();

            if (audioBase64 == null || audioBase64.isEmpty()) {
                System.err.println("Local TTS returned empty audio data, falling back to web API.");
                streamViaWebApi(clientId, token, text, speed, voiceIds);
                return;
            }

            // Decode base64 audio and play it
            byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
            try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                    new BufferedInputStream(new ByteArrayInputStream(audioBytes)))) {

                AudioFormat format = audioStream.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                try (SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info)) {
                    sourceDataLine.open(format);
                    sourceDataLine.start();

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = audioStream.read(buffer)) != -1) {
                        sourceDataLine.write(buffer, 0, bytesRead);
                    }

                    sourceDataLine.drain();
                    sourceDataLine.stop();
                }
            }

        } catch (Exception e) {
            System.err.println("Error during local TTS, falling back to web API: " + e.getMessage());
            streamViaWebApi(clientId, token, text, speed, voiceIds);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Uses the web API's /v1/tts/stream endpoint to stream WAV audio
     * and plays it through the Java sound system.
     */
    private static void streamViaWebApi(String clientId, String token, String text, double speed, String[] voiceIds) {
        HttpURLConnection connection = null;
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("text", text);
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("audio_format", "wav");
            JsonArray voiceIdsArray = new JsonArray();
            for (String id : voiceIds) {
                voiceIdsArray.add(id);
            }
            requestBody.add("voice_ids", voiceIdsArray);

            URL url = new URL(WEB_API_URL + "/v1/tts/stream");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "audio/wav");

            connection.setRequestProperty("player2-game-key", clientId);
            connection.setRequestProperty("Authorization", "Bearer " + token);

            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try (InputStream inputStream = connection.getInputStream();
                 AudioInputStream audioStream = AudioSystem.getAudioInputStream(new BufferedInputStream(inputStream))) {

                AudioFormat format = audioStream.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                try (SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info)) {
                    sourceDataLine.open(format);
                    sourceDataLine.start();

                    byte[] buffer = new byte[4096];
                    int bytesRead = 0;
                    while ((bytesRead = audioStream.read(buffer)) != -1) {
                        sourceDataLine.write(buffer, 0, bytesRead);
                    }

                    sourceDataLine.drain();
                    sourceDataLine.stop();
                }
            }
        } catch (Exception e) {
            System.err.println("Error during TTS streaming: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
