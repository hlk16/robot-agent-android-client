package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

// 使用腾讯地图SDK内置的WebService API进行POI搜索
// 注意：腾讯地图SDK中的POI搜索功能通过WebService API实现
import com.lhht.xiaozhi.R;
import com.tencent.tencentmap.mapsdk.maps.CameraUpdate;
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory;
import com.tencent.tencentmap.mapsdk.maps.MapView;
import com.tencent.tencentmap.mapsdk.maps.TencentMap;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds;
import com.tencent.tencentmap.mapsdk.maps.model.Marker;
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SearchNaviActivity extends AppCompatActivity implements TencentMap.OnMapClickListener {

    private static final String TAG = "SearchNaviActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    // 腾讯地图API密钥 - 请替换为您的实际API密钥
    private static final String TENCENT_MAP_API_KEY = "R2RBZ-JY5WT-T6LX6-LD4XP-RV3D2-RBFHS";

    // HTTP客户端
    private OkHttpClient httpClient;

    // UI控件
    private EditText etCity;
    private EditText etSearchKeyword;
    private Spinner spSearchMethod;
    private Button btnSearchPoi;
    private Button btnClearSearch;
    private Button btnStartNavigation;
    private Button btnUseCurrentLocation;
    private TextView tvSearchResultDesc;
    private LinearLayout llCityInput;

    // 地图相关
    private MapView mapView;
    private TencentMap tencentMap;
    private List<Marker> searchMarkers = new ArrayList<>();
    private Marker selectedMarker;
    private Marker currentLocationMarker;

    // 搜索相关 - 使用WebService API进行POI搜索
    private int searchMethod = 0; // 0: 搜城市, 1: 搜周边
    private String[] searchMethodArray = {"搜城市", "搜周边"};
    private List<Map<String, Object>> searchResults = new ArrayList<>(); // 存储搜索结果

    // 定位相关
    private LocationManager locationManager;
    private Location currentLocation;
    private boolean isLocationRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_search_navi);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        initMap();
        initSearch();
        initLocationService();
        initHttpClient();
        setupListeners();
    }

    private void initViews() {
        etCity = findViewById(R.id.et_city);
        etSearchKeyword = findViewById(R.id.et_search_keyword);
        spSearchMethod = findViewById(R.id.sp_search_method);
        btnSearchPoi = findViewById(R.id.btn_search_poi);
        btnClearSearch = findViewById(R.id.btn_clear_search);
        btnStartNavigation = findViewById(R.id.btn_start_navigation);
        btnUseCurrentLocation = findViewById(R.id.btn_use_current_location);
        tvSearchResultDesc = findViewById(R.id.tv_search_result_desc);
        llCityInput = findViewById(R.id.ll_city_input);
        mapView = findViewById(R.id.map_view);

        // 初始化搜索方式下拉框
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, searchMethodArray);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSearchMethod.setAdapter(adapter);
    }

    private void initMap() {
        tencentMap = mapView.getMap();
        if (tencentMap != null) {
            // 设置地图类型为普通地图
            tencentMap.setMapType(TencentMap.MAP_TYPE_NORMAL);
            // 启用定位图层
            tencentMap.setMyLocationEnabled(true);
            // 设置默认缩放级别
            tencentMap.moveCamera(CameraUpdateFactory.zoomTo(15));
            // 设置地图点击监听器
            tencentMap.setOnMapClickListener(this);

            // 设置标记点击监听器
            tencentMap.setOnMarkerClickListener(marker -> {
                // 如果点击的是搜索结果标记
                if (searchMarkers.contains(marker)) {
                    // 重置之前选中的标记
                    if (selectedMarker != null) {
                        selectedMarker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
                    }

                    // 设置新选中的标记
                    selectedMarker = marker;
                    marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));

                    // 显示导航按钮
                    btnStartNavigation.setVisibility(View.VISIBLE);

                    // 显示选中地点的详细POI信息
                    Integer index = (Integer) marker.getTag();
                    if (index != null && index < searchResults.size()) {
                        Map<String, Object> data = searchResults.get(index);
                        displayPoiDetails(data);

                        // 显示提示信息，提醒用户点击"开始导航"按钮
                        Toast.makeText(SearchNaviActivity.this, "已选择: " + data.get("title") + "\n点击'开始导航'按钮进行导航", Toast.LENGTH_LONG).show();
                    }

                    return true;
                }
                return false;
            });

            // 设置默认地图中心点（北京）
            LatLng defaultCenter = new LatLng(39.908823, 116.397470);
            CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(defaultCenter, 10);
            tencentMap.moveCamera(cameraUpdate);
        }
    }

    private void initSearch() {
        // 使用WebService API进行POI搜索，无需初始化TencentSearch对象
        // 搜索功能将通过HTTP请求调用腾讯地图WebService API实现
    }

    private void initLocationService() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    private void initHttpClient() {
        httpClient = new OkHttpClient();
    }

    private void setupListeners() {
        // 搜索方式选择监听
        spSearchMethod.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchMethod = position;
                // 根据搜索方式显示/隐藏城市输入框
                if (searchMethod == 0) { // 搜城市
                    llCityInput.setVisibility(View.VISIBLE);
                    tvSearchResultDesc.setText("请输入城市和关键词进行搜索");
                } else { // 搜周边
                    llCityInput.setVisibility(View.GONE);
                    tvSearchResultDesc.setText("请输入关键词搜索周边地点（需要先获取当前位置）");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 搜索按钮点击
        btnSearchPoi.setOnClickListener(v -> performSearch());

        // 清除数据按钮点击
        btnClearSearch.setOnClickListener(v -> clearSearchData());

        // 开始导航按钮点击
        btnStartNavigation.setOnClickListener(v -> startNavigation());

        // 当前位置按钮点击
        btnUseCurrentLocation.setOnClickListener(v -> getCurrentLocation());
    }

    private void performSearch() {
        String keyword = etSearchKeyword.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }

        if (searchMethod == 0) { // 搜城市
            String city = etCity.getText().toString().trim();
            if (TextUtils.isEmpty(city)) {
                Toast.makeText(this, "请输入城市名称", Toast.LENGTH_SHORT).show();
                return;
            }
            searchInCity(keyword, city);
        } else { // 搜周边
            if (currentLocation == null) {
                Toast.makeText(this, "请先获取当前位置", Toast.LENGTH_SHORT).show();
                return;
            }
            searchNearby(keyword);
        }
    }

    private void searchInCity(String keyword, String city) {
        // 使用WebService API进行城市内POI搜索
        String url = "https://apis.map.qq.com/ws/place/v1/search?boundary=region(" + city + ",0)&keyword=" + keyword + "&key=" + TENCENT_MAP_API_KEY;

        tvSearchResultDesc.setText("正在搜索中...");

        Request request = new Request.Builder()
                .url(url)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "搜索请求失败", e);
                runOnUiThread(() -> {
                    tvSearchResultDesc.setText("搜索失败，请检查网络连接");
                    Toast.makeText(SearchNaviActivity.this, "网络请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    Log.d(TAG, "搜索响应: " + responseBody);
                    parseSearchResults(responseBody);
                } else {
                    Log.e(TAG, "搜索请求失败，响应码: " + response.code());
                    runOnUiThread(() -> {
                        tvSearchResultDesc.setText("搜索失败，服务器错误");
                        Toast.makeText(SearchNaviActivity.this, "服务器错误: " + response.code(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void searchNearby(String keyword) {
        // 使用WebService API进行周边POI搜索
        String url = "https://apis.map.qq.com/ws/place/v1/search?boundary=nearby(" +
                currentLocation.getLatitude() + "," + currentLocation.getLongitude() + ",5000)&keyword=" +
                keyword + "&key=" + TENCENT_MAP_API_KEY;

        tvSearchResultDesc.setText("正在搜索周边...");

        Request request = new Request.Builder()
                .url(url)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "周边搜索请求失败", e);
                runOnUiThread(() -> {
                    tvSearchResultDesc.setText("搜索失败，请检查网络连接");
                    Toast.makeText(SearchNaviActivity.this, "网络请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    Log.d(TAG, "周边搜索响应: " + responseBody);
                    parseSearchResults(responseBody);
                } else {
                    Log.e(TAG, "周边搜索请求失败，响应码: " + response.code());
                    runOnUiThread(() -> {
                        tvSearchResultDesc.setText("搜索失败，服务器错误");
                        Toast.makeText(SearchNaviActivity.this, "服务器错误: " + response.code(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void handleSearchResults(List<Map<String, Object>> results) {
        runOnUiThread(() -> {
            searchResults.clear();
            searchResults.addAll(results);

            // 清除之前的搜索标记
            clearSearchMarkers();

            if (results.isEmpty()) {
                tvSearchResultDesc.setText("未找到相关地点");
                return;
            }

            tvSearchResultDesc.setText("找到 " + results.size() + " 个地点，点击标记查看详情");

            // 在地图上添加标记
            LatLng firstLocation = null;
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> data = results.get(i);
                double lat = (Double) data.get("lat");
                double lng = (Double) data.get("lng");
                String title = (String) data.get("title");
                String address = (String) data.get("address");

                LatLng location = new LatLng(lat, lng);

                if (firstLocation == null) {
                    firstLocation = location;
                }

                MarkerOptions markerOptions = new MarkerOptions()
                        .position(location)
                        .title(title)
                        .snippet(address)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));

                Marker marker = tencentMap.addMarker(markerOptions);
                marker.setTag(i); // 设置标记的索引
                searchMarkers.add(marker);
            }

            // 调整地图视野以显示所有搜索结果
            adjustMapViewForResults(results);
        });
    }

    private void adjustMapViewForResults(List<Map<String, Object>> results) {
        if (results.isEmpty()) return;

        // 计算所有结果的边界
        double minLat = Double.MAX_VALUE, maxLat = Double.MIN_VALUE;
        double minLng = Double.MAX_VALUE, maxLng = Double.MIN_VALUE;

        for (Map<String, Object> result : results) {
            double lat = (Double) result.get("lat");
            double lng = (Double) result.get("lng");
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLng = Math.min(minLng, lng);
            maxLng = Math.max(maxLng, lng);
        }

        // 设置地图视野
        LatLng southwest = new LatLng(minLat, minLng);
        LatLng northeast = new LatLng(maxLat, maxLng);
        LatLngBounds bounds = new LatLngBounds(southwest, northeast);

        CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100);
        tencentMap.animateCamera(cameraUpdate);
    }

    private void clearSearchData() {
        etCity.setText("");
        etSearchKeyword.setText("");
        clearSearchMarkers();
        selectedMarker = null;
        btnStartNavigation.setVisibility(View.GONE);
        tvSearchResultDesc.setText("请输入搜索条件");
        searchResults.clear();
    }

    private void clearSearchMarkers() {
        for (Marker marker : searchMarkers) {
            marker.remove();
        }
        searchMarkers.clear();
    }

    private void getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        if (isLocationRequested) {
            Toast.makeText(this, "正在获取位置，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }

        btnUseCurrentLocation.setText("定位中...");
        btnUseCurrentLocation.setEnabled(false);
        isLocationRequested = true;

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0,
                    0,
                    locationListener);

            // 15秒超时
            new android.os.Handler().postDelayed(() -> {
                if (isLocationRequested) {
                    stopLocationUpdates();
                    Toast.makeText(this, "定位超时，请检查GPS设置", Toast.LENGTH_LONG).show();
                }
            }, 15000);

        } catch (SecurityException e) {
            Log.e(TAG, "定位权限异常", e);
            resetLocationButton();
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            Log.d(TAG, "获取到位置: " + location.getLatitude() + ", " + location.getLongitude());

            stopLocationUpdates();

            // 在地图上显示当前位置
            LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());

            if (currentLocationMarker != null) {
                currentLocationMarker.remove();
            }

            MarkerOptions markerOptions = new MarkerOptions()
                    .position(currentLatLng)
                    .title("我的位置")
                    .snippet("精度: " + String.format("%.1f", location.getAccuracy()) + "米")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));

            currentLocationMarker = tencentMap.addMarker(markerOptions);

            // 移动地图到当前位置
            CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(currentLatLng, 15);
            tencentMap.animateCamera(cameraUpdate);

            Toast.makeText(SearchNaviActivity.this, "定位成功", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {
            Toast.makeText(SearchNaviActivity.this, "GPS已关闭，请在设置中开启", Toast.LENGTH_LONG).show();
            stopLocationUpdates();
        }
    };

    private void stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException e) {
            Log.e(TAG, "停止定位更新异常", e);
        }
        resetLocationButton();
    }

    private void resetLocationButton() {
        isLocationRequested = false;
        btnUseCurrentLocation.setText("当前位置");
        btnUseCurrentLocation.setEnabled(true);
    }

    private void startNavigation() {
        if (selectedMarker == null) {
            Toast.makeText(this, "请先选择目的地", Toast.LENGTH_SHORT).show();
            return;
        }

        // 跳转到ChatActivity - 使用FLAG_ACTIVITY_CLEAR_TOP返回到现有实例
        Intent intent = new Intent(this, ChatActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // 传递目的地POI信息
        Integer index = (Integer) selectedMarker.getTag();
        if (index != null && index < searchResults.size()) {
            Map<String, Object> destData = searchResults.get(index);
            String title = (String) destData.get("title");
            String address = (String) destData.get("address");
            double lat = (Double) destData.get("lat");
            double lng = (Double) destData.get("lng");
            String category = (String) destData.get("category");
            String type = (String) destData.get("type");
            String tel = (String) destData.get("tel");
            String province = (String) destData.get("province");
            String city = (String) destData.get("city");
            String district = (String) destData.get("district");

            // 传递完整的POI信息到ChatActivity
            intent.putExtra("poi_name", title);
            intent.putExtra("poi_address", address);
            intent.putExtra("poi_lat", lat);
            intent.putExtra("poi_lng", lng);
            intent.putExtra("poi_category", category);
            intent.putExtra("poi_type", type);
            intent.putExtra("poi_tel", tel);
            intent.putExtra("poi_province", province);
            intent.putExtra("poi_city", city);
            intent.putExtra("poi_district", district);
            
            // 标记这是从搜索页面跳转过来的
            intent.putExtra("from_search", true);
        }

        // 如果有当前位置，也传递过去
        if (currentLocation != null) {
            intent.putExtra("current_lat", currentLocation.getLatitude());
            intent.putExtra("current_lng", currentLocation.getLongitude());
        }

        startActivity(intent);
        
        // 显示跳转提示
        Toast.makeText(this, "已选择目的地，跳转到聊天页面", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMapClick(LatLng latLng) {
        // 地图点击事件，可以用于取消选择
        if (selectedMarker != null) {
            selectedMarker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
            selectedMarker = null;
            btnStartNavigation.setVisibility(View.GONE);
        }
    }

    private void parseSearchResults(String jsonResponse) {
        try {
            Gson gson = new Gson();
            JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();

            int status = jsonObject.get("status").getAsInt();
            if (status != 0) {
                String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "搜索失败";
                runOnUiThread(() -> {
                    tvSearchResultDesc.setText("搜索失败: " + message);
                    Toast.makeText(SearchNaviActivity.this, "API错误: " + message, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            JsonArray dataArray = jsonObject.getAsJsonArray("data");
            if (dataArray == null || dataArray.size() == 0) {
                runOnUiThread(() -> {
                    tvSearchResultDesc.setText("未找到相关结果");
                    Toast.makeText(SearchNaviActivity.this, "未找到相关结果", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            // 解析搜索结果数据
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < Math.min(dataArray.size(), 10); i++) {
                JsonObject poi = dataArray.get(i).getAsJsonObject();
                Map<String, Object> data = new HashMap<>();

                data.put("title", poi.get("title").getAsString());
                data.put("address", poi.has("address") ? poi.get("address").getAsString() : "");

                JsonObject location = poi.getAsJsonObject("location");
                data.put("lat", location.get("lat").getAsDouble());
                data.put("lng", location.get("lng").getAsDouble());

                // 添加更多POI详细信息
                if (poi.has("category")) {
                    data.put("category", poi.get("category").getAsString());
                }
                if (poi.has("type")) {
                    data.put("type", poi.get("type").getAsString());
                }
                if (poi.has("tel")) {
                    data.put("tel", poi.get("tel").getAsString());
                }
                if (poi.has("postcode")) {
                    data.put("postcode", poi.get("postcode").getAsString());
                }
                if (poi.has("ad_info")) {
                    JsonObject adInfo = poi.getAsJsonObject("ad_info");
                    if (adInfo.has("province")) {
                        data.put("province", adInfo.get("province").getAsString());
                    }
                    if (adInfo.has("city")) {
                        data.put("city", adInfo.get("city").getAsString());
                    }
                    if (adInfo.has("district")) {
                        data.put("district", adInfo.get("district").getAsString());
                    }
                }

                results.add(data);
            }

            // 在UI线程中处理结果
            handleSearchResults(results);

        } catch (Exception e) {
            Log.e(TAG, "解析搜索结果失败", e);
            runOnUiThread(() -> {
                tvSearchResultDesc.setText("解析结果失败");
                Toast.makeText(SearchNaviActivity.this, "解析结果失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * 显示POI详细信息
     */
    private void displayPoiDetails(Map<String, Object> data) {
        StringBuilder details = new StringBuilder();

        String title = (String) data.get("title");
        String address = (String) data.get("address");
        String category = (String) data.get("category");
        String type = (String) data.get("type");
        String tel = (String) data.get("tel");
        String province = (String) data.get("province");
        String city = (String) data.get("city");
        String district = (String) data.get("district");
        String postcode = (String) data.get("postcode");
        Double lat = (Double) data.get("lat");
        Double lng = (Double) data.get("lng");

        details.append("📍 已选择地点\n");
        details.append("━━━━━━━━━━━━━━━━━━━━\n");

        if (title != null && !title.isEmpty()) {
            details.append("🏢 名称: ").append(title).append("\n");
        }

        if (address != null && !address.isEmpty()) {
            details.append("📍 地址: ").append(address).append("\n");
        }

        if (category != null && !category.isEmpty()) {
            details.append("🏷️ 分类: ").append(category).append("\n");
        }

        if (type != null && !type.isEmpty()) {
            details.append("🔖 类型: ").append(type).append("\n");
        }

        if (tel != null && !tel.isEmpty()) {
            details.append("📞 电话: ").append(tel).append("\n");
        }

        // 行政区域信息
        if (province != null || city != null || district != null) {
            details.append("🗺️ 行政区域: ");
            if (province != null && !province.isEmpty()) {
                details.append(province);
            }
            if (city != null && !city.isEmpty()) {
                if (province != null && !province.isEmpty()) details.append(" - ");
                details.append(city);
            }
            if (district != null && !district.isEmpty()) {
                if ((province != null && !province.isEmpty()) || (city != null && !city.isEmpty())) {
                    details.append(" - ");
                }
                details.append(district);
            }
            details.append("\n");
        }

        if (postcode != null && !postcode.isEmpty()) {
            details.append("📮 邮编: ").append(postcode).append("\n");
        }

        if (lat != null && lng != null) {
            details.append("🌐 坐标: ").append(String.format("%.6f, %.6f", lat, lng)).append("\n");
        }

        details.append("━━━━━━━━━━━━━━━━━━━━\n");
        details.append("💡 点击 开始导航 按钮进行导航");

        tvSearchResultDesc.setText(details.toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation();
            } else {
                Toast.makeText(this, "需要位置权限才能使用定位功能", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLocationUpdates();
        mapView.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }
}