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
        
        // Setup the client with a 30-second timeout for slow uploads
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Creates a new Forum Topic in the Telegram Group.
     * Includes 2025 Topic Styling (Emoji + Color).
     */
    public String createTopic(String folderName) throws Exception {
        String url = "https://api.telegram.org/bot" + botToken + "/createForumTopic";
        
        // icon_custom_emoji_id could be added here if you have premium, 
        // but icon_color is free for all bots.
        FormBody formBody = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("name", "📁 " + folderName) // Adds the folder emoji
                .add("icon_color", "0x6FB9F0") // Sets the topic icon to Blue
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(formBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String jsonData = response.body().string();
            JSONObject json = new JSONObject(jsonData);
            
            if (json.getBoolean("ok")) {
                // Return the message_thread_id which is needed for uploads
                return json.getJSONObject("result").getString("message_thread_id");
            } else {
                throw new Exception("Telegram API Error: " + json.getString("description"));
            }
        }
    }

    /**
     * Uploads a photo file to a specific Telegram Topic.
     */
    public boolean uploadPhoto(File photo, String threadId) throws IOException {
        String url = "https://api.telegram.org/bot" + botToken + "/sendPhoto";

        // Create a multipart request to send the actual image file
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("message_thread_id", threadId)
                .addFormDataPart("photo", photo.getName(),
                        RequestBody.create(photo, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}