# 情感陪伴 AI · NFC 管理 — 项目总体设计文档

> **版本**: v1.0  
> **最后更新**: 2026-07-22  
> **阅读对象**: 开发人员  

---

## 1. 项目概述

### 1.1 项目背景

用户拥有多个情感陪伴 AI 角色（如温柔型、知性型、幽默型等），每个 AI 角色对应一个 NFC 芯片。用户触碰芯片即可直接打开 Android App 并跳转到该 AI 的对话界面。

### 1.2 项目目标

- **核心功能**: 触碰 NFC → 打开 App → 跳转对应 AI 对话界面
- **芯片绑定**: 首次触碰自动绑定到该用户，后续不能被其他用户使用
- **防伪（可选）**: NTAG 424 DNA 芯片采用 SUN/SDM 防伪认证
- **轻量演示**: 无需实现真实 AI 对话功能，界面占位即可

### 1.3 演示流程

```
用户触碰 NFC 芯片
    ↓
Android 系统捕获 NDEF Intent
    ↓
App 解析 chipId
    ↓
调用后端: GET /api/chips/{chipId}
    ↓
    是否首次触碰?
    ├── 是 → 显示绑定页面 → POST /api/chips/bind → 跳转 AI 对话页
    └── 否 → 判断绑定用户 == 当前用户?
                ├── 是 → 直接跳转 AI 对话页
                └── 否 → 提示"该芯片已被其他用户绑定"
```

---

## 2. 技术选型

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **App** | Android 原生 (Kotlin) | API 26+ (Android 8.0) | NFC Intent Filter 原生支持 |
| **UI** | Jetpack Compose + Material3 | BOM 2024.02+ | 声明式 UI，开发快速 |
| **网络** | Retrofit 2 + OkHttp + Gson | 最新稳定版 | REST API 调用 |
| **本地存储** | SharedPreferences | — | 存登录 token / 用户信息 |
| **后端** | Node.js + Express | 18+ | 单文件轻量服务器 |
| **数据库** | SQLite (better-sqlite3) | — | 零配置，嵌入式 |
| **NFC 424** | ACR1252U 读卡器 + Python | 3.10+ | 批量注入脚本 |
| **NFC 213** | TagWriter App + CSV | — | 手机直接写入 |

### 选型理由

- **仅 Android**：iOS NFC 受限严重，且 Demo 后需重新设计 App，不做跨平台
- **Express + SQLite**：单进程运行，没有任何外部依赖，适合演示环境
- **Python 脚本批量注**：ACR1252 支持 PC/SC，pyscard 库成熟
- **TagWriter CSV**：无需额外硬件，手机+NFC工具直接写 NTAG 213

---

## 3. 系统架构

```
┌─────────────────────────────┐     ┌──────────────────┐
│  Android App                │     │   Express Server  │
│                             │     │                  │
│  ┌───────────────────────┐  │     │  ┌────────────┐  │
│  │ NFC Intent Handler    │  │     │  │ Auth API   │  │
│  │ (Intent Filter)       │  │     │  │ /api/auth/*│  │
│  └────────┬──────────────┘  │     │  └────────────┘  │
│           │                 │ HTTP │  ┌────────────┐  │
│  ┌────────▼──────────────┐  │ ◄───► │  │ Chip API   │  │
│  │ Retrofit API Client   │  │     │  │ /api/chips/*│  │
│  └────────┬──────────────┘  │     │  └────────────┘  │
│           │                 │     │  ┌────────────┐  │
│  ┌────────▼──────────────┐  │     │  │ SQLite DB  │  │
│  │ ViewModel + Repository│  │     │  └────────────┘  │
│  └────────┬──────────────┘  │     └──────────────────┘
│           │                 │
│  ┌────────▼──────────────┐  │
│  │ Jetpack Compose UI    │  │
│  └───────────────────────┘  │
└─────────────────────────────┘

            ┌──────────────────┐
            │   NFC 芯片实体    │
            │   (424 / 213)    │
            │  NDEF: emoai://  │
            └──────────────────┘
```

---

## 4. 用户认证方案

### 4.1 设计原则

- **无密码**：演示场景下不需要真实密码
- **多账号**：支持创建和切换不同账号（模拟多用户场景）
- **不用设备 IMEI**：测试时只有一部手机，不能以此作为用户标识

