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

    public String uploadHistoryFile(String jsonContent) throws Exception {
        File tempFile = File.createTempFile("history", ".json");
        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
            writer.write(jsonContent);
        }
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chat_id", chatId).addFormDataPart("document", "history.json", RequestBody.create(tempFile, MediaType.parse("application/json"))).build();
        try (Response response = client.newCall(new Request.Builder().url(API_URL + "sendDocument").post(body).build()).execute()) {
            JSONObject res = new JSONObject(response.body().string());
            return res.getBoolean("ok") ? res.getJSONObject("result").getJSONObject("document").getString("file_id") : null;
        }
    }

    public String downloadHistoryFile(String fileId) throws Exception {
        Request req = new Request.Builder().url(API_URL + "getFile?file_id=" + fileId).build();
        String path;
        try (Response res = client.newCall(req).execute()) {
            path = new JSONObject(res.body().string()).getJSONObject("result").getString("file_path");
        }
        try (Response res = client.newCall(new Request.Builder().url("https://api.telegram.org/file/bot" + botToken + "/" + path).build()).execute()) {
            return res.body().string();
        }
    }

    public Map<String, String> getTopicRegistry() throws Exception {
        Map<String, String> registry = new HashMap<>();
        try (Response response = client.newCall(new Request.Builder().url(API_URL + "getChat?chat_id=" + chatId).build()).execute()) {
            JSONObject json = new JSONObject(response.body().string());
            if (json.getBoolean("ok") && json.getJSONObject("result").has("pinned_message")) {
                String text = json.getJSONObject("result").getJSONObject("pinned_message").optString("text", "");
                if (text.startsWith("PHOTOGRAM_REGISTRY:")) {
                    JSONObject map = new JSONObject(text.replace("PHOTOGRAM_REGISTRY:", ""));
                    Iterator<String> keys = map.keys();
                    while (keys.hasNext()) { String k = keys.next(); registry.put(k, map.getString(k)); }
                }
            }
        } catch (Exception e) {}
        return registry;
    }

    public void saveTopicRegistry(Map<String, String> registry) throws Exception {
        String text = "PHOTOGRAM_REGISTRY:" + new JSONObject(registry).toString();
        FormBody body = new FormBody.Builder().add("chat_id", chatId).add("text", text).build();
        try (Response res = client.newCall(new Request.Builder().url(API_URL + "sendMessage").post(body).build()).execute()) {
            JSONObject json = new JSONObject(res.body().string());
            if (json.getBoolean("ok")) {
                int mid = json.getJSONObject("result").getInt("message_id");
                client.newCall(new Request.Builder().url(API_URL + "pinChatMessage?chat_id=" + chatId + "&message_id=" + mid).build()).execute();
            }
        }
    }

    public String createTopic(String name) throws Exception {
        FormBody body = new FormBody.Builder().add("chat_id", chatId).add("name", "📁 " + name).build();
        try (Response res = client.newCall(new Request.Builder().url(API_URL + "createForumTopic").post(body).build()).execute()) {
            return new JSONObject(res.body().string()).getJSONObject("result").getString("message_thread_id");
        }
    }

    public boolean uploadPhoto(File photo, String tid) {
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chat_id", chatId).addFormDataPart("message_thread_id", tid).addFormDataPart("photo", photo.getName(), RequestBody.create(photo, MediaType.parse("image/jpeg"))).build();
        try (Response res = client.newCall(new Request.Builder().url(API_URL + "sendPhoto").post(body).build()).execute()) {
            return res.isSuccessful();
        } catch (Exception e) { return false; }
    }
}