package com.talqyn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.talqyn.consultant.TalqynClarifyDraft
import com.talqyn.consultant.TalqynConversation
import com.talqyn.consultant.TalqynConversationLimits
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynException
import com.talqyn.sdk.TalqynFilterCriteria
import com.talqyn.sdk.TalqynProduct
import java.util.UUID

/**
 * A conversation that lives as long as the screen's `ViewModel`: it survives a rotation and a
 * trip to a product page, and stops — along with the answer it was streaming, an LLM call
 * nobody will read — when the screen leaves for good.
 *
 * @param key Tells apart two consultant screens under one `ViewModelStoreOwner`. A client of its
 *   own gets a conversation of its own under the same key: an app that builds a new `Talqyn` —
 *   another shopper signed in — is not handed the conversation the old client holds.
 */
@Composable
public fun rememberTalqynConversation(
    talqyn: Talqyn,
    maxFollowUps: Int = TalqynConversationLimits.MAX_FOLLOW_UPS,
    key: String? = null,
): TalqynConversation {
    val holderKey = "${key ?: "com.talqyn.conversation"}@${System.identityHashCode(talqyn)}"
    val holder = viewModel(key = holderKey) { TalqynConversationHolder(talqyn, maxFollowUps) }
    return holder.conversation
}

internal class TalqynConversationHolder(talqyn: Talqyn, maxFollowUps: Int) : ViewModel() {
    val conversation = TalqynConversation(talqyn, maxFollowUps, viewModelScope)
}

/**
 * The consultant screen, drawn by the SDK.
 *
 * Give it a client, a theme, and three callbacks for what only the app can do — open a product,
 * open search results, open a filtered listing — and it handles the rest: the conversation,
 * streaming, clarifications, product cards and comparison, ratings, chat history, click events.
 *
 * ```kotlin
 * TalqynConsultantScreen(
 *     talqyn = talqyn,
 *     theme = brandTheme,
 *     onOpenProduct = { navController.navigate(Pdp(it.externalId)) },
 *     onOpenSearch = { navController.navigate(Search(it)) },
 *     onApplyFilters = { navController.navigate(Listing(TalqynFullSearchQuery(it))) },
 *     navigation = TalqynNavigation.Back { navController.popBackStack() },
 * )
 * ```
 *
 * The conversation is kept in a `ViewModel` of the current `ViewModelStoreOwner` — see
 * [rememberTalqynConversation]; pass your own through the other overload to prefill a question
 * or keep the conversation past the screen. What the screen shows over the conversation — an
 * open comparison, a question moved into the transcript — is kept there too, so a trip to a
 * product page and back finds the screen as the shopper left it.
 *
 * Draw the screen edge to edge (`enableEdgeToEdge()`): it pads its header by the status bar,
 * keeps its content clear of a cutout or navigation buttons at the side, rides the composer on
 * the keyboard, and while it is shown sets the icons of the bars to read against its header and
 * composer. Under an app bar of your own (`showsHeader = false`), pass on the insets the bar has
 * taken — `Modifier.padding(inner).consumeWindowInsets(inner)` — or the screen pads by them again.
 *
 * @param title What the screen calls itself, in its header and over the examples. `null` keeps
 *   the copy's own name.
 * @param exampleQuestions The chips on an empty screen, offered again under the first answer
 *   where the shopper has not asked them yet. `null` keeps the copy's own; an empty list shows none.
 * @param strings Copy. `null` follows the client's locale.
 * @param showsHeader Whether to draw the screen's own header. Without it, a new chat is
 *   `conversation.reset()` and history is [TalqynChatHistoryScreen].
 * @param showsPoweredBy Whether the empty screen carries "Powered by Talqyn" under the examples.
 *   The wording is fixed, in every locale; this is the switch.
 * @param navigation The way out drawn at the start of the header.
 * @param maxFollowUps How many follow-up prompts to offer under an answer. `0` shows none.
 * @param productCard The app's own product cards; `null` draws the SDK's.
 * @param onRate The shopper rated a turn — for the app's own analytics.
 */
