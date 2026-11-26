package app.kite.core

import kotlin.math.abs
import kotlin.math.roundToInt

object RouteWeather {
    data class Sample(val distance: Double, val time: Long, val at: LatLng, val arrival: Boolean)
    data class Forecast(val sample: Sample, val celsius: Int, val rainChance: Int?, val code: Int)

    private val client = Http.client(9)

    internal fun samples(route: Route, departure: Long): List<Sample> {
        val line = route.polyline
        if (line.size < 2) return emptyList()
        val cumulative = Geo.cumulative(line)
        val total = cumulative.last()
        if (total <= 0.0) return emptyList()
        val legs = (total / 40_000).roundToInt().coerceIn(2, 6)
        return (0..legs).map { index ->
            val fraction = index.toDouble() / legs
            val distance = total * fraction
            Sample(distance, departure + (route.durationSeconds * fraction * 1000).toLong(),
                Geo.point(line, distance, cumulative), arrival = index == legs)
        }
    }

    suspend fun forecast(route: Route, departure: Long): List<Forecast>? {
        val schedule = samples(route, departure)
        if (schedule.isEmpty()) return emptyList()
        fun listCoordinate(select: (LatLng) -> Double) =
            schedule.joinToString(",") { String.format(java.util.Locale.ROOT, "%.4f", select(it.at)) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${listCoordinate { it.lat }}" +
            "&longitude=${listCoordinate { it.lng }}&timeformat=unixtime&timezone=GMT" +
            "&forecast_days=7&hourly=temperature_2m,precipitation_probability,weather_code"
        val response = Http.getOrNull(url, client = client)?.takeIf { it.ok } ?: return null
        return read(response.body, schedule)
    }

    internal fun read(data: ByteArray, schedule: List<Sample>): List<Forecast> {
        val rows = Json.parse(data).asList() ?: return emptyList()
        if (rows.size != schedule.size) return emptyList()
        return schedule.zip(rows).mapNotNull { (sample, result) ->
            val hourly = result.asMap()?.get("hourly").asMap() ?: return@mapNotNull null
            val times = hourly["time"].asList().orEmpty()
            val seconds = sample.time / 1000
            val nearest = times.indices.minByOrNull { abs((times[it].num() ?: -1.0) - seconds) } ?: return@mapNotNull null
            val epoch = times[nearest].num()?.toLong() ?: return@mapNotNull null
            if (abs(epoch - seconds) > 3600) return@mapNotNull null
            fun number(key: String) = hourly[key].asList()?.getOrNull(nearest).num()
            val degrees = number("temperature_2m")?.roundToInt() ?: return@mapNotNull null
            val code = number("weather_code")?.toInt() ?: return@mapNotNull null
            Forecast(sample, degrees, number("precipitation_probability")?.toInt(), code)
        }
    }
}
