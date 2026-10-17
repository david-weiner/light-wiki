package io.github.davidweiner.lightwiki

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ArticleViewModel(
    private val store: ArticleStore,
    private val requestedTitle: String,
) : LightViewModel<NewSearch>() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val article: Article) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    // Redirects mean the stored title can differ from the one that was tapped.
    private val resolvedTitle = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val saved: Flow<Boolean> = resolvedTitle.filterNotNull()
        .flatMapLatest { store.isSaved(it) }
        .map { it == true }

    // Replaced with one at the stored reading position once the article loads. Kept here so the
    // position also survives trips to linked articles, images and the contents screen.
    var listState = LazyListState()
        private set
    val pendingJump = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch {
            _state.value = runCatching { store.open(requestedTitle) }.fold(
                onSuccess = {
                    listState = LazyListState(it.scrollIndex, it.scrollOffset)
                    resolvedTitle.value = it.article.title
                    State.Loaded(it.article)
                },
                onFailure = { State.Failed(it.toUserMessage()) },
            )
        }
    }

    fun toggleSaved(currentlySaved: Boolean) {
        val title = resolvedTitle.value ?: return
        viewModelScope.launch { store.setSaved(title, !currentlySaved) }
    }

    fun jumpTo(section: Section) {
        // +1 for the title row at the top of the list.
        pendingJump.value = section.blockIndex + 1
    }

    override fun onScreenHide(screen: SimpleLightScreen<NewSearch>) {
        super.onScreenHide(screen)
        saveScroll()
    }

    override fun onAppPause() {
        super.onAppPause()
        saveScroll()
    }

    private fun saveScroll() {
        val title = resolvedTitle.value ?: return
        store.saveScroll(title, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }
}

class ArticleScreen(sealedActivity: SealedLightActivity, private val title: String) :
    LightScreen<NewSearch, ArticleViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArticleViewModel>
        get() = ArticleViewModel::class.java

    override fun createViewModel() = ArticleViewModel(ArticleStore.get(lightContext), title)

    private fun openLink(target: String) {
        navigateTo({ ArticleScreen(it, target) }) { goBack(it) }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val saved by viewModel.saved.collectAsState(initial = false)
        val pendingJump by viewModel.pendingJump.collectAsState()

        LaunchedEffect(pendingJump) {
            pendingJump?.let {
                viewModel.listState.scrollToItem(it)
                viewModel.pendingJump.value = null
            }
        }

        WikiPage(onBack = { goBack() }, rightButton = newSearchButton { goBack(NewSearch) }) {
            when (val s = state) {
                ArticleViewModel.State.Loading -> StatusMessage("Loading…")
                is ArticleViewModel.State.Failed -> StatusMessage(s.message)
                is ArticleViewModel.State.Loaded -> {
                    ArticleBody(s.article, modifier = Modifier.weight(1f))
                    val sections = s.article.sections
                    LightBottomBar(
                        items = listOf(
                            if (sections.isEmpty()) null else LightBarButton.Text("CONTENTS") {
                                navigateTo({ ContentsScreen(it, sections) }) { section ->
                                    viewModel.jumpTo(section)
                                }
                            },
                            LightBarButton.Text(if (saved) "SAVED" else "SAVE") {
                                viewModel.toggleSaved(saved)
                            },
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun ArticleBody(article: Article, modifier: Modifier) {
        val margin = PageMargin.gridUnitsAsDp()
        LazyColumn(state = viewModel.listState, modifier = modifier) {
            item(key = "title") {
                Column(modifier = Modifier.padding(start = margin, end = margin, bottom = 1f.gridUnitsAsDp())) {
                    LightText(text = article.title, variant = LightTextVariant.Heading)
                    LightText(text = "From Wikipedia, the free encyclopedia", variant = LightTextVariant.Detail, lighten = true)
                }
            }
            items(article.blocks) { block ->
                when (block) {
                    is Block.Heading -> LightText(
                        text = block.text,
                        variant = if (block.level <= 2) LightTextVariant.Subheading else LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = margin, end = margin, top = 1.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
                    )
                    is Block.Paragraph -> RichText(
                        spans = block.spans,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = margin, end = margin, bottom = 0.75f.gridUnitsAsDp()),
                    )
                    is Block.ListItem -> Row(
                        modifier = Modifier.padding(
                            start = margin + (block.depth - 1).toFloat().gridUnitsAsDp(),
                            end = margin,
                            bottom = 0.4f.gridUnitsAsDp(),
                        ),
                    ) {
                        RichText(spans = listOf(Span("•  ")))
                        RichText(spans = block.spans, modifier = Modifier.weight(1f))
                    }
                    is Block.Image -> if (block.canShow) ImageRow(block)
                }
            }
            item(key = "attribution") {
                // Wikipedia's text is CC BY-SA 4.0, which requires credit, the license, a link to
                // the source and a note that it was changed.
                LightText(
                    text = "This article is adapted from Wikipedia's \"${article.title}\": tables, references and " +
                        "some formatting are left out. Text is available under the Creative Commons " +
                        "Attribution-ShareAlike 4.0 License (creativecommons.org/licenses/by-sa/4.0). " +
                        "For the authors and the full article, see ${wikipediaUrl(article.title)}",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(start = margin, end = margin, top = 2f.gridUnitsAsDp(), bottom = 2f.gridUnitsAsDp()),
                )
            }
        }
    }

    /** Images aren't shown inline; a row like this opens one full screen when tapped. */
    @Composable
    private fun ImageRow(image: Block.Image) {
        val margin = PageMargin.gridUnitsAsDp()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { navigateTo({ ImageScreen(it, image) }) }
                .padding(start = margin, end = margin, top = 0.25f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = "VIEW IMAGE", variant = LightTextVariant.Detail, underline = true)
            (image.caption ?: image.alt)?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /** Paragraph text with bold, italics and underlined links to other articles. */
    @Composable
    private fun RichText(spans: List<Span>, modifier: Modifier = Modifier) {
        val colors = LightThemeTokens.colors
        val base = LightThemeTokens.typography.paragraph
        val style = base.copy(
            color = colors.content,
            fontSize = base.fontSize.scaled(),
            lineHeight = base.lineHeight.scaled(),
            letterSpacing = base.letterSpacing.scaled(),
        )
        val text = remember(spans) { annotate(spans) }
        BasicText(text = text, style = style, modifier = modifier)
    }

    // Mirrors how LightText sizes its fonts relative to the screen height.
    @Composable
    private fun TextUnit.scaled(): TextUnit =
        if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()

    private fun annotate(spans: List<Span>): AnnotatedString = buildAnnotatedString {
        for (span in spans) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
            val link = span.link
            if (link == null) {
                withStyle(style) { append(span.text) }
            } else {
                val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
                withLink(LinkAnnotation.Clickable(tag = link, styles = linkStyle) { openLink(link) }) {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}
