package com.photogram.backup;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TelegramHelper {
    private final OkHttpClient client;
    private final String botToken;
    private final String chatId;
    private final String API_URL;

    public TelegramHelper(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.API_URL = "https://api.telegram.org/bot" + botToken + "/";
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // NEW: Search for the Topic Registry in Pinned Messages
    public Map<String, String> getTopicRegistry() throws Exception {
        Map<String, String> registry = new HashMap<>();
        Request request = new Request.Builder().url(API_URL + "getChat?chat_id=" + chatId).build();
        
        try (Response response = client.newCall(request).execute()) {
            JSONObject json = new JSONObject(response.body().string());
            if (json.getBoolean("ok") && json.getJSONObject("result").has("pinned_message")) {
                String text = json.getJSONObject("result").getJSONObject("pinned_message").optString("text", "");
                if (text.startsWith("PHOTOGRAM_REGISTRY:")) {
                    String cleanJson = text.replace("PHOTOGRAM_REGISTRY:", "");
                    JSONObject map = new JSONObject(cleanJson);
                    for (String key : map.keySet()) {
                        registry.put(key, map.getString(key));
                    }
                }
            }
        }
        return registry;
    }

    // NEW: Update/Create the Registry Message
    public void saveTopicRegistry(Map<String, String> registry) throws Exception {
        JSONObject jsonMap = new JSONObject(registry);
        String registryText = "PHOTOGRAM_REGISTRY:" + jsonMap.toString();
        
        // Check if we already have a pinned message to edit
        // For simplicity, we just send a new one and tell the user to pin it, 
        // OR we can try to find the existing one. 
        // Better: Always send and pin to ensure it's at the top.
        
        FormBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", registryText)
                .build();

        Request request = new Request.Builder().url(API_URL + "sendMessage").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject res = new JSONObject(response.body().string());
            if (res.getBoolean("ok")) {
                int msgId = res.getJSONObject("result").getInt("message_id");
                // Pin it
                client.newCall(new Request.Builder().url(API_URL + "pinChatMessage?chat_id=" + chatId + "&message_id=" + msgId).build()).execute();
            }
        }
    }

    public String createTopic(String name) throws Exception {
        FormBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("name", "📁 " + name)
                .build();
        Request request = new Request.Builder().url(API_URL + "createForumTopic").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject json = new JSONObject(response.body().string());
            return json.getJSONObject("result").getString("message_thread_id");
        }
    }

    public boolean uploadPhoto(File photo, String threadId) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("message_thread_id", threadId)
                .addFormDataPart("photo", photo.getName(), RequestBody.create(photo, MediaType.parse("image/jpeg")))
                .build();
        Request request = new Request.Builder().url(API_URL + "sendPhoto").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }
}