package com.example.stocktracker

import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Result(val price: Double?, val inStock: Boolean?, val error: String? = null)

object Checker {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    fun retailer(url: String): String {
        val h = url.lowercase()
        return when {
            "amazon." in h || "amzn." in h || "a.co/" in h -> "Amazon"
            "target.com" in h -> "Target"
            "pokemoncenter.com" in h -> "Pokémon Center"
            "walmart.com" in h -> "Walmart"
            else -> "Other"
        }
    }

    fun check(url: String): Result {
        // Target and Amazon benefit from a real JavaScript-capable browser session.
        // This also resolves Amazon a.co short links before parsing the final page.
        return when (retailer(url)) {
            "Target", "Amazon" -> {
                val web = WebChecker.check(url)
                if (web.price != null || web.inStock != null || web.error != null) web
                else checkHttp(url)
            }
            else -> checkHttp(url)
        }
    }

    private fun checkHttp(url: String): Result = try {
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9").build()
        client.newCall(req).execute().use { r ->
            val html = r.body?.string().orEmpty()
            val finalUrl = r.request.url.toString()
            val site = retailer(finalUrl)
            when {
                r.code == 404 -> Result(null, null, "Page not found (404)")
                r.code in listOf(403, 429, 503) || isBotWall(html) ->
                    Result(null, null, "Blocked by retailer bot protection (HTTP ${r.code})")
                !r.isSuccessful -> Result(null, null, "HTTP ${r.code}")
                else -> when (site) {
                    "Amazon" -> parseAmazon(html)
                    "Target" -> parseTarget(html)
                    "Walmart" -> parseWalmart(html)
                    "Pokémon Center" -> parsePokemonCenter(html)
                    else -> parseGeneric(html)
                }
            }
        }
    } catch (e: Exception) { Result(null, null, e.message ?: "Network error") }

    private fun isBotWall(h: String): Boolean {
        val l = h.lowercase()
        return h.length < 30000 && ("robot or human" in l || "captcha" in l ||
            "access denied" in l || "px-captcha" in l || "are you a human" in l ||
            "request blocked" in l)
    }