@Composable
public fun TalqynConsultantScreen(
    talqyn: Talqyn,
    onOpenProduct: (TalqynProduct) -> Unit,
    onOpenSearch: (String) -> Unit,
    onApplyFilters: (TalqynFilterCriteria) -> Unit,
    modifier: Modifier = Modifier,
    theme: TalqynTheme = TalqynTheme.Default,
    title: String? = null,
    exampleQuestions: List<String>? = null,
    strings: TalqynUiStrings? = null,
    priceFormatter: TalqynPriceFormatter = TalqynPriceFormatter.Tenge,
    imageLoader: TalqynImageLoader = TalqynUrlImageLoader.Shared,
    showsHeader: Boolean = true,
    showsPoweredBy: Boolean = true,
    navigation: TalqynNavigation? = null,
    maxFollowUps: Int = TalqynConversationLimits.MAX_FOLLOW_UPS,
    productCard: TalqynProductCardProvider? = null,
    onRate: TalqynRatingListener? = null,
) {
    TalqynConsultantScreen(
        conversation = rememberTalqynConversation(talqyn, maxFollowUps),
        onOpenProduct = onOpenProduct,
        onOpenSearch = onOpenSearch,
        onApplyFilters = onApplyFilters,
        modifier = modifier,
        theme = theme,
        title = title,
        exampleQuestions = exampleQuestions,
        strings = strings,
        priceFormatter = priceFormatter,
        imageLoader = imageLoader,
        showsHeader = showsHeader,
        showsPoweredBy = showsPoweredBy,
        navigation = navigation,
        productCard = productCard,
        onRate = onRate,
    )
}

/**
 * The consultant screen over a conversation the app owns: prefill the composer with
 * `conversation.updateDraft("…")`, and keep the conversation past the screen.
 *
 * See the other overload for the parameters.
 */
@Composable
public fun TalqynConsultantScreen(
    conversation: TalqynConversation,
    onOpenProduct: (TalqynProduct) -> Unit,
    onOpenSearch: (String) -> Unit,
    onApplyFilters: (TalqynFilterCriteria) -> Unit,
    modifier: Modifier = Modifier,
    theme: TalqynTheme = TalqynTheme.Default,
    title: String? = null,
    exampleQuestions: List<String>? = null,
    strings: TalqynUiStrings? = null,
    priceFormatter: TalqynPriceFormatter = TalqynPriceFormatter.Tenge,
    imageLoader: TalqynImageLoader = TalqynUrlImageLoader.Shared,
    showsHeader: Boolean = true,
    showsPoweredBy: Boolean = true,
    navigation: TalqynNavigation? = null,
    productCard: TalqynProductCardProvider? = null,
    onRate: TalqynRatingListener? = null,
) {
    val locale = conversation.talqyn.currentLocale
    // A name and a set of examples given here beat the ones in the copy, so an app can change
    // either without carrying a whole string set.
    val copy = remember(strings, title, exampleQuestions, locale) {
        val base = strings ?: TalqynUiStrings.forLocale(locale)
        base.copy(title = title ?: base.title, exampleQuestions = exampleQuestions ?: base.exampleQuestions)
    }
    ProvideTalqyn(theme, copy, priceFormatter, imageLoader) {
        CompositionLocalProvider(LocalTalqynCardProvider provides productCard) {
            TalqynConsultantContent(
                conversation = conversation,
                onOpenProduct = onOpenProduct,
                onOpenSearch = onOpenSearch,
                onApplyFilters = onApplyFilters,
                modifier = modifier,
                showsHeader = showsHeader,
                showsPoweredBy = showsPoweredBy,
                navigation = navigation,
                onRate = onRate,
            )
        }
    }
}

/**
 * The screen's presentation of [conversation], kept by the `ViewModelStoreOwner` the screen is
 * composed under — or by the composition, for a host that has no owner to keep it.
 */
