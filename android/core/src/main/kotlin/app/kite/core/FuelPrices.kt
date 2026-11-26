package app.kite.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader
import java.util.Locale
import java.util.zip.GZIPInputStream

object FuelPrices {
    data class Quote(val labels: List<String>, val updatedSeconds: Long)
    internal data class Station(val position: LatLng, val quote: Quote)

    private const val GB_FEED = "https://github.com/PimpinPumpkin/Vela/releases/download/fuel-gb/fuel-gb.csv.gz"
    private const val MAX_AGE = 7 * 24 * 60 * 60L
    private val lock = Mutex()
    private var checkedAt = 0L
    private var stations = emptyList<Station>()
    private val csvComma = Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
    private val currency = Regex("""(?:[$€£]\s*\d|\d[\d.,]*\s*(?:Kč|CZK|€|¢|p\s*/))""", RegexOption.IGNORE_CASE)

    internal fun googleLabel(value: String?): String? =
        value?.replace('\u00a0', ' ')?.replace('\u202f', ' ')?.trim()?.takeIf { it.length in 3..35 && currency.containsMatchIn(it) }

    fun isStation(place: Place): Boolean = place.fuelStation || OutdoorData.kind(place.category.orEmpty()) == "fuel" ||
        place.category?.lowercase()?.let { it == "gas station" || it == "petrol station" || it == "fuel station" } == true

    private fun nearBritain(p: LatLng) = p.lat in 49.7..61.0 && p.lng in -8.3..2.0

    /** Pick the main listing at each forecourt and order the stations by distance. */
    fun rankedStations(places: List<Place>, origin: LatLng): List<Place> {
        fun brand(p: Place) = Place.fold(p.name).replace(Regex("[^a-z0-9]+"), " ").trim()
            .removePrefix("cerpaci stanice ").removePrefix("benzinka ").removeSuffix(" express").trim()
        val primary = mutableListOf<Place>()
        val ordered = places.filterNot { it.permanentlyClosed }.sortedWith(
            compareByDescending<Place> { it.openNow == true }
                .thenByDescending { it.fuelPrice != null }
                .thenByDescending { it.source == "google" }
                .thenByDescending { it.reviewCount ?: 0 })
        for (place in ordered) {
            if (primary.none {
                val distance = it.location.distanceTo(place.location)
                it.id == place.id || it.featureId != null && it.featureId == place.featureId ||
                    distance < 20 && isStation(it) && isStation(place) ||
                    distance < 85 && brand(it).isNotBlank() && brand(it) == brand(place)
            }) primary += place
        }
        return primary.sortedBy { it.location.distanceTo(origin) }
    }

    suspend fun ukQuotes(places: List<Place>): Map<String, Quote> {
        val candidates = places.filter { it.fuelPrice == null && isStation(it) && nearBritain(it.location) }
        if (candidates.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            val data = lock.withLock {
                if (now - checkedAt >= if (stations.isEmpty()) 15 * 60 else 3 * 60 * 60) {
                    val incoming = try {
                        val response = Http.get(GB_FEED)
                        if (!response.ok) emptyList() else GZIPInputStream(response.body.inputStream()).bufferedReader().use(::readStations)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (incoming.isNotEmpty()) stations = incoming
                    checkedAt = now
                }
                stations
            }
            candidates.mapNotNull { place -> match(place.location, data, now)?.let { place.id to it } }.toMap()
        }
    }

    internal fun match(point: LatLng, stations: List<Station>, now: Long): Quote? {
        val nearby = stations.mapNotNull { station ->
            if (kotlin.math.abs(point.lat - station.position.lat) > 0.001 ||
                kotlin.math.abs(point.lng - station.position.lng) > 0.002) return@mapNotNull null
            val distance = point.distanceTo(station.position)
            if (distance <= 75) station to distance else null
        }.sortedBy { it.second }
        val (best, distance) = nearby.firstOrNull() ?: return null
        // Some divided highways have a forecourt on each side.
        if (nearby.getOrNull(1)?.let { it.second - distance < 25 } == true) return null
        return best.quote.takeIf { now - it.updatedSeconds in -3600..MAX_AGE }
    }

    internal fun readStations(reader: Reader): List<Station> {
        val csv = reader.buffered()
        val columns = csv.readLine()?.split(',').orEmpty()
        if (!columns.containsAll(listOf("lat", "lng", "e10", "updated"))) return emptyList()
        val indices = columns.withIndex().associate { it.value to it.index }
        return csv.lineSequence().take(20_000).mapNotNull { line ->
            val fields = line.split(csvComma)
            fun field(key: String) = indices[key]?.let(fields::getOrNull)?.trim()?.trim('"')
            val lat = field("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = field("lng")?.toDoubleOrNull() ?: return@mapNotNull null
            val updated = field("updated")?.toLongOrNull() ?: return@mapNotNull null
            val position = LatLng(lat, lng)
            if (!nearBritain(position)) return@mapNotNull null
            val values = listOf("e10" to "E10", "e5" to "E5", "b7s" to "Diesel", "b7p" to "Premium diesel").mapNotNull fuel@{ (column, label) ->
                val price = field(column)?.toDoubleOrNull()?.takeIf { it in 60.0..450.0 } ?: return@fuel null
                val formatted = String.format(Locale.UK, "%.1f", price).removeSuffix(".0")
                "$label $formatted p/L"
            }
            if (values.isEmpty()) null else Station(position, Quote(values, updated))
        }.toList()
    }
}
