package com.lhht.xiaozhi.settings;

import android.content.Context;
import com.tencent.mmkv.MMKV;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.HashSet;
import java.util.Set;

public class SettingsManager {
    private static final String KEY_WS_URL = "ws_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ENABLE_TOKEN = "enable_token";
    private static final String KEY_WS_URLS = "ws_urls";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_SECRET = "api_secret";

    private final MMKV mmkv;
    // 初始化MMKV
    public SettingsManager(Context context) {
        mmkv = MMKV.defaultMMKV();//默认一个路径为/data/data/包名/mmkv文件
    }

    public void saveSettings(String wsUrl, String token, boolean enableToken) {
        mmkv.encode(KEY_WS_URL, wsUrl);
        mmkv.encode(KEY_TOKEN, token);
        mmkv.encode(KEY_ENABLE_TOKEN, enableToken);
    }

    public void saveWsUrls(Set<String> urls) {
        JSONArray jsonArray = new JSONArray(urls);
        mmkv.encode(KEY_WS_URLS, jsonArray.toString());//序列化存储·
    }

    public void saveApiSettings(String appId, String apiKey, String apiSecret) {
        mmkv.encode(KEY_APP_ID, appId);
        mmkv.encode(KEY_API_KEY, apiKey);
        mmkv.encode(KEY_API_SECRET, apiSecret);
    }
    //获取默认的ws_url
    public String getWsUrl() {
        return mmkv.decodeString(KEY_WS_URL, "wss://api.tenclass.net/xiaozhi/v1/");
    }

    public String getToken() {
        return mmkv.decodeString(KEY_TOKEN, "test-token");
    }

    public boolean isTokenEnabled() {
        return mmkv.decodeBool(KEY_ENABLE_TOKEN, true);
    }
    //获取自定义的ws_urls
    public Set<String> getWsUrls() {
        String jsonStr = mmkv.decodeString(KEY_WS_URLS, null);
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        try {
            JSONArray jsonArray = new JSONArray(jsonStr);//反序列化存储的JSONArray字符串
            Set<String> urls = new HashSet<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                urls.add(jsonArray.getString(i));
            }
            return urls;
        } catch (JSONException e) {
            return null;
        }
    }

    public String getAppId() {
        return mmkv.decodeString(KEY_APP_ID, "");
    }

    public String getApiKey() {
        return mmkv.decodeString(KEY_API_KEY, "");
    }

    public String getApiSecret() {
        return mmkv.decodeString(KEY_API_SECRET, "");
    }
}