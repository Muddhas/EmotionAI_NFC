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
                                    errorMessage = null
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
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 用颜色圆圈代替头像
            val avatarColor = try {
                Color(android.graphics.Color.parseColor(character.avatarColor))
            } catch (_: Exception) {
                Color(0xFF6750A4)
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = character.name.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
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
