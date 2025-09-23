package com.lhht.xiaozhi.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.lhht.xiaozhi.R;

public class menu extends AppCompatActivity {
    private TextView mtext;
    private Button mtableRobot;
    private Button mnbIot;
    private Button more;

    private Button manus;
    private Button navgation;
    private Button chat;


    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_menu);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        mtext= findViewById(R.id.textView);
        mtableRobot= findViewById(R.id.tableRobot);
        mnbIot= findViewById(R.id.nbIot);
        more= findViewById(R.id.morePluge);
        manus= findViewById(R.id.manus);

//        navgation=findViewById(R.id.navigation);
        chat=findViewById(R.id.chat);
//        video=findViewById(R.id.video);
        mtableRobot.setOnClickListener(view -> {
            //跳转到桌面机器人界面
            if (!MainActivity.webSocketManager.isConnected()) {
                Toast.makeText(this, "请先连接", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(menu.this, BluetoothActivity.class);
            startActivity(intent);
        });
        mnbIot.setOnClickListener(view -> {
            //跳转到物联网界面
            Intent intent = new Intent(menu.this, NBIOTActivity.class);
            startActivity(intent);
        });
        more.setOnClickListener(view -> {
            Toast.makeText(this, "敬请期待", Toast.LENGTH_SHORT).show();
        });
//        drone.setOnClickListener(view -> {
//
//            Intent intent = new Intent(menu.this, DroneActivity.class);
//            startActivity(intent);
//        });
        manus.setOnClickListener(view -> {
            Toast.makeText(this, "敬请期待", Toast.LENGTH_SHORT).show();
//            Intent intent = new Intent(menu.this, NavigationTestActivity.class);
//            startActivity(intent);
        });
//        navgation.setOnClickListener(view -> {
//            Intent intent = new Intent(menu.this, WalkNaviActivity.class);
//            startActivity(intent);
//        });
        chat.setOnClickListener(view -> {
            Intent intent = new Intent(menu.this, ChatActivity.class);
            startActivity(intent);
        });
//        video.setOnClickListener(view -> {
//            Intent intent = new Intent(menu.this, video.class);
//            startActivity(intent);
//        });

    }


}