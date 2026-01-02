package com.photogram.backup;

import okhttp3.*;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
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
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // FIXED: Added throws Exception to handle JSONException
    public String uploadHistoryFile(String jsonContent) throws Exception {
        File tempFile = File.createTempFile("history", ".json");
        java.io.FileWriter writer = new java.io.FileWriter(tempFile);
        writer.write(jsonContent);
        writer.close();

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("document", "history.json",
                        RequestBody.create(tempFile, MediaType.parse("application/json")))
                .build();

        Request request = new Request.Builder().url(API_URL + "sendDocument").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            String responseData = response.body().string();
            JSONObject res = new JSONObject(responseData);
            if (res.getBoolean("ok")) {
                return res.getJSONObject("result").getJSONObject("document").getString("file_id");
            }
        }
        return null;
    }

    // FIXED: Added throws Exception
    public String downloadHistoryFile(String fileId) throws Exception {
        Request req = new Request.Builder().url(API_URL + "getFile?file_id=" + fileId).build();
        String filePath;
        try (Response res = client.newCall(req).execute()) {
            JSONObject json = new JSONObject(res.body().string());
            filePath = json.getJSONObject("result").getString("file_path");
        }

        String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
        Request downReq = new Request.Builder().url(downloadUrl).build();
        try (Response res = client.newCall(downReq).execute()) {
            return res.body().string();
        }
    }

    public Map<String, String> getTopicRegistry() throws Exception {
        Map<String, String> registry = new HashMap<>();
        Request request = new Request.Builder().url(API_URL + "getChat?chat_id=" + chatId).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject json = new JSONObject(response.body().string());
            if (json.getBoolean("ok") && json.getJSONObject("result").has("pinned_message")) {
                String text = json.getJSONObject("result").getJSONObject("pinned_message").optString("text", "");
                if (text.startsWith("PHOTOGRAM_REGISTRY:")) {
                    JSONObject map = new JSONObject(text.replace("PHOTOGRAM_REGISTRY:", ""));
                    Iterator<String> keys = map.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        registry.put(key, map.getString(key));
                    }
                }
            }
        }
        return registry;
    }

    public void saveTopicRegistry(Map<String, String> registry) throws Exception {
        JSONObject jsonMap = new JSONObject(registry);
        FormBody body = new FormBody.Builder().add("chat_id", chatId).add("text", "PHOTOGRAM_REGISTRY:" + jsonMap.toString()).build();
        Request request = new Request.Builder().url(API_URL + "sendMessage").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject res = new JSONObject(response.body().string());
            if (res.getBoolean("ok")) {
                int msgId = res.getJSONObject("result").getInt("message_id");
                client.newCall(new Request.Builder().url(API_URL + "pinChatMessage?chat_id=" + chatId + "&message_id=" + msgId).build()).execute();
            }
        }
    }

    public String createTopic(String name) throws Exception {
        FormBody body = new FormBody.Builder().add("chat_id", chatId).add("name", "📁 " + name).build();
        Request request = new Request.Builder().url(API_URL + "createForumTopic").post(body).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject json = new JSONObject(response.body().string());
            return json.getJSONObject("result").getString("message_thread_id");
        }
    }

    public boolean uploadPhoto(File photo, String threadId) {
        try {
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
        } catch (Exception e) {
            return false;
        }
    }
}