package app.kite

import app.kite.core.LatLng
import app.kite.core.Place
import app.kite.core.Route
import app.kite.core.TravelMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test

class RoadStartTests {
    private val here = LatLng(50.07, 14.43)
    private val start = Place("start", "Start", LatLng(50.11, 14.43), "osm")
    private val via = Place("via", "Stop", LatLng(50.14, 14.43), "osm")
    private val end = Place("end", "Destination", LatLng(50.17, 14.43), "osm")
    private val direct = Route(TravelMode.DRIVE, listOf(here, end.location), 11_000.0, 900.0, null, null, mutableListOf(), source = "test")
    private val alternative = Route(TravelMode.DRIVE, listOf(here, via.location, end.location), 12_000.0, 950.0, null, null, mutableListOf(), source = "test")

    @Test fun goWaitsForGpsAndFollowsTheChosenDrivingRouteWhenItArrives() {
        val plan = DirectionsState(end, mode = TravelMode.DRIVE,
            routes = mapOf(TravelMode.DRIVE to listOf(direct, alternative)), selected = 1, startRequested = true)
        assertIs<RoadStart.WaitForLocation>(plan.roadStart(null))
        assertSame(alternative, assertIs<RoadStart.Follow>(plan.roadStart(here)).route)
    }

    @Test fun startUsesAShortRouteFromTheCurrentGpsInsteadOfAFarawayPreviewOrigin() {
        val plan = DirectionsState(end, mode = TravelMode.DRIVE, routes = mapOf(TravelMode.DRIVE to listOf(direct)))
        assertIs<RoadStart.FindRoute>(plan.roadStart(via.location))
        // The router may snap a fresh request to a remote access road.
        assertIs<RoadStart.Follow>(plan.copy(routeOrigins = mapOf(TravelMode.DRIVE to via.location)).roadStart(via.location))
    }

    @Test fun goCanBeRequestedBeforeTheRoadRouterHasAnswered() {
        val plan = DirectionsState(end, mode = TravelMode.DRIVE, startRequested = true)
        assertIs<RoadStart.FindRoute>(plan.roadStart(here))
        assertNull(plan.copy(transit = true).roadStart(here))
    }

    @Test fun savedPlansJoinTheExplicitStartAndPreserveStops() {
        val plan = DirectionsState(end, mode = TravelMode.DRIVE,
            routes = mapOf(TravelMode.DRIVE to listOf(direct)), start = start, stops = listOf(via))
        val action = assertIs<RoadStart.ConnectToPlan>(plan.roadStart(here))
        assertEquals(end, action.destination)
        assertEquals(listOf(start, via), action.stops)
        assertIs<RoadStart.Follow>(plan.roadStart(start.location))
    }

    @Test fun reversedPlansReturnToTheCurrentPositionAfterVisitingEveryPoint() {
        val plan = DirectionsState(end, mode = TravelMode.DRIVE,
            routes = mapOf(TravelMode.DRIVE to listOf(alternative)), reversed = true, stops = listOf(via, start))
        val action = assertIs<RoadStart.ConnectToPlan>(plan.roadStart(here))
        assertEquals(here, action.destination.location)
        assertEquals(listOf(end, start, via), action.stops)
    }

    @Test fun remotePlannerDoesNotUseThePhoneLocationUntilSelected() {
        val remote = DirectionsState(end, mode = TravelMode.DRIVE, useCurrentOrigin = false)
        assertNull(remote.endpoints(here).first)
        assertNull(remote.roadStart(here))
        assertEquals(start.location, remote.copy(start = start).endpoints(null).first)
        assertEquals(here, remote.copy(useCurrentOrigin = true).endpoints(here).first)
    }

    @Test fun changingDestinationKeepsTheOriginAndStopOrder() {
        val replacement = Place("new", "New destination", here, "osm")
        val plan = DirectionsState(end, start = start, stops = listOf(via), mode = TravelMode.DRIVE)
        assertEquals(listOf(start, via, replacement), plan.replacingDestination(replacement).orderedPlaces())
        val reversed = plan.copy(start = null, reversed = true, stops = listOf(via, start))
        assertEquals(listOf(end, start, via, replacement), reversed.replacingDestination(replacement).orderedPlaces())
    }

    @Test fun liveStartUsesTheRouteThatJustArrivedEvenWhenGpsMovedDuringTheRequest() {
        val driving = TravelMode.DRIVE
        val plan = DirectionsState(end, mode = driving, startRequested = true,
            selected = 1, loading = setOf(driving), routes = mapOf(driving to listOf(direct, alternative)))
        val from = LatLng(50.095, 14.43)
        val moved = LatLng(50.101, 14.43)
        val ready = plan.withRoadResult(driving, listOf(direct), from, "error", shouldStart = true, isLiveStart = true)
        assertIs<RoadStart.WaitForLocation>(ready.roadStart(null))
        assertSame(direct, assertIs<RoadStart.Follow>(ready.roadStart(moved)).route)
        assertIs<RoadStart.FindRoute>(ready.copy(startRequested = false).roadStart(moved))
    }

    @Test fun aFailedLiveStartExposesTheErrorInsteadOfKeepingTheStalePreview() {
        val driving = TravelMode.DRIVE
        val plan = DirectionsState(end, mode = driving, startRequested = true,
            loading = setOf(driving), routes = mapOf(driving to listOf(direct)))
        val failed = plan.withRoadResult(driving, emptyList(), here, "Could not connect", shouldStart = true, isLiveStart = true)
        assertNull(failed.route)
        assertNull(failed.readyToStartRouteId)
        assertEquals(false, failed.startRequested)
        assertEquals("Could not connect", failed.errors[driving])
    }
}
