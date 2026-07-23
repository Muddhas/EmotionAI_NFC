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
