package com.photogram.backup;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator; // Added for the fix
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

    // FIXED: Using keys() Iterator instead of keySet()
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
                    
                    // --- THE FIX STARTS HERE ---
                    Iterator<String> keys = map.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        registry.put(key, map.getString(key));
                    }
                    // --- THE FIX ENDS HERE ---
                }
            }
        } catch (Exception e) {
            // Return empty if no registry found
        }
        return registry;
    }

    public void saveTopicRegistry(Map<String, String> registry) throws Exception {
        JSONObject jsonMap = new JSONObject(registry);
        String registryText = "PHOTOGRAM_REGISTRY:" + jsonMap.toString();
        
        FormBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", registryText)
                .build();

        Request request = new Request.Builder().url(API_URL + "sendMessage").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject res = new JSONObject(response.body().string());
            if (res.getBoolean("ok")) {
                int msgId = res.getJSONObject("result").getInt("message_id");
                // Attempt to pin the registry message automatically
                Request pinReq = new Request.Builder()
                        .url(API_URL + "pinChatMessage?chat_id=" + chatId + "&message_id=" + msgId)
                        .build();
                client.newCall(pinReq).execute();
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
            if (json.getBoolean("ok")) {
                return json.getJSONObject("result").getString("message_thread_id");
            } else {
                throw new Exception(json.getString("description"));
            }
        }
    }

    public boolean uploadPhoto(File photo, String threadId) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("message_thread_id", threadId)
                .addFormDataPart("photo", photo.getName(), 
                        RequestBody.create(photo, MediaType.parse("image/jpeg")))
                .build();
        Request request = new Request.Builder().url(API_URL + "sendPhoto").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }
}