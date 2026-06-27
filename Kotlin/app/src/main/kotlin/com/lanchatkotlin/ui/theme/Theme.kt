package com.lanchatkotlin.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LanChatColors = lightColorScheme(
    primary       = Color(0xFF3F51B5),
    onPrimary     = Color.White,
    secondary     = Color(0xFF5C6BC0),
    background    = Color(0xFFF0F2F5),
    surface       = Color.White,
    onBackground  = Color(0xFF212121),
    onSurface     = Color(0xFF212121),
)

@Composable
fun LanChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LanChatColors, content = content)
}
