package io.github.davidweiner.lightwiki

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val store: ArticleStore) : LightViewModel<Unit>() {
    val saved: Flow<List<String>> = store.savedTitles
    val recent: Flow<List<String>> = store.recentTitles

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing

    fun setEditing(editing: Boolean) {
        _editing.value = editing
    }

    fun delete(title: String) {
        viewModelScope.launch { store.delete(title) }
    }

    // Back leaves edit mode before it leaves the tool.
    override fun onBackPressed(): Boolean {
        if (!_editing.value) return false
        _editing.value = false
        return true
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(ArticleStore.get(lightContext))

    private fun openSearch() {
        navigateTo(::SearchInputScreen) { query ->
            navigateTo({ ResultsScreen(it, query) }) { openSearch() }
        }
    }

    private fun openArticle(title: String) {
        navigateTo({ ArticleScreen(it, title) }) { openSearch() }
    }

    @Composable
    override fun Content() {
        val saved by viewModel.saved.collectAsState(initial = emptyList())
        val recent by viewModel.recent.collectAsState(initial = emptyList())
        val editing by viewModel.editing.collectAsState()
        val hasArticles = saved.isNotEmpty() || recent.isNotEmpty()
        if (editing && !hasArticles) viewModel.setEditing(false)

        WikiPage(
            title = "Wiki",
            rightButton = when {
                editing -> LightBarButton.Text("DONE") { viewModel.setEditing(false) }
                hasArticles -> LightBarButton.Text("EDIT") { viewModel.setEditing(true) }
                else -> null
            },
        ) {
            if (!editing) {
                LightTextField(
                    label = "Search",
                    value = "",
                    placeholder = "Search Wikipedia",
                    onClick = ::openSearch,
                    modifier = Modifier.padding(horizontal = PageMargin.gridUnitsAsDp()),
                )
            }
            if (!hasArticles) {
                StatusMessage("Articles you open are kept here, so you can read them without a connection.")
            } else {
                LazyColumn(modifier = Modifier.padding(top = 1f.gridUnitsAsDp())) {
                    titleList("Saved", saved, editing)
                    titleList("Recent", recent, editing)
                }
            }
        }
    }

    private fun LazyListScope.titleList(heading: String, titles: List<String>, editing: Boolean) {
        if (titles.isEmpty()) return
        item(key = "heading-$heading") {
            LightText(
                text = heading,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(
                    start = PageMargin.gridUnitsAsDp(),
                    end = PageMargin.gridUnitsAsDp(),
                    top = 1.5f.gridUnitsAsDp(),
                    bottom = 0.5f.gridUnitsAsDp(),
                ),
            )
        }
        items(titles, key = { "$heading-$it" }) { title ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { if (editing) viewModel.delete(title) else openArticle(title) }
                    .padding(horizontal = PageMargin.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = title,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (editing) {
                    LightIcon(
                        icon = LightIcons.DELETE,
                        modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
