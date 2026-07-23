# Project Context

## EmotionAI_NFC — 情感陪伴 AI NFC 管理 Demo
- **技术栈**: Android 原生 (Kotlin + Jetpack Compose) + Express + SQLite 后端
- **核心功能**: 触碰 NFC → 打开 App → 跳转对应 AI 对话页; 首次触碰绑定账号; 已绑定芯片不能被其他用户使用
- **NFC 芯片**: NTAG 424 DNA (防伪方案, ACR1252 + Python 批量注入) 和 NTAG 213 (简易方案, TagWriter CSV)
- **用户认证**: 无密码用户名认证; 支持多账号切换; 不依赖设备 IMEI
- **关键文档**: PROJECT_DOCUMENT.md (架构设计), IMPLEMENTATION_GUIDE.md (完整实现代码)
- **服务器**: server/server.js (Express + SQLite, 单文件)
- **注入脚本**: nfc_injection/ntag424_batch_inject.py (ACR1252 批量), nfc_injection/ntag213_tagwriter.csv (TagWriter)
- **Android 代码**: 均在 IMPLEMENTATION_GUIDE.md 第4章内, 需要 Android Studio 创建项目后放置
- **重要约定**: APK 编译前需询问用户确认(内存限制)
