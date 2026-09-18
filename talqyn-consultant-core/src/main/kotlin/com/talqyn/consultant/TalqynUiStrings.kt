package com.talqyn.consultant

import com.talqyn.sdk.TalqynFallbackReason
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynLocale
import java.util.Locale

/**
 * Every piece of copy the consultant screens show.
 *
 * Ships in English, Russian, and Kazakh — the locales the API serves — as plain values rather
 * than string resources, so an app can replace a single line without shipping resources of its
 * own. Pick a set with [forLocale] or start from [En] / [Ru] / [Kk] and `copy` what needs
 * changing.
 *
 * @property locale The locale numbers and dates are written in: a rating score, a date in the
 *   history. Follows the copy, not the device — a Russian screen on an English phone still
 *   writes `4,8`. English uses `en-US` rather than an `en-KZ` matching the other two: the
 *   platforms disagree on what `en-KZ` means — one reads it as a comma decimal separator and a
 *   24-hour clock, the other as a dot and a 12-hour clock — and the three SDKs have to write a
 *   score the same way.
 * @property fallbackProductsHeader The header over the products of a turn the consultant gave
 *   up on. They are not a recommendation on top of an answer — they are all the turn has — so
 *   they do not get the ordinary header.
 * @property back The accessibility label of the button that goes back to the screen the
 *   consultant was opened from.
 * @property exampleQuestions The prompts offered on an empty screen and after the first answer.
 * @property clarifySkipValue What is sent when the shopper skips a clarification.
 * @property fallbackByReason Fallback copy by reason, keyed by the wire value before any `:`
 *   suffix. Each line says only what went wrong; what the turn still has to offer is
 *   [fallbackWithProducts], added when there is something to show.
 * @property fallbackWithProducts The fallback line when the turn found products anyway: `%@`
 *   is the reason. A turn that found none shows the reason alone — the copy must not point at
 *   products that are not there.
 * @property errorByCode Error copy by code.
 * @property filterFrom `%@` is the formatted price.
 * @property filterUpTo `%@` is the formatted price.
 * @property outOfStock The mark on a product card that cannot be bought right now.
 * @property productsCountForms "N products", one form per plural category the locale needs:
 *   `[one, few, many]` for Russian, `[one, other]` for English, a single form for Kazakh. `%d`
 *   is the count. See [productsCount].
 * @property copyAnswer The accessibility label of the copy button under an answer.
 * @property copied What the copy button says once the answer is on the clipboard.
 * @property rateHelpful The accessibility label of the "helpful" button under an answer.
 * @property rateNotHelpful The accessibility label of the "not helpful" button under an answer.
 * @property feedbackReasonsTitle The line over the reasons offered once an answer is rated unhelpful.
 * @property feedbackReasons The reasons' labels, keyed by [TalqynFeedbackReason.rawValue]. A
 *   reason with no label here is not offered.
 * @property copyQuestion The shopper's own message: copy it.
 * @property editQuestion The shopper's own message: put it back into the composer to change it.
 * @property disclaimer The line under the composer: the consultant can be wrong, so check its
 *   answers. `%@` is [title], so the line follows a screen the app renamed. Empty hides the line.
 */
