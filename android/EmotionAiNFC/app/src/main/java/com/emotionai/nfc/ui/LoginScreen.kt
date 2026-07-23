package com.emotionai.nfc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    // 加载已有用户列表
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
                    errorMessage = null
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
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(bottom = 8.dp)
                )

                LazyColumn {
                    items(users) { user ->
                        Card(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
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
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
