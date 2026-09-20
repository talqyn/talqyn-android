# Talqyn Android SDK

The Kotlin SDK for the public Talqyn API: smart search, facets, and the LLM
consultant. Three modules, each added separately:

| Module | What it is |
|---|---|
| `talqyn-sdk` | the client: search, the consultant, ratings, chat history, events |
| `talqyn-consultant-core` | the consultant screen's logic, for a screen of your own |
| `talqyn-ui` | the ready-made consultant screen, Jetpack Compose |

- **Almost no dependencies.** `talqyn-sdk` and the core pull in only
  `kotlinx-coroutines`; `talqyn-ui` adds Compose, Material 3, and the AndroidX
  activity, lifecycle, and core artifacts, which a Compose app has anyway.
- **minSdk 26**, Kotlin 2.1 or newer; `talqyn-ui` asks what Compose 1.10 asks —
  compileSdk 35 and AGP 8.6. No R8 rules needed.
- **Data safety in Google Play:** the SDK sends the shopper identifier (a UUID),
  search queries, product clicks, and questions to the consultant. Check it against
  what the app declares.
- **Documented in place.** This README covers integration; every public type and
  function carries its full reference in KDoc, shipped in the sources jar — hover
  over it in Android Studio.

## Quick start

The API host, the storefront slug, and the client key — an id and a secret — are
handed out at onboarding; nothing below works without them. Four steps from an
empty project to a working consultant, each unpacked in the section of the same
name.

