# 情感陪伴 AI · NFC 管理 Demo

用户通过触碰 NFC 芯片，直接打开 Android App 并跳转到对应 AI 角色的对话界面。每个 NFC 芯片在用户首次触碰时绑定到该用户账号，后续不能被其他用户触发。

## 快速开始

```bash
# 1. 启动后端
cd server
npm install
node server.js

# 2. 编译 APK（在另一终端）
cd android/EmotionAiNFC
./gradlew assembleDebug -x test --parallel
# APK 路径: app/build/outputs/apk/debug/app-debug.apk

# 3. 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **注意**: 编译前确认 `app/build.gradle.kts` 中 `SERVER_BASE_URL` 的 IP 地址与手机 Wi-Fi 可达。

## 项目结构

```
EmotionAI_NFC/
├── server/                    # Express + SQLite 后端
│   ├── server.js              # 主服务 (API + 数据库)
│   └── emotion_ai.db          # SQLite 数据库（自动生成）
├── nfc_injection/             # NFC 芯片注入工具
│   ├── ntag424_batch_inject.py      # ACR1252 + NTAG 424 DNA 批量注入
│   ├── ntag424_config.json          # 424 密钥配置
│   ├── ntag213_tagwriter.csv        # TagWriter App 导入 CSV (NTAG 213)
│   └── ntag213_injection_guide.txt  # TagWriter 操作说明
├── android/EmotionAiNFC/      # Android App 源码 (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/emotionai/nfc/
│       ├── MainActivity.kt    # 入口 + NFC Intent 处理 + 导航
│       ├── nfc/NfcHandler.kt  # NFC 标签读取工具类
│       ├── network/           # Retrofit API 客户端
│       ├── data/              # 数据模型 / 仓库 / 本地存储
│       └── ui/                # Compose UI 界面
├── PROJECT_DOCUMENT.md        # 项目设计文档
├── PROJECT_PLAN.md            # 项目规划
└── IMPLEMENTATION_GUIDE.md    # 完整实现指南
```

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| App | Android (Kotlin + Jetpack Compose) | API 26+, NFC Intent Filter |
| 后端 | Node.js (Express) + SQLite | 单进程，零配置 |
| NFC 424 | ACR1252U 读卡器 + Python | NTAG 424 DNA 防伪注入 |
| NFC 213 | NXP TagWriter App | 手机直接写入 |

## 演示流程

```
触碰 NFC 芯片 → App 解析 chipId → GET /api/chips/{chipId}
    ├── 未绑定 → ChipBindingScreen → 选角色 → POST /api/chips/bind → ChatScreen
    └── 已绑定 → 判断是否本人
                    ├── 是 → ChatScreen
                    └── 否 → 提示"已被绑定"
```

## API 接口

| 方法 | 路径 | 说明 | 需 Token |
|------|------|------|----------|
| POST | `/api/auth/register` | 注册 | 否 |
| POST | `/api/auth/login` | 登录 | 否 |
| GET | `/api/auth/users` | 用户列表 | 否 |
| GET | `/api/chips/:chipId` | 查询芯片 | 可选 |
| POST | `/api/chips/bind` | 绑定芯片 | 是 |
| POST | `/api/chips/verify` | 424 防伪验证 | 是 |
| GET | `/api/user/chips` | 我的芯片列表 | 是 |

## Bug 修复记录

- **processChipId 缺少失败处理** — API 失败时弹出"查询芯片失败"错误对话框
- **extractChipId 只处理 NDEF_DISCOVERED** — 增加从 Tag 直读 NDEF 的 fallback，兼容 TECH_DISCOVERED
- **TagWriter CSV scheme 不匹配** — 将 subtype 从 `http://` 修正为 `none`
