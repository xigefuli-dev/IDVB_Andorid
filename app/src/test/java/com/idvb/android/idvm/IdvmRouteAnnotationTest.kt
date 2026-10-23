package com.idvb.android.idvm

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class IdvmRouteAnnotationTest {
    private val first = NormalizedPoint(.1, .2)
    private val last = NormalizedPoint(.7, .9)
    private fun route() = MapAnnotation(UUID.randomUUID().toString(), "route", start = first, end = last,
        points = listOf(first, NormalizedPoint(.4, .5), last), routePlanId = UUID.randomUUID().toString())

    @Test fun routeGeometryAndPlanSurviveSerialization() {
        val original = route()
        IdvmValidator.validateAnnotation(original)
        val encoded = IdvmJson.instance.encodeToString(MapAnnotation.serializer(), original)
        assertEquals(original, IdvmJson.instance.decodeFromString<MapAnnotation>(encoded))
    }

    @Test fun rejectsInvalidOrUnboundedRouteGeometry() {
        val valid = route()
        for (invalid in listOf(valid.copy(points = null), valid.copy(points = listOf(first)),
            valid.copy(points = List(8193) { first }), valid.copy(end = first),
            valid.copy(points = listOf(first, NormalizedPoint(Double.NaN, .5), last)),
            valid.copy(points = listOf(first, NormalizedPoint(1.1, .5), last)))) {
            assertThrows(IllegalArgumentException::class.java) { IdvmValidator.validateAnnotation(invalid) }
        }
    }

    @Test fun acceptsTraversableClosedRoutesButRejectsDegenerateOnes() {
        IdvmValidator.validateAnnotation(route().copy(end = first, points = listOf(first, last, first)))
        assertThrows(IllegalArgumentException::class.java) {
            IdvmValidator.validateAnnotation(route().copy(end = first, points = listOf(first, first)))
        }
    }
}
