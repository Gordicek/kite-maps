package app.kite.core

import java.net.URI

data class PlaceReview(
    val author: String, val stars: Int?, val relativeTime: String?, val text: String?, val photos: List<String>,
    val avatar: String? = null, val likes: Int? = null, val publishedMs: Long? = null, val id: String? = null,
)
data class PlaceReviewPage(val reviews: List<PlaceReview>, val nextToken: String? = null)
data class PublicPlaceReviews(val reviews: List<PlaceReview>, val histogram: List<Int> = emptyList())

/** Place detail response shapes from Vela Maps' Google client (GPL-3.0). */
object GooglePlaceMedia {
    private val featureIdPattern = Regex("^0x([0-9a-fA-F]{1,16}):0x([0-9a-fA-F]{1,16})$")
    private val size = Regex("=(?:[swh]\\d[\\w-]*|k-no)$")

    fun cid(featureId: String): String? = featureIdPattern.matchEntire(featureId)
        ?.groupValues?.get(2)?.toULong(16)?.toString()

    fun reviewParameter(featureId: String): String? {
        val match = featureIdPattern.matchEntire(featureId) ?: return null
        val high = match.groupValues[1].toULong(16)
        val low = match.groupValues[2].toULong(16)
        return "!1m2!1y$high!2y$low!2m2!2i0!3i20!3e1!5m2!1skite!7e81"
    }

    fun photoRequest(featureId: String): String? {
        if (!featureIdPattern.matches(featureId)) return null
        val inner = """[2,null,["$featureId",null,null,null,null,null,null,null,0],null,[null,[1200,1000],[null,30,null,null,1],null,null,null,[[[1,0,3],[2,1,2],[2,0,3],[8,0,3],[10,0,3],[10,1,2],[10,0,4],[9,1,2]],1],null,0],null,null,null,null,null,null,null,null,null,null,[null,1,null,1]]"""
        return Json.write(listOf(listOf(listOf("hspqX", inner, null, "generic"))))
    }

    fun reviews(root: Any?): List<PlaceReview> = arr(at(root, 2)).orEmpty().mapNotNull { row ->
        val author = gstr(at(row, 0, 1))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PlaceReview(author, gint(at(row, 4))?.takeIf { it in 1..5 }, gstr(at(row, 1)),
            gstr(at(row, 3))?.takeIf { it.isNotBlank() }, imageUrls(row, limit = 8),
            avatar = profilePhoto(gstr(at(row, 0, 2))))
    }

    fun reviewFeedRequest(featureId: String, token: String = ""): String? {
        if (!featureIdPattern.matches(featureId)) return null
        if (token.length > 2000 || token.any { !it.isLetterOrDigit() && it !in "-_=" }) return null
        val inner = """[[["$featureId"],null,null,null,null,[null,null,null,[[1],[3]]]],[10,"$token"],null,null,[null,null,null,null,null,null,81],null,null,[null,1,1,null,1,null,1,null,null,null,null,[1,1,null,[[1]]]],null,null,[3,1,null,null,null,[2]],null,[1]]"""
        return Json.write(listOf(listOf(listOf("qv9Egd", inner, null, "generic"))))
    }

    private fun batchPayload(body: String, rpc: String): Any? =
        body.lineSequence().filter { it.trimStart().startsWith("[[") }
            .flatMap { arr(Json.parse(it)).orEmpty().asSequence() }
            .firstOrNull { at(it, 0) == "wrb.fr" && at(it, 1) == rpc }
            ?.let { (at(it, 2) as? String)?.let(Json::parse) }

    fun reviewFeed(body: String): List<PlaceReview> = reviewPage(body).reviews

    fun reviewPage(body: String): PlaceReviewPage {
        val payload = batchPayload(body, "qv9Egd")
        val next = gstr(at(payload, 1))?.takeIf { it.isNotBlank() && at(payload, 5) != true }
        val reviews = arr(at(payload, 2)).orEmpty().mapNotNull { entry ->
            val review = at(entry, 0)
            val author = gstr(at(review, 1, 4, 5, 0)) ?: return@mapNotNull null
            val epoch = at(review, 1, 2).long()?.takeIf { it in 946_684_800_000_000L..4_133_980_800_000_000L }
            PlaceReview(author, gint(at(review, 2, 0, 0))?.takeIf { it in 1..5 }, gstr(at(review, 1, 6)),
                gstr(at(review, 2, 15, 0, 0))?.takeIf { it.isNotBlank() }, imageUrls(at(review, 2, 2), limit = 8),
                avatar = profilePhoto(gstr(at(review, 1, 4, 5, 1))), publishedMs = epoch?.div(1000),
                id = gstr(at(review, 0)))
        }
        return PlaceReviewPage(reviews, next)
    }

