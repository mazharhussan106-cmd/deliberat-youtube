package com.mazhar.deliberate

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * What the PIN overlay is currently asking for.
 *
 * Making this explicit is what keeps the first-run setup flow from turning into a pile of
 * booleans: the overlay is one view with three jobs, and the mode says which one.
 */
private sealed interface OverlayMode {
    /** The block wall. There is a navigation waiting on the other side of this. */
    data class Unlock(val type: NavigationType) : OverlayMode

    /** First run. No PIN exists yet and the app is unusable until one does. */
    object SetPin : OverlayMode

    /** First run, second step. [first] is what they typed a moment ago. */
    data class ConfirmPin(val first: String) : OverlayMode
}

/**
 * Single screen, three states stacked in a FrameLayout:
 *
 *   landing    — search box. What you get on launch. Never the Home feed.
 *   webView    — YouTube, once you have actually searched for something.
 *   pinOverlay — modal, on top of everything: first-run setup, or the block wall.
 *
 * NOTE ON STRUCTURE: the plan named a `SearchLandingFragment`. This build uses a plain view
 * inside the Activity instead. For a one-screen app a Fragment adds a container, a
 * transaction, a back-stack and a callback interface for zero behavioural gain.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var landing: View
    private lateinit var searchInput: EditText

    private lateinit var pinOverlay: View
    private lateinit var pinTitle: TextView
    private lateinit var pinSubtitle: TextView
    private lateinit var pinInput: EditText
    private lateinit var pinMessage: TextView
    private lateinit var pinSubmit: Button
    private lateinit var pinCancel: Button

    private lateinit var pinManager: PinManager

    /** null when the overlay is hidden. */
    private var overlayMode: OverlayMode? = null
    private var cooldownTimer: CountDownTimer? = null

    /**
     * PBKDF2 at [PinHasher.ITERATIONS] costs a few hundred milliseconds on a phone. That is
     * the point — but it is far too long to spend on the main thread, so every derivation
     * happens here and comes back via [runInBackground].
     */
    private val background: ExecutorService = Executors.newSingleThreadExecutor()

    /** Where to go once the PIN is accepted. */
    private var pendingUrl: String? = null

    /**
     * True when the page already moved before we caught it (an SPA pushState), so cancelling
     * has to actively navigate away. False when nothing loaded, in which case cancelling can
     * just dismiss the overlay — reloading would restart whatever video is playing.
     */
    private var pendingNavigated: Boolean = false

    /** Last page that was allowed, so a denied navigation has somewhere to fall back to. */
    private var lastSafeUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        landing = findViewById(R.id.landing)
        searchInput = findViewById(R.id.searchInput)
        pinOverlay = findViewById(R.id.pinOverlay)
        pinTitle = findViewById(R.id.pinTitle)
        pinSubtitle = findViewById(R.id.pinSubtitle)
        pinInput = findViewById(R.id.pinInput)
        pinMessage = findViewById(R.id.pinMessage)
        pinSubmit = findViewById(R.id.pinSubmit)
        pinCancel = findViewById(R.id.pinCancel)

        pinManager = PinManager(this)

        configureWebView()
        wireLanding()
        wirePinOverlay()
        wireBackButton()

        showLanding()
        if (!pinManager.isPinSet()) showOverlay(OverlayMode.SetPin)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // A plain Chrome-on-Android UA. The stock WebView UA contains "; wv", which
            // YouTube sometimes answers with an "unsupported browser" page.
            userAgentString = MOBILE_USER_AGENT
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = YouTubeWebViewClient(
            onBlocked = { type, url, navigated -> showPinPrompt(type, url, navigated) },
            onSafeUrl = { url -> lastSafeUrl = url }
        )
    }

    // ---------------------------------------------------------------- Landing

    private fun wireLanding() {
        findViewById<Button>(R.id.searchButton).setOnClickListener { submitSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(); true
            } else {
                false
            }
        }
    }

    private fun submitSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard(searchInput)
        val url = SEARCH_URL + URLEncoder.encode(query, "UTF-8")
        lastSafeUrl = url
        showWebView()
        webView.loadUrl(url)
    }

    /** Does not touch the overlay — callers that need it gone call [dismissPin] first. */
    private fun showLanding() {
        webView.visibility = View.GONE
        landing.visibility = View.VISIBLE
        searchInput.setText("")
    }

    private fun showWebView() {
        landing.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- PIN overlay

    private fun wirePinOverlay() {
        pinSubmit.setOnClickListener { submitPin() }
        pinCancel.setOnClickListener { dismissPinAndRetreat() }
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPin(); true
            } else {
                false
            }
        }
    }

    /**
     * Called from the WebViewClient. May fire several times for one blocked navigation
     * (SPA history updates arrive in bursts), so an already-visible prompt for the same
     * section is left alone rather than wiping a half-typed PIN.
     */
    private fun showPinPrompt(type: NavigationType, url: String, navigated: Boolean) {
        pendingUrl = url

        val current = overlayMode
        if (current is OverlayMode.Unlock && current.type == type) {
            // Same block, another callback for it. Only ever escalate to "we must retreat".
            pendingNavigated = pendingNavigated || navigated
            return
        }

        pendingNavigated = navigated
        showOverlay(OverlayMode.Unlock(type))
    }

    private fun showOverlay(mode: OverlayMode) {
        overlayMode = mode
        pinInput.setText("")
        clearMessage()
        // A fresh prompt always starts usable; the cooldown check at the bottom is what
        // disables it again if a lockout is still running.
        setInputBusy(false)

        when (mode) {
            is OverlayMode.Unlock -> {
                pinTitle.setText(
                    if (mode.type == NavigationType.SHORTS) R.string.pin_title_shorts
                    else R.string.pin_title_home
                )
                pinSubtitle.setText(R.string.pin_subtitle)
                pinSubmit.setText(R.string.pin_submit)
                pinCancel.visibility = View.VISIBLE
            }

            OverlayMode.SetPin -> {
                pinTitle.setText(R.string.pin_setup_title)
                pinSubtitle.setText(R.string.pin_setup_subtitle)
                pinSubmit.setText(R.string.pin_setup_submit)
                // First-run setup is not skippable — there is nothing to go back to.
                pinCancel.visibility = View.GONE
            }

            is OverlayMode.ConfirmPin -> {
                pinTitle.setText(R.string.pin_confirm_title)
                pinSubtitle.setText(R.string.pin_confirm_subtitle)
                pinSubmit.setText(R.string.pin_confirm_submit)
                pinCancel.visibility = View.GONE
            }
        }

        pinOverlay.visibility = View.VISIBLE
        // The overlay is clickable/focusable so it swallows touches, and the WebView is
        // disabled underneath as a second guard.
        webView.isEnabled = false
        pinInput.requestFocus()
        showKeyboard(pinInput)

        // A lockout from an earlier session is still in force.
        if (mode is OverlayMode.Unlock) {
            val remaining = pinManager.cooldownRemainingMs()
            if (remaining > 0L) startCooldown(remaining)
        }
    }

    private fun submitPin() {
        val entered = pinInput.text.toString()
        when (val mode = overlayMode) {
            null -> return
            OverlayMode.SetPin -> handleSetPin(entered)
            is OverlayMode.ConfirmPin -> handleConfirmPin(mode.first, entered)
            is OverlayMode.Unlock -> handleUnlock(mode.type, entered)
        }
    }

    // ---------------------------------------------------------------- First-run setup

    private fun handleSetPin(entered: String) {
        if (entered.length < MIN_PIN_LENGTH) {
            showMessage(getString(R.string.pin_too_short, MIN_PIN_LENGTH))
            return
        }
        showOverlay(OverlayMode.ConfirmPin(entered))
    }

    private fun handleConfirmPin(first: String, entered: String) {
        if (entered != first) {
            showOverlay(OverlayMode.SetPin)
            showMessage(getString(R.string.pin_mismatch))
            return
        }
        setInputBusy(true)
        runInBackground(
            work = { pinManager.setPin(entered) },
            then = {
                dismissPin()
                showLanding()
                Toast.makeText(this, R.string.pin_set_done, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ---------------------------------------------------------------- Unlocking

    private fun handleUnlock(type: NavigationType, entered: String) {
        setInputBusy(true)
        runInBackground(
            work = { pinManager.verify(entered) },
            then = { result ->
                setInputBusy(false)
                applyUnlockResult(type, result)
            }
        )
    }

    private fun applyUnlockResult(type: NavigationType, result: PinResult) {
        when (result) {
            PinResult.Correct -> {
                SessionState.unlock(type)
                val target = pendingUrl
                dismissPin()
                pendingUrl = null
                pendingNavigated = false
                if (target != null) {
                    showWebView()
                    webView.loadUrl(target)
                }
            }

            is PinResult.Incorrect -> {
                // Phase 3 keeps you on the overlay instead of ejecting you on the first
                // mistake — a five-attempt budget means nothing if one typo ends the attempt.
                // "Go back" is still right there.
                pinInput.setText("")
                showMessage(
                    resources.getQuantityString(
                        R.plurals.pin_attempts_left, result.attemptsLeft, result.attemptsLeft
                    )
                )
            }

            is PinResult.LockedOut -> {
                pinInput.setText("")
                startCooldown(result.remainingMs)
            }
        }
    }

    // ---------------------------------------------------------------- Cooldown

    private fun startCooldown(remainingMs: Long) {
        cooldownTimer?.cancel()
        pinInput.isEnabled = false
        pinSubmit.isEnabled = false

        val timer = object : CountDownTimer(remainingMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ceil(millisUntilFinished / 1000.0).toInt()
                showMessage(getString(R.string.pin_locked, seconds))
            }

            override fun onFinish() {
                cooldownTimer = null
                pinInput.isEnabled = true
                pinSubmit.isEnabled = true
                clearMessage()
            }
        }
        cooldownTimer = timer

        // CountDownTimer's first tick lands one interval in, so paint the message now.
        showMessage(getString(R.string.pin_locked, ceil(remainingMs / 1000.0).toInt()))
        timer.start()
    }

    // ---------------------------------------------------------------- Dismissal

    private fun dismissPin() {
        cooldownTimer?.cancel()
        cooldownTimer = null
        hideKeyboard(pinInput)
        pinOverlay.visibility = View.GONE
        overlayMode = null
        pinInput.setText("")
        pinInput.isEnabled = true
        pinSubmit.isEnabled = true
        clearMessage()
        webView.isEnabled = true
    }

    /**
     * "Go back", or the back button on the block wall.
     *
     * If nothing actually loaded — the usual case, where `shouldOverrideUrlLoading` returned
     * true — the WebView is still sitting on the page you were already on, so dismissing the
     * overlay is the whole job. Reloading here would restart a playing video.
     *
     * If the SPA already moved (a pushState Home tap), we have to navigate away. Going to the
     * last allowed page is the same denial with less collateral damage than the plan's
     * "redirect to the search page". Only when there is no such page do we fall back to the
     * landing screen.
     */
    private fun dismissPinAndRetreat() {
        val mustRetreat = pendingNavigated
        dismissPin()
        pendingUrl = null
        pendingNavigated = false

        if (!mustRetreat) return

        val safe = lastSafeUrl
        if (safe != null) {
            showWebView()
            webView.loadUrl(safe)
        } else {
            showLanding()
        }
    }

    // ---------------------------------------------------------------- Background work

    /**
     * Runs [work] off the main thread and delivers the result back on it.
     *
     * The result is dropped if the Activity went away while PBKDF2 was grinding — there is
     * nothing left to update, and touching the views then would crash.
     */
    private fun <T> runInBackground(work: () -> T, then: (T) -> Unit) {
        background.execute {
            val result = work()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                then(result)
            }
        }
    }

    /** Locks the overlay's controls while a derivation is in flight, so it cannot be double-submitted. */
    private fun setInputBusy(busy: Boolean) {
        pinInput.isEnabled = !busy
        pinSubmit.isEnabled = !busy
    }

    // ---------------------------------------------------------------- Messages

    private fun showMessage(text: String) {
        pinMessage.text = text
        pinMessage.visibility = View.VISIBLE
    }

    private fun clearMessage() {
        pinMessage.text = ""
        pinMessage.visibility = View.GONE
    }

    // ---------------------------------------------------------------- Back button

    private fun wireBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val mode = overlayMode
                when {
                    // No PIN yet means no app. Backing out of setup leaves.
                    mode is OverlayMode.SetPin || mode is OverlayMode.ConfirmPin -> finish()
                    mode is OverlayMode.Unlock -> dismissPinAndRetreat()
                    landing.visibility == View.VISIBLE -> finish()
                    webView.canGoBack() -> webView.goBack()
                    else -> showLanding()
                }
            }
        })
    }

    // ---------------------------------------------------------------- Lifecycle

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        cooldownTimer = null
        background.shutdownNow()
        webView.stopLoading()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Keyboard

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private companion object {
        const val SEARCH_URL = "https://www.youtube.com/results?search_query="
        const val MIN_PIN_LENGTH = 4
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
