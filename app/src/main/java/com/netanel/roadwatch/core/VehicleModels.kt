package com.netanel.roadwatch.core

enum class VehicleClass(val he: String) {
    CAR("מכונית"),
    TRUCK("משאית"),
    BUS("אוטובוס"),
    MOTORCYCLE("אופנוע"),
    UNKNOWN("רכב");

    companion object {
        fun fromLabel(label: String): VehicleClass = when (label.lowercase()) {
            "car" -> CAR
            "truck" -> TRUCK
            "bus" -> BUS
            "motorcycle", "motorbike" -> MOTORCYCLE
            else -> UNKNOWN
        }
    }
}

enum class VehicleState(val he: String) {
    MOVING("בתנועה"),
    STOPPING("עוצר"),
    PARKED("חונה"),
    LEAVING("יוצא"),
    UNKNOWN("נבדק")
}

data class Detection(
    val box: Box,
    val vehicleClass: VehicleClass,
    val confidence: Float
)

data class PersonDetection(
    val box: Box,
    val confidence: Float
)

data class CrosswalkEstimate(
    val box: Box,
    val confidence: Float
)

data class TrackSnapshot(
    val id: Int,
    val box: Box,
    val vehicleClass: VehicleClass,
    val confidence: Float,
    val speed: Float,
    val velocity: Vec2,
    val ageMs: Long,
    val lastSeenMs: Long,
    val hits: Int
) {
    val bottomCenter: Vec2 get() = box.bottomCenter
}

data class TrackVisual(
    val track: TrackSnapshot,
    val state: VehicleState,
    val stateSinceMs: Long
)

data class ZoneConfig(
    val road: Polygon2 = Polygon2(emptyList()),
    val parkingZones: List<Polygon2> = emptyList(),
    val countLine: Line2? = null
) {
    fun isComplete(): Boolean = road.isValid() && parkingZones.any { it.isValid() } && countLine != null
    fun isInParking(point: Vec2): Boolean = parkingZones.any { it.contains(point) }
}

data class DashboardMetrics(
    val parkedNow: Int = 0,
    val movingNow: Int = 0,
    val passedToday: Int = 0,
    val parkedToday: Int = 0,
    val leftParkingToday: Int = 0,
    val activeTracks: Int = 0,
    val carsNow: Int = 0,
    val trucksNow: Int = 0,
    val busesNow: Int = 0,
    val motorcyclesNow: Int = 0,
    val peopleNow: Int = 0,
    val peopleInCrosswalkNow: Int = 0,
    val yieldRiskNow: Int = 0
)

data class DailyCounts(
    val dateIso: String,
    val passedToday: Int,
    val parkedToday: Int,
    val leftParkingToday: Int
)
