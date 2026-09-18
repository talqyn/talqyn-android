package com.talqyn.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynConfiguration
import com.talqyn.sdk.TalqynDeviceTokenCredentials
import com.talqyn.sdk.TalqynLocale
import com.talqyn.ui.TalqynConsultantScreen
import com.talqyn.ui.TalqynNavigation
import com.talqyn.ui.TalqynTheme
import com.talqyn.ui.rememberTalqynConversation

/**
 * The consultant over a canned transport: no credentials, no network for the conversation.
 *
 * `adb shell am start -n com.talqyn.sample/.MainActivity --es question "…" --es appearance dark`
 * opens the screen with a question already asked, in the appearance named.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val talqyn = SampleClient.get(this)
        val question = intent.getStringExtra("question")
        val appearance = when (intent.getStringExtra("appearance")) {
            "dark" -> TalqynTheme.Appearance.Dark
            "light" -> TalqynTheme.Appearance.Light
            else -> TalqynTheme.Appearance.System
        }
        setContent {
            val conversation = rememberTalqynConversation(talqyn)
            if (question != null && savedInstanceState == null) {
                LaunchedEffect(Unit) { conversation.send(question) }
            }
            TalqynConsultantScreen(
                conversation = conversation,
                theme = TalqynTheme(appearance = appearance),
                exampleQuestions = listOf(
                    "A quiet dishwasher under 250 000 ₸",
                    "Compare two smartphones",
                    "What to give as a housewarming gift?",
                ),
                navigation = TalqynNavigation.Close { finish() },
                onOpenProduct = { toast("Open product ${it.externalId}") },
                onOpenSearch = { toast("Search: $it") },
                onApplyFilters = { toast("Listing: ${it.query} ${it.filters}") },
            )
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/** One client for the process, as an app keeps it. */
private object SampleClient {
    @Volatile
    private var instance: Talqyn? = null

    fun get(activity: ComponentActivity): Talqyn = instance ?: synchronized(this) {
        instance ?: Talqyn(
            activity.applicationContext,
            TalqynConfiguration(
                credentials = TalqynDeviceTokenCredentials(
                    storefront = "demo",
                    clientKeyId = "ck_demo",
                    clientSecret = "demo-secret",
                ),
                // The demo transport answers from memory, so the host is never dialled —
                // but the SDK ships no endpoint of its own and every client names one.
                baseUrl = "https://api.example.com",
                transport = DemoTransport(),
                // The demo answers English keywords and nothing else, so it pins the locale
                // the canned scenarios are written in instead of following the SDK default.
                defaultLocale = TalqynLocale.En,
            ),
        ).also { instance = it }
    }
}
