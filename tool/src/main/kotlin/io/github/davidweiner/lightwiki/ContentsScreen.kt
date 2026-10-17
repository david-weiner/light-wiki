package io.github.davidweiner.lightwiki

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Lists an article's sections. Returns the chosen section to the caller. */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val sections: List<Section>,
) : SimpleLightScreen<Section>(sealedActivity) {

    @Composable
    override fun Content() {
        WikiPage(title = "Contents", onBack = { goBack() }) {
            LazyColumn {
                items(sections) { section ->
                    val indent = PageMargin + (section.level - 2).coerceAtLeast(0) * 1f
                    LightText(
                        text = section.title,
                        variant = if (section.level <= 2) LightTextVariant.Copy else LightTextVariant.Detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { goBack(section) }
                            .padding(
                                start = indent.gridUnitsAsDp(),
                                end = PageMargin.gridUnitsAsDp(),
                                top = 0.6f.gridUnitsAsDp(),
                                bottom = 0.6f.gridUnitsAsDp(),
                            ),
                    )
                }
            }
        }
    }
}
