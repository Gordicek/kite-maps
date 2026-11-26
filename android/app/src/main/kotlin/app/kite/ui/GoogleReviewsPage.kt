package app.kite.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.kite.core.GooglePlaceMedia
import app.kite.core.Place
import app.kite.core.PublicPlaceReviews
import kotlinx.coroutines.delay
import app.kite.primaryText

/** Google Maps' public reviews tab as an in-app fallback when the native feed is empty. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GoogleReviewsPage(place: Place, onReviews: (PublicPlaceReviews) -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val cid = place.featureId?.let(GooglePlaceMedia::cid)
    val url = cid?.let { "https://www.google.com/maps?cid=$it&hl=en" } ?: place.shareUrl
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf(false) }
    var browser by remember(url) { mutableStateOf<WebView?>(null) }
    val publish by rememberUpdatedState(onReviews)
    LaunchedEffect(browser, loading, error) {
        val view = browser ?: return@LaunchedEffect
        if (loading || error) return@LaunchedEffect
        while (true) {
            delay(1000)
            if (view.url?.startsWith("https://www.google.com/maps") != true) continue
            view.evaluateJavascript(COLLECT_REVIEWS) { reply ->
                if (browser !== view || view.url?.startsWith("https://www.google.com/maps") != true) return@evaluateJavascript
                val page = GooglePlaceMedia.publicReviewDetails(reply.orEmpty())
                if (page.reviews.isNotEmpty() || page.histogram.isNotEmpty()) publish(page)
            }
        }
    }
    TripSheet("Google Maps reviews", "Done", close) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                val target = request.url
                                if (target.scheme == "https" && target.host in setOf("www.google.com", "consent.google.com")) return false
                                if (target.scheme == "https" || target.scheme == "http") ctx.open(target.toString())
                                return true
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame) { error = true; loading = false }
                            }

                            override fun onPageFinished(view: WebView, URL: String) {
                                loading = false
                                if (view.url?.startsWith("https://www.google.com/maps") == true) {
                                    view.evaluateJavascript(SELECT_REVIEWS, null)
                                }
                            }
                        }
                        browser = this
                        loadUrl(url)
                    }
                },
                onReset = null,
                onRelease = { view ->
                    if (browser === view) browser = null
                    view.stopLoading()
                    view.destroy()
                },
            )
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            if (error) Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Google Maps could not load the reviews.", color = primaryText)
                TextButton({ ctx.open(place.shareUrl) }) { Text("Open in Google Maps") }
            }
        }
    }
}

private const val SELECT_REVIEWS = """(() => {
    if (window.kiteReviewTab) return;
    window.kiteReviewTab = true;
    let attempts = 0;
    const timer = setInterval(() => {
        const button = [...document.querySelectorAll('[role="tab"],button')].find(el =>
            /^reviews(?: for .+)?$/i.test((el.getAttribute('aria-label') || el.textContent || '').trim()));
        if (button) button.click();
        if (button || ++attempts === 20) clearInterval(timer);
    }, 400);
})();"""


private const val COLLECT_REVIEWS = """(() => {
    const text = (card, selector) => card.querySelector(selector)?.textContent?.trim() || null;
    const reviews = Array.from(document.querySelectorAll('.jJc9Ad')).slice(0, 100).flatMap(card => {
        if (!card.matches('[data-review-id]') && !card.querySelector('[data-review-id]')) return [];
        const author = text(card, '.d4r55,.Vpc5Fe,.TSUbDb');
        if (!author) return [];
        const stars = Array.from(card.querySelectorAll('[role="img"][aria-label]'))
            .map(el => el.getAttribute('aria-label')?.match(/^([1-5])(?:\.0)?\s/))
            .find(Boolean);
        const photos = Array.from(card.querySelectorAll('img,button[style],div[style]')).flatMap(el => {
            const urls = Array.from((el.style.backgroundImage || '').matchAll(/url\(["']?([^"')]+)["']?\)/g), match => match[1]);
            if (el instanceof HTMLImageElement && (el.currentSrc || el.src)) urls.push(el.currentSrc || el.src);
            return urls;
        });
        const avatar = card.querySelector('img[src*="/a/"], img[src*="/a-"], img[src*="/a0-"]')?.src || null;
        const likeButton = [...card.querySelectorAll('button[aria-label]')].find(button =>
            /^(like|helpful|líbí)/i.test(button.getAttribute('aria-label') || ''));
        const likeText = likeButton?.querySelector('.pkWtMe')?.textContent?.trim() || likeButton?.textContent?.trim() || '';
        const likes = /^\d+$/.test(likeText) ? Number(likeText) : null;
        return [{author, id: card.getAttribute('data-review-id') || card.querySelector('[data-review-id]')?.getAttribute('data-review-id'),
            avatar, likes, rating: stars ? Number(stars[1]) : null,
            text: text(card, '.wiI7pd'), time: text(card, '.rsqaWe'), photos: [...new Set(photos)].slice(0, 16)}];
    });
    const stars = new Map();
    for (const element of document.querySelectorAll('tr[aria-label]')) {
        const match = element.getAttribute('aria-label')?.match(/^\s*([1-5])\s+stars?[,\s]+([\d,\s]+)\s+reviews?\b/i);
        if (match) stars.set(Number(match[1]), Number(match[2].replace(/[^\d]/g, '')));
    }
    const histogram = [1,2,3,4,5].map(score => stars.get(score) ?? null);
    return {reviews, histogram: histogram.every(count => count !== null) ? histogram : []};
})();"""
