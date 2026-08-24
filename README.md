# OneSignal Android 推送测试 App

一个最小、可本地安装的 Android 测试应用，用于验证 OneSignal 设备注册、通知权限、External ID 和 Data Tag。

## 已配置

- OneSignal App ID：`36e39826-e283-4bb5-82e4-c73cfa46c71f`
- Firebase Project ID：`apppush-374db`（仅用于界面提示与配置核对）
- Android applicationId：`com.yqj.onesignaltester`
- OneSignal Android SDK：`5.9.1` Stable，精确锁定版本
- minSdk：21；targetSdk / compileSdk：35

## 安全设计

- App 中没有 OneSignal REST API Key，也没有 Firebase Service Account 私钥。
- 不允许从手机端直接调用 OneSignal REST API 发广播，避免密钥被反编译泄漏。
- 所有 OneSignal SDK 调用集中在 `OneSignalManager.kt`。
- 未添加 `google-services.json` 和 Google Services Gradle 插件；OneSignal SDK 不要求在 App 工程中添加它们。

## 使用步骤

1. 在 Firebase 项目 `apppush-374db` 中启用 Firebase Cloud Messaging API v1。
2. 在 Firebase 项目设置的 Service Accounts 页面生成私钥 JSON。
3. 在 OneSignal 控制台进入 `Settings > Push & In-App > Push Platforms > Google Android (FCM)`，上传该 Service Account JSON。
4. 安装 APK 并打开。等待界面显示真实 `Push Subscription ID`，该 ID 不能是空值，也不能以 `local-` 开头。
5. 注册成功后会显示一次英文确认框。点击 `Got it`，再在 Android 系统弹窗中允许通知。
6. 在 OneSignal 控制台的 Audience / Subscriptions 中查找该 Subscription ID，向它发送 Test Message。

重复验证时请直接覆盖安装或重新打开 App，不要为了测试而清除数据或反复卸载重装，否则会生成新的订阅记录。