public data class TalqynUiStrings(
    val locale: Locale,
    val title: String,
    val introSubtitle: String,
    val placeholder: String,
    val thinking: String,
    val searching: String,
    val composing: String,
    val retry: String,
    val aborted: String,
    val redirectNotice: String,
    val openSearch: String,
    val applyFilters: String,
    val productsHeader: String,
    val fallbackProductsHeader: String,
    val comparisonTitle: String,
    val comparisonOnlyDifferences: String,
    val comparisonNoDifferences: String,
    val openComparison: String,
    val newChat: String,
    val newChatConfirm: String,
    val cancel: String,
    val ok: String,
    val close: String,
    val back: String,
    val exampleQuestions: List<String>,
    val clarifySubmit: String,
    val clarifySkip: String,
    val clarifySkipValue: String,
    val clarifyAnsweredLabel: String,
    val clarifyCustomPlaceholder: String,
    val fallbackGeneric: String,
    val fallbackByReason: Map<String, String>,
    val fallbackWithProducts: String,
    val errorGeneric: String,
    val errorByCode: Map<String, String>,
    val filterFrom: String,
    val filterUpTo: String,
    val filterDiscount: String,
    val scrollToBottom: String,
    val send: String,
    val stop: String,
    val answerReady: String,
    val noReviews: String,
    val historyTitle: String,
    val historyEmpty: String,
    val historyUntitled: String,
    val historyYesterday: String,
    val historyError: String,
    val historyUnavailable: String,
    val historyGone: String,
    val historyDelete: String,
    val historyDeleteTitle: String,
    val historyDeleteConfirm: String,
    val outOfStock: String,
    val productsCountForms: List<String>,
    val copyAnswer: String,
    val copied: String,
    val rateHelpful: String,
    val rateNotHelpful: String,
    val feedbackReasonsTitle: String,
    val feedbackReasons: Map<String, String>,
    val copyQuestion: String,
    val editQuestion: String,
    val disclaimer: String,
) {
    /**
     * A line with its `%@` filled in.
     *
     * Substitution is a plain replacement rather than `String.format`: copy an app replaced may
     * carry a bare `%` — a price, a percentage — and that must not be read as a placeholder.
     */
    public fun fill(template: String, value: String): String = template.replace("%@", value)

    /** The copy for a fallback reason, generic when the reason is unknown. */
    public fun fallbackText(reason: TalqynFallbackReason): String = fallbackByReason[reason.base.rawValue] ?: fallbackGeneric

    /** The copy for an error code, generic when the code is unknown. */
    public fun errorText(code: String): String = errorByCode[code] ?: errorGeneric

    /** The label of a feedback reason, or `null` when the copy has none. */
    public fun feedbackReasonText(reason: TalqynFeedbackReason): String? = feedbackReasons[reason.rawValue]

    /**
     * "N products" in the plural form the count calls for: one, few, or many in Russian, one or
     * other in English, the single form in Kazakh.
     */
    public fun productsCount(count: Int): String {
        val first = productsCountForms.firstOrNull() ?: return count.toString()
        var form = first
        if (productsCountForms.size >= 3) {
            // Russian: one for 1, 21, 31…; few for 2–4, 22–24…; many for the rest, 11–14 included.
            val mod10 = count % 10
            val mod100 = count % 100
            form = when {
                mod10 == 1 && mod100 != 11 -> productsCountForms[0]
                mod10 in 2..4 && mod100 !in 12..14 -> productsCountForms[1]
                else -> productsCountForms[2]
            }
        } else if (productsCountForms.size == 2) {
            // English: one for 1, other for everything else.
            form = if (count == 1) productsCountForms[0] else productsCountForms[1]
        }
        return form.replace("%d", count.toString())
    }

    public companion object {
        /** The strings for a locale. */
        @JvmStatic
        public fun forLocale(locale: TalqynLocale): TalqynUiStrings = when (locale) {
            TalqynLocale.En -> En
            TalqynLocale.Ru -> Ru
            TalqynLocale.Kk -> Kk
        }

        /** English. The default. */
        @JvmField
        public val En: TalqynUiStrings = TalqynUiStrings(
            locale = Locale.forLanguageTag("en-US"),
            title = "AI consultant",
            introSubtitle = "I will find products for your request and explain the choice",
            placeholder = "Ask about products…",
            thinking = "Thinking it over…",
            searching = "Looking through the options…",
            composing = "Writing the answer…",
            retry = "Try again",
            aborted = "Answer stopped",
            redirectNotice = "This looks like a search query — results come up faster that way",
            openSearch = "Open results",
            applyFilters = "Show in search",
            productsHeader = "Also worth a look",
            fallbackProductsHeader = "What turned up for your request",
            comparisonTitle = "Comparison",
            comparisonOnlyDifferences = "Differences only",
            comparisonNoDifferences = "Every specification matches",
            openComparison = "Open comparison",
            newChat = "New chat",
            newChatConfirm = "Start a new chat? The current one stays in your history.",
            cancel = "Cancel",
            ok = "OK",
            close = "Close",
            back = "Back",
            exampleQuestions = listOf(
                "Find me an affordable smartphone",
                "Which fridge should I pick for a family?",
                "A laptop for studying under 300,000 ₸",
            ),
            clarifySubmit = "Continue",
            clarifySkip = "No preference",
            clarifySkipValue = "no preference",
            clarifyAnsweredLabel = "Your choice",
            clarifyCustomPlaceholder = "Something else…",
            fallbackGeneric = "Could not explain the choice",
            fallbackByReason = mapOf(
                // The shopper's own limit clears in hours; the account's is not theirs to wait
                // out, and not theirs to be told about.
                "user_budget_exceeded" to "You have used up your questions for the next few hours",
                "budget_exceeded" to "The consultant is unavailable right now",
                "turn_budget" to "This chat has run too long",
                "empty_answer" to "Could not put an answer together",
                "timeout" to "The answer took too long",
                "ttft_timeout" to "The service is answering slower than usual",
                "tool_deadline" to "Could not look up the details in time",
                "circuit_open" to "The consultant is temporarily unavailable",
                "refusal" to "Could not answer this request",
            ),
            fallbackWithProducts = "%@, but here is what matches",
            errorGeneric = "Could not get an answer, please try again",
            errorByCode = mapOf(
                "retrieval_failed" to "Could not find any products, please try again",
            ),
            filterFrom = "from %@",
            filterUpTo = "up to %@",
            filterDiscount = "on sale",
            scrollToBottom = "To the latest message",
            send = "Send",
            stop = "Stop the answer",
            answerReady = "Answer ready",
            noReviews = "No reviews",
            historyTitle = "Chat history",
            historyEmpty = "Your chats with the AI consultant will show up here",
            historyUntitled = "Untitled chat",
            historyYesterday = "Yesterday",
            historyError = "Could not load the history, please try again",
            historyUnavailable = "Chat history is not available yet",
            historyGone = "This chat has been deleted",
            historyDelete = "Delete",
            historyDeleteTitle = "Delete this chat?",
            historyDeleteConfirm = "The conversation will be gone for good.",
            outOfStock = "Out of stock",
            productsCountForms = listOf("%d product", "%d products"),
            copyAnswer = "Copy the answer",
            copied = "Copied",
            rateHelpful = "Helpful answer",
            rateNotHelpful = "Unhelpful answer",
            feedbackReasonsTitle = "What went wrong?",
            feedbackReasons = mapOf(
                "not_relevant" to "Not what I was looking for",
                "wrong_info" to "Something in the answer is wrong",
                "too_many_questions" to "Too many questions",
                "no_answer" to "No answer",
                "price_stock" to "Price or availability",
                "other" to "Other",
            ),
            copyQuestion = "Copy",
            editQuestion = "Edit the question",
            disclaimer = "%@ can be wrong. Double-check its answers.",
        )

        /** Russian. */
        @JvmField
        public val Ru: TalqynUiStrings = TalqynUiStrings(
            locale = Locale.forLanguageTag("ru-KZ"),
            title = "AI-консультант",
            introSubtitle = "Подберу товары под ваш запрос и объясню выбор",
            placeholder = "Спросите про товары…",
            thinking = "Думаю над запросом…",
            searching = "Подбираю варианты…",
            composing = "Формулирую ответ…",
            retry = "Повторить",
            aborted = "Ответ остановлен",
            redirectNotice = "Похоже на поисковый запрос — так выдача найдётся быстрее",
            openSearch = "Открыть результаты",
            applyFilters = "Показать в поиске",
            productsHeader = "Также рекомендуем посмотреть",
            fallbackProductsHeader = "Что нашлось по запросу",
            comparisonTitle = "Сравнение",
            comparisonOnlyDifferences = "Только отличия",
            comparisonNoDifferences = "Все характеристики совпадают",
            openComparison = "Открыть сравнение",
            newChat = "Новый диалог",
            newChatConfirm = "Начать новый диалог? Текущий сохранится в истории.",
            cancel = "Отмена",
            ok = "Ок",
            close = "Закрыть",
            back = "Назад",
            exampleQuestions = listOf(
                "Подбери недорогой смартфон",
                "Какой холодильник выбрать для семьи?",
                "Ноутбук для учёбы до 300 000 ₸",
            ),
            clarifySubmit = "Продолжить",
            clarifySkip = "Не важно",
            clarifySkipValue = "не важно",
            clarifyAnsweredLabel = "Ваш выбор",
            clarifyCustomPlaceholder = "Свой вариант…",
            fallbackGeneric = "Не получилось объяснить выбор",
            fallbackByReason = mapOf(
                // The shopper's own limit clears in hours; the account's is not theirs to wait
                // out, and not theirs to be told about.
                "user_budget_exceeded" to "Лимит вопросов на ближайшие часы исчерпан",
                "budget_exceeded" to "Консультант сейчас недоступен",
                "turn_budget" to "Диалог получился слишком длинным",
                "empty_answer" to "Не получилось сформировать ответ",
                "timeout" to "Ответ занял слишком много времени",
                "ttft_timeout" to "Сервис отвечает медленнее обычного",
                "tool_deadline" to "Не успел уточнить детали",
                "circuit_open" to "Консультант временно недоступен",
                "refusal" to "Не получилось ответить на этот запрос",
            ),
            fallbackWithProducts = "%@, но вот подходящие товары",
            errorGeneric = "Не получилось получить ответ, попробуйте ещё раз",
            errorByCode = mapOf(
                "retrieval_failed" to "Не получилось найти товары, попробуйте ещё раз",
            ),
            filterFrom = "от %@",
            filterUpTo = "до %@",
            filterDiscount = "со скидкой",
            scrollToBottom = "К последнему сообщению",
            send = "Отправить",
            stop = "Остановить ответ",
            answerReady = "Ответ готов",
            noReviews = "Нет отзывов",
            historyTitle = "История диалогов",
            historyEmpty = "Здесь появятся ваши диалоги с AI-консультантом",
            historyUntitled = "Диалог без названия",
            historyYesterday = "Вчера",
            historyError = "Не получилось загрузить историю, попробуйте ещё раз",
            historyUnavailable = "История диалогов пока недоступна",
            historyGone = "Этот диалог удалён",
            historyDelete = "Удалить",
            historyDeleteTitle = "Удалить диалог?",
            historyDeleteConfirm = "Переписка удалится безвозвратно.",
            outOfStock = "Нет в наличии",
            productsCountForms = listOf("%d товар", "%d товара", "%d товаров"),
            copyAnswer = "Скопировать ответ",
            copied = "Скопировано",
            rateHelpful = "Полезный ответ",
            rateNotHelpful = "Бесполезный ответ",
            feedbackReasonsTitle = "Что не так?",
            feedbackReasons = mapOf(
                "not_relevant" to "Не то, что искал",
                "wrong_info" to "Ошибка в ответе",
                "too_many_questions" to "Слишком много вопросов",
                "no_answer" to "Нет ответа",
                "price_stock" to "Цена или наличие",
                "other" to "Другое",
            ),
            copyQuestion = "Скопировать",
            editQuestion = "Изменить вопрос",
            disclaimer = "%@ может ошибаться. Перепроверяйте ответы.",
        )

        /** Kazakh. */
        @JvmField
        public val Kk: TalqynUiStrings = TalqynUiStrings(
            locale = Locale.forLanguageTag("kk-KZ"),
            title = "AI-кеңесші",
            introSubtitle = "Сұранысыңызға сай тауарларды таңдап, таңдауымды түсіндіремін",
            placeholder = "Тауарлар туралы сұраңыз…",
            thinking = "Сұранысты ойлануда…",
            searching = "Нұсқаларды таңдауда…",
            composing = "Жауапты құрастыруда…",
            retry = "Қайталау",
            aborted = "Жауап тоқтатылды",
            redirectNotice = "Іздеу сұранысына ұқсайды — нәтиже жылдамырақ табылады",
            openSearch = "Нәтижелерді ашу",
            applyFilters = "Іздеуден көрсету",
            productsHeader = "Мынаны да қарауды ұсынамыз",
            fallbackProductsHeader = "Сұраныс бойынша табылғаны",
            comparisonTitle = "Салыстыру",
            comparisonOnlyDifferences = "Тек айырмашылықтар",
            comparisonNoDifferences = "Барлық сипаттамалар бірдей",
            openComparison = "Салыстыруды ашу",
            newChat = "Жаңа диалог",
            newChatConfirm = "Жаңа диалог бастау керек пе? Ағымдағысы тарихта сақталады.",
            cancel = "Бас тарту",
            ok = "Жарайды",
            close = "Жабу",
            back = "Артқа",
            exampleQuestions = listOf(
                "Арзан смартфон таңда",
                "Отбасыға қандай тоңазытқыш таңдауға болады?",
                "Оқуға арналған ноутбук, 300 000 ₸ дейін",
            ),
            clarifySubmit = "Жалғастыру",
            clarifySkip = "Маңызды емес",
            clarifySkipValue = "маңызды емес",
            clarifyAnsweredLabel = "Сіздің таңдауыңыз",
            clarifyCustomPlaceholder = "Өз нұсқаңыз…",
            fallbackGeneric = "Таңдауды түсіндіру мүмкін болмады",
            fallbackByReason = mapOf(
                "user_budget_exceeded" to "Жақын сағаттарға сұрақ лимиті таусылды",
                "budget_exceeded" to "Кеңесші қазір қолжетімсіз",
                "turn_budget" to "Диалог тым ұзақ болды",
                "empty_answer" to "Жауап қалыптаспады",
                "timeout" to "Жауап тым ұзаққа созылды",
                "ttft_timeout" to "Қызмет әдеттегіден баяу жауап беруде",
                "tool_deadline" to "Толық ақпаратты нақтылауға үлгермедім",
                "circuit_open" to "Кеңесші уақытша қолжетімсіз",
                "refusal" to "Бұл сұранысқа жауап беру мүмкін болмады",
            ),
            fallbackWithProducts = "%@, бірақ сәйкес тауарлар осында",
            errorGeneric = "Жауап алу мүмкін болмады, қайталап көріңіз",
            errorByCode = mapOf(
                "retrieval_failed" to "Тауарларды табу мүмкін болмады, қайталап көріңіз",
            ),
            filterFrom = "%@ бастап",
            filterUpTo = "%@ дейін",
            filterDiscount = "жеңілдікпен",
            scrollToBottom = "Соңғы хабарламаға",
            send = "Жіберу",
            stop = "Жауапты тоқтату",
            answerReady = "Жауап дайын",
            noReviews = "Пікірлер жоқ",
            historyTitle = "Диалогтар тарихы",
            historyEmpty = "Мұнда AI-кеңесшімен диалогтарыңыз шығады",
            historyUntitled = "Атауы жоқ диалог",
            historyYesterday = "Кеше",
            historyError = "Тарихты жүктеу мүмкін болмады, қайталап көріңіз",
            historyUnavailable = "Диалогтар тарихы әзірге қолжетімсіз",
            historyGone = "Бұл диалог жойылған",
            historyDelete = "Жою",
            historyDeleteTitle = "Диалогты жою керек пе?",
            historyDeleteConfirm = "Жазысу қайтарымсыз жойылады.",
            outOfStock = "Қоймада жоқ",
            productsCountForms = listOf("%d тауар"),
            copyAnswer = "Жауапты көшіру",
            copied = "Көшірілді",
            rateHelpful = "Пайдалы жауап",
            rateNotHelpful = "Пайдасыз жауап",
            feedbackReasonsTitle = "Не ұнамады?",
            feedbackReasons = mapOf(
                "not_relevant" to "Іздегенім емес",
                "wrong_info" to "Жауапта қате бар",
                "too_many_questions" to "Сұрақ тым көп",
                "no_answer" to "Жауап жоқ",
                "price_stock" to "Баға не қолда бары",
                "other" to "Басқа",
            ),
            copyQuestion = "Көшіру",
            editQuestion = "Сұрақты өзгерту",
            disclaimer = "%@ қателесуі мүмкін. Жауаптарды қайта тексеріңіз.",
        )
    }
}
