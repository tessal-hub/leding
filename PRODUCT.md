# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- Chủ nhân / người dùng đèn LED Matrix 360 độ (ESP32 DIY Smart Lamp) đặt trên bàn làm việc, góc setup cá nhân hoặc phòng ngủ.
- Nhu cầu chính: Điều khiển nhanh hiệu ứng ánh sáng (10+ mode), chỉnh màu sắc tĩnh RGB theo tâm trạng, nhận phản hồi thông báo tức thì (cuộc gọi, Zalo, Telegram, Messenger) và đồng bộ thời tiết, giờ hệ thống.
- Nhà phát triển / maker: Cần theo dõi telemetry thời gian thực (góc nghiêng pitch/roll, mức pin/sạc, RAM heap) và nạp firmware OTA trực tiếp qua BLE.

## Product Purpose

- Cung cấp giao diện điều khiển trung tâm, mượt mà và trực quan cho đèn LED Matrix 360 độ qua kết nối Bluetooth Low Energy (BLE).
- Thành công nghĩa là: Kết nối BLE ổn định tức thì, phản hồi điều khiển thời gian thực với độ trễ thấp (<50ms), đồng bộ thông báo và thời tiết tin cậy trong nền, không tốn pin điện thoại.

## Positioning

- Ứng dụng điều khiển chuyên biệt cho phần cứng LED Matrix 360 hình trụ/khối lập phương 4 mặt, kết hợp giữa đèn thông minh trang trí và trạm thông tin bàn làm việc.
- Giao tiếp trực tiếp qua giao thức nhị phân siêu nhẹ Nordic UART Service (NUS) trên BLE, hoạt động hoàn toàn offline không bắt buộc phải có tài khoản đám mây.

## Operating Context

- Môi trường sử dụng: Trong phòng, góc làm việc hoặc đầu giường; thường được mở vào buổi tối hoặc khi bắt đầu phiên làm việc.
- Thiết bị chạy: Điện thoại Android (hỗ trợ từ Android 7.0 / SDK 24 đến Android 14+ / SDK 34), kết nối với phần cứng ESP32.
- Dịch vụ nền: `BleForegroundService` duy trì kết nối và `LampNotificationListener` bắt ngắt thông báo hệ thống để kích hoạt flash đèn.

## Capabilities and Constraints

- **Kết nối**: Bluetooth Low Energy (Nordic UART Service: RX `6E400002...`, TX `6E400003...`, Device name: `LED-MATRIX-360`).
- **Điều khiển ánh sáng**:
  - Độ sáng tức thì (0-255).
  - 10 chế độ hiệu ứng tinh chọn (Lava Noise, Edge Light, Face Wave, Tilt Glow, Water Physics, Night Breathing, Aurora Flow, v.v.).
  - Bảng màu tĩnh HSV/RGB và preset màu một chạm.
- **Tương tác thông báo**: Chớp đèn theo mã màu ứng dụng (Zalo: xanh dương, Messenger: tím hồng, Cuộc gọi: đỏ, Telegram: cyan, v.v.).
- **Đồng bộ thời tiết & thời gian**: Định kỳ qua `WorkManager` (API Open-Meteo).
- **Cập nhật OTA**: Nạp firmware `.bin` qua BLE GATT với gói nhị phân (Opcode 0x10, 0x11, 0x12).
- **Ràng buộc UI**: Tuân thủ chuẩn Jetpack Compose Material 3, hỗ trợ Material You Dynamic Theme (theo màu nền hệ thống Android 12+) với fallback Dark Mode tương phản cao cho môi trường ban đêm.

## Brand Commitments

- Tên ứng dụng: **LED Lamp 360°** (CubeLamp / LED Matrix 360).
- Phong cách nhận diện: Công nghệ hiện đại, tinh gọn, tương phản sắc nét, tối ưu cho môi trường điều khiển ánh sáng ban đêm.

## Evidence on Hand

- Toàn bộ mã nguồn Android Compose native tại `android/app/src/main/java/com/esp32/ledmatrix360/`.
- Định nghĩa giao thức nhị phân và danh sách hiệu ứng tại `model/LampModels.kt`.
- Mã nguồn ESP32 Firmware tại thư mục gốc của repository.

## Product Principles

1. **Phản hồi tức thì (Zero Latency Feel)**: Mọi thao tác kéo slider độ sáng hoặc chọn màu phải gửi frame BLE ngay lập tức mà không gây giật lag UI.
2. **Offline & Tự chủ**: Đèn hoạt động độc lập không phụ thuộc cloud; kết nối BLE cục bộ là kênh điều khiển cốt lõi.
3. **Thân thiện ban đêm**: Ưu tiên Dark Theme và độ tương phản dịu mắt, tránh chói lóa khi người dùng chỉnh đèn trong phòng tối.
4. **Chuẩn mực Native Android**: Tuân thủ hướng dẫn Material 3, touch targets tối thiểu 48dp, hỗ trợ điều hướng cử chỉ và Dynamic Color.

## Accessibility & Inclusion

- Đảm bảo touch target tối thiểu 48×48 dp cho tất cả các nút bấm và slider.
- Kích thước văn bản sử dụng đơn vị `sp` để tôn trọng cài đặt tỷ lệ chữ của hệ điều hành.
- Nhãn ngữ cảnh (contentDescription) đầy đủ cho icon và các nút điều khiển.
