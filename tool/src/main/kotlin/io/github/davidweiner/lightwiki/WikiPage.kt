package io.github.davidweiner.lightwiki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Horizontal margin used by every screen's content. */
val PageMargin = 1.5f

/** Applies the user's LightOS theme (light or dark). */
@Composable
fun WikiTheme(content: @Composable () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors, content = content)
}

/** Lays out a top bar above the screen's content, in the user's theme. */
@Composable
fun WikiPage(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    rightButton: LightBarButton? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    WikiTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
        ) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it) },
                center = title?.let { LightTopBarCenter.Text(it) },
                rightButton = rightButton,
            )
            content()
        }
    }
}

/** Centered secondary text used for loading, empty and error states. */
@Composable
fun StatusMessage(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin.gridUnitsAsDp(), vertical = 2f.gridUnitsAsDp()),
    )
}

fun Throwable.toUserMessage(): String = when (this) {
    is ArticleNotFoundException -> "That article doesn't exist."
    else -> "Can't reach Wikipedia. Check your connection and try again."
}
