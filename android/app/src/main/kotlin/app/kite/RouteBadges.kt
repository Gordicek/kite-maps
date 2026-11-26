package app.kite

import app.kite.core.Geo
import app.kite.core.LatLng
import app.kite.core.Route
import kotlin.math.abs
import kotlin.math.min

internal object RouteBadges {
    /** Candidate anchors are on each route, ranked by separation and distance from the endpoints. */
    fun anchors(routes: List<Route>): List<List<LatLng>> {
        val lines = routes.map { route -> route.polyline }
        val lengths = lines.map(Geo::cumulative)
        return lines.mapIndexed { index, line ->
            val total = lengths[index].last()
            val threshold = (total * 0.025).coerceIn(18.0, 100.0)
            (3..21).map { i ->
                val t = i / 24.0
                val point = Geo.point(line, total * t, lengths[index])
                val gap = lines.indices.filter { it != index }
                    .minOfOrNull { Geo.project(point, lines[it], lengths[it]).offset } ?: 0.0
                val score = min(gap / threshold, 2.0) - 1.65 * abs(0.52 - t)
                point to score
            }.sortedByDescending { it.second }.take(12).map { it.first }.distinct()
        }
    }

    data class Tag(val x: Double, val y: Double, val width: Double, val height: Double)
    data class Offset(val x: Int = 0, val y: Int = 0)
    data class Placement(val anchor: Int, val offset: Offset)
    data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** Prefer distinct coordinates on the routes. Offset a badge only when nearby paths leave no room. */
    fun place(candidates: List<List<Tag>>, bounds: Bounds, selected: Int): List<Placement> {
        val occupied = mutableListOf<Tag>()
        val placements = MutableList(candidates.size) { Placement(0, Offset()) }
        val order = candidates.indices.sortedBy { it != selected }
        for (index in order) {
            val tags = candidates[index]
            if (tags.isEmpty()) continue
            fun fits(tag: Tag) = tag.x - tag.width / 2 >= bounds.left && tag.x + tag.width / 2 <= bounds.right &&
                tag.y - tag.height / 2 >= bounds.top && tag.y + tag.height / 2 <= bounds.bottom &&
                occupied.none {
                    abs(tag.x - it.x) < (tag.width + it.width) / 2 + 8.0 &&
                        abs(tag.y - it.y) < (tag.height + it.height) / 2 + 7.0
                }
            fun shifted(tag: Tag, offset: Offset) = tag.copy(x = tag.x + offset.x, y = tag.y + offset.y)

            val anchor = tags.indexOfFirst(::fits)
            val chosen = if (anchor >= 0) Placement(anchor, Offset()) else {
                val shifts = tags.flatMapIndexed { i, tag ->
                    val xStep = (tag.width + 10).toInt()
                    val yStep = (tag.height + 8).toInt()
                    (-2..2).flatMap { x -> (-2..2).map { y -> Placement(i, Offset(x * xStep, y * yStep)) } }
                }.sortedBy { abs(it.offset.x) + abs(it.offset.y) + it.anchor * 4 }
                shifts.firstOrNull { fits(shifted(tags[it.anchor], it.offset)) } ?: Placement(0, Offset())
            }
            placements[index] = chosen
            occupied += shifted(tags[chosen.anchor], chosen.offset)
        }
        return placements
    }
}