**1. Add the module.** `talqyn-ui` brings the other two with it; take `talqyn-sdk`
alone if all you need is search — see [Installation](#installation).

```kotlin
// build.gradle.kts of the app's module
implementation("com.talqyn:talqyn-ui:1.0.0")
```

**2. Make one client for the whole app** and warm it up at launch, so the first
search is as fast as the rest.

```kotlin
class App : Application() {
    val talqyn by lazy {
        Talqyn(this, TalqynConfiguration(
            baseUrl = BuildConfig.TALQYN_BASE_URL,                // the API host
            credentials = TalqynDeviceTokenCredentials(
                storefront = "myshop",                            // storefront slug
                clientKeyId = "client_key_id",                    // client key id
                clientSecret = BuildConfig.TALQYN_KEY,            // client key secret
                identity = TalqynDeviceIdentity.PersistentAnonymous, // a shopper id that survives restarts
            ),
            defaultLocale = TalqynLocale.En,
            defaultCityId = "10",                                 // city id in your catalog's numbering
        ))
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycleScope.launch { runCatching { talqyn.prepare() } }
    }
}
```

**3. Search.** Every call is a `suspend` function. `externalId` is your own SKU: the
app opens its own product page by it.

```kotlin
val found = talqyn.search.search("iphone 15")
found.results.map { it.title to it.externalId }
```

**4. Show the consultant.** The screen is an ordinary composable; the default theme
needs no setup, and the callbacks are the three actions that lead out of the screen.

```kotlin
TalqynConsultantScreen(
    talqyn = app.talqyn,
    onOpenProduct = { product -> product.externalId?.let { navController.navigate(Pdp(it)) } },
    onOpenSearch = { query -> navController.navigate(Search(query)) },
    onApplyFilters = { criteria -> navController.navigate(Listing(TalqynFullSearchQuery(criteria))) },
    navigation = TalqynNavigation.Back { navController.popBackStack() },
)
```

That is the whole integration: tokens, retries, and the consultant's stream are
the SDK's business, not the app's.

## Installation

The modules are on Maven Central, which a new Android project already lists
among its repositories.

```kotlin
// build.gradle.kts of the app's module
dependencies {
    implementation("com.talqyn:talqyn-sdk:1.0.0")
    implementation("com.talqyn:talqyn-consultant-core:1.0.0") // a consultant screen of your own
    implementation("com.talqyn:talqyn-ui:1.0.0")              // the ready-made screen; the core comes with it
}
```

To build against the sources instead, `includeBuild("../talqyn-android")` in
`settings.gradle.kts` substitutes the same coordinates — as long as the app uses
the SDK's AGP version, 8.13.2.

## Initialization

**One client for the whole app** — `App.talqyn` from the
[quick start](#quick-start). It holds what every request shares — the shopper, the
city, the language — so there must be exactly one, living as long as the process.
The SDK keeps no singleton of its own: a `by lazy` property on the `Application`
is enough, or a `@Singleton` in your DI graph. The constructor sends nothing and
reads nothing from disk. The snippets below call the client `talqyn`.

### The API host

`baseUrl` is required and has no default. Keep it in the build configuration next
to the client key, a `buildConfigField` per flavor: both change together when the
app moves between stands. A path prefix is kept as given, so
`https://gateway.example.com/talqyn` works too.

The host cannot change for the life of a client: another stand means another
`Talqyn`.

### The shopper

The shopper id has to survive an app restart: chat history rests on it.
`PersistentAnonymous` creates one on first launch and keeps it in a file Android
does not back up, so a restored backup does not hand the history to somebody else's
phone. When the shopper signs in or out, tell the client:

```kotlin
talqyn.setIdentity(TalqynDeviceIdentity.User(accountUuid))    // signed in: history follows the account
talqyn.setIdentity(TalqynDeviceIdentity.PersistentAnonymous)  // back to this device's anonymous shopper
talqyn.setIdentity(TalqynDeviceIdentity.Guest)                // no history is kept; the consultant still works
```

For a signed-in shopper, pass the same UUID every time — the account's id from
your backend, say. Skip the call when the account changes, and the next
conversation lands in the previous shopper's history.

### Defaults

```kotlin
talqyn.setPlace(cityId = "10", locationId = null)  // changing the city RESETS the store
talqyn.setLocale(TalqynLocale.Kk)
talqyn.setVariant("exp-b")                          // A/B bucket: echoed into analytics
```

Every request that names no city, store, or language of its own takes these.

## Search

```kotlin
// Instant search — the search field with its dropdown.
val found = talqyn.search.search("iphone 15")
found.results        // List<TalqynProduct> — externalId is YOUR SKU
found.suggestions    // suggestions; highlightFrom is the boundary of the typed text
found.categories     // categories worth navigating to for the query
found.showcase       // showcase queries (these are suggestions, not products)
found.history        // the shopper's past queries (needs events, see below)
found.correctedFrom  // set if the server quietly searched for corrected text
found.searchId       // travels into the click event

// The start screen — what to show when the field is focused and empty.
val start = talqyn.search.start(TalqynStartQuery(limit = 8))
start.history         // this shopper's recent queries (needs events, see below)
start.popularQueries  // what the storefront searches for
start.categories      // root categories of the catalog
start.products        // popular products, ranked by clicks — no score, no relevance
start.searchId        // travels into the click event, with source Start

// A listing with filters and sorting, a page at a time.
val query = TalqynFullSearchQuery(
    query = "smartphone",
    limit = 24,
    sort = TalqynSort.PriceAscending,
    filters = mapOf("brand" to listOf("apple")),
)
val listing = talqyn.search.full(query)
query.nextPage(after = listing)?.let { next -> talqyn.search.full(next) }

// The filter panel and the results together, for the same selection.
val (page, panel) = talqyn.search.listingWithFilters(query)
panel.panelGroups        // the filters, without the city and store groups
panel.cityGroup          // the city picker: option.id goes into cityId
panel.locationGroup      // the store picker: option.id goes into locationId
panel.selectedFilters    // what is selected now, in the shape of the next request
```

Call `start` when the field takes focus, not on every recomposition: each call is
billed as a search. Report a tap on one of its cards with
`source = TalqynEventSource.Start` — those clicks are kept out of search ranking, so
that the screen cannot rank itself.

`talqynId` is Talqyn's internal id and does not exist in your catalog. Everything
you do on your side, do by `externalId` — it is optional, and whether to show a
product that came without one is the app's call.

### Search as the shopper types

Cancel the previous request before starting the next one, or answers arrive out of
order. On a Flow that is `mapLatest`; catch the error inside it, or the first
failure ends the flow and the field stops searching:

```kotlin
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class) // debounce, mapLatest
fun searchResults(queryFlow: Flow<String>): Flow<TalqynSearchResponse?> = queryFlow
    .debounce(150)
    .mapLatest { text ->
        try {
            talqyn.search.search(text)
        } catch (e: TalqynException) {
            null
        }
    }
```

## The consultant

`talqyn-ui` and `talqyn-consultant-core` do everything in this section and the next
two — the stream, ratings, chat history — on their own (see
[The consultant screen](#the-consultant-screen)). Read on if you work with the
consultant's answers directly.

```kotlin
// Kept between questions: the conversation to continue, and the turn to rate.
var session: String? = null
var turn: String? = null

talqyn.consultant.ask("need a laptop for school under 300000", sessionId = session).collect { event ->
    when (event) {
        is TalqynConsultantEvent.Status -> {}           // Thinking → Searching
        is TalqynConsultantEvent.Products -> {}         // cards; products.searchId — for clicks
        is TalqynConsultantEvent.Delta -> {}            // an increment of the answer, see the markers below
        is TalqynConsultantEvent.Clarify -> {}          // clarifying questions: chips; the reply is sent as a question
        is TalqynConsultantEvent.RedirectToSearch -> {} // this was a search query — replay it as a search
        is TalqynConsultantEvent.Fallback -> {}         // there will be no text, the products are there
        is TalqynConsultantEvent.Action -> {}           // ApplyFilters / ShowComparison
        is TalqynConsultantEvent.FollowUps -> {}        // 2–3 follow-up lines, send them VERBATIM
        is TalqynConsultantEvent.Done -> {              // always last
            session = event.done.sessionId              // the next question continues this conversation
            turn = event.done.turnId                    // the key for rating this answer
        }
        else -> {}                                      // Error, and event types from a newer SDK version
    }
}
```

The stream always ends with `Done`. If the connection drops before it, collection
throws after the events already delivered: show that turn as failed, not as
finished. Keep the `else` branch — a newer SDK version can add event types.

The Flow is cold: the question goes out on `collect`, and every `collect` asks it
again — collect it once. Cancelling the collecting coroutine stops the turn.

Every question is a paid call to the model, so do not ask again on your own after
an error: offer the shopper a retry instead.

For a place with no room for a stream — a widget, an answer prepared in the
background — `answer` waits for the whole turn:

```kotlin
val answer = talqyn.consultant.answer(TalqynConsultantQuery(question = "a quiet dishwasher"))
```

### Product markers

The text of a `Delta` may contain `[p:1234]` markers — references to cards from a
`Products` event that has already arrived. Every marker that reaches you has its
card.

```kotlin
TalqynAnswerMarkup.segments(text)   // [Text("Take "), Product(talqynId = 1234)]
TalqynAnswerMarkup.stripped(text)   // if you do not want inline mentions
```

### Actions

```kotlin
is TalqynConsultantEvent.Action -> when (val action = event.action) {
    is TalqynConsultantAction.ApplyFilters -> {          // open a listing the consultant put together
        val criteria = action.filters.criteria(query = lastQuery, cityId = cityId)
        val page = talqyn.search.full(TalqynFullSearchQuery(criteria))
    }
    is TalqynConsultantAction.ShowComparison ->          // a column per product, a row per characteristic
        showComparison(titles = action.table.titles, rows = action.table.rows)
    else -> {}
}
```

### Fallback

`Fallback` means the products are there and the text is not:

- `UserBudgetExceeded` — this shopper has used up their budget for the next few
  hours; search works as usual;
- `BudgetExceeded` — the storefront's monthly budget is used up: a matter for
  Talqyn, not for the app.

The list is open: treat a reason you do not know as "no text", not as an error.
Products are not always there either.

## Rating an answer

A thumb up or down under a finished turn — an answer, a clarification, a fallback —
keyed by the `turnId` from its `Done`:

```kotlin
talqyn.consultant.submitFeedback(TalqynFeedback(
    turnId = turnId, sessionId = sessionId,
    verdict = TalqynFeedbackVerdict.Down,
    reasons = listOf(TalqynFeedbackReason.NotRelevant), // Down only
    comment = "was looking for a fridge",               // Down only, up to 500 characters
    talqynIds = listOf(1234L),                          // Down only: which cards do not belong
))
talqyn.consultant.withdrawFeedback(turnId = turnId)      // the shopper un-pressed the thumb
```

Rating the same turn again **overwrites** the rating, and a rating already given
comes back with the chat from history (`TalqynChatMessage.feedback`). `NotFound`
from `withdrawFeedback` means the rating is already gone — the state you wanted.

## Chat history

```kotlin
val chats = talqyn.consultant.chats(limit = 20, offset = 0)
val chat = talqyn.consultant.chat(sessionId = chats[0].sessionId)
chat.productsFor(chat.messages[1])         // the cards of one particular message
talqyn.consultant.deleteChat(sessionId = chats[0].sessionId)
```

- **History needs a shopper.** Under `Guest` the list throws `Forbidden` rather
  than coming back empty.
- **Somebody else's chat answers like a missing one** — `NotFound`.
- **Anonymous conversations stay anonymous**: signing in later does not move them
  into the account's history.

## Events

Search learns from what shoppers do, and only the storefront can report it. The
shopper's own query history (`found.history`), click-through, and ranking all rest
on these three events:

| Event | Report it when | Carries |
|---|---|---|
| `TalqynSearchSubmitEvent` | the shopper submits a query — the keyboard's search key, or opening a listing | the query, `source` (`Instant` or `Full`, never `Consultant`), `resultsCount` when it is known |
| `TalqynProductClickEvent` | a product card is tapped in your own search UI | the `searchId` of the results it was shown in, `talqynId` (not your SKU), the zero-based `position`, the `source` |
| `TalqynCategoryClickEvent` | a category from `found.categories` is tapped | the category id and the query it was shown for |

```kotlin
talqyn.events.track(TalqynSearchSubmitEvent(
    query = text, source = TalqynEventSource.Instant, resultsCount = found.total,
))
talqyn.events.track(TalqynProductClickEvent(
    searchId = found.searchId, talqynId = product.talqynId, position = index, source = TalqynEventSource.Instant,
))
talqyn.events.track(TalqynCategoryClickEvent(categoryId = category.id, query = text))
```

Where the `searchId` comes from:

- **Instant search** — `found.searchId`, one per response.
- **A listing** — `listing.searchId`, on the **first** page only: later pages
  continue the same results, so keep the id for the whole listing.
- **The start screen** — `start.searchId`, with `source = TalqynEventSource.Start`.
  These clicks are kept out of search ranking: the screen's products are the
  most-clicked ones, so counting them there would let it rank itself.
- **The consultant** — the `searchId` of the turn's `Products`, with
  `source = TalqynEventSource.Consultant` and `position` counted across all of the
  turn's products.

**Consultant clicks are reported for you**: the ready-made screen does it before
calling `onOpenProduct`, and a screen of your own calls
`conversation.trackProductTap`. Do not report them again.

`track` returns at once and never throws. There is no offline queue: an event not
yet sent when the process is killed is lost.

## The consultant screen

Two ways, from the least work to the most.

### The ready-made screen

`talqyn-ui` draws the whole consultant: the empty state with example questions, the
streaming answer, product cards inline and in a carousel, clarifying questions,
comparison, chat history, ratings, copying, retry — and reports its own clicks.
Step 4 of the quick start shows it on the default theme; on top of that, the app
can bring its brand, its title and example questions, and its own product cards.

```kotlin
val BrandTheme = TalqynTheme(
    colors = TalqynTheme.Colors(
        accent = Brand, background = Grey02, surface = Color.White, surfaceSecondary = Grey03,
        border = Grey10, textPrimary = Grey90, textSecondary = Grey65, textTertiary = Grey30,
    ),
    darkColors = TalqynTheme.Colors(                     // the palette for dark mode
        accent = BrandLifted, background = Ink, surface = Ink80, surfaceSecondary = Ink70,
        border = Ink60, textPrimary = Grey03, textSecondary = Grey30, textTertiary = Grey50,
    ),
    fonts = TalqynTheme.Fonts.custom(regular = MuseoSans),
)

TalqynConsultantScreen(
    talqyn = app.talqyn,
    theme = BrandTheme,
    title = "Shop AI",
    exampleQuestions = listOf("A quiet dishwasher under 250 000 ₸", "What to give as a housewarming gift?"),
    productCard = { product, layout ->                   // your own card; null keeps the SDK's
        when (layout) {
            TalqynProductCardLayout.Horizontal -> { { ProductRowCard(product) } }
            TalqynProductCardLayout.Vertical -> { { ProductTileCard(product) } }
        }
    },
    // onOpenProduct, onOpenSearch, onApplyFilters, and navigation — as in step 4
)
```

In a View-based app the same screen goes into a fragment through a `ComposeView`:

```kotlin
class ConsultantFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TalqynConsultantScreen(
                    talqyn = (requireActivity().application as App).talqyn,
                    theme = BrandTheme,
                    onOpenProduct = { product -> product.externalId?.let { router.openPdp(it) } },
                    onOpenSearch = { query -> router.openSearch(query) },
                    onApplyFilters = { criteria -> router.openListing(TalqynFullSearchQuery(criteria)) },
                    navigation = TalqynNavigation.Back { findNavController().popBackStack() },
                )
            }
        }
}
```

What you can set:

| | |
|---|---|
| `theme` | colors by role with a second palette for dark mode, fonts, icons, corner radii, a pinned light or dark appearance, haptics |
| `title` | the name in the header and above the examples |
| `exampleQuestions` | the chips on the empty screen; an empty list removes them |
| `navigation` | the way out: `TalqynNavigation.Back` draws an arrow, `Close` a cross, `null` nothing — the root of a tab |
| `strings` | the en/ru/kk copy, any line replaceable: `TalqynUiStrings.En.copy(placeholder = "…")` |
| `showsHeader` | `false` keeps your own app bar; "new conversation" is then `conversation.reset()`, and history is `TalqynChatHistoryScreen` |
| `showsPoweredBy` | `false` removes "Powered by Talqyn" from the empty screen |
| `priceFormatter` | `449 990 ₸` by default |
| `imageLoader` | your app's image cache and pipeline — Coil, say — in place of the SDK's |
| `conversation` | the second overload takes a conversation of your own — to put a question into the field (`conversation.updateDraft("…")`) or keep the transcript when the screen closes |

- **Your own product cards.** `productCard` returns your composable: `Horizontal` for
  a product cited in the text, at full width; `Vertical` for a tile in the carousel.
  The SDK sets the width and handles the tap — it reports the click and calls
  `onOpenProduct` — so the card must not open the product itself; buttons inside it
  work as usual.
- **Edge to edge.** Call `enableEdgeToEdge()` in the activity: the screen pads for
  the system bars, a cutout, and the keyboard on its own. Under an app bar of your
  own, pass on the insets it took:
  `Modifier.padding(inner).consumeWindowInsets(inner)`.
- **The conversation's lifetime.** By default it lives in the `ViewModel` of the
  current `ViewModelStoreOwner`: it survives a rotation and a trip to a product page,
  and a streaming turn stops when the screen goes away for good. Where that owner
  outlives the screen — a tab in a single-activity app — pass the screen a
  conversation from `rememberTalqynConversation` and call `stop()` on it on the way
  out.
- **Dark mode, tablets, and accessibility** come with the screen: the second palette
  follows the system, the conversation keeps to a readable column on a tablet, text
  grows with the system font size, "Remove animations" is respected, and small
  controls take a tap over at least 48×48 dp.

### A screen of your own

`talqyn-consultant-core` is the same screen's logic with no view layer.
`TalqynConversation` runs the conversation — streaming, clarifying questions,
retry, ratings, reopening a chat, click events — and publishes everything there is
to draw as one `StateFlow`:

```kotlin
class ConsultantViewModel(talqyn: Talqyn) : ViewModel() {
    val conversation = TalqynConversation(talqyn, scope = viewModelScope) // a turn stops with the screen
}

conversation.state.collect { state -> render(state.turns) }             // updated as the answer streams

conversation.send("A quiet dishwasher under 250 000 ₸")
conversation.stop()                                                     // the stop button
conversation.retry(turnId = turn.id)
conversation.rate(turnId = turn.id, TalqynAnswerRating.Helpful)
conversation.trackProductTap(product, turn)                             // before opening the product
conversation.restore(sessionId = chat.sessionId)                        // a chat picked from history

// An answer as paragraphs, headings, lists, and bold, with each product's card
// right after the sentence that cites it.
val blocks = TalqynAnswerRenderer.blocks(turn.text, conversation.state.value.productsById)
```

`TalqynChatHistory` does the same for the list of past chats, and
`TalqynUiStrings` holds the en/ru/kk copy.

## Errors

Errors come as `TalqynException`:

| Subclass | What it means |
|---|---|
| `Unauthorized` | the client key is revoked or wrong |
| `Forbidden` | the SDK is not enabled for the storefront; reading chat history as a guest |
| `NotFound` | no such chat or turn — or it belongs to somebody else |
| `Validation(fields)` | the request failed validation; `fields` names what failed |
| `RateLimited(retryAfter)` | too many requests for now |
| `Server(status, code, retryAfter)` | Talqyn cannot answer right now |
| `DeviceTokensNotConfigured` / `DeviceTokensUnavailable` | the storefront is not set up on Talqyn's side: a matter for support, not a retry |
| `IdentityChanged` | the shopper changed while the request was waiting to go out: it was not sent |
| `InvalidConfiguration` | an empty client key or storefront slug, or a `baseUrl` that does not parse |
| `Transport` / `Decoding` / `Encoding` | the network, the response, the request |

Before an error reaches you, the SDK has already retried whatever was safe to
retry. `exception.isRetryable` says whether a "Try again" button makes sense, and
`exception.requestId` is what to quote to support. Cancellation is not an error: a
cancelled coroutine gets its `CancellationException` as always, and
`TalqynException.wrap(error)` — which brings anything caught to a
`TalqynException` — rethrows it.

## Diagnostics

```kotlin
val configuration = TalqynConfiguration(
    baseUrl = BuildConfig.TALQYN_BASE_URL,
    credentials = credentials,
    logHandler = { event -> Log.d("Talqyn", "${event.message} ${event.requestId.orEmpty()}") },
)
```

Nothing secret reaches a log: the client secret, the token, and the shopper id never
appear in `logHandler`, and `toString` shows them masked.

Quote `Talqyn.VERSION` together with `exception.requestId` when contacting support.
Certificate pinning, a proxy, or a traffic logger such as Chucker plug in as your
own `transport`.

## Tests

```
JAVA_HOME=<JDK 21> ./gradlew test          # SDK — 185 tests, core — 58, screen — 74
./gradlew :sample:installDebug             # the screen on an emulator with no keys
```

The unit tests run on the JVM; the view layer is checked by `sample` on an emulator.
It plays Talqyn itself, and every state of the screen is a question away —
`DemoTransport` lists them.
