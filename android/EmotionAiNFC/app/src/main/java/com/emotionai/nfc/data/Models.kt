package com.emotionai.nfc.data

// ========================================
// API 通用响应包装
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
// 用户绑定列表
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
