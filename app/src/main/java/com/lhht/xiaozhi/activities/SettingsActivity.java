package com.lhht.xiaozhi.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.adapters.WsUrlAdapter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private SettingsManager settingsManager;
    private TextInputEditText tokenInput;
    private TextInputEditText appIdInput;
    private TextInputEditText apiKeyInput;
    private TextInputEditText apiSecretInput;
    private SwitchMaterial enableTokenSwitch;
    private RecyclerView wsUrlList;
    private MaterialButton addWsUrlButton;
    private WsUrlAdapter wsUrlAdapter;
    
    private ExecutorService executorService;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 设置Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        settingsManager = new SettingsManager(this);
        
        tokenInput = findViewById(R.id.tokenInput);
        appIdInput = findViewById(R.id.getAppId);
        apiKeyInput = findViewById(R.id.getApiKey);
        apiSecretInput = findViewById(R.id.getApiSecret);
        enableTokenSwitch = findViewById(R.id.enableTokenSwitch);
        ExtendedFloatingActionButton saveButton = findViewById(R.id.saveButton);
        wsUrlList = findViewById(R.id.wsUrlList);
        addWsUrlButton = findViewById(R.id.addWsUrlButton);

        // 设置 RecyclerView 的 LayoutManager（只设置一次）
        wsUrlList.setLayoutManager(new LinearLayoutManager(this));

        // 初始化异步执行器
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 异步加载设置数据
        loadSettingsAsync();

        // 添加新的WebSocket地址
        addWsUrlButton.setOnClickListener(v -> {
            if (wsUrlAdapter != null) {
                wsUrlAdapter.addUrl("");
            }
        });

        // 保存设置
        saveButton.setOnClickListener(v -> {
            String token = tokenInput.getText().toString();
            String appId = appIdInput.getText().toString();
            String apiKey = apiKeyInput.getText().toString();
            String apiSecret = apiSecretInput.getText().toString();
            boolean enableToken = enableTokenSwitch.isChecked();

            // 获取当前所有WebSocket地址
            ArrayList<String> currentUrls = wsUrlAdapter != null ? wsUrlAdapter.getUrls() : new ArrayList<>();
            String selectedWsUrl = wsUrlAdapter != null ? wsUrlAdapter.getSelectedUrl() : "";

            // 保存设置
            if (!selectedWsUrl.isEmpty()) {
                settingsManager.saveSettings(selectedWsUrl, token, enableToken);
            }
            settingsManager.saveApiSettings(appId, apiKey, apiSecret);
            settingsManager.saveWsUrls(new HashSet<>(currentUrls));
            finish();
        });
    }
    
    private void loadSettingsAsync() {
        executorService.execute(() -> {
            // 后台线程加载数据
            final String token = settingsManager.getToken();
            final String appId = settingsManager.getAppId();
            final String apiKey = settingsManager.getApiKey();
            final String apiSecret = settingsManager.getApiSecret();
            final boolean tokenEnabled = settingsManager.isTokenEnabled();
            final String currentWsUrl = settingsManager.getWsUrl();
            
            Set<String> wsUrls = settingsManager.getWsUrls();
            if (wsUrls == null) {
                wsUrls = new HashSet<>();
                wsUrls.add(currentWsUrl);
            }
            final ArrayList<String> finalUrls = new ArrayList<>(wsUrls);
            
            // 切换到主线程更新UI
            mainHandler.post(() -> {
                tokenInput.setText(token);
                appIdInput.setText(appId);
                apiKeyInput.setText(apiKey);
                apiSecretInput.setText(apiSecret);
                enableTokenSwitch.setChecked(tokenEnabled);
                setupWsUrlList(finalUrls, currentWsUrl);
                updateTokenInputState();
                enableTokenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateTokenInputState());
            });
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupWsUrlList(ArrayList<String> urls, String currentUrl) {
        wsUrlAdapter = new WsUrlAdapter(urls, currentUrl, url -> wsUrlAdapter.removeUrl(url));
        wsUrlList.setAdapter(wsUrlAdapter);
    }

    private void updateTokenInputState() {
        tokenInput.setEnabled(enableTokenSwitch.isChecked());
    }
}