### 4.2 认证流程

```
首次使用:
  输入用户名 → POST /api/auth/register → 生成 userId + token → 存入本地

再次使用:
  启动 App → 显示已有用户列表 → 点击选择 → 登录 (带 token)

切换用户:
  设置 → 切换账号 → 返回用户列表
```

### 4.3 API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册新用户 |
| POST | `/api/auth/login` | 登录（返回 token） |
| GET | `/api/auth/users` | 获取所有已注册用户（供选择列表使用） |

### 4.4 安全性说明

Demo 阶段的用户名认证仅用于区分用户身份，**不涉及真实安全**。生产环境应替换为 OAuth/手机号/邮箱验证。

---

## 5. 数据库设计 (SQLite)

### 5.1 表结构

```sql
-- 用户表
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    token TEXT NOT NULL,                    -- 简单 UUID token
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- AI 角色表（预置数据）
CREATE TABLE ai_characters (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,                     -- 角色名称
    description TEXT,                       -- 角色描述
    personality TEXT,                       -- 性格标签
    avatar_color TEXT DEFAULT '#6750A4'     -- 头像颜色（Demo 中用颜色代替头像）
);

-- NFC 芯片绑定表
CREATE TABLE chips (
    id TEXT PRIMARY KEY,                    -- chipId (NDEF 中的唯一标识)
    chip_type TEXT NOT NULL DEFAULT '424',  -- '424' 或 '213'
    uid TEXT,                               -- NFC 芯片 UID（424 防伪用）
    bound_user_id INTEGER,                  -- 绑定的用户 ID，NULL=未绑定
    ai_character_id INTEGER NOT NULL,       -- 绑定到哪个 AI 角色
    first_bound_at DATETIME,
    last_access_at DATETIME,
    is_genuine INTEGER DEFAULT 0,           -- 424 防伪验证是否通过
    FOREIGN KEY (bound_user_id) REFERENCES users(id),
    FOREIGN KEY (ai_character_id) REFERENCES ai_characters(id)
);

-- 索引
CREATE INDEX idx_chips_bound_user ON chips(bound_user_id);
CREATE INDEX idx_chips_uid ON chips(uid);
```

### 5.2 预置 AI 角色数据

```sql
INSERT INTO ai_characters (id, name, description, personality, avatar_color)
VALUES 
    (1, '小林',   '温柔贴心的日常陪伴',        '温柔、细腻、善解人意',  '#6750A4'),
    (2, '苏菲',   '知性优雅的深度对话',        '博学、冷静、有见解',    '#006D40'),
    (3, '小阳',   '阳光开朗的元气伙伴',        '热情、幽默、正能量',    '#B64500'),
    (4, '月月',   '古灵精怪的脑洞少女',        '俏皮、创意、不按常理',  '#9C27B0'),
    (5, '老陈',   '成熟稳重的知心大哥',        '靠谱、理性、话少但暖',  '#004D73');
```

---

## 6. API 设计 (完整)

Base URL: `http://<server-ip>:3000/api`

### 6.1 认证

**POST /api/auth/register**
```json
// Request
{ "username": "测试用户A" }

// Response 201
{ "success": true, "data": { "userId": 1, "username": "测试用户A", "token": "uuid-string" } }

// Error 409
{ "success": false, "error": "用户名已存在" }
```

**POST /api/auth/login**
```json
// Request
{ "username": "测试用户A" }

// Response 200
{ "success": true, "data": { "userId": 1, "username": "测试用户A", "token": "uuid-string" } }

// Error 404
{ "success": false, "error": "用户不存在" }
```

**GET /api/auth/users**
```json
// Response 200
{ "success": true, "data": [
    { "userId": 1, "username": "测试用户A" },
    { "userId": 2, "username": "测试用户B" }
]}
```

### 6.2 NFC 芯片

**GET /api/chips/:chipId**
```json
// 芯片已绑定 & 本人
{ "success": true, "data": {
    "chipId": "abc-123",
    "chipType": "424",
    "isBound": true,
    "isCurrentUser": true,
    "aiCharacter": { "id": 1, "name": "小林", "description": "...", "personality": "...", "avatarColor": "#6750A4" }
}}

// 芯片已绑定 & 非本人
{ "success": true, "data": {
    "chipId": "abc-123",
    "isBound": true,
    "isCurrentUser": false,
    "boundTo": "其他用户"
}}

// 芯片未绑定
{ "success": true, "data": {
    "chipId": "abc-123",
    "isBound": false,
    "aiCharacters": [ /* 所有可选角色列表 */ ]
}}

// 芯片不存在（自动创建记录）
{ "success": true, "data": {
    "chipId": "new-chip-id",
    "isBound": false,
    "isNew": true,
    "aiCharacters": [ /* 所有可选角色列表 */ ]
}}
```

**POST /api/chips/bind**
```json
// Request (需要 Header: Authorization: Bearer <token>)
{ "chipId": "abc-123", "aiCharacterId": 1 }

// Response 200
{ "success": true, "data": { "chipId": "abc-123", "boundUserId": 1, "aiCharacterId": 1 }}

// Error 409
{ "success": false, "error": "该芯片已被其他用户绑定" }
```

**GET /api/chips/verify (424 防伪)**
```json
// Request (需要 Header: Authorization: Bearer <token>)
{ "chipId": "abc-123", "uid": "048A3B12...", "signature": "AABBCCDD..." }

// Response 200 - 验证通过
{ "success": true, "data": { "isGenuine": true }}

// Response 200 - 验证失败
{ "success": true, "data": { "isGenuine": false, "message": "芯片签名验证失败" }}
```

### 6.3 用户管理

**GET /api/user/chips**
```json
// Header: Authorization: Bearer <token>

// Response 200
{ "success": true, "data": [
    { "chipId": "abc-123", "aiCharacter": { "name": "小林", ... }, "boundAt": "2024-xx-xx" },
    { "chipId": "def-456", "aiCharacter": { "name": "苏菲", ... }, "boundAt": "2024-xx-xx" }
]}
```

---

## 7. NFC 芯片数据格式

### 7.1 通用 NDEF 记录格式

所有芯片（424 和 213）使用统一的 NDEF 记录格式：

- **记录类型**: URI (0x55)
- **URI 协议**: `emoai://` (自定义 scheme)
- **完整 URI**: `emoai://chip/{chipId}`
- **chipId**: UUID v4 格式，如 `a1b2c3d4-e5f6-7890-abcd-ef1234567890`

Android Intent Filter 匹配 `emoai://` scheme，触碰即打开 App。

### 7.2 Android Intent Filter 配置

```xml
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="emoai" android:host="chip" />
</intent-filter>
```

### 7.3 NTAG 424 DNA 数据布局

424 芯片除了 NDEF 记录外，还配置 SDM (Secure Dynamic Messaging) 用于防伪：

```
内存布局:
┌──────────────────────────────────────────────┐
│  块 0x00: CC + NDEF TLV 头部                  │
│  块 0x01-0x03: NDEF URI 数据 ("emoai://...")  │
│  ...                                          │
│  SDM 配置区:                                  │
│    - UID 包含在 SDM 返回数据中                  │
│    - ReadCounter: 每次读取递增                  │
│    - CMAC: 用 AES 密钥对 UID+计数+数据签名      │
└──────────────────────────────────────────────┘
```

SUN (Secure Unique NFC) 验证流程:
```
1. App 读取芯片 → 获取 NDEF URI + UID + SDM CMAC
2. App 将 UID + CMAC + chipId 发给后端
3. 后端用预置 AES 密钥计算 CMAC，对比芯片返回的 CMAC
4. 一致 → 真品；不一致 → 克隆/伪造
```

### 7.4 NTAG 213 数据布局

213 芯片仅存储 NDEF URI，不做加密：

```
内存布局:
┌──────────────────────────────────────────────┐
│  块 0x00: UID (只读)                         │
│  块 0x01-0x02: Capability Container           │
│  块 0x03+: NDEF 消息                          │
│    - NDEF TLV: 03 <len> <type> <payload>      │
│    - URI: "emoai://chip/{chipId}"             │
│  ...                                          │
└──────────────────────────────────────────────┘
```

---

## 8. Android App 架构

### 8.1 包结构

```
com.emotionai.nfc/
├── EmotionAiApp.kt            # Application 类
├── MainActivity.kt             # 入口 + NFC Intent 处理 + 导航
│
├── ui/
│   ├── LoginScreen.kt          # 用户登录/注册/选择
│   ├── ChipBindingScreen.kt    # 首次触碰 → 选择 AI 角色绑定
│   ├── ChatListScreen.kt       # 已绑定的芯片列表（角色列表）
│   ├── ChatScreen.kt           # AI 对话占位页面
│   └── theme/
│       └── Theme.kt            # Material3 主题
│
├── nfc/
│   └── NfcHandler.kt           # NFC 标签读取工具类
│
├── network/
│   ├── ApiService.kt           # Retrofit 接口定义
│   └── RetrofitClient.kt       # Retrofit 单例
│
├── data/
│   ├── Models.kt               # 数据类定义
│   ├── UserPreferences.kt      # SharedPreferences 封装
│   └── Repository.kt           # 仓库层
│
└── viewmodel/
    ├── AuthViewModel.kt        # 认证状态管理
    ├── ChipViewModel.kt        # 芯片绑定逻辑
    └── ChatViewModel.kt        # AI 对话页面状态
```

### 8.2 导航图

```
[LoginScreen] ──(登录成功)──> [ChatListScreen]
                                    │
                               (点击 AI 角色)
                                    │
                                    ▼
                              [ChatScreen]
                                    ▲
                                    │
[NFC Tag Detected] ──(解析 chipId)──┘
       │
       ├── 未绑定 ──> [ChipBindingScreen] ──(选择角色)──> ChatScreen
       └── 已绑定 ──> 直接跳转 ChatScreen (本人)
```

### 8.3 NFC 处理流程

```
onNewIntent(Intent)
    ↓
intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED
    ↓
解析 NDEF 消息 → 提取 URI → 提取 chipId
    ↓
检查本地是否有已登录用户
    ├── 没有 → 跳转 LoginScreen（提示先登录）
    └── 有 → 调用 GET /api/chips/{chipId}
                ├── 404 → 自动创建 → 跳转 ChipBindingScreen
                ├── 未绑定 → 跳转 ChipBindingScreen
                └── 已绑定
                    ├── 本人 → 跳转 ChatScreen
                    └── 非本人 → 弹窗提示
```

---

## 9. 防伪方案 (NTAG 424 DNA)

### 9.1 密钥配置

```
系统主密钥 (Master Key): 16 字节随机数
  用于派生每张芯片的唯一密钥

每张芯片的密钥:
  NDEF 加密密钥 = AES(MasterKey, chipUID || 0x01)  -- AES-128 加密派生
  SDM 签名密钥   = AES(MasterKey, chipUID || 0x02)  -- AES-128 加密派生
```

Demo 阶段为简化，使用共享主密钥，所有芯片密钥一致。

### 9.2 验证流程

```
App 端:
  1. 读取 NDEF → 获得 chipId
  2. 读取芯片 UID（通过 READ 命令）
  3. 获取 SDM 数据（含加密的 ReadCounter + CMAC）
  4. 将 {chipId, UID, ReadCounter, CMAC} 发送给后端

后端:
  1. 根据 chipId 查找芯片记录
  2. 使用预共享 AES 密钥计算期望 CMAC
  3. CMAC = AES-CMAC(Key, UID || ReadCounter || 应用数据)
  4. 对比期望 CMAC 与收到的 CMAC
  5. 一致 → isGenuine = true
```

---

## 10. 后端 API 完整错误码

| 状态码 | 含义 | 场景 |
|--------|------|------|
| 200 | 成功 | 正常返回 |
| 201 | 创建成功 | 注册/绑定成功 |
| 400 | 请求参数错误 | 缺少必填字段 |
| 401 | 未授权 | Token 无效或过期 |
| 404 | 资源不存在 | 用户不存在等 |
| 409 | 冲突 | 芯片已绑定、用户名已存在 |
| 500 | 服务器内部错误 | 异常情况 |

---

## 11. 技术约束与注意事项

1. **App 编译**: 仅在确认电脑资源充足后编译 APK，编译前会询问用户
2. **服务器地址**: 演示时使用 `ipconfig` 获取的本机局域网 IP，手机与电脑需同一 Wi-Fi
3. **NFC 芯片数量**: 建议至少 3-5 张芯片用于演示绑定和切换
4. **测试场景**:
   - 同一用户绑多张芯片 → App 中显示角色列表
   - 不同用户触碰同一芯片 → 显示"已被绑定"
   - 424 芯片防伪验证 → 后端记录 isGenuine
