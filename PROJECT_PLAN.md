# 情感陪伴 AI · NFC 管理 Demo — 项目规划

## 项目概述

用户通过触碰 NFC 芯片，直接打开 Android App 并跳转到对应 AI 角色的对话界面。每个 NFC 芯片在用户首次触碰时绑定到该用户账号，后续不能被其他用户触发。

## 技术选型

| 层级 | 技术 | 说明 |
|------|------|------|
| **App** | Android 原生 (Kotlin + Jetpack Compose) | 仅用于本次 Demo 测试，非最终产品 |
| **后端** | Express + SQLite | 轻量级自托管服务器，单文件可运行 |
| **部署** | 服务器本地运行，手机通过 Wi-Fi / ngrok 访问 | 演示时需联网 |

## 为什么选择这些技术

- **Android 原生**：NFC 支持最直接（内置 `NfcAdapter`、NDEF Intent Filter），无需跨平台插件层
- **Express + SQLite**：零配置数据库，一个 `server.js` 约 80 行即可完成全部 API
- **不选 Flutter**：最终 App 会重新设计，现在引入 Flutter 增加不必要的学习成本和桥接复杂度
- **不选 Firebase**：虽然可以零代码后端，但 Express 方案更可控且不依赖第三方服务

## 演示流程

```
用户触碰 NFC 芯片
    ↓
Android Intent Filter 捕获 NDEF 记录
    ↓
App 读取芯片 ID (chipId)
    ↓
调用后端 API: GET /chips/{chipId}
    ↓
是否为首次触碰？ (isBound?)
    ├── 是 → 显示绑定页面 → POST /chips/bind → 跳转对应 AI 对话页
    └── 否 → 判断是否本人 (userId 匹配)？
                ├── 是 → 直接跳转对应 AI 对话页
                └── 否 → 提示"该芯片已绑定"
```

## 后端 API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/register` | 注册（设备 ID 或简单用户名） |
| POST | `/auth/login` | 登录，返回 token |
| POST | `/chips/bind` | 绑定芯片到当前用户 |
| GET | `/chips/{chipId}` | 查询芯片信息（绑定状态、绑定的 AI 角色） |
| GET | `/user/chips` | 获取用户已绑定的芯片列表 |
| GET | `/ai/characters` | 获取可选的 AI 角色列表 |

## 数据库设计 (SQLite)

```sql
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chips (
    id TEXT PRIMARY KEY,           -- NFC 芯片 UID 或 NDEF 记录中的唯一 ID
    bound_user_id INTEGER,         -- NULL = 未绑定
    ai_character_id INTEGER,
    first_bound_at DATETIME,
    FOREIGN KEY (bound_user_id) REFERENCES users(id),
    FOREIGN KEY (ai_character_id) REFERENCES ai_characters(id)
);

CREATE TABLE ai_characters (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    avatar_url TEXT
);
```

## NFC 芯片数据格式

- **NDEF 记录类型**：外部类型 (`android.com:pkg`) 或自定义 URI
- **记录内容**：`emoai://chips/{chipId}`
- Android Intent Filter 匹配此 scheme，触碰即打开 App

## 当前状态

- [x] 项目方向确定
- [ ] App 项目搭建
- [ ] 后端项目搭建
- [ ] NFC 写入工具准备
- [ ] 联调测试

---

*此文档记录项目规划阶段的技术决策，后续实现前可根据实际情况调整。*
