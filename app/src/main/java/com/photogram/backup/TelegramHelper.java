package com.photogram.backup;

import okhttp3.*;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class TelegramHelper {
    private final OkHttpClient client;
    private final String botToken;
    private final String chatId;

    public TelegramHelper(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // 1. Create a Topic for the folder
    public String createTopic(String folderName) throws Exception {
        String url = "https://api.telegram.org/bot" + botToken + "/createForumTopic";
        
        FormBody formBody = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("name", folderName)
                .build();

        Request request = new Request.Builder().url(url).post(formBody).build();
        try (Response response = client.newCall(request).execute()) {
            String jsonData = response.body().string();
            JSONObject json = new JSONObject(jsonData);
            if (json.getBoolean("ok")) {
                return json.getJSONObject("result").getString("message_thread_id");
            }
            throw new Exception("Telegram Error: " + jsonData);
        }
    }

    // 2. Upload a single photo to a specific topic
    public boolean uploadPhoto(File photo, String threadId) throws IOException {
        String url = "https://api.telegram.org/bot" + botToken + "/sendPhoto";

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("message_thread_id", threadId)
                .addFormDataPart("photo", photo.getName(),
                        RequestBody.create(photo, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder().url(url).post(requestBody).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }
}