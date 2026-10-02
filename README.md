# Giám sát thời tiết & giàn phơi — Kotlin + Firebase

Ứng dụng Android native (Material 3 Views, minSdk 24) kết nối Firebase Realtime Database. Có đăng nhập Email/Password, bốn thẻ cảm biến, badge mưa, chuyển AUTO/MANUAL, điều khiển servo và thông báo FCM khi khô chuyển sang mưa. Màn hình cuộn, nền sáng, chữ lớn; số liệu chưa có hiển thị `—`, không giả lập giá trị cảm biến.

## 1. Cấu trúc mã nguồn

| File | Chức năng |
| --- | --- |
| `app/src/main/java/com/example/esp8266prj/MainActivity.kt` | Đăng nhập, dashboard, quyền notification, điều khiển, trạng thái chờ xác nhận |
| `app/src/main/java/com/example/esp8266prj/WeatherRepository.kt` | RTDB listeners, trạng thái kết nối, token FCM, ghi lệnh có thời hạn |
| `app/src/main/java/com/example/esp8266prj/RainMessagingService.kt` | Notification channel và nhận FCM khi app ở foreground |
| `firebase/database.example.json` | Dữ liệu minh họa để import trên project thử nghiệm |
| `firebase/database.rules.json` | Quyền riêng cho điện thoại và ESP8266, kiểm tra dữ liệu/lệnh |
| `firebase/functions/index.js` | Cloud Function gửi FCM khi `false → true` |
| `firmware/WeatherDryer/WeatherDryer.ino` | Firmware REST mẫu cùng chân với sketch ESP8266 hiện có |

Giao diện được dựng bằng Kotlin; `activity_main.xml` ban đầu không còn được dùng. Mã ESP8266 gốc tại workspace Arduino vẫn được giữ nguyên; nạp sketch mới nếu muốn kết nối Firebase.

## 2. Tạo Firebase và tài khoản

