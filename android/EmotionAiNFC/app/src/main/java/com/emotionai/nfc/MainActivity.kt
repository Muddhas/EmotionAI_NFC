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

    // 处理 NFC 触碰
    // 用 nfcTrigger 做 key（自增计数器），协程执行期间 key 不会变化 → 不会被取消
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

    val startDestination = when {
        isLoggedIn -> "chat_list"
        else -> "login"
    }

    // NFC 错误提示弹窗
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
