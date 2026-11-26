package app.kite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapboxDirectionsTests {
    private val fixture = """
        {
          "code": "Ok",
          "routes": [{
            "geometry": "??o}@?o}@?o}@?",
            "distance": 333.6,
            "duration": 45,
            "duration_typical": 40,
            "legs": [
              {
                "annotation": {"congestion": ["low"], "maxspeed": [{"speed": 30, "unit": "mph"}]},
                "steps": [
                  {"name": "First Road", "distance": 111.2, "maneuver": {"type": "depart", "location": [0, 0]}},
                  {"distance": 0, "maneuver": {"type": "arrive", "location": [0, 0.001]}}
                ]
              },
              {
                "annotation": {"congestion": ["heavy", "heavy"], "maxspeed": [{"speed": 30, "unit": "mph"}, {"unknown": true}]},
                "steps": [
                  {"name": "First Road", "distance": 111.2, "maneuver": {"type": "depart", "location": [0, 0.001]}},
                  {"name": "Second Road", "distance": 111.2, "maneuver": {
                    "type": "turn", "modifier": "left", "location": [0, 0.002], "instruction": "Bear left onto Second Road"
                  }, "intersections": [{"lanes": [
                    {"indications": ["left"], "valid": true}, {"indications": ["straight"], "valid": false}
                  ]}]},
                  {"distance": 0, "maneuver": {"type": "arrive", "location": [0, 0.003]}}
                ]
              }
            ]
          }]
        }
    """.trimIndent()

    @Test fun usesTrafficInstructionsAndLaneDataAcrossStops() {
        val route = MapboxDirections.parse(fixture.toByteArray()).single()
        val turn = route.maneuvers[1]
        assertEquals("mapbox", route.source)
        assertEquals(45.0, route.durationSeconds)
        assertEquals(40.0, route.typicalSeconds)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), route.maneuvers.map { it.type })
        assertEquals("Bear left onto Second Road", turn.instruction)
        assertEquals(listOf(true, false), turn.lanes.map { it.valid })
        assertEquals(222.4, turn.alongMeters, 0.1)
        assertEquals(30 * 1.609344, route.speedLimits.first().kmh)
        assertEquals(null, route.speedLimits.last().kmh)
        val span = route.trafficSpans.single()
        assertEquals(2, span.level)
        assertEquals(111.2, span.startMeters, 1.0)
        assertEquals(222.4, span.lengthMeters, 1.0)
    }

    @Test fun showsLaneChoicesAtASignalBetweenTwoManeuvers() {
        val data = fixture.replace(
            """"intersections": [{"lanes":""",
            """"intersections": [
                {"location": [0, 0.002]},
                {"location": [0, 0.0025], "traffic_signal": true, "lanes":"""
        )
        val route = MapboxDirections.parse(data.toByteArray()).single()
        assertTrue(route.maneuvers[1].lanes.isEmpty())
        val approach = route.laneApproaches.single()
        assertEquals(listOf(true, false), approach.lanes.map { it.valid })
        assertEquals(route.roadControls.single().along, approach.alongMeters, 1.0)
    }

    @Test fun startsWithRoadControlsFromTheChosenRoute() {
        val marked = fixture.replace(
            """"intersections": [{"lanes":""",
            """"intersections": [
                {"location": [0, 0.0015], "traffic_signal": true, "railway_crossing": true},
                {"location": [0, 0.0025], "stop_sign": true},
                {"lanes":"""
        )
        val controls = MapboxDirections.parse(marked.toByteArray()).single().roadControls
        assertEquals(listOf(Overpass.ControlKind.SIGNAL, Overpass.ControlKind.RAIL, Overpass.ControlKind.STOP),
            controls.map { it.kind })
        assertEquals(167.0, controls.first().along, 0.3)
    }

    @Test fun usesTheExplicitExitNumberOnTheRoundaboutEntryAndExit() {
        val route = MapboxDirections.parse(fixture
            .replace(""""type": "turn", "modifier": "left", "location": [0, 0.002], "instruction": "Bear left onto Second Road"""",
                """"type": "roundabout", "location": [0, 0.002]""")
            .replace("""{"distance": 0, "maneuver": {"type": "arrive", "location": [0, 0.003]}}""",
                """{"exit": 3, "distance": 0, "maneuver": {"type": "exit roundabout", "location": [0, 0.0029]}},
                   {"distance": 0, "maneuver": {"type": "arrive", "location": [0, 0.003]}}""")
            .toByteArray()).single()
        val circle = route.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }
        assertEquals(listOf(3, 3), circle.map { it.exit })
        assertTrue(circle.first().instruction.contains("3rd exit"))
        assertTrue(circle.last().leavesRoundabout)
    }

    @Test fun ignoresAnnotationsThatCannotBeAlignedToTheRoute() {
        val route = MapboxDirections.parse(fixture.replace("\"heavy\", \"heavy\"", "\"heavy\"")
            .replace(", {\"unknown\": true}", "").toByteArray()).single()
        assertTrue(route.trafficSpans.isEmpty())
        assertTrue(route.speedLimits.isEmpty())
    }

    @Test fun refreshesLongTrafficSpansWithoutDroppingLaterCongestion() {
        val line = (0..20).map { LatLng(0.0, it * 0.009) }
        val fresh = Route(TravelMode.DRIVE, line, Geo.length(line), 1300.0, 1500.0, null, mutableListOf(),
            trafficSpans = listOf(TrafficSpan(3, 3800.0, 4400.0)), source = "google")
        val previous = listOf(TrafficSpan(2, 17500.0, 500.0))
        val spans = requireNotNull(MapboxDirections.refreshTraffic(line, previous, fresh, 0.0))
        assertEquals(2, spans.size)
        assertEquals(3800.0, spans.first().startMeters, 1.0)
        assertEquals(4400.0, spans.first().lengthMeters, 1.0)
        assertEquals(previous.single(), spans.last())
    }

    @Test fun rejectsApiErrorsAndIncompleteTurnLists() {
        assertTrue(MapboxDirections.parse("""{"code":"NoRoute"}""".toByteArray()).isEmpty())
        assertTrue(MapboxDirections.parse(fixture.replace("\"arrive\"", "\"continue\"").toByteArray()).isEmpty())
    }
}
