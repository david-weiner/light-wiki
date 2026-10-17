package io.github.davidweiner.lightwiki

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ImageViewModel(cache: ImageCache, image: Block.Image) : LightViewModel<Unit>() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val bitmap: ImageBitmap) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            val bitmap = runCatching { cache.load(image.largeUrl ?: image.url) }.getOrNull()
                ?: runCatching { cache.load(image.url) }.getOrNull()
            _state.value = bitmap?.let { State.Loaded(it) } ?: State.Failed
        }
    }
}

/** Shows one picture full width. Pinch to zoom, double-tap to reset. */
class ImageScreen(sealedActivity: SealedLightActivity, private val image: Block.Image) :
    LightScreen<Unit, ImageViewModel>(sealedActivity) {

    override val viewModelClass: Class<ImageViewModel>
        get() = ImageViewModel::class.java

    override fun createViewModel() = ImageViewModel(ImageCache(lightContext.filesDir), image)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        WikiPage(onBack = { goBack() }) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val s = state) {
                    ImageViewModel.State.Loading -> StatusMessage("Loading image…")
                    ImageViewModel.State.Failed -> StatusMessage("Can't load this image. Check your connection and try again.")
                    is ImageViewModel.State.Loaded -> ZoomableImage(s.bitmap, image.alt)
                }
            }
            Column(
                modifier = Modifier.padding(
                    start = PageMargin.gridUnitsAsDp(),
                    end = PageMargin.gridUnitsAsDp(),
                    top = 1f.gridUnitsAsDp(),
                    bottom = 1.5f.gridUnitsAsDp(),
                ),
            ) {
                (image.caption ?: image.alt)?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Credit required by the image's license, with where to find the full details.
                LightText(
                    text = listOfNotNull(image.author, image.license).joinToString(" · "),
                    variant = LightTextVariant.Detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                image.file?.let {
                    LightText(text = wikipediaUrl(it), variant = LightTextVariant.Detail, lighten = true)
                }
            }
        }
    }

    @Composable
    private fun ZoomableImage(bitmap: ImageBitmap, description: String?) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    })
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
