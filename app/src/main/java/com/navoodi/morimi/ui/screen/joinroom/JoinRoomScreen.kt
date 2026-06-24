package com.navoodi.morimi.ui.screen.joinroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.navoodi.morimi.data.repository.ChatRepository
import com.navoodi.morimi.data.repository.JoinResult
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.ui.theme.*
import kotlinx.coroutines.launch

private val VALID_CODE_CHARS = (('A'..'H') + ('J'..'N') + ('P'..'Z') + ('2'..'9')).toSet()

@Composable
fun JoinRoomScreen(navController: NavController) {
    var codeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    fun submit() {
        if (isLoading) return
        keyboardController?.hide()
        scope.launch {
            isLoading = true
            errorMessage = null
            when (val result = ChatRepository.joinRoomByCode(codeInput)) {
                is JoinResult.Success -> {
                    navController.navigate(Screen.Chat.createRoute(result.roomId)) {
                        popUpTo(Screen.JoinRoom.route) { inclusive = true }
                    }
                }
                JoinResult.NotFound -> {
                    errorMessage = "방을 찾을 수 없어요"
                    isLoading = false
                }
                JoinResult.InvalidFormat -> {
                    errorMessage = "올바른 코드 형식이 아니에요 (대문자·숫자 6자리)"
                    isLoading = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .statusBarsPadding(),
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Gray900)
            }
            Text(
                "코드로 입장",
                modifier = Modifier.align(Alignment.Center),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Gray900,
            )
        }

        HorizontalDivider(color = Gray100)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                "초대 코드 입력",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Gray900,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "방장에게 받은 6자리 초대 코드를 입력하세요",
                fontSize = 14.sp,
                color = Gray400,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            OutlinedTextField(
                value = codeInput,
                onValueChange = { new ->
                    val filtered = new.uppercase()
                        .filter { it in VALID_CODE_CHARS }
                        .take(6)
                    codeInput = filtered
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("AB3K7Z", color = Gray300, fontFamily = FontFamily.Monospace, fontSize = 20.sp) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Gray900,
                    letterSpacing = 6.sp,
                ),
                singleLine = true,
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Blue600,
                    unfocusedBorderColor = Gray200,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                ),
            )

            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    errorMessage!!,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = codeInput.length == 6 && !isLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue600),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("입장하기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