    // Recursively visits every JSONObject embedded in a JSONObject/JSONArray.
    // Kept as a member of Checker so calls from parseTarget() compile on all
    // Kotlin versions used by GitHub Actions.
    internal fun walkJsonObjects(value: Any?, visitor: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                visitor(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walkJsonObjects(value.opt(key), visitor)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJsonObjects(value.opt(i), visitor)
                }
            }
        }
    }

    private fun scriptJson(html: String, idOrVarNames: List<String>): List<Any> {
        val out = mutableListOf<Any>()
        for (name in idOrVarNames) {
            Regex("id=\"${Regex.escape(name)}\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.let { m -> try { out += JSONTokener(m.groupValues[1].trim()).nextValue() } catch (_: Exception) {} }
            Regex("${Regex.escape(name)}\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.let { m -> try { out += JSONTokener(m.groupValues[1].trim()).nextValue() } catch (_: Exception) {} }
        }
        return out
    }

    private fun jsonLdOffers(html: String): Pair<Double?, Boolean?> {
        var price: Double? = null; var stock: Boolean? = null
        Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                try {
                    walkJsonObjects(JSONTokener(m.groupValues[1].trim()).nextValue()) { o ->
                        val offers = o.opt("offers") ?: return@walkJsonObjects
                        val list = if (offers is JSONArray)
                            (0 until offers.length()).map { offers.optJSONObject(it) }
                        else listOf(offers as? JSONObject)
                        list.filterNotNull().forEach { of ->
                            val p = (of.opt("price") ?: of.opt("lowPrice"))?.toString()
                                ?.replace(",", "")?.toDoubleOrNull()
                            if (p != null && price == null) price = p
                            val av = of.optString("availability")
                            if (av.isNotEmpty()) stock = (stock ?: false) ||
                                av.contains("InStock", true) || av.contains("LimitedAvailability", true)
                            if (av.contains("OutOfStock", true) || av.contains("Discontinued", true)) stock = false
                        }
                    }
                } catch (_: Exception) {}
            }
        return price to stock
    }

    private fun metaPrice(html: String): Double? =
        Regex("property=\"product:price:amount\"\\s+content=\"([\\d.,]+)\"")
            .find(html)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    internal fun parseAmazon(html: String): Result {
        var price: Double? = null
        val priceRegexes = listOf(
            "class=\"a-offscreen\">\\$([\\d,]+\\.\\d{2})<",
            "\"priceAmount\"\\s*:\\s*([\\d.]+)",
            "id=\"priceblock_ourprice\"[^>]*>\\s*\\$([\\d,]+\\.\\d{2})",
            "id=\"priceblock_dealprice\"[^>]*>\\s*\\$([\\d,]+\\.\\d{2})"
        )
        for (rx in priceRegexes) {
            price = Regex(rx).find(html)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            if (price != null) break
        }
        val avail = Regex("id=\"availability\"[\\s\\S]{0,400}?<span[^>]*>\\s*([^<]{2,60})</span>")
            .find(html)?.groupValues?.get(1)?.trim()?.lowercase()
        val stock = when {
            avail == null -> {
                val l = html.lowercase()
                when {
                    "currently unavailable" in l -> false
                    "id=\"add-to-cart-button\"" in l -> true
                    else -> null
                }
            }
            "unavailable" in avail || "out of stock" in avail -> false
            "in stock" in avail || "available" in avail || "get it as soon" in avail -> true
            else -> null
        }
        if (price == null && stock == null)
            return Result(null, null, "Couldn't read this Amazon page")
        return Result(price, stock)
    }

    internal fun parseTarget(html: String): Result {
        var price: Double? = null; var stock: Boolean? = null
        for (blob in scriptJson(html, listOf("__TGT_DATA__", "__NEXT_DATA__", "__PRELOADED_STATE__"))) {
            walkJsonObjects(blob) { o ->
                for (key in listOf("current_retail", "formatted_current_price", "reg_retail")) {
                    if (price == null && o.has(key)) {
                        o.opt(key)?.toString()?.replace("$", "")?.replace(",", "")
                            ?.toDoubleOrNull()?.let { price = it }
                    }
                }
                for (key in listOf("availability_status", "availability")) {
                    if (o.has(key)) {
                        val v = o.optString(key).uppercase()
                        if ("OUT_OF_STOCK" in v) stock = false
                        else if ("IN_STOCK" in v || "AVAILABLE" == v) stock = true
                    }
                }
                if (o.has("is_out_of_stock"))
                    stock = if (o.optBoolean("is_out_of_stock")) false else (stock ?: true)
            }
        }
        val (ldPrice, ldStock) = jsonLdOffers(html)
        price = price ?: ldPrice ?: metaPrice(html)
        stock = stock ?: ldStock
        if (price == null && stock == null)
            return Result(null, null, "Target page did not expose price/stock data")
        return Result(price, stock)
    }

    private fun parseWalmart(html: String): Result {
        var price: Double? = null; var stock: Boolean? = null
        for (blob in scriptJson(html, listOf("__NEXT_DATA__", "__WML_REDUX_INITIAL_STATE__"))) {
            walkJsonObjects(blob) { o ->
                if (price == null && o.has("currentPrice")) {
                    val cp = o.opt("currentPrice")
                    if (cp is JSONObject) cp.opt("price")?.toString()?.toDoubleOrNull()?.let { price = it }
                    else cp?.toString()?.toDoubleOrNull()?.let { price = it }
                }
                if (price == null && o.has("priceInfo"))
                    o.optJSONObject("priceInfo")?.optJSONObject("currentPrice")
                        ?.opt("price")?.toString()?.toDoubleOrNull()?.let { price = it }
                for (key in listOf("availabilityStatus", "stockStatus")) if (o.has(key)) {
                    val v = o.optString(key).uppercase()
                    if ("OUT_OF_STOCK" in v) stock = false
                    else if ("IN_STOCK" in v || "AVAILABLE" in v) stock = true
                }
            }
        }
        val (ldPrice, ldStock) = jsonLdOffers(html)
        price = price ?: ldPrice ?: metaPrice(html)
        stock = stock ?: ldStock
        if (price == null && stock == null)
            return Result(null, null, "Walmart page did not expose price/stock data")
        return Result(price, stock)
    }

    private fun parsePokemonCenter(html: String): Result {
        val (ldPrice, ldStock) = jsonLdOffers(html)
        var price = ldPrice ?: metaPrice(html)
        var stock = ldStock
        if (price == null) price = Regex("\"price\"\\s*:\\s*\"?([\\d]+\\.\\d{2})")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val l = html.lowercase()
        if (stock == null) stock = when {
            "sold out" in l || ("out of stock" in l && "notify me" in l) -> false
            "class=\"add-to-cart\"" in l && "disabled" !in l -> true
            else -> null
        }
        if (price == null && stock == null)
            return Result(null, null, "Couldn't read this Pokémon Center page")
        return Result(price, stock)
    }

    private fun parseGeneric(html: String): Result {
        val (ldPrice, ldStock) = jsonLdOffers(html)
        var price = ldPrice ?: metaPrice(html)
        var stock = ldStock
        val l = html.lowercase()
        if (stock == null) {
            val out = listOf("out of stock", "sold out", "currently unavailable",
                "temporarily out of stock").any { it in l }
            stock = if (out) false else null
        }
        if (price == null && stock == null) return Result(null, null, "Couldn't read price/stock from this page")
        return Result(price, stock)
    }
}

