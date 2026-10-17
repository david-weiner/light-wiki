package io.github.davidweiner.lightwiki

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ResultsViewModel(private val query: String) : LightViewModel<NewSearch>() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val results: List<SearchResult>) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            _state.value = runCatching { WikiApi.search(query) }
                .fold({ State.Loaded(it) }, { State.Failed(it.toUserMessage()) })
        }
    }
}

class ResultsScreen(sealedActivity: SealedLightActivity, private val query: String) :
    LightScreen<NewSearch, ResultsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ResultsViewModel>
        get() = ResultsViewModel::class.java

    override fun createViewModel() = ResultsViewModel(query)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        WikiPage(title = query, onBack = { goBack() }, rightButton = newSearchButton { goBack(NewSearch) }) {
            when (val s = state) {
                ResultsViewModel.State.Loading -> StatusMessage("Searching…")
                is ResultsViewModel.State.Failed -> StatusMessage(s.message)
                is ResultsViewModel.State.Loaded ->
                    if (s.results.isEmpty()) {
                        StatusMessage("No articles found.")
                    } else {
                        LazyColumn {
                            items(s.results, key = { it.title }) { result ->
                                ResultRow(result)
                            }
                        }
                    }
            }
        }
    }

    @Composable
    private fun ResultRow(result: SearchResult) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { navigateTo({ ArticleScreen(it, result.title) }) { goBack(it) } }
                .padding(horizontal = PageMargin.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp())
        ) {
            LightText(
                text = result.title,
                variant = LightTextVariant.Copy,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            result.description?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