@Composable
private fun rememberScreenPresentation(conversation: TalqynConversation): TalqynScreenPresentation {
    if (LocalViewModelStoreOwner.current == null) return remember(conversation) { TalqynScreenPresentation() }
    val store = viewModel(key = "com.talqyn.presentation") { TalqynPresentationStore() }
    return remember(store, conversation) { store.presentation(conversation) }
}

@Composable
private fun TalqynConsultantContent(
    conversation: TalqynConversation,
    onOpenProduct: (TalqynProduct) -> Unit,
    onOpenSearch: (String) -> Unit,
    onApplyFilters: (TalqynFilterCriteria) -> Unit,
    modifier: Modifier,
    showsHeader: Boolean,
    showsPoweredBy: Boolean,
    navigation: TalqynNavigation?,
    onRate: TalqynRatingListener?,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val strings = talqyn.strings
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state by conversation.state.collectAsState()

    val builder = remember(strings, talqyn.price) { TalqynTranscriptRowBuilder(strings, talqyn.price) }
    val presentation = rememberScreenPresentation(conversation)
    var confirmsReset by rememberSaveable { mutableStateOf(false) }
    var alert by rememberSaveable { mutableStateOf<String?>(null) }
    // A request to move the transcript rather than a position: it stays with the composition, so a
    // screen composed again does not make the same move a second time.
    var anchor by remember { mutableStateOf(TalqynAnchorRequest(0, TalqynTranscriptAnchor.Keep)) }
    var announcement by remember { mutableStateOf("") }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val transcript = remember(listState) { TalqynTranscriptState(listState) }
    val focusRequester = remember { FocusRequester() }

    val openProduct by rememberUpdatedState(onOpenProduct)
    val openSearch by rememberUpdatedState(onOpenSearch)
    val applyFilters by rememberUpdatedState(onApplyFilters)
    val rateListener by rememberUpdatedState(onRate)

    // The shopper may have changed while the screen was covered: their conversation must not go on
    // under someone else's history.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { conversation.refreshIdentity() }
    }

    val actions = remember(conversation, presentation) {
        fun tap(product: TalqynProduct, turnId: UUID) {
            conversation.state.value.assistantTurn(turnId)?.let { conversation.trackProductTap(product, it) }
            openProduct(product)
        }
        TalqynTurnActions(
            onProduct = ::tap,
            onProductMention = { id, turnId -> conversation.state.value.productsById[id]?.let { tap(it, turnId) } },
            onRetry = conversation::retry,
            onRedirect = { openSearch(it) },
            onFilters = { filters, question -> applyFilters(filters.criteria(query = question)) },
            onComparison = {
                // The keyboard must not stay up for a composer the table covers.
                focusManager.clearFocus()
                presentation.comparison = it
            },
            onSubmitClarify = { answer, turnId -> conversation.submitClarify(turnId, answer) },
            onClarifyDraft = { draft, turnId -> conversation.updateClarifyDraft(turnId, draft) },
            currentClarifyDraft = { turnId -> conversation.state.value.clarifyDrafts[turnId] ?: TalqynClarifyDraft() },
            onSuggestion = conversation::send,
            onRate = { rating, reasons, turnId ->
                // The turn is redrawn by the state change the rating makes — and again if Talqyn
                // does not take it and it is put back.
                conversation.rate(turnId, rating, reasons)
                conversation.state.value.assistantTurn(turnId)?.let { rateListener?.onRate(rating, reasons, it) }
            },
            onEditQuestion = { question ->
                conversation.updateDraft(question)
                focusRequester.requestFocus()
            },
        )
    }

    val turnIds = state.turns.map { it.id }
    LaunchedEffect(presentation, turnIds, state.isStreaming, state.isRestoring) {
        val reaction = presentation.update(
            state = conversation.state.value,
            transcriptAtStart = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0,
        )
        builder.forget(reaction.removedTurnIds)
        if (reaction.isReplaced) builder.forgetAll()
        if (reaction.anchor != TalqynTranscriptAnchor.Keep) anchor = TalqynAnchorRequest(anchor.id + 1, reaction.anchor)
        if (reaction.answerSettled) {
            // A zero-width space toggled on the end makes the same words a new announcement.
            announcement = if (announcement == strings.answerReady) strings.answerReady + "​" else strings.answerReady
        }
    }

    LaunchedEffect(state.restoreFailure) {
        val failure = state.restoreFailure ?: return@LaunchedEffect
        conversation.clearRestoreFailure()
        alert = if (failure is TalqynException.NotFound) strings.historyGone else strings.historyError
    }

    val inlineClarifyTurns = presentation.inlineClarifyTurns
    val rows = remember(state, inlineClarifyTurns, builder) { builder.rows(state, inlineClarifyTurns) }
    val composerHeight = with(density) { composerHeightPx.toDp() }
    val comparison = presentation.comparison
    val isCovered = comparison != null || presentation.showsHistory
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    // The bars' icons read against the header at the top — the table's or history's own, over a
    // screen that draws none — and against the composer, or the layer over it, at the bottom.
    TalqynSystemBars(
        top = if (showsHeader || isCovered) colors.surface else null,
        bottom = if (isCovered) colors.surface else colors.background,
    )

    Box(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Under the comparison or history the screen is out of reach: of TalkBack, which would read
        // the transcript behind the table, and of focus moved by a keyboard.
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (isCovered) {
                        Modifier
                            .clearAndSetSemantics {}
                            .focusProperties { onEnter = { cancelFocusChange() } }
                            .focusGroup()
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (showsHeader) {
                    TalqynHeader(
                        navigation = navigation,
                        showsNewChat = !state.isEmpty,
                        actionsEnabled = !state.isStreaming && !state.isRestoring,
                        onHistory = {
                            if (!conversation.state.value.isStreaming) {
                                focusManager.clearFocus()
                                presentation.showsHistory = true
                            }
                        },
                        onNewChat = { confirmsReset = true },
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Without its own header the screen still starts under the status bar.
                        .then(if (showsHeader) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
                        .windowInsetsPadding(sideInsets)
                        .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
                ) {
                    AnimatedContent(
                        targetState = state.isEmpty,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        label = "emptyState",
                        // While a chat is restored, what is under the spinner is on its way out.
                        modifier = if (state.isRestoring) Modifier.clearAndSetSemantics {} else Modifier,
                    ) { isEmpty ->
                        if (isEmpty) {
                            TalqynEmptyState(
                                showsPoweredBy = showsPoweredBy,
                                onExample = conversation::send,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = composerHeight),
                            )
                        } else {
                            // The transcript runs to the bottom of the screen and the composer floats
                            // over it; the room the last row needs to clear it is kept inside the list.
                            TalqynTranscript(
                                rows = rows,
                                anchor = anchor,
                                bottomReserve = composerHeight + TALQYN_COMPOSER_GAP - TALQYN_COMPOSER_TOP_INSET,
                                state = transcript,
                                actions = actions,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (state.isRestoring) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colors.background)
                                .talqynBlocksTouches()
                                .padding(bottom = composerHeight),
                        ) {
                            CircularProgressIndicator(color = colors.textSecondary)
                        }
                    }
                }
            }

            // The bar runs to the bottom edge, under the navigation bar and the keyboard, so bare
            // transcript never shows in the strip beneath the pill.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height },
            ) {
                TalqynGlassBar(Modifier.matchParentSize())
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The keyboard and the navigation bar below, a cutout or buttons at the side.
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                ) {
                    TalqynComposer(
                        draft = state.draft,
                        currentDraft = { conversation.state.value.draft },
                        isStreaming = state.isStreaming,
                        onDraftChange = conversation::updateDraft,
                        onSend = conversation::send,
                        onStop = conversation::stop,
                        focusRequester = focusRequester,
                        modifier = Modifier
                            .widthIn(max = talqyn.metrics.maxContentWidth)
                            .fillMaxWidth(),
                    )
                }
            }

            AnimatedVisibility(
                visible = !state.isEmpty && !state.isRestoring && !transcript.isPinnedToBottom,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(sideInsets)
                    .padding(end = talqyn.metrics.horizontalMargin, bottom = composerHeight + 4.dp),
            ) {
                val reduced = isReducedMotion()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .talqynShadow(CircleShape, colors.shadow, radius = 8.dp, offsetY = 2.dp)
                        .background(colors.surface, CircleShape)
                        .talqynPressable { scope.launch { transcript.scrollToBottom(rows.size, animated = !reduced) } }
                        .semantics { contentDescription = strings.scrollToBottom },
                ) {
                    TalqynIconImage(talqyn.icons.scrollToBottom, colors.textPrimary, 16.dp)
                }
            }
        }

        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                },
        )

        AnimatedContent(
            targetState = comparison,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "comparison",
        ) { table ->
            if (table != null) {
                TalqynComparisonContent(
                    table = table,
                    products = state.productsById,
                    onOpenProduct = { openProduct(it) },
                    onClose = { presentation.comparison = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // History replaces the consultant rather than sliding over it: it is a screen of its own,
        // with its own header and close button, and it fades in where the consultant was.
        AnimatedVisibility(visible = presentation.showsHistory, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            TalqynChatHistoryContent(
                talqyn = conversation.talqyn,
                onSelect = conversation::restore,
                onDelete = conversation::discardIfOpen,
                onClose = { presentation.showsHistory = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    BackHandler(enabled = comparison != null) { presentation.comparison = null }

    val sheetTurnId = presentation.sheetTurnId
    val sheetTurn = sheetTurnId?.let { state.assistantTurn(it) }
    val sheetClarify = sheetTurn?.clarify
    if (sheetTurn != null && sheetClarify != null && sheetTurn.clarifyAnswer == null) {
        TalqynClarifySheet(
            clarify = sheetClarify,
            draft = state.clarifyDrafts[sheetTurn.id] ?: TalqynClarifyDraft(),
            currentDraft = { conversation.state.value.clarifyDrafts[sheetTurn.id] ?: TalqynClarifyDraft() },
            onDraftChange = { conversation.updateClarifyDraft(sheetTurn.id, it) },
            onSubmit = { answer ->
                presentation.sheetTurnId = null
                conversation.submitClarify(sheetTurn.id, answer)
            },
            onDismiss = { presentation.sheetDismissed(sheetTurn.id) },
        )
    } else if (sheetTurnId != null) {
        LaunchedEffect(sheetTurnId) { presentation.sheetTurnId = null }
    }

    if (confirmsReset) {
        TalqynAlert(
            title = strings.newChat,
            message = strings.newChatConfirm,
            confirm = strings.newChat,
            dismiss = strings.cancel,
            onConfirm = {
                confirmsReset = false
                focusManager.clearFocus()
                conversation.reset()
            },
            onDismiss = { confirmsReset = false },
        )
    }
    alert?.let { message ->
        TalqynAlert(title = null, message = message, confirm = strings.ok, dismiss = null, onConfirm = { alert = null }, onDismiss = { alert = null })
    }
}

/** An alert in the theme's colors and type. A dialog is a window of its own, so its content caps the type again. */
@Composable
internal fun TalqynAlert(
    title: String?,
    message: String,
    confirm: String,
    dismiss: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = title?.let { text ->
            { ProvideTalqynDensity { BasicText(text, style = talqyn.fonts.headline.copy(color = colors.textPrimary)) } }
        },
        text = { ProvideTalqynDensity { BasicText(message, style = talqyn.fonts.body.copy(color = colors.textSecondary)) } },
        confirmButton = {
            ProvideTalqynDensity {
                TextButton(onClick = onConfirm) {
                    BasicText(confirm, style = talqyn.fonts.label.copy(color = if (destructive) colors.error else colors.accent))
                }
            }
        },
        dismissButton = dismiss?.let { text ->
            {
                ProvideTalqynDensity {
                    TextButton(onClick = onDismiss) {
                        BasicText(text, style = talqyn.fonts.label.copy(color = colors.accent))
                    }
                }
            }
        },
    )
}