/**
 * Uses Android's real Chromium WebView so Target's React/Next.js application can
 * execute JavaScript before we inspect the rendered DOM. It also resolves a.co
 * Amazon short links and then parses the final rendered Amazon page.
 */
private object WebChecker {
    private const val TIMEOUT_MS = 45000L
    private val main = Handler(Looper.getMainLooper())

    fun check(url: String): Result {
        val latch = CountDownLatch(1)
        var result = Result(null, null, "Browser check timed out")
        main.post {
            val web = try { WebView(StockTrackerApp.context()) } catch (e: Exception) {
                result = Result(null, null, "Android WebView unavailable: ${e.message}")
                latch.countDown()
                return@post
            }
            val settings = web.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = false
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            web.webChromeClient = WebChromeClient()
            var finished = false
            fun done(r: Result) {
                if (finished) return
                finished = true
                result = r
                try { web.stopLoading(); web.destroy() } catch (_: Exception) {}
                latch.countDown()
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    // Amazon and Target populate important fields asynchronously.  Do not
                    // take a single snapshot 1.8s after load; poll the rendered DOM for
                    // useful product data for several seconds instead.
                    val started = System.currentTimeMillis()
                    fun inspect() {
                        if (finished) return
                        val js = """
                            (function() {
                              function txt(el) { return el ? (el.innerText || el.textContent || '').trim() : ''; }
                              function first(selectors) {
                                for (const s of selectors) {
                                  try {
                                    const e = document.querySelector(s);
                                    const t = txt(e);
                                    if (t) return t;
                                  } catch(e) {}
                                }
                                return '';
                              }
                              function all(selectors) {
                                const out=[];
                                for (const s of selectors) {
                                  try { document.querySelectorAll(s).forEach(e => { const t=txt(e); if(t) out.push(t); }); } catch(e) {}
                                }
                                return out;
                              }
                              const u=location.href.toLowerCase();
                              const amazonPrice = first([
                                '#corePriceDisplay_desktop_feature_div .a-price .a-offscreen',
                                '#corePrice_feature_div .a-price .a-offscreen',
                                '#apex_desktop .a-price .a-offscreen',
                                '#buybox .a-price .a-offscreen',
                                '#priceblock_ourprice', '#priceblock_dealprice',
                                '#price_inside_buybox', '#newBuyBoxPrice',
                                '[data-a-color="price"] .a-offscreen',
                                '.a-price .a-offscreen'
                              ]);
                              const targetPrice = first([
                                '[data-test="product-price"]',
                                '[data-test="currentPrice"]',
                                '[data-test="productPrice"]',
                                '[data-test="item-price"]',
                                '[data-test="price"]',
                                '[class*="ProductPrice"]',
                                '[class*="product-price"]'
                              ]);
                              const targetPrices = all([
                                '[data-test="product-price"]', '[data-test="currentPrice"]',
                                '[data-test="productPrice"]', '[data-test="item-price"]'
                              ]);
                              const amazonPrices = all([
                                '#corePriceDisplay_desktop_feature_div .a-price .a-offscreen',
                                '#corePrice_feature_div .a-price .a-offscreen',
                                '#price_inside_buybox', '#newBuyBoxPrice',
                                '[data-a-color="price"] .a-offscreen'
                              ]);
                              const stockSignals = all([
                                '#availability', '#availability_feature_div',
                                '#buybox', '[data-test="shippingBlock"]',
                                '[data-test="fulfillment-messaging"]',
                                '[data-test="orderPickup"]', '[data-test="shipIt"]'
                              ]);
                              const targetData = Array.from(document.scripts || [])
                                .filter(s => { const id=(s.id||'').toLowerCase(); return id.includes('__tgt_data__') || id.includes('__next_data__') || id.includes('__preloaded_state__'); })
                                .map(s => s.textContent || '')
                                .filter(Boolean);
                              const jsonLd = Array.from(document.querySelectorAll('script[type="application/ld+json"]'))
                                .map(s => s.textContent || '').filter(Boolean);
                              const metaPrice = first(['meta[property="product:price:amount"]','meta[itemprop="price"]']);
                              const h1 = first(['h1']);
                              return JSON.stringify({
                                title:h1,
                                body:document.body?.innerText || '',
                                url:location.href,
                                amazonPrice:amazonPrice,
                                amazonPrices:amazonPrices,
                                targetPrice:targetPrice,
                                targetPrices:targetPrices,
                                stockSignals:stockSignals,
                                targetData:targetData,
                                jsonLd:jsonLd,
                                metaPrice:metaPrice,
                                ready:document.readyState
                              });
                            })()
                        """.trimIndent()
                        view.evaluateJavascript(js) { raw ->
                            val payload = try {
                                val decoded = JSONTokener(raw ?: "\"\"").nextValue() as? String ?: ""
                                JSONObject(decoded)
                            } catch (_: Exception) { null }
                            if (payload == null) {
                                done(Result(null, null, "Couldn't read rendered page"))
                                return@evaluateJavascript
                            }
                            val finalUrl = payload.optString("url", loadedUrl)
                            val title = payload.optString("title")
                            val body = payload.optString("body")
                            val amazonPrice = payload.optString("amazonPrice")
                            val targetPrice = payload.optString("targetPrice")
                            val targetPrices = jsonArrayStrings(payload.optJSONArray("targetPrices"))
                            val amazonPrices = jsonArrayStrings(payload.optJSONArray("amazonPrices"))
                            val stockSignals = jsonArrayStrings(payload.optJSONArray("stockSignals"))
                            val targetData = jsonArrayStrings(payload.optJSONArray("targetData"))
                            val jsonLd = jsonArrayStrings(payload.optJSONArray("jsonLd"))
                            val metaPrice = payload.optString("metaPrice")
                            val lowerUrl = finalUrl.lowercase()
                            val isAmazon = lowerUrl.contains("amazon.") || lowerUrl.contains("amzn.")
                            val isTarget = lowerUrl.contains("target.com")

                            val r = if (isTarget) {
                                parseRenderedTarget(title, body, targetPrice, targetPrices, stockSignals, targetData, jsonLd, metaPrice)
                            } else if (isAmazon) {
                                parseRenderedAmazon(title, body, amazonPrice, amazonPrices, stockSignals)
                            } else {
                                Result(null, null, "Short link did not resolve to Amazon")
                            }

                            // Keep waiting if the page is still hydrating and neither a
                            // price nor stock state is available yet.
                            if (r.price == null && r.inStock == null &&
                                System.currentTimeMillis() - started < 10000L) {
                                main.postDelayed({ inspect() }, 1000L)
                            } else {
                                done(r)
                            }
                        }
                    }
                    main.post { inspect() }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame)
                        done(Result(null, null, "Web page load error: ${error.description}"))
                }
            }
            web.loadUrl(url)
        }
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                main.post { /* callback may already have destroyed the WebView */ }
                return Result(null, null, "Browser check timed out")
            }
        } catch (_: InterruptedException) {
            return Result(null, null, "Browser check interrupted")
        }
        return result
    }

    private fun jsonArrayStrings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            a.optString(i, "").takeIf { it.isNotBlank() }
        }

    private fun moneyFromText(text: String): Double? {
        val normalized = text.replace('\u00a0', ' ')
        return Regex("\\$\\s*([0-9]{1,4}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)")
            .find(normalized)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }

    private fun clean(s: String): String = s.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun parseRenderedTarget(
        titleRaw: String,
        bodyRaw: String,
        selectorPrice: String,
        selectorPrices: List<String>,
        stockSignals: List<String>,
        targetData: List<String>,
        jsonLd: List<String>,
        metaPriceRaw: String
    ): Result {
        val body = clean(bodyRaw)
        val title = clean(titleRaw)

        // Target's product price and availability appear immediately after the H1.
        // Restrict parsing to that narrow product section so recommendation cards cannot
        // supply a different price or "In Stock" state.
        val titlePos = if (title.isNotBlank()) body.indexOf(title, ignoreCase = true) else -1
        val productStart = if (titlePos >= 0) titlePos else body.indexOf("New at Target", ignoreCase = true)
        val productText = if (productStart >= 0) {
            val endCandidates = listOf(
                body.indexOf("About this item", productStart, ignoreCase = true),
                body.indexOf("Shipping & Returns", productStart, ignoreCase = true),
                body.indexOf("Find alternative", productStart, ignoreCase = true)
            ).filter { it > productStart }
            val end = endCandidates.minOrNull() ?: minOf(body.length, productStart + 1400)
            body.substring(productStart, end)
        } else body.take(1400)

        // First choice is the product section itself. On Target pages tested here the
        // price appears directly after the title and before the stock state.
        var price = moneyFromText(productText)
        if (price == null) price = moneyFromText(selectorPrice)
        if (price == null) price = selectorPrices.asSequence()
            .mapNotNull { moneyFromText(it) }.firstOrNull()

        // JSON-LD / meta price fallback.
        if (price == null) {
            for (blob in jsonLd) {
                val m = Regex("\"price\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)").find(blob)
                if (m != null) { price = m.groupValues[1].toDoubleOrNull(); if (price != null) break }
            }
        }
        if (price == null) price = moneyFromText(metaPriceRaw)

        // Only the product section is authoritative for visible stock. The previous
        // 5,000-character window included recommendation cards and falsely returned
        // "In Stock" for out-of-stock products.
        val combined = clean((stockSignals + listOf(productText)).joinToString(" ")).lowercase()
        val stock = when {
            Regex("\\b(out of stock|sold out|currently unavailable|unavailable)\\b").containsMatchIn(combined) -> false
            Regex("\\b(in stock|add to cart|ship it|deliver it|pickup|pick it up|same day delivery)\\b")
                .containsMatchIn(combined) -> true
            else -> null
        }

        // Embedded Target JSON is a fallback only when the visible product section has
        // no explicit stock state.
        var jsonStock = stock
        if (jsonStock == null) {
            for (blob in targetData) {
                if (jsonStock != null) break
                try {
                    val obj = JSONObject(blob)
                    Checker.walkJsonObjects(obj) { o ->
                        if (jsonStock != null) return@walkJsonObjects
                        for (key in listOf("availability_status", "availability", "stockStatus", "fulfillmentStatus")) {
                            if (o.has(key)) {
                                val v = o.opt(key)?.toString()?.uppercase() ?: ""
                                if ("OUT_OF_STOCK" in v || "SOLD_OUT" in v || "UNAVAILABLE" in v) jsonStock = false
                                else if ("IN_STOCK" in v || "AVAILABLE" in v || "SELLABLE" in v) jsonStock = true
                            }
                        }
                        if (o.has("is_out_of_stock")) jsonStock = !o.optBoolean("is_out_of_stock")
                    }
                } catch (_: Exception) {}
            }
        }

        if (price == null && jsonStock == null) {
            return Result(null, null, if (combined.contains("captcha") || combined.contains("verify"))
                "Target presented a verification page" else "Target rendered, but price/stock could not be found")
        }
        return Result(price, jsonStock)
    }

    private fun parseRenderedAmazon(
        titleRaw: String,
        bodyRaw: String,
        selectorPrice: String,
        selectorPrices: List<String>,
        stockSignals: List<String>
    ): Result {
        val body = clean(bodyRaw)
        val lower = body.lowercase()

        // Prefer Amazon's dedicated price nodes. Generic page text contains many
        // unrelated prices (recommended products, coupons, shipping, etc.).
        var price = moneyFromText(selectorPrice)
        if (price == null) price = selectorPrices.asSequence().mapNotNull { moneyFromText(it) }.firstOrNull()
        if (price == null) {
            val title = clean(titleRaw)
            val titlePos = if (title.isNotBlank()) body.indexOf(title, ignoreCase = true) else -1
            if (titlePos >= 0) {
                val section = body.substring(titlePos, minOf(body.length, titlePos + 3500))
                price = moneyFromText(section)
            }
        }

        val stockText = clean((stockSignals + listOf(body)).joinToString(" ")).lowercase()
        val stock = when {
            "pre-order now" in stockText || "preorder now" in stockText ||
                "pre-order" in stockText || "preorder" in stockText -> true
            "currently unavailable" in stockText || "out of stock" in stockText -> false
            "in stock" in stockText || "add to cart" in stockText || "buy now" in stockText -> true
            else -> null
        }

        if (price == null && stock == null) {
            return Result(null, null, if (lower.contains("robot check") || lower.contains("captcha") || lower.contains("automated access"))
                "Amazon presented a verification page" else "Couldn't read rendered Amazon price/stock")
        }
        return Result(price, stock)
    }

}

/** Application context holder used by the background WebView checker. */
class StockTrackerApp : android.app.Application() {
    override fun onCreate() { super.onCreate(); instance = this }
    companion object {
        private lateinit var instance: StockTrackerApp
        fun context(): android.content.Context = instance.applicationContext
    }
}
