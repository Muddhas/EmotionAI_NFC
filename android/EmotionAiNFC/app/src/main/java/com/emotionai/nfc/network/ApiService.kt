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