    fun publicPageReviews(value: String): List<PlaceReview> = publicReviewDetails(value).reviews

    fun publicReviewDetails(value: String): PublicPlaceReviews {
        val decoded = Json.parse(value).let { if (it is String) Json.parse(it) else it }
        val objectValue = decoded.asMap()
        val rows = (objectValue?.get("reviews") ?: decoded).asMaps().orEmpty()
        val histogram = objectValue?.get("histogram").asList().orEmpty().mapNotNull { it.int()?.takeIf { n -> n >= 0 } }
            .takeIf { it.size == 5 && it.sumOf(Int::toLong) > 0 }.orEmpty()
        val reviews = rows.take(100).mapNotNull { item ->
            val author = item["author"].text()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val stars = item["rating"].int()?.takeIf { it in 1..5 }
            val text = item["text"].text()?.trim()?.takeIf(String::isNotEmpty)
            val date = item["time"].text()?.trim()?.takeIf(String::isNotEmpty)
            val photos = imageUrls(item["photos"], limit = 8)
            if (stars == null && text == null && photos.isEmpty()) return@mapNotNull null
            PlaceReview(author, stars, date, text, photos, profilePhoto(item["avatar"].text()),
                item["likes"].int()?.takeIf { it >= 0 }, id = item["id"].text())
        }
        return PublicPlaceReviews(reviews.distinct(), histogram)
    }

    fun popularTimes(node: Any?): List<BusyDay> = arr(at(node, 0)).orEmpty().mapNotNull { row ->
        val day = gint(at(row, 0))?.takeIf { it in 1..7 } ?: return@mapNotNull null
        val hours = arr(at(row, 1)).orEmpty().mapNotNull hourOfDay@ { cell ->
            val hour = gint(at(cell, 0))?.takeIf { it in 0..23 } ?: return@hourOfDay null
            val percent = gint(at(cell, 1))?.takeIf { it in 0..100 } ?: return@hourOfDay null
            BusyHour(hour, percent)
        }.distinctBy { it.hour }.sortedBy { it.hour }
        if (hours.isEmpty()) null else BusyDay(day, hours)
    }.distinctBy { it.dayOfWeek }.sortedBy { it.dayOfWeek }

    private fun profilePhoto(value: String?): String? {
        val url = value?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return url.takeIf { uri.scheme == "https" && uri.userInfo == null &&
            (host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")) &&
            uri.path.orEmpty().startsWith("/a") }
    }

    fun photos(body: String): List<String> = imageUrls(at(batchPayload(body, "hspqX"), 0), limit = 40)

    fun imageUrls(root: Any?, limit: Int): List<String> = strings(root).mapNotNull { value ->
        val uri = if (value.startsWith("//")) "https:$value" else value
        if (!uri.startsWith("https://")) return@mapNotNull null
        val url = runCatching { URI(uri) }.getOrNull() ?: return@mapNotNull null
        val host = url.host ?: return@mapNotNull null
        if (!host.endsWith(".googleusercontent.com") && !host.endsWith(".ggpht.com")) return@mapNotNull null
        val path = url.path ?: return@mapNotNull null
        if (!listOf("/p/", "/gps-cs", "/grass-cs/", "/geougc", "/places/").any(path::startsWith)) return@mapNotNull null
        val base = uri.substringBefore('?').replace(size, "")
        "$base=w800-h600"
    }.distinct().take(limit).toList()

    fun streetViewHint(root: Any?): StreetView.Hint? = strings(root).mapNotNull { value ->
        if (!value.contains("streetviewpixels-pa.googleapis.com/v1/thumbnail?")) return@mapNotNull null
        val url = runCatching { URI(if (value.startsWith("//")) "https:$value" else value) }.getOrNull()
            ?: return@mapNotNull null
        if (url.host != "streetviewpixels-pa.googleapis.com") return@mapNotNull null
        val query = url.query?.split('&').orEmpty().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val id = query["panoid"]?.takeIf(StreetView::validId) ?: return@mapNotNull null
        StreetView.Hint(id, query["yaw"]?.toDoubleOrNull()?.takeIf(Double::isFinite))
    }.firstOrNull()

    private fun strings(root: Any?): Sequence<String> = sequence {
        when (root) {
            is String -> yield(root)
            is List<*> -> root.forEach { yieldAll(strings(it)) }
        }
    }
}
