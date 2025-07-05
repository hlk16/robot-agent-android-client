package com.lhht.xiaozhi.activities;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.lhht.xiaozhi.R;
import com.tencent.map.navi.TencentNavi;
import com.tencent.navi.surport.utils.DeviceUtils;

public class NavActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_nav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;

        });
        initTencentNavi();

    }
    private void initTencentNavi() {
        TencentNavi.Config config = new TencentNavi.Config();
        // 记录设备标识，反馈导航问题时请提供该设备标识以及发生问题的时间
        String deviceID = DeviceUtils.getImei(getApplicationContext());
        config.setDeviceId(deviceID);
        // 或者设置开发者自己的的设备号config.setDeviceId(xxxxxxxx);
        TencentNavi.init(this, config);
    }
}