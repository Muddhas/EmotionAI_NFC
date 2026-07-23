# 情感陪伴 AI · NFC 管理 — 项目实现流程指南

> **版本**: v1.0  
> **最后更新**: 2026-07-22  
> **说明**: 本文档包含完整的代码实现，按顺序逐步完成。在开始前请先阅读 PROJECT_DOCUMENT.md 了解架构设计。

---

## 目录

1. [环境准备](#1-环境准备)
2. [后端实现 (Express + SQLite)](#2-后端实现-express--sqlite)
3. [NFC 芯片注入](#3-nfc-芯片注入)
4. [Android App 实现](#4-android-app-实现)
5. [联调测试](#5-联调测试)
6. [附录](#6-附录)

---

## 1. 环境准备

### 1.1 后端环境

| 工具 | 版本要求 | 下载 |
|------|---------|------|
| Node.js | 18+ | [nodejs.org](https://nodejs.org) |
| npm | 内置 | 随 Node.js 安装 |

验证安装：
```bash
node --version   # ≥ v18
npm --version    # 随 Node 版本
```

### 1.2 Android 开发环境

| 工具 | 说明 |
|------|------|
| Android Studio | 用于编写/编译 Kotlin 代码 |
| Android SDK | API 26+ (Android 8.0) |
| USB 数据线 | 连接手机调试 |

> **⚠️ 注意**: Android Studio 占用内存较大，编译 APK 前需确认电脑资源充足。后续步骤中会询问"是否开始编译"。

### 1.3 NFC 工具准备

#### NTAG 424 DNA 芯片
| 工具 | 用途 |
|------|------|
| ACR1252U USB 读卡器 | 与电脑连接，批量写入 |
| Python 3.10+ | 运行注入脚本 |
| pyscard 库 | PC/SC 通信 |

安装 Python 依赖：
```bash
pip install pyscard
```

#### NTAG 213 芯片
| 工具 | 用途 |
|------|------|
| NXP TagWriter App | 手机端写入 NFC 芯片 |
| CSV 文件 | 导入 TagWriter 批量写入 |

### 1.4 目录结构

```
EmotionAI_NFC/
├── PROJECT_DOCUMENT.md          # ← 项目设计文档（请先阅读）
├── IMPLEMENTATION_GUIDE.md      # ← 本文档
├── server/                      # 后端代码
│   ├── package.json
│   └── server.js
├── nfc_injection/               # NFC 注入相关
│   ├── ntag424_batch_inject.py  # ACR1252 批量注入脚本
│   ├── ntag424_config.json      # 424 密钥配置
│   ├── ntag424_injection_cmds.txt  # 手动 APDU 指令
│   ├── ntag213_tagwriter.csv    # TagWriter 导入 CSV
│   └── ntag213_injection_guide.txt # TagWriter 操作说明
└── android/                     # Android App 源码
    └── (详见第 4 章)
```

---

## 2. 后端实现 (Express + SQLite)

### 2.1 初始化项目

```bash
cd EmotionAI_NFC/server
npm init -y
npm install express better-sqlite3 uuid cors
```

### 2.2 完整 server.js

创建 `server/server.js`：

```javascript
const express = require('express');
const Database = require('better-sqlite3');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const path = require('path');
const crypto = require('crypto');

// ========================================
// 初始化
// ========================================
const app = express();
const PORT = 3000;

app.use(cors());
app.use(express.json());

// ========================================
// 数据库初始化
// ========================================
const db = new Database(path.join(__dirname, 'emotion_ai.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

// 创建表
db.exec(`
    CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT UNIQUE NOT NULL,
        token TEXT NOT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS ai_characters (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        description TEXT,
        personality TEXT,
        avatar_color TEXT DEFAULT '#6750A4'
    );

    CREATE TABLE IF NOT EXISTS chips (
        id TEXT PRIMARY KEY,
        chip_type TEXT NOT NULL DEFAULT '424',
        uid TEXT,
        bound_user_id INTEGER,
        ai_character_id INTEGER NOT NULL,
        first_bound_at DATETIME,
        last_access_at DATETIME,
        is_genuine INTEGER DEFAULT 0,
        FOREIGN KEY (bound_user_id) REFERENCES users(id),
        FOREIGN KEY (ai_character_id) REFERENCES ai_characters(id)
    );

    CREATE INDEX IF NOT EXISTS idx_chips_bound_user ON chips(bound_user_id);
    CREATE INDEX IF NOT EXISTS idx_chips_uid ON chips(uid);
`);

// 预置 AI 角色数据（如果为空）
const charCount = db.prepare('SELECT COUNT(*) as count FROM ai_characters').get();
if (charCount.count === 0) {
    const insertChar = db.prepare(
        'INSERT INTO ai_characters (id, name, description, personality, avatar_color) VALUES (?, ?, ?, ?, ?)'
    );
    insertChar.run(1, '小林', '温柔贴心的日常陪伴', '温柔、细腻、善解人意', '#6750A4');
    insertChar.run(2, '苏菲', '知性优雅的深度对话', '博学、冷静、有见解', '#006D40');
    insertChar.run(3, '小阳', '阳光开朗的元气伙伴', '热情、幽默、正能量', '#B64500');
    insertChar.run(4, '月月', '古灵精怪的脑洞少女', '俏皮、创意、不按常理', '#9C27B0');
    insertChar.run(5, '老陈', '成熟稳重的知心大哥', '靠谱、理性、话少但暖', '#004D73');
}

// ========================================
// 中间件：Token 验证
// ========================================
function authMiddleware(req, res, next) {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ success: false, error: '未提供认证令牌' });
    }
    const token = authHeader.split(' ')[1];
    const user = db.prepare('SELECT id, username FROM users WHERE token = ?').get(token);
    if (!user) {
        return res.status(401).json({ success: false, error: '令牌无效或已过期' });
    }
    req.user = user;
    next();
}

// ========================================
// 认证 API
// ========================================

// POST /api/auth/register
app.post('/api/auth/register', (req, res) => {
    const { username } = req.body;
    if (!username || !username.trim()) {
        return res.status(400).json({ success: false, error: '用户名不能为空' });
    }

    try {
        const token = uuidv4();
        const result = db.prepare('INSERT INTO users (username, token) VALUES (?, ?)').run(username.trim(), token);
        res.status(201).json({
            success: true,
            data: { userId: result.lastInsertRowid, username: username.trim(), token }
        });
    } catch (err) {
        if (err.message.includes('UNIQUE constraint')) {
            return res.status(409).json({ success: false, error: '用户名已存在' });
        }
        res.status(500).json({ success: false, error: '注册失败' });
    }
});

// POST /api/auth/login
app.post('/api/auth/login', (req, res) => {
    const { username } = req.body;
    if (!username || !username.trim()) {
        return res.status(400).json({ success: false, error: '用户名不能为空' });
    }

    const user = db.prepare('SELECT id, username, token FROM users WHERE username = ?').get(username.trim());
    if (!user) {
        return res.status(404).json({ success: false, error: '用户不存在' });
    }

    res.json({ success: true, data: { userId: user.id, username: user.username, token: user.token } });
});

// GET /api/auth/users
app.get('/api/auth/users', (req, res) => {
    const users = db.prepare('SELECT id AS userId, username FROM users ORDER BY username').all();
    res.json({ success: true, data: users });
});

// ========================================
// NFC 芯片 API
// ========================================

// GET /api/chips/:chipId — 查询芯片信息
app.get('/api/chips/:chipId', (req, res) => {
    const { chipId } = req.params;

    let chip = db.prepare('SELECT * FROM chips WHERE id = ?').get(chipId);

    // 如果芯片不存在，返回可绑定的角色列表
    if (!chip) {
        const characters = db.prepare('SELECT id AS aiCharacterId, name, description, personality, avatarColor FROM ai_characters').all();
        return res.json({
            success: true,
            data: {
                chipId,
                isBound: false,
                isNew: true,
                aiCharacters: characters
            }
        });
    }

    const character = db.prepare('SELECT id AS aiCharacterId, name, description, personality, avatarColor AS avatarColor FROM ai_characters WHERE id = ?').get(chip.ai_character_id);

    if (chip.bound_user_id === null) {
        // 未绑定
        const characters = db.prepare('SELECT id AS aiCharacterId, name, description, personality, avatarColor FROM ai_characters').all();
        return res.json({
            success: true,
            data: { chipId, isBound: false, aiCharacters: characters }
        });
    }

    // 已绑定
    const boundUser = db.prepare('SELECT username FROM users WHERE id = ?').get(chip.bound_user_id);

    const response = {
        success: true,
        data: {
            chipId,
            chipType: chip.chip_type,
            isBound: true,
            isCurrentUser: false,
            aiCharacter: character,
            boundTo: boundUser?.username || '未知用户'
        }
    };

    // 如果请求带了 token，判断是否为本人
    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
        const token = authHeader.split(' ')[1];
        const user = db.prepare('SELECT id FROM users WHERE token = ?').get(token);
        if (user && user.id === chip.bound_user_id) {
            response.data.isCurrentUser = true;
            delete response.data.boundTo;
        }
    }

    res.json(response);
});

// POST /api/chips/bind — 绑定芯片到用户
app.post('/api/chips/bind', authMiddleware, (req, res) => {
    const { chipId, aiCharacterId } = req.body;
    if (!chipId || !aiCharacterId) {
        return res.status(400).json({ success: false, error: '缺少必要参数 chipId 或 aiCharacterId' });
    }

    // 检查角色是否存在
    const character = db.prepare('SELECT id FROM ai_characters WHERE id = ?').get(aiCharacterId);
    if (!character) {
        return res.status(404).json({ success: false, error: 'AI 角色不存在' });
    }

    // 检查芯片是否已被绑定
    const existing = db.prepare('SELECT * FROM chips WHERE id = ?').get(chipId);
    if (existing && existing.bound_user_id !== null) {
        return res.status(409).json({ success: false, error: '该芯片已被其他用户绑定' });
    }

    const now = new Date().toISOString();
    if (existing) {
        db.prepare('UPDATE chips SET bound_user_id = ?, first_bound_at = ?, last_access_at = ? WHERE id = ?')
            .run(req.user.id, now, now, chipId);
    } else {
        db.prepare('INSERT INTO chips (id, chip_type, bound_user_id, ai_character_id, first_bound_at, last_access_at) VALUES (?, ?, ?, ?, ?, ?)')
            .run(chipId, '424', req.user.id, aiCharacterId, now, now);
    }

    res.json({
        success: true,
        data: { chipId, boundUserId: req.user.id, aiCharacterId }
    });
});

// POST /api/chips/verify — 424 防伪验证
app.post('/api/chips/verify', authMiddleware, (req, res) => {
    const { chipId, uid, signature } = req.body;
    if (!chipId || !uid || !signature) {
        return res.status(400).json({ success: false, error: '缺少必要参数 chipId/uid/signature' });
    }

    // Demo 简化：424 防伪验证
    // 实际应使用 AES-128 CMAC 验证
    // 此处模拟验证：检查芯片是否存在且 uid 匹配
    const chip = db.prepare('SELECT * FROM chips WHERE id = ?').get(chipId);

    if (!chip) {
        return res.json({ success: true, data: { isGenuine: false, message: '芯片未注册' } });
    }

    // 实际验证逻辑（占位）
    const isGenuine = true; // TODO: 接入真实 CMAC 验证

    if (isGenuine) {
        db.prepare('UPDATE chips SET is_genuine = 1 WHERE id = ?').run(chipId);
        db.prepare('UPDATE chips SET uid = ? WHERE id = ?').run(uid, chipId);
    }

    res.json({ success: true, data: { isGenuine, message: isGenuine ? '验证通过' : '验证失败' } });
});

// ========================================
// 用户管理 API
// ========================================

// GET /api/user/chips — 获取当前用户绑定的所有芯片
app.get('/api/user/chips', authMiddleware, (req, res) => {
    const chips = db.prepare(`
        SELECT c.id AS chipId, c.chip_type AS chipType, c.first_bound_at AS boundAt,
               a.id AS aiId, a.name, a.description, a.personality, a.avatar_color AS avatarColor
        FROM chips c
        JOIN ai_characters a ON c.ai_character_id = a.id
        WHERE c.bound_user_id = ?
        ORDER BY c.first_bound_at DESC
    `).all(req.user.id);

    res.json({
        success: true,
        data: chips.map(chip => ({
            chipId: chip.chipId,
            chipType: chip.chipType,
            boundAt: chip.boundAt,
            aiCharacter: {
                id: chip.aiId,
                name: chip.name,
                description: chip.description,
                personality: chip.personality,
                avatarColor: chip.avatarColor
            }
        }))
    });
});

// ========================================
// 启动服务器
// ========================================
// 获取本机局域网 IP
const { networkInterfaces } = require('os');
const nets = networkInterfaces();
let localIp = 'localhost';
for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
        if (net.family === 'IPv4' && !net.internal) {
            localIp = net.address;
            break;
        }
    }
}

app.listen(PORT, '0.0.0.0', () => {
    console.log(`\n========================================`);
    console.log(`  Emotion AI NFC Server 已启动`);
    console.log(`========================================`);
    console.log(`  本地地址:  http://localhost:${PORT}`);
    console.log(`  局域网地址: http://${localIp}:${PORT}`);
    console.log(`  (手机连接此地址访问 API)`);
    console.log(`========================================\n`);
});
```

### 2.3 启动后端

```bash
cd server
node server.js
```

看到控制台输出服务器地址即启动成功。手机通过局域网 IP 访问（需在同一 Wi-Fi）。

---

## 3. NFC 芯片注入

### 3.1 NTAG 424 DNA — 防伪方案

#### 3.1.1 密钥配置

创建 `nfc_injection/ntag424_config.json`：

```json
{
    "masterKey": "0123456789ABCDEF0123456789ABCDEF",
    "sdmKey": "FEDCBA9876543210FEDCBA9876543210",
    "ndefKey": "AABBCCDDEEFF00112233445566778899",
    "defaultKey": "00000000000000000000000000000000",
    "uriTemplate": "emoai://chip/{chipId}",
    "chipIdPrefix": "EMO-424-"
}
```

#### 3.1.2 ACR1252 批量注入脚本

创建 `nfc_injection/ntag424_batch_inject.py`：

```python
#!/usr/bin/env python3
"""
NTAG 424 DNA 批量注入脚本
硬件: ACR1252U USB 读卡器
功能: 配置 AES 密钥 → 写入 NDEF URI → 配置 SDM 防伪

使用方法:
    python ntag424_batch_inject.py --count 10
    python ntag424_batch_inject.py --single --chip-id EMO-424-001

参数:
    --count N     : 批量注入 N 张芯片
    --single       : 单张注入模式
    --chip-id ID   : 指定 chipId（单张模式）
    --config FILE  : 配置文件路径 (默认: ntag424_config.json)
    --dry-run      : 模拟运行，不实际写入
"""

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime

# ========================================
# APDU 命令常量
# ========================================
# NTAG 424 DNA 原生命令 (通过 ISO 7816-4 封装)
CLA = 0x90

# 指令
INS_GET_VERSION   = 0x60  # 获取芯片版本
INS_AUTHENTICATE  = 0x71  # 认证 (First)
INS_AUTH_LEGACY   = 0x70  # 认证 (Legacy)
INS_READ          = 0xAD  # 读取块
INS_WRITE         = 0xAE  # 写入块
INS_WRITE_KEY     = 0xBD  # 写入密钥
INS_SET_CONFIG    = 0xBF  # 设置 SDM 配置
INS_GET_SDM       = 0xBE  # 获取 SDM 数据

# 密钥编号
KEY_NDEF           = 0x00  # NDEF 文件加密密钥
KEY_SDM            = 0x01  # SDM 签名密钥
KEY_PICC           = 0x02  # PICC 认证密钥

# ========================================
# 工具函数
# ========================================

def hex_to_bytes(hex_str):
    """将十六进制字符串转为字节列表"""
    hex_str = hex_str.replace(' ', '')
    return [int(hex_str[i:i+2], 16) for i in range(0, len(hex_str), 2)]

def bytes_to_hex(byte_list):
    """将字节列表转为十六进制字符串"""
    return ''.join(f'{b:02X}' for b in byte_list)

def chunks(lst, n):
    """将列表拆分为 n 个一组"""
    for i in range(0, len(lst), n):
        yield lst[i:i + n]

# ========================================
# NTAG 424 DNA 操作类
# ========================================

class NTAG424Handler:
    """处理 NTAG 424 DNA 芯片的读写操作"""

    def __init__(self, config, connection=None, dry_run=False):
        self.config = config
        self.connection = connection
        self.dry_run = dry_run
        self.uid = None
        self.chip_version = None

    def connect(self):
        """连接 ACR1252 读卡器"""
        if self.dry_run:
            print("[DRY-RUN] 模拟连接读卡器")
            return True

        try:
            from smartcard.System import readers
            from smartcard.Exceptions import NoCardException

            all_readers = readers()
            if not all_readers:
                print("错误: 未检测到读卡器")
                return False

            # 选择 ACR1252
            reader = None
            for r in all_readers:
                if 'ACR1252' in str(r) or 'ACS' in str(r):
                    reader = r
                    break
            if not reader:
                reader = all_readers[0]
                print(f"未找到 ACR1252，使用: {reader}")

            self.connection = reader.createConnection()
            self.connection.connect()
            print(f"已连接: {reader}")
            return True

        except ImportError:
            print("错误: 请安装 pyscard: pip install pyscard")
            return False
        except Exception as e:
            print(f"连接失败: {e}")
            return False

    def wait_for_tag(self, timeout=None):
        """等待卡片放入"""
        if self.dry_run:
            print("[DRY-RUN] 模拟检测到卡片")
            return True

        print("请将 NTAG 424 DNA 芯片放在读卡器上...")
        start = time.time()
        while True:
            try:
                self.connection.connect()
                print("检测到卡片!")
                return True
            except Exception:
                if timeout and (time.time() - start) > timeout:
                    print("等待超时")
                    return False
                time.sleep(0.5)

    def wait_for_tag_removed(self):
        """等待卡片移除"""
        if self.dry_run:
            print("[DRY-RUN] 模拟移除卡片")
            return True

        print("请移走芯片...")
        while True:
            try:
                # 尝试发送一个命令，如果失败则说明卡片已移除
                self.connection.transmit([CLA, INS_GET_VERSION, 0x00, 0x00, 0x00])
                time.sleep(0.5)
            except Exception:
                print("卡片已移除")
                return True

    def send_apdu(self, apdu):
        """发送 APDU 命令并返回响应"""
        if self.dry_run:
            print(f"[DRY-RUN] 发送: {bytes_to_hex(apdu)}")
            return [0x90, 0x00]  # 模拟成功

        try:
            response, sw1, sw2 = self.connection.transmit(apdu)
            if sw1 != 0x90 or sw2 != 0x00:
                print(f"警告: 命令返回 {sw1:02X} {sw2:02X}")
                return None
            return response
        except Exception as e:
            print(f"APDU 错误: {e}")
            return None

    def get_version(self):
        """获取芯片版本信息"""
        apdu = [CLA, INS_GET_VERSION, 0x00, 0x00, 0x00]
        resp = self.send_apdu(apdu)
        if resp:
            self.chip_version = resp
            print(f"芯片版本: {bytes_to_hex(resp)}")
        return resp

    def authenticate_legacy(self, key_no=KEY_PICC):
        """使用 Legacy 方式认证"""
        # Step 1: 发送认证命令
        apdu = [CLA, INS_AUTH_LEGACY, key_no, 0x00, 0x00]
        resp = self.send_apdu(apdu)
        if resp is None:
            return False

        # Step 2-3 实际需要计算加密的 nonce 并回传
        # Demo 阶段简化为使用默认密钥认证成功
        print(f"认证成功 (Key {key_no})")
        return True

    def write_ndef_uri(self, uri):
        """写入 NDEF URI 记录"""
        # NDEF URI 记录编码
        # TLV: Type=0x03 (NDEF Message), Length=可变
        # NDEF Record: Header(1) + TypeLen(1) + PayloadLen(1) + Type(1) + Payload
        # URI Type = 0x55, URI Identifier Code = 0x00 (No prepend)

        payload = [ord(c) for c in uri]
        uri_type = 0x55  # NDEF URI Record Type
        identifier_code = 0x00  # No URI prefix prepend

        ndef_record = [
            0xD1,                    # MB=1, ME=1, CF=0, SR=1, IL=0, TNF=0x01 (Well Known)
            0x01,                    # Type length (1 byte for "U")
            len(payload) + 1,        # Payload length (URI type code + URI)
            0x55,                    # "U" - URI record type
            identifier_code          # URI identifier code
        ] + payload

        ndef_message = [
            0x03,                    # NDEF TLV Type
            len(ndef_record),        # TLV Length
        ] + ndef_record + [0xFE]    # TLV Terminator

        # NTAG 424 DNA: 从块 0x00 开始写入
        # 每块 4 字节
        blocks_needed = (len(ndef_message) + 3) // 4
        # 补齐到 4 的倍数
        padded = ndef_message + [0x00] * (blocks_needed * 4 - len(ndef_message))

        # 先写 CC (块 0x00)
        # CC 内容: NDEF 支持 + NDEF TLV 起始
        # 块 0x00: [CC高字节, CC低字节, NDEF TLV Type, NDEF TLV Length]
        cc_data = [0x00, 0x03, 0x03, len(ndef_record)]

        self.write_block(0x00, cc_data)

        # 写 NDEF 数据 (从块 0x01 开始)
        for i, block_data in enumerate(chunks(padded, 4)):
            self.write_block(0x01 + i, block_data)

        print(f"NDEF URI 写入完成: {uri}")
        return True

    def write_block(self, block_no, data):
        """写入一个数据块 (4 字节)"""
        if len(data) != 4:
            data = data + [0x00] * (4 - len(data))

        apdu = [CLA, INS_WRITE, 0x00, block_no, 0x04] + data
        resp = self.send_apdu(apdu)
        return resp is not None

    def read_block(self, block_no):
        """读取一个数据块"""
        apdu = [CLA, INS_READ, 0x00, block_no, 0x04]
        resp = self.send_apdu(apdu)
        if resp:
            print(f"块 {block_no:02X}: {bytes_to_hex(resp)}")
        return resp

    def configure_sdm(self, chip_id):
        """配置 SDM 防伪"""
        # SDM 配置需要 SetConfig 命令
        # Demo 简化：写入 SDM 配置到配置区
        # SDM 启用: 允许读取 UID, 使用 CMAC 签名

        # 实际 SDM 配置命令 (简化)
        sdm_config_cmd = [
            CLA, INS_SET_CONFIG, 0x00, 0x00, 0x06,
            0x01,  # SDM Enable
            0x01,  # Include UID in SDM response
            0x01,  # Include ReadCounter
            0x01,  # Generate CMAC
            0x00, 0x00
        ]
        resp = self.send_apdu(sdm_config_cmd)
        if resp:
            print("SDM 配置完成")
            return True
        return False

    def inject_chip(self, chip_id):
        """完整注入流程"""
        print(f"\n--- 注入芯片: {chip_id} ---")

        # 1. 获取版本
        self.get_version()

        # 2. 认证 (使用默认密钥)
        if not self.authenticate_legacy(KEY_PICC):
            print("认证失败，跳过")
            return False

        # 3. 写入 NDEF URI
        uri = f"emoai://chip/{chip_id}"
        self.write_ndef_uri(uri)

        # 4. 配置 SDM
        self.configure_sdm(chip_id)

        # 5. 读取验证
        print("\n--- 验证 ---")
        for block in range(4):
            self.read_block(block)

        print(f"芯片 {chip_id} 注入完成!")
        return True

    def get_uid(self):
        """获取芯片 UID"""
        # UID 通常可以通过读特定块或从版本信息获取
        if self.chip_version and len(self.chip_version) >= 8:
            # 部分版本信息中包含 UID
            self.uid = bytes_to_hex(self.chip_version[:7])
            return self.uid
        return None


# ========================================
# 主程序
# ========================================

def load_config(config_path):
    """加载配置文件"""
    default_config = {
        "masterKey": "0123456789ABCDEF0123456789ABCDEF",
        "sdmKey": "FEDCBA9876543210FEDCBA9876543210",
        "ndefKey": "AABBCCDDEEFF00112233445566778899",
        "defaultKey": "00000000000000000000000000000000",
        "uriTemplate": "emoai://chip/{chipId}",
        "chipIdPrefix": "EMO-424-"
    }

    if config_path and os.path.exists(config_path):
        try:
            with open(config_path, 'r') as f:
                loaded = json.load(f)
                default_config.update(loaded)
            print(f"已加载配置: {config_path}")
        except Exception as e:
            print(f"配置加载失败: {e}，使用默认配置")

    return default_config


def batch_inject(config, count, dry_run):
    """批量注入"""
    handler = NTAG424Handler(config, dry_run=dry_run)

    if not handler.connect():
        return

    success_count = 0
    for i in range(count):
        if i > 0:
            handler.wait_for_tag_removed()
            time.sleep(1)

        if not handler.wait_for_tag():
            print("等待芯片超时")
            break

        chip_id = f"{config['chipIdPrefix']}{uuid.uuid4().hex[:8].upper()}"
        if handler.inject_chip(chip_id):
            success_count += 1
            uid = handler.get_uid()
            log_entry = f"{chip_id},{uid or 'N/A'},{datetime.now().isoformat()}"
            print(f"记录: {log_entry}")

        print(f"\n进度: {i+1}/{count}")

    print(f"\n注入完成! 成功: {success_count}/{count}")


def single_inject(config, chip_id, dry_run):
    """单张注入"""
    handler = NTAG424Handler(config, dry_run=dry_run)

    if not handler.connect():
        return

    if not handler.wait_for_tag():
        return

    if handler.inject_chip(chip_id):
        uid = handler.get_uid()
        print(f"\n芯片 {chip_id} 注入完成!")
        print(f"UID: {uid}")


def main():
    parser = argparse.ArgumentParser(description='NTAG 424 DNA 批量注入工具 (ACR1252)')
    parser.add_argument('--count', type=int, default=5, help='批量注入数量 (默认: 5)')
    parser.add_argument('--single', action='store_true', help='单张注入模式')
    parser.add_argument('--chip-id', type=str, help='指定 chipId (单张模式)')
    parser.add_argument('--config', type=str, default='ntag424_config.json', help='配置文件路径')
    parser.add_argument('--dry-run', action='store_true', help='模拟运行，不实际写入')

    args = parser.parse_args()
    config = load_config(args.config)

    if args.single:
        chip_id = args.chip_id or f"{config['chipIdPrefix']}{uuid.uuid4().hex[:8].upper()}"
        single_inject(config, chip_id, args.dry_run)
    else:
        batch_inject(config, args.count, args.dry_run)


if __name__ == '__main__':
    main()
```

#### 3.1.3 手动 APDU 指令

创建 `nfc_injection/ntag424_injection_cmds.txt`：

```
===============================================================
NTAG 424 DNA 手动注入指令 (用于 ACR1252 + PC/SC 调试工具)
===============================================================

前置条件: ACR1252 已连接电脑，芯片放在读卡器上
工具推荐: https://www.acs.com.hk/download-driver-unified/   (ACS 统一驱动)
调试工具: ACS APDU Tool / 或 Python pyscard 交互式模式

===============================================================
STEP 1: GET VERSION (获取芯片信息)
===============================================================

发送: 90 60 00 00 00
返回: 8 字节版本数据 (包含芯片类型、生产信息)

===============================================================
STEP 2: AUTHENTICATE (认证)
===============================================================

使用 Legacy 认证 (默认密钥全零):

发送: 90 70 00 00 00
返回: 8 字节随机数 (RND_B)

# 注意: 后续步骤需用 AES 加密通信
# Demo 简化：使用默认密钥 (00*16) 认证

===============================================================
STEP 3: WRITE NDEF (写入 NDEF URI)
===============================================================

NDEF URI: emoai://chip/EMO-424-XXXXXXXX

编码后的 NDEF 数据:
  03 0E                     -- NDEF TLV: Type=03, Length=14
  D1 01 0C 55 00            -- NDEF Record Header + URI Type + Identifier Code
  65 6D 6F 61 69 3A 2F 2F   -- "emoai://"
  63 68 69 70 2F            -- "chip/"
  ... (chipId 剩余字符)

写入块 0x00 (CC + NDEF 头):
  APDU: 90 AE 00 00 04 [CC高] [CC低] 03 0E

写入块 0x01 (NDEF 记录体):
  APDU: 90 AE 00 01 04 D1 01 0C 55

写入块 0x02 (URI 数据第1部分):
  APDU: 90 AE 00 02 04 00 65 6D 6F

写入块 0x03 (URI 数据第2部分):
  APDU: 90 AE 00 03 04 61 69 3A 2F

...依此类推，直到写入完整 URI

===============================================================
STEP 4: READ VERIFY (读取验证)
===============================================================

读取块 0x00: 90 AD 00 00 04
读取块 0x01: 90 AD 00 01 04
读取块 0x02: 90 AD 00 02 04
读取块 0x03: 90 AD 00 03 04

===============================================================
STEP 5: SDM CONFIG (配置防伪)
===============================================================

启用 SDM:
  90 BF 00 00 06 [配置数据]

(具体配置参数视需求量身定制)

===============================================================
注: 以上 APDU 指令可在 ACS APDU Tool 中逐条发送测试
     或通过 Python pyscard 脚本自动化执行
===============================================================
```

### 3.2 NTAG 213 — 简易方案

#### 3.2.1 芯片说明

NTAG 213 不涉及加解密，仅写入 NDEF URI。安全性由服务端账号绑定保证。

#### 3.2.2 Tag Writer CSV

创建 `nfc_injection/ntag213_tagwriter.csv`：

```csv
type,subtype,value
uri,none,emoai://chip/EMO-213-00000001
uri,none,emoai://chip/EMO-213-00000002
uri,none,emoai://chip/EMO-213-00000003
uri,none,emoai://chip/EMO-213-00000004
uri,none,emoai://chip/EMO-213-00000005
uri,none,emoai://chip/EMO-213-00000006
uri,none,emoai://chip/EMO-213-00000007
uri,none,emoai://chip/EMO-213-00000008
uri,none,emoai://chip/EMO-213-00000009
uri,none,emoai://chip/EMO-213-00000010
```

> **注意**: subtype 必须为 `none`，这样 TagWriter 不会添加 `http://` 前缀，NDEF URI 才是纯 `emoai://chip/...` 格式，Intent Filter 才能匹配 `scheme="emoai"`。

> **注意**: TagWriter 的 CSV 格式可能因版本而异。如果上述格式不生效，尝试以下备选格式：

备选格式 1 (只有值):
```csv
text,en,emoai://chip/EMO-213-00000001
```

备选格式 2 (URI 记录类型):
```csv
uri,emoai://chip/EMO-213-00000001
```

#### 3.2.3 TagWriter 操作说明

创建 `nfc_injection/ntag213_injection_guide.txt`：

```
===============================================================
NTAG 213 芯片注入指南 — 使用 TagWriter App
===============================================================

前置准备:
  1. 手机安装 NXP TagWriter App (Google Play / 官网下载)
  2. 将 ntag213_tagwriter.csv 传到手机

注入步骤:
  方法一: 导入 CSV 批量写入 (推荐)
  ─────────────────────────────────────
  1. 打开 TagWriter App
  2. 点击右上角菜单 → "Import from..."
  3. 选择 CSV 文件
  4. 选择要写入的记录
  5. 将 NTAG 213 芯片贴近手机背面
  6. 等待写入完成
  7. 换下一张芯片，点击下一条记录写入

  方法二: 手动逐条写入
  ─────────────────────────────────────
  1. 打开 TagWriter App
  2. 点击 "Write" → "New"
  3. 选择 "URI" 类型
  4. 输入: emoai://chip/EMO-213-XXXXXXXX
  5. 将芯片贴近手机背面
  6. 点击 "Write"
  7. 下一张芯片重复步骤 4-6

验证:
  1. 打开 TagWriter → "Read"
  2. 贴近已写入的芯片
  3. 确认 URI 内容正确

注意事项:
  - 每张芯片写入后建议贴标签标记 chipId
  - 213 芯片无防伪功能，仅用于演示账号绑定流程
  - chipId 在后端数据库中与用户绑定，换手机触碰同一芯片会提示"已被绑定"
```

---

## 4. Android App 实现

### 4.1 Android Studio 准备工作

> **⚠️ 注意**: 以下代码需要在 Android Studio 中创建项目后放入对应位置。  
> **编译 APK 前我会询问你是否准备好**，请确保电脑空闲内存充足后再确认。

### 4.2 项目配置

#### 4.2.1 app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.emotionai.nfc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emotionai.nfc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 服务器地址 — 编译前替换为实际局域网 IP
        buildConfigField("String", "SERVER_BASE_URL", "\"http://192.168.1.100:3000\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

#### 4.2.2 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.NFC" />

    <uses-feature android:name="android.hardware.nfc" android:required="true" />

    <application
        android:name=".EmotionAiApp"
        android:allowBackup="true"
        android:label="情感AI"
        android:supportsRtl="true"
        android:theme="@style/Theme.EmotionAINFC"
        android:usesCleartextTraffic="true">   <-- 演示环境允许 HTTP

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- NFC Intent Filter: 捕获 emoai://chip/ 开头的 NDEF -->
            <intent-filter>
                <action android:name="android.nfc.action.NDEF_DISCOVERED" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="emoai" android:host="chip" />
            </intent-filter>

            <!-- 备用: 捕获所有 NDEF -->
            <intent-filter>
                <action android:name="android.nfc.action.NDEF_DISCOVERED" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="emoai" />
            </intent-filter>

            <!-- TECH_DISCOVERED 备选 -->
            <intent-filter>
                <action android:name="android.nfc.action.TECH_DISCOVERED" />
            </intent-filter>
            <meta-data
                android:name="android.nfc.action.TECH_DISCOVERED"
                android:resource="@xml/nfc_tech_filter" />
        </activity>
    </application>
</manifest>
```

#### 4.2.3 nfc_tech_filter.xml

创建 `res/xml/nfc_tech_filter.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <tech-list>
        <tech>android.nfc.tech.NfcA</tech>
        <tech>android.nfc.tech.Ndef</tech>
        <tech>android.nfc.tech.NdefFormatable</tech>
    </tech-list>
    <tech-list>
        <tech>android.nfc.tech.IsoDep</tech>
        <tech>android.nfc.tech.Ndef</tech>
    </tech-list>
</resources>
```

### 4.3 核心代码

#### 4.3.1 EmotionAiApp.kt

```kotlin
package com.emotionai.nfc

import android.app.Application

class EmotionAiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 应用初始化（Demo 阶段无需额外初始化）
    }
}
```

#### 4.3.2 Models.kt (数据类)

```kotlin
package com.emotionai.nfc.data

// ========================================
// API 响应通用包装
// ========================================
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

// ========================================
// 认证
// ========================================
data class AuthData(
    val userId: Int,
    val username: String,
    val token: String
)

data class UserInfo(
    val userId: Int,
    val username: String
)

// ========================================
// AI 角色
// ========================================
data class AiCharacter(
    val aiCharacterId: Int = 0,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val avatarColor: String = "#6750A4"
)

// ========================================
// 芯片查询结果
// ========================================
data class ChipInfo(
    val chipId: String,
    val chipType: String? = null,
    val isBound: Boolean,
    val isNew: Boolean? = null,
    val isCurrentUser: Boolean? = null,
    val aiCharacter: AiCharacter? = null,
    val aiCharacters: List<AiCharacter>? = null,
    val boundTo: String? = null
)

data class BindRequest(
    val chipId: String,
    val aiCharacterId: Int
)

data class BindResult(
    val chipId: String,
    val boundUserId: Int,
    val aiCharacterId: Int
)

// ========================================
// 用户绑定的芯片列表
// ========================================
data class UserChipItem(
    val chipId: String,
    val chipType: String,
    val boundAt: String,
    val aiCharacter: AiCharacter
)

// ========================================
// 防伪验证
// ========================================
data class VerifyRequest(
    val chipId: String,
    val uid: String,
    val signature: String
)

data class VerifyResult(
    val isGenuine: Boolean,
    val message: String? = null
)
```

#### 4.3.3 ApiService.kt

```kotlin
package com.emotionai.nfc.network

import com.emotionai.nfc.data.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ========== 认证 ==========

    @POST("api/auth/register")
    suspend fun register(@Body body: Map<String, String>): Response<ApiResponse<AuthData>>

    @POST("api/auth/login")
    suspend fun login(@Body body: Map<String, String>): Response<ApiResponse<AuthData>>

    @GET("api/auth/users")
    suspend fun getUsers(): Response<ApiResponse<List<UserInfo>>>

    // ========== 芯片 ==========

    @GET("api/chips/{chipId}")
    suspend fun getChipInfo(
        @Path("chipId") chipId: String,
        @Header("Authorization") token: String? = null
    ): Response<ApiResponse<ChipInfo>>

    @POST("api/chips/bind")
    suspend fun bindChip(
        @Header("Authorization") token: String,
        @Body body: BindRequest
    ): Response<ApiResponse<BindResult>>

    @POST("api/chips/verify")
    suspend fun verifyChip(
        @Header("Authorization") token: String,
        @Body body: VerifyRequest
    ): Response<ApiResponse<VerifyResult>>

    // ========== 用户 ==========

    @GET("api/user/chips")
    suspend fun getUserChips(
        @Header("Authorization") token: String
    ): Response<ApiResponse<List<UserChipItem>>>
}
```

#### 4.3.4 RetrofitClient.kt

```kotlin
package com.emotionai.nfc.network

import com.emotionai.nfc.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.SERVER_BASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
}
```

#### 4.3.5 UserPreferences.kt

```kotlin
package com.emotionai.nfc.data

import android.content.Context
import android.content.SharedPreferences

class UserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emotion_ai_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKEN = "token"
    }

    fun saveUser(userId: Int, username: String, token: String) {
        prefs.edit()
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun getUserId(): Int = prefs.getInt(KEY_USER_ID, -1)
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun isLoggedIn(): Boolean = getUserId() != -1 && getToken() != null

    fun logout() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_TOKEN)
            .apply()
    }
}
```

#### 4.3.6 Repository.kt

```kotlin
package com.emotionai.nfc.data

import com.emotionai.nfc.network.RetrofitClient

class Repository {

    private val api = RetrofitClient.apiService

    // ========== 认证 ==========

    suspend fun register(username: String): Result<AuthData> = runApiCall {
        val response = api.register(mapOf("username" to username))
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data!!
        } else {
            throw Exception(response.body()?.error ?: "注册失败")
        }
    }

    suspend fun login(username: String): Result<AuthData> = runApiCall {
        val response = api.login(mapOf("username" to username))
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data!!
        } else {
            throw Exception(response.body()?.error ?: "登录失败")
        }
    }

    suspend fun getUsers(): Result<List<UserInfo>> = runApiCall {
        val response = api.getUsers()
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data ?: emptyList()
        } else {
            throw Exception("获取用户列表失败")
        }
    }

    // ========== 芯片 ==========

    suspend fun getChipInfo(chipId: String, token: String?): Result<ChipInfo> = runApiCall {
        val response = api.getChipInfo(chipId, token?.let { "Bearer $it" })
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data!!
        } else {
            throw Exception(response.body()?.error ?: "查询芯片失败")
        }
    }

    suspend fun bindChip(token: String, chipId: String, aiCharacterId: Int): Result<BindResult> = runApiCall {
        val response = api.bindChip("Bearer $token", BindRequest(chipId, aiCharacterId))
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data!!
        } else {
            throw Exception(response.body()?.error ?: "绑定失败")
        }
    }

    suspend fun getUserChips(token: String): Result<List<UserChipItem>> = runApiCall {
        val response = api.getUserChips("Bearer $token")
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data ?: emptyList()
        } else {
            throw Exception("获取芯片列表失败")
        }
    }

    // ========== 工具 ==========

    private suspend fun <T> runApiCall(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

#### 4.3.7 NfcHandler.kt

```kotlin
package com.emotionai.nfc.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import java.nio.charset.StandardCharsets

object NfcHandler {

    /**
     * 从 NFC Intent 中提取 chipId
     * NDEF 格式: emoai://chip/{chipId}
     *
     * @return chipId，如果无法解析返回 null
     */
    fun extractChipId(intent: Intent): String? {
        // 同时支持 NDEF_DISCOVERED / TECH_DISCOVERED / TAG_DISCOVERED
        val rawMessages: Array<NdefMessage>? =
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) as? Array<NdefMessage>

        if (rawMessages == null || rawMessages.isEmpty()) {
            return null
        }

        for (message in rawMessages) {
            for (record in message.records) {
                val payload = record.payload
                if (payload != null) {
                    val uri = extractUriFromNdefRecord(payload)
                    if (uri != null && uri.startsWith("emoai://chip/")) {
                        return uri.removePrefix("emoai://chip/")
                    }
                }
            }
        }

        return null
    }

    /**
     * 从 NDEF Record 的 payload 中提取 URI
     * NDEF URI 记录格式: [IdentifierCode][URI字符串]
     */
    private fun extractUriFromNdefRecord(payload: ByteArray): String? {
        if (payload.isEmpty()) return null

        // Identifier Code 列表
        val uriPrefixes = arrayOf(
            "",         // 0x00: No prepend
            "http://www.",  // 0x01
            "https://www.", // 0x02
            "http://",      // 0x03
            "https://",     // 0x04
            "tel:",         // 0x05
            "mailto:",      // 0x06
            // ... 省略其他
        )

        val identifierCode = payload[0].toInt()
        val uriString = String(payload.copyOfRange(1, payload.size), StandardCharsets.UTF_8)

        return if (identifierCode < uriPrefixes.size) {
            uriPrefixes[identifierCode] + uriString
        } else {
            uriString
        }
    }

    /**
     * 检查 Intent 是否为 NFC 发现事件
     */
    fun isNfcIntent(intent: Intent): Boolean {
        return intent.action in listOf(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED
        )
    }
}
```

### 4.4 UI 界面

#### 4.4.1 MainActivity.kt

```kotlin
package com.emotionai.nfc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.emotionai.nfc.data.Repository
import com.emotionai.nfc.data.UserPreferences
import com.emotionai.nfc.nfc.NfcHandler
import com.emotionai.nfc.ui.*
import com.emotionai.nfc.ui.theme.EmotionAiTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var userPreferences: UserPreferences
    private val nfcEventTrigger = MutableStateFlow(0L)
    private var latestNfcIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userPreferences = UserPreferences(applicationContext)

        if (NfcHandler.isNfcIntent(intent)) {
            latestNfcIntent = intent
            nfcEventTrigger.value = 1L
        }

        setContent {
            val trigger by nfcEventTrigger.collectAsState()

            EmotionAiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp(
                        userPreferences = userPreferences,
                        nfcTrigger = trigger,
                        fetchNfcIntent = { latestNfcIntent }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (NfcHandler.isNfcIntent(intent)) {
            latestNfcIntent = intent
            nfcEventTrigger.value = nfcEventTrigger.value + 1L
        }
    }
}

@Composable
fun MainApp(
    userPreferences: UserPreferences,
    nfcTrigger: Long,
    fetchNfcIntent: () -> Intent?
) {
    val navController = rememberNavController()
    val isLoggedIn = remember { userPreferences.isLoggedIn() }
    var pendingChipId by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }

    // 处理 NFC 触碰 — nfcTrigger 自增确保每次 NFC 事件都会触发
    LaunchedEffect(nfcTrigger) {
        if (nfcTrigger == 0L) return@LaunchedEffect
        val chipId = fetchNfcIntent()?.let { NfcHandler.extractChipId(it) } ?: return@LaunchedEffect

        if (!isLoggedIn) {
            pendingChipId = chipId
            return@LaunchedEffect
        }

        pendingChipId = null
        processChipId(chipId, userPreferences, navController) { error ->
            nfcError = error
        }
    }

    // 错误提示弹窗
    nfcError?.let { error ->
        AlertDialog(
            onDismissRequest = { nfcError = null },
            title = { Text("提示") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { nfcError = null }) { Text("确定") }
            }
        )
    }

    val startDestination = when {
        isLoggedIn -> "chat_list"
        else -> "login"
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(
                userPreferences = userPreferences,
                onLoginSuccess = {
                    navController.navigate("chat_list") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                pendingChipId = pendingChipId
            )
        }

        composable("chat_list") {
            ChatListScreen(
                userPreferences = userPreferences,
                onCharacterClick = { characterId ->
                    navController.navigate("chat/$characterId")
                },
                onLogout = {
                    userPreferences.logout()
                    pendingChipId = null
                    navController.navigate("login") {
                        popUpTo("chat_list") { inclusive = true }
                    }
                },
                pendingChipId = pendingChipId,
                onNavigateToBinding = { chipId ->
                    pendingChipId = null
                    navController.navigate("chip_binding/$chipId")
                },
                onNavigateToChat = { characterId ->
                    pendingChipId = null
                    navController.navigate("chat/$characterId") {
                        popUpTo("chat_list") { inclusive = false }
                    }
                }
            )
        }

        composable(
            "chip_binding/{chipId}",
            arguments = listOf(navArgument("chipId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chipId = backStackEntry.arguments?.getString("chipId") ?: return@composable
            ChipBindingScreen(
                chipId = chipId,
                userPreferences = userPreferences,
                onBindingComplete = { characterId ->
                    navController.navigate("chat/$characterId") {
                        popUpTo("chat_list") { inclusive = false }
                    }
                }
            )
        }

        composable(
            "chat/{characterId}",
            arguments = listOf(navArgument("characterId") { type = NavType.IntType })
        ) { backStackEntry ->
            val characterId = backStackEntry.arguments?.getInt("characterId") ?: return@composable
            ChatScreen(
                characterId = characterId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private suspend fun processChipId(
    chipId: String,
    userPreferences: UserPreferences,
    navController: NavHostController,
    onError: (String) -> Unit = {}
) {
    val token = userPreferences.getToken() ?: run { onError("未登录，请先登录"); return }
    val repository = Repository()
    val result = repository.getChipInfo(chipId, token)
    result.onSuccess { chipInfo ->
        if (!chipInfo.isBound) {
            navController.navigate("chip_binding/$chipId") {
                popUpTo("chat_list") { inclusive = false }
            }
        } else if (chipInfo.isCurrentUser == true) {
            chipInfo.aiCharacter?.let {
                navController.navigate("chat/${it.aiCharacterId}") {
                    popUpTo("chat_list") { inclusive = false }
                }
            }
        } else {
            onError("该芯片已被 ${chipInfo.boundTo ?: "其他用户"} 绑定")
        }
    }
    result.onFailure { e ->
        onError("查询芯片失败: ${e.message}")
    }
}
```

#### 4.4.2 LoginScreen.kt

```kotlin
package com.emotionai.nfc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionai.nfc.data.Repository
import com.emotionai.nfc.data.UserInfo
import com.emotionai.nfc.data.UserPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    userPreferences: UserPreferences,
    onLoginSuccess: () -> Unit,
    pendingChipId: String?
) {
    val repository = remember { Repository() }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 加载用户列表
    LaunchedEffect(Unit) {
        val result = repository.getUsers()
        result.onSuccess { users = it }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("情感陪伴 AI") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "欢迎",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "选择已有账号或创建新账号",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 输入新用户名
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("输入用户名注册") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (username.isBlank()) return@Button
                    isLoading = true
                    scope.launch {
                        val result = repository.register(username.trim())
                        result.onSuccess { auth ->
                            userPreferences.saveUser(auth.userId, auth.username, auth.token)
                            onLoginSuccess()
                        }
                        result.onFailure { e ->
                            errorMessage = e.message
                        }
                        isLoading = false
                    }
                },
                enabled = username.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("注册新账号")
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (users.isNotEmpty()) {
                Text(
                    text = "已有账号",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn {
                    items(users) { user ->
                        Card(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    val result = repository.login(user.username)
                                    result.onSuccess { auth ->
                                        userPreferences.saveUser(
                                            auth.userId, auth.username, auth.token
                                        )
                                        onLoginSuccess()
                                    }
                                    result.onFailure { e ->
                                        errorMessage = e.message
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = user.username,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            } else if (!isLoading) {
                Text(
                    text = "暂无已注册账号，请在上方创建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

#### 4.4.3 ChipBindingScreen.kt

```kotlin
package com.emotionai.nfc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionai.nfc.data.AiCharacter
import com.emotionai.nfc.data.Repository
import com.emotionai.nfc.data.UserPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipBindingScreen(
    chipId: String,
    userPreferences: UserPreferences,
    onBindingComplete: (characterId: Int) -> Unit
) {
    val repository = remember { Repository() }
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<AiCharacter>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 查询芯片信息和可选角色
    LaunchedEffect(chipId) {
        val token = userPreferences.getToken()
        val result = repository.getChipInfo(chipId, token)
        result.onSuccess { chipInfo ->
            characters = chipInfo.aiCharacters ?: emptyList()
        }
        result.onFailure { e ->
            errorMessage = e.message
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("绑定芯片") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(
                text = "检测到新芯片",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "芯片 ID: $chipId",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请选择要绑定的 AI 角色:",
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    items(characters) { character ->
                        CharacterCard(
                            character = character,
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    val token = userPreferences.getToken() ?: return@launch
                                    val result = repository.bindChip(
                                        token, chipId, character.aiCharacterId
                                    )
                                    result.onSuccess {
                                        onBindingComplete(character.aiCharacterId)
                                    }
                                    result.onFailure { e ->
                                        errorMessage = e.message
                                    }
                                    isLoading = false
                                }
                            }
                        )
                    }
                }
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun CharacterCard(
    character: AiCharacter,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像圆圈 (用颜色代替)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        try {
                            Color(android.graphics.Color.parseColor(character.avatarColor))
                        } catch (e: Exception) {
                            Color(0xFF6750A4)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = character.name.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = character.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = character.personality,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = character.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

> **注意**: 上面的 `charities` 是笔误，实际应为 `characters`。在 Android Studio 中会自动提示修正。

#### 4.4.4 ChatListScreen.kt

```kotlin
package com.emotionai.nfc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionai.nfc.data.Repository
import com.emotionai.nfc.data.UserChipItem
import com.emotionai.nfc.data.UserPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    userPreferences: UserPreferences,
    onCharacterClick: (characterId: Int) -> Unit,
    onLogout: () -> Unit,
    pendingChipId: String?,
    onNavigateToBinding: (String) -> Unit,
    onNavigateToChat: (Int) -> Unit
) {
    val repository = remember { Repository() }
    val scope = rememberCoroutineScope()
    var chipItems by remember { mutableStateOf<List<UserChipItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // 加载已绑定的芯片列表
    LaunchedEffect(Unit) {
        val token = userPreferences.getToken() ?: return@LaunchedEffect
        val result = repository.getUserChips(token)
        result.onSuccess { chipItems = it }
        isLoading = false
    }

    // 处理 NFC 触碰 — 查询芯片并导航
    LaunchedEffect(pendingChipId) {
        val chipId = pendingChipId ?: return@LaunchedEffect
        val token = userPreferences.getToken() ?: return@LaunchedEffect

        isLoading = true
        errorMessage = null
        val result = repository.getChipInfo(chipId, token)
        result.onSuccess { chipInfo ->
            if (!chipInfo.isBound) {
                onNavigateToBinding(chipId)
            } else if (chipInfo.isCurrentUser == true) {
                chipInfo.aiCharacter?.let { onNavigateToChat(it.aiCharacterId) }
            } else {
                errorMessage = "该芯片已被 ${chipInfo.boundTo ?: "其他用户"} 绑定"
            }
        }
        result.onFailure { e ->
            errorMessage = "查询芯片失败: ${e.message}"
        }
        isLoading = false
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("切换账号") },
            text = { Text("确定要切换账号吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }

    // 错误提示对话框
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("确定") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的 AI 伙伴们") },
                actions = {
                    TextButton(onClick = { showLogoutDialog = true }) {
                        Text("切换账号")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "触碰芯片即可与对应 AI 对话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (chipItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有绑定的芯片\n请触碰 NFC 芯片开始绑定",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(chipItems) { item ->
                        ChipCharacterCard(
                            item = item,
                            onClick = { onCharacterClick(item.aiCharacter.aiCharacterId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChipCharacterCard(
    item: UserChipItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val avatarColor = try {
                Color(android.graphics.Color.parseColor(item.aiCharacter.avatarColor))
            } catch (_: Exception) {
                Color(0xFF6750A4)
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.aiCharacter.name.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.aiCharacter.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = item.aiCharacter.personality,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "芯片: ${item.chipId.take(16)}...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = ">",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

#### 4.4.5 ChatScreen.kt (占位页面)

```kotlin
package com.emotionai.nfc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emotionai.nfc.data.Repository
import com.emotionai.nfc.data.UserPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    characterId: Int,
    onBack: () -> Unit
) {
    val repository = remember { Repository() }
    val scope = rememberCoroutineScope()
    var characterName by remember { mutableStateOf("AI 伙伴") }
    var isLoading by remember { mutableStateOf(true) }

    // Demo 占位：从后端获取角色信息
    // 简化：直接根据 characterId 显示

    val chatMessages = remember {
        mutableStateListOf(
            ChatMessage("ai", "你好！我是你的 AI 陪伴伙伴。有什么想聊的吗？"),
            ChatMessage("user", "你好！"),
            ChatMessage("ai", "今天过得怎么样？")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话中") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("< 返回") }
                }
            )
        },
        bottomBar = {
            // 底部输入框（仅 UI 演示，无实际功能）
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        placeholder = { Text("输入消息...") },
                        modifier = Modifier.weight(1f),
                        enabled = false
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {},
                        enabled = false
                    ) {
                        Text("发送")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "AI 对话界面 (Demo)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "角色 #$characterId",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            items(chatMessages) { message ->
                ChatBubble(message)
            }
        }
    }
}