1. Tạo project ở [Firebase Console](https://console.firebase.google.com/). Thêm Android app có package **`com.example.esp8266prj`**.
2. Tạo **Realtime Database**, chọn location, ví dụ Singapore `asia-southeast1`. Không chọn Firestore. Ghi lại URL chính xác, không tự suy diễn từ project ID.
3. Tải `google-services.json` **sau khi đã tạo RTDB**, đặt tại `app/google-services.json`. Kiểm tra trường `project_info.firebase_url` chứa URL Database. Nếu thiếu, tải lại cấu hình sau khi tạo RTDB.
4. Trong Authentication → Sign-in method, bật **Email/Password**. Tạo **hai tài khoản khác nhau**: một cho điện thoại, một riêng cho ESP8266. Sao chép UID từng tài khoản.
5. Trong bản sao `firebase/database.example.json`, thay `PHONE_USER_UID` và `ESP_USER_UID` bằng UID thật. Import vào database thử nghiệm trống bằng Console. Không import đè root trên database đang có dữ liệu. Với database sẵn có, chỉ thêm các nhánh của thiết bị.
6. Publish nội dung `firebase/database.rules.json` ở tab Rules. Chỉ quản trị viên sửa `deviceUsers`/`deviceAuth`; app không tự cấp quyền.
7. Sync Gradle, Run app, đăng nhập bằng tài khoản điện thoại. Thiết bị mặc định là `dryer-01`; nút **Chọn thiết bị** đổi ID. Cấp quyền cho các thiết bị khác bằng `deviceUsers/<deviceId>/<phoneUid>: true`.

Nếu chưa có `google-services.json`, Gradle vẫn build được; app hiện hướng dẫn cấu hình. Đây không phải chế độ demo có dữ liệu giả. Điện thoại cần Internet và Google Play services để nhận FCM.

## 3. Dependencies và Manifest

Project đang dùng AGP 9.4.0 với Kotlin tích hợp, Gradle 9.6.0, compileSdk/targetSdk 37; giữ cấu hình có sẵn. Dùng JDK tương thích do Android Studio cung cấp và Android SDK 37. Không thêm plugin Kotlin cũ vào AGP 9 chỉ để sao chép ví dụ.

Root `build.gradle` bổ sung:

```groovy
plugins {
    alias(libs.plugins.android.application) apply false
    id 'com.google.gms.google-services' version '4.5.0' apply false
}
```

Trong `app/build.gradle`, plugin Google Services được áp dụng khi file cấu hình tồn tại. Các SDK dùng BoM:

```groovy
if (file('google-services.json').exists()) {
    apply plugin: 'com.google.gms.google-services'
}
dependencies {
    implementation platform('com.google.firebase:firebase-bom:34.19.0')
    implementation 'com.google.firebase:firebase-database'
    implementation 'com.google.firebase:firebase-auth'
    implementation 'com.google.firebase:firebase-messaging'
    // Các dependencies AndroidX và Material đã có trong project.
}
```

Không cần artifact `firebase-*-ktx`. [Hướng dẫn setup/BoM chính thức](https://firebase.google.com/docs/android/setup).

`AndroidManifest.xml` đã khai báo bên ngoài `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

Trong `<application>` có service:

```xml
<service android:name=".RainMessagingService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
<meta-data android:name="com.google.firebase.messaging.default_notification_channel_id"
    android:value="rain_alerts" />
<meta-data android:name="com.google.firebase.messaging.default_notification_icon"
    android:resource="@drawable/ic_rain" />
```

Internet/network state không cần hỏi runtime. Android 13+ cần xin `POST_NOTIFICATIONS`; code dùng Activity Result API. Android 8+ cần notification channel: `rain_alerts`, mức HIGH, bật rung, âm thanh mặc định. Người dùng có thể tắt chuông/rung trong Cài đặt; app không vượt qua lựa chọn đó. [Quyền notification Android](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## 4. JSON và đồng bộ hai chiều

```json
{
  "devices": {
    "dryer-01": {
      "telemetry": {
        "temperatureC": 29.5,
        "humidityPct": 72,
        "flowLpm": 0.125,
        "totalMl": 345,
        "raining": false,
        "updatedAt": 1789516800000
      },
      "control": {
        "mode": "MANUAL",
        "targetAngle": 90,
        "commandId": "uuid-moi-cho-moi-lenh",
        "issuedAt": 1789516800000,
        "expiresAt": 1789516815000
      },
      "reported": {
        "mode": "MANUAL",
        "servoAngle": 90,
        "appliedCommandId": "uuid-moi-cho-moi-lenh"
      }
    }
  }
}
```

Timestamp ở ví dụ là minh họa, không phải thời gian hiện tại. Lệnh minh họa đã hết hạn; không dùng nguyên nó để thử servo.

- **ESP → app:** ESP ghi `telemetry` và `reported`, app dùng `ValueEventListener` để nhận cập nhật. DHT lỗi thì bỏ hai trường nhiệt độ/độ ẩm trong telemetry mới; app hiện `—`.
- **App → ESP:** app ghi **nguyên object** `control` để mode, angle, commandId, thời hạn luôn nhất quán. `AUTO` lấy góc theo mưa; `MANUAL` dùng `targetAngle`. Nút thủ công bị khóa khi đang AUTO. Nút đặt lại tổng dùng tiền tố `reset-total:` trong `commandId`; ESP xóa bộ đếm xung và xác nhận bằng chính ID đó, nên không cần mở rộng Security Rules đã triển khai.
- **Xác nhận:** ghi Firebase thành công chỉ có nghĩa server đã nhận yêu cầu. ESP kiểm tra lệnh, gọi `servo.write()`, rồi gửi `reported.appliedCommandId`. App mới báo đã thực thi. SG90 không có phản hồi vị trí: `servoAngle` là góc ESP đã yêu cầu, không phải phép đo cơ khí.
- **Nhiều điện thoại:** RTDB giữ yêu cầu được ghi sau cùng; từng điện thoại chỉ xác nhận nếu ID của mình được ESP phản hồi. Không xây hàng đợi các chuyển động cơ khí.
- **Thời hạn:** 15 giây, kiểm tra cả Security Rules và firmware. Lệnh bị phát lại sau mất mạng sẽ bị từ chối nếu hết hạn. App dùng `.info/serverTimeOffset`; ESP dùng NTP. ESP không đọc lệnh khi chưa đồng bộ đồng hồ.
- **Mất kết nối:** SDK tự kết nối lại; app quan sát `.info/connected`. Nếu telemetry cũ quá 20 giây thì khóa điều khiển dù kết nối điện thoại–Firebase vẫn tốt. Không bật disk persistence cho các lệnh servo. SDK vẫn có thể giữ một lần ghi trong RAM khi mạng đứt giữa lúc gửi, nên thời hạn ở server/ESP là bắt buộc. [Cơ chế offline RTDB](https://firebase.google.com/docs/database/android/offline-capabilities).
- **Timeout:** sau 20 giây không có ACK, báo chưa xác nhận, không giả định servo đã chạy và không tự lặp lại thao tác. Permission denied cần sửa rules rồi bấm **Kết nối lại**.

## 5. Cảnh báo mưa cả khi chạy nền

Chỉ lắng nghe Database trong Activity không đủ để nhận cảnh báo khi tiến trình app bị dừng. Project này dùng **Cloud Functions → FCM** cho cả foreground và background; không tạo thêm notification từ listener RTDB để tránh cảnh báo trùng từ hai nguồn.

1. Cấu hình Cloud Functions/billing phù hợp trên Firebase project (triển khai Functions cần gói Blaze).
2. Cài Node.js 22 và Firebase CLI trên máy triển khai. Mở terminal:

```powershell
cd firebase/functions
npm install
npm test
cd ..
firebase login
firebase use --add
firebase deploy --only database,functions
```

3. Trước deploy, sửa `region` trong `functions/index.js` cho cùng location RTDB; mẫu là `asia-southeast1`. Nếu một project có nhiều RTDB trong cùng region, thêm `instance: 'TEN_DATABASE_INSTANCE'` vào options của trigger để giới hạn nguồn sự kiện.
4. Đăng nhập app và chấp nhận notification. Token được lưu riêng ở `users/<uid>/fcmTokens/<token>`. Function chỉ gửi tới người được cấp quyền tại `deviceUsers/<deviceId>`; không dùng topic công khai.
5. Test bằng cách gửi telemetry `raining: false`, sau đó `true`. `true → true`, `true → false`, và bản ghi lần đầu `null → true` không gửi thông báo. Không cần thiết bị Android mở dashboard để nhận notification nền.

Foreground: `onMessageReceived` tự dựng notification. Background: FCM notification payload để Android tự hiển thị. [Cách nhận FCM chính thức](https://firebase.google.com/docs/cloud-messaging/android/receive-messages).

FCM là dịch vụ best-effort, không bảo đảm tức thì hoặc đúng một lần. Mất Internet, Doze, Force stop, tắt quyền/channel, hoặc tiết kiệm pin có thể làm trễ/ngăn thông báo. Function loại sự kiện quá 2 phút và đặt TTL 2 phút; retry có thể cập nhật lại notification cùng tag. Thu đồ tự động phải chạy tại ESP, không phụ thuộc điện thoại hay FCM.

## 6. Nạp firmware ESP8266 mẫu

Mở `firmware/WeatherDryer/WeatherDryer.ino` trong Arduino IDE. Chọn board NodeMCU 1.0 (ESP-12E), ESP8266 core 3.x. Cài **ArduinoJson 7.x**, **DHT sensor library by Adafruit**, **Adafruit Unified Sensor**; Servo và ESP8266WiFi đi cùng core.

File `secrets.h` cục bộ đã có Web API key, Database URL, ID thiết bị, tài khoản ESP và GTS Root R1. Chỉ điền `WIFI_SSID` và `WIFI_PASSWORD`; giữ file này ngoài Git. Nếu tạo lại từ `secrets.example.h`, điền thêm mật khẩu tài khoản ESP. Không đưa tài khoản quản trị hoặc service-account key vào firmware/app.

HTTPS xác minh bằng GTS Root R1 hiện hành, lấy từ [Google Trust Services](https://pki.goog/repository/) và có hạn đến 22/06/2036. Khi Google đổi chuỗi TLS hoặc trước ngày hết hạn, cập nhật `TLS_ROOT_CA` từ nguồn chính thức. Đồng hồ NTP phải hoạt động để kiểm tra TLS; firmware không dùng `setInsecure()`.

| Thiết bị | Chân ESP8266 |
| --- | --- |
| DHT11 data | D3 / GPIO0 |
| Cảm biến mưa DO (active LOW) | D0 / GPIO16 |
| YF-S401 signal qua chuyển mức 3.3V | D2 / GPIO4 |
| SG90 signal | D4 / GPIO2 |

Giữ D3/D4 ở mức boot hợp lệ. SG90 dùng nguồn 5V riêng đủ dòng, chung GND; không lấy dòng servo từ chân 3.3V ESP. Tín hiệu cảm biến 5V cần chuyển mức trước ESP8266. Nếu dùng DHT22, đổi `DHT11` thành `DHT22`; cảm biến mưa active HIGH thì đổi `RAIN_LEVEL`.

Servo lắp ngược chiều nên firmware đảo hướng và dùng hành trình 180°: giá trị logic (`targetAngle`/`servoAngle` trong Firebase, app và Security Rules) vẫn là `0` = đưa đồ ra và `90` = thu đồ vào, còn góc PWM thực tế là `90 → 0°` và `0 → 180°` qua hàm `servoWrite()`. Chỉ cần sửa hàm này nếu muốn đổi lại hướng quay hoặc giới hạn hành trình.

Firmware debounce mưa 300ms, đọc DHT mỗi 2 giây, đếm xung bằng ISR, tính lưu lượng theo thời gian lấy mẫu thực. `totalMl` là tích lũy từ lúc khởi động; giá trị sẽ về 0 khi ESP reset hoặc khi người dùng xác nhận nút **Đặt lại tổng lượng mưa** trong app. Hệ số `FLOW_HZ_PER_LPM = 5.5` kế thừa sketch đang có, chỉ là giá trị thử và **phải hiệu chuẩn**:

```text
flowLpm = pulseCount × 1000 / elapsedMs / FLOW_HZ_PER_LPM
totalMl = totalPulses × 1000 / (FLOW_HZ_PER_LPM × 60)
```

YF-S401 đo lưu lượng nước, không trực tiếp đo lượng mưa mm. Nếu dùng phễu diện tích A cm², lượng mưa mm = tổng mL × 10 / A, sau khi hiệu chuẩn hệ thu nước. DHT có thể khóa ngắt ngắn và gây sai số đếm xung.

Mẫu REST tự reconnect Wi-Fi, retry Firebase với backoff tối đa 30 giây và đăng nhập lại trước khi token hết hạn. Khởi động lại luôn trở về AUTO, không phát lại lệnh đã hết hạn. Khi MANUAL mất mạng, ESP giữ góc cuối cùng; muốn mưa luôn được ưu tiên thu đồ cả MANUAL cần đổi chính sách rõ ràng trong firmware.

**Giới hạn thời gian thực:** adapter REST mẫu là đồng bộ, có timeout đọc 2.5 giây. ESP8266 core 3.1.2 không cung cấp `setHandshakeTimeout()`; không có cấu hình timeout handshake riêng trong sketch này. DNS/TCP/TLS và request có thể làm chậm vòng cảm biến; timeout đọc không bảo đảm giới hạn tổng thời gian request và chu kỳ publish/đọc lệnh không được bảo đảm đúng 1 giây. Không có vòng chờ vô hạn Wi-Fi, nhưng đây chưa phải transport bất đồng bộ cho yêu cầu an toàn thời gian thực nghiêm ngặt. Nếu cần phản ứng mưa dưới một giây ngay cả khi mạng lỗi, thay adapter REST bằng client bất đồng bộ hoặc tách bộ điều khiển an toàn. Logic AUTO vẫn hoạt động khi không có Wi-Fi sau khi network call trả về.

## 7. Kiểm tra và build

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test firebase/functions/rain.test.js
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Kiểm tra tích hợp sau khi điền cấu hình:

1. Dữ liệu thật hiện đủ đơn vị °C, %, L/min, mL. Ngắt DHT: hai ô hiện `—`.
2. AUTO + mưa → ESP thu đồ vào (góc PWM 0°), khô → đưa đồ ra (180°). Hai nút thủ công bị khóa.
3. Chuyển MANUAL, chờ ACK rồi thử 90° và 0°; `reported` phải đổi theo.
4. Tắt Wi-Fi điện thoại: báo offline, khóa nút; bật lại: tự cập nhật.
5. Tắt ESP nhưng giữ điện thoại online: sau 20 giây báo dữ liệu cũ, khóa nút.
6. Gửi lệnh rồi ngắt mạng: quá hạn không được chạy khi nối lại. Hai điện thoại gửi gần nhau không được báo ACK sai ID.
7. Mưa liên tục chỉ báo một lần khi chuyển trạng thái; thử khi mở app, app ở nền và sau khi từ chối notification.
8. Tài khoản chưa được cấp quyền không đọc/điều khiển được; tài khoản phone không ghi telemetry/reported; tài khoản ESP không ghi control hay ACL.

Build/unit test không thay thế kiểm thử thiết bị thật, Firebase Rules Emulator và kiểm tra chuông/rung trên điện thoại. Cần Firebase project thật và phần cứng để xác minh toàn tuyến.
"# iot-android-rain" 
