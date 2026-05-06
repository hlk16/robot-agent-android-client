package com.lhht.xiaozhi.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.lhht.xiaozhi.R;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NBIOTActivity extends AppCompatActivity {
    private static final String TAG = "NBIOT";

    private EditText etServerAddress;
    private EditText etUsername;
    private EditText etPassword;
    private Button btnConnect;
    private TextView tvStatus;
    private TextView textView;

    private MqttClient mqttClient;
    private boolean isConnected = false;
    private ExecutorService executor;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nbiotactivity);

        executor = Executors.newSingleThreadExecutor();

        initViews();
        setupListeners();
    }

    private void initViews() {
        etServerAddress = findViewById(R.id.etServerAddress);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus = findViewById(R.id.tvStatus);
        textView = findViewById(R.id.test);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnectMqtt();
            } else {
                connectMqtt();
            }
        });
    }

    private void connectMqtt() {
        String serverIp = etServerAddress.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (serverIp.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }

        String clientId = "app" + System.currentTimeMillis();
        String subTopic = "xiaozhi";
        String pubTopic = "nbiot";

        btnConnect.setEnabled(false);
        btnConnect.setText("连接中...");

        executor.execute(() -> {
            try {
                MqttClient client = new MqttClient(serverIp, clientId, new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(username);
                options.setPassword(password.toCharArray());
                options.setConnectionTimeout(30);
                options.setKeepAliveInterval(50);
                options.setAutomaticReconnect(true);
                options.setCleanSession(false);

                client.connect(options);
                client.subscribe(subTopic);

                mqttClient = client;
                isConnected = true;

                client.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        runOnUiThread(() -> {
                            isConnected = false;
                            updateConnectionStatus(false);
                            Toast.makeText(NBIOTActivity.this, "连接断开", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String msg = new String(message.getPayload());
                        runOnUiThread(() -> textView.setText(msg));
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });

                runOnUiThread(() -> {
                    updateConnectionStatus(true);
                    Toast.makeText(NBIOTActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "连接失败", e);
                runOnUiThread(() -> {
                    updateConnectionStatus(false);
                    Toast.makeText(NBIOTActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    btnConnect.setEnabled(true);
                    btnConnect.setText("连接服务器");
                });
            }
        });
    }

    private void disconnectMqtt() {
        executor.execute(() -> {
            try {
                if (mqttClient != null && mqttClient.isConnected()) {
                    mqttClient.disconnect();
                    mqttClient.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "断开失败", e);
            }
            runOnUiThread(() -> {
                isConnected = false;
                updateConnectionStatus(false);
                Toast.makeText(NBIOTActivity.this, "已断开", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            tvStatus.setText("已连接");
            tvStatus.setTextColor(0xFF4CAF50);
            btnConnect.setText("断开连接");
        } else {
            tvStatus.setText("未连接");
            tvStatus.setTextColor(0xFFFF0000);
            btnConnect.setText("连接服务器");
        }
        btnConnect.setEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectMqtt();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
