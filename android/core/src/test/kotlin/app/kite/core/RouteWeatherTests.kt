package app.kite.core

import kotlin.test.*

class RouteWeatherTests {
    @Test fun lookupUsesTheArrivalTimeAtEachSample() {
        val a = RouteWeather.Sample(0.0, 3_600_000, LatLng(49.6, 17.2), false)
        val b = RouteWeather.Sample(13_000.0, 7_200_000, LatLng(49.7, 17.3), true)
        val fixture = """[
            {"hourly":{"time":[3600,7200],"temperature_2m":[3,4],"weather_code":[3,51],"precipitation_probability":[10,25]}},
            {"hourly":{"time":[3600,7200],"temperature_2m":[1,2],"weather_code":[61,71],"precipitation_probability":[30,70]}}
        ]""".toByteArray()
        val results = RouteWeather.read(fixture, listOf(a, b))
        assertEquals(listOf(3, 2), results.map { it.celsius })
        assertEquals(listOf(10, 70), results.map { it.rainChance })
        assertTrue(results.last().sample.arrival)
        assertTrue(RouteWeather.read(fixture, listOf(a)).isEmpty())
    }
}
