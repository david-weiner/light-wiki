package io.github.davidweiner.lightwiki

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor

/** Full-screen keyboard entry. Returns the trimmed query to the caller. */
class SearchInputScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val state = rememberTextFieldState()
        WikiTheme {
            LightTextInputEditor(
                title = "Search",
                state = state,
                onSubmit = { text ->
                    val query = text.toString().trim()
                    if (query.isNotEmpty()) goBack(query)
                },
                onBack = { goBack() },
                keyboardOptionsFlow = rememberKeyboardOptions(),
                submitLabel = "SEARCH",
                singleLine = true,
                initialCaps = true,
            )
        }
    }
}
