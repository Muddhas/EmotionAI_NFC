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

    suspend fun bindChip(
        token: String, chipId: String, aiCharacterId: Int
    ): Result<BindResult> = runApiCall {
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

    suspend fun verifyChip(
        token: String, chipId: String, uid: String, signature: String
    ): Result<VerifyResult> = runApiCall {
        val response = api.verifyChip(
            "Bearer $token", VerifyRequest(chipId, uid, signature)
        )
        if (response.isSuccessful && response.body()?.success == true) {
            response.body()!!.data!!
        } else {
            throw Exception(response.body()?.error ?: "验证失败")
        }
    }

    // ========== 工具封装 ==========

    private suspend fun <T> runApiCall(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