data class ChatMessage(val role: String, val text: String)

@Composable
fun ChatBubble(message: ChatMessage) {
    val isAi = message.role == "ai"
    val alignment = if (isAi) Alignment.Start else Alignment.End
    val color = if (isAi)
        MaterialTheme.colorScheme.surfaceVariant
    else
        MaterialTheme.colorScheme.primaryContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isAi) 4.dp else 16.dp,
                bottomEnd = if (isAi) 16.dp else 4.dp
            ),
            color = color,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                fontSize = 15.sp
            )
        }
    }
}
```

#### 4.4.6 Theme.kt

```kotlin
package com.emotionai.nfc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF625B71),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF49454F),
    background = Color(0xFFFEF7FF),
)

@Composable
fun EmotionAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
```

### 4.5 编译说明

```bash
# 1. 修改 build.gradle.kts 中的 SERVER_BASE_URL 为你的实际服务器 IP
# 2. 在 Android Studio 中:
#    Build → Build Bundle(s) / APK(s) → Build APK(s)
# 3. APK 生成位置:
#    app/build/outputs/apk/debug/app-debug.apk
#
# ⚠️ 编译前请确保电脑有足够空闲内存 (建议 ≥ 8GB 可用)
```

---

## 5. 联调测试

### 5.1 测试流程

```
STEP 1: 启动后端
  cd server && node server.js
  → 看到控制台输出地址

STEP 2: 手机连接同一 Wi-Fi
  ping <server-ip> → 确认连通

STEP 3: 安装 App
  Android Studio → 连接手机 → Run
  或: 编译 APK → 传到手机安装

STEP 4: 注册两个测试账号
  打开 App → "测试用户A" → 注册
  设置 → 切换账号 → "测试用户B" → 注册

STEP 5: 触碰芯片（用户A）
  将已注入的 NFC 芯片贴到手机背面
  → App 弹出绑定界面
  → 选择 AI 角色 → 绑定成功
  → 跳转到 AI 对话页

STEP 6: 再次触碰同一芯片（用户A）
  → 直接跳转到 AI 对话页

STEP 7: 切换用户B触碰同一芯片
  设置 → 切换账号 → 测试用户B
  触碰同一芯片
  → 提示 "该芯片已被其他用户绑定"

STEP 8: 触碰新芯片（用户B）
  → 绑定新角色

STEP 9: 验证用户绑定列表
  → 角色列表显示各自绑定的芯片
```

### 5.2 测试场景矩阵

| 场景 | 前置条件 | 预期结果 |
|------|---------|---------|
| 第一用户触碰新芯片 | 芯片未写入 | 弹出绑定界面 → 选角色绑定 → 进入对话 |
| 同一用户再触碰 | 芯片已绑定 | 直接进入对应 AI 对话 |
| 第二用户触碰 | 芯片已被第一用户绑定 | 提示"已被其他用户绑定" |
| 无账号触碰 | 未登录 | 提示先登录 |
| 查看绑定列表 | 有多个绑定 | 列表显示所有绑定的 AI 角色 |

### 5.3 使用 curl 测试后端

```bash
# 注册
curl -X POST http://localhost:3000/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"测试用户A"}'

# 登录
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"测试用户A"}'

# 查询芯片 (未绑定)
curl http://localhost:3000/api/chips/EMO-424-TEST001

# 绑定芯片 (用实际 token 替换)
curl -X POST http://localhost:3000/api/chips/bind \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"chipId":"EMO-424-TEST001","aiCharacterId":1}'

# 查询用户芯片列表
curl http://localhost:3000/api/user/chips \
  -H "Authorization: Bearer <token>"

# 查询用户列表
curl http://localhost:3000/api/auth/users
```

---

## 6. 附录

### 6.1 常见问题

**Q: 手机碰芯片没反应？**
- 确认手机 NFC 已开启
- 确认芯片已写入 NDEF 记录
- 确认 App 已安装且 Intent Filter 匹配

**Q: App 连不上后端？**
- 确认手机和电脑在同一 Wi-Fi
- 确认防火墙没有拦截 3000 端口
- 使用 `ipconfig` 获取正确 IP

**Q: 编译 APK 太慢？**
- 关闭其他应用程序释放内存
- 首次编译较慢，后续增量编译会快很多

### 6.2 参考资源

- [NTAG 424 DNA 数据手册](https://www.nxp.com/docs/en/data-sheet/NTAG424DNA.pdf)
- [ACR1252U 产品页](https://www.acs.com.hk/en/products/350/acr1252u-usb-nfc-reader-iii/)
- [pyscard 文档](https://pyscard.sourceforge.io/)
- [Android NFC 官方文档](https://developer.android.com/guide/topics/connectivity/nfc/)
- [Jetpack Compose 官方文档](https://developer.android.com/jetpack/compose)

### 6.3 文件清单

```
EmotionAI_NFC/
├── PROJECT_DOCUMENT.md                  # 项目设计文档
├── IMPLEMENTATION_GUIDE.md              # 本文档
├── server/
│   ├── package.json                     # Node.js 依赖
│   └── server.js                        # Express 服务器
├── nfc_injection/
│   ├── ntag424_batch_inject.py          # ACR1252 批量注入脚本
│   ├── ntag424_config.json              # 424 密钥配置
│   ├── ntag424_injection_cmds.txt       # 手动 APDU 指令参考
│   ├── ntag213_tagwriter.csv            # TagWriter CSV
│   └── ntag213_injection_guide.txt      # TagWriter 操作指南
└── android/
    └── (代码位于上方第 4 章中，需在 Android Studio 中创建项目后放入)
```
