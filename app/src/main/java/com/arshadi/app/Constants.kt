package com.arshadi.app

import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
data class DetectionResult(
    val label: String,
    val classId: Int,
    val conf: Float,
    val x1: Float, val y1: Float,   // normalized 0–1
    val x2: Float, val y2: Float,
    val distance: Float,             // meters
    val position: String,            // على يسارك / أمامك مباشرة / على يمينك
    val verticalPos: String          // أعلى / وسط / أسفل
)

// ─────────────────────────────────────────────────────────────────────────────
data class LatLngPoint(val lat: Double, val lng: Double)

data class Destination(
    val nameAr: String,
    val keywords: List<String>,
    val path: List<LatLngPoint>,
    val end: LatLngPoint
)

// ─────────────────────────────────────────────────────────────────────────────
object Constants {

    const val MODEL_PATH   = "models/arshadi_int8.tflite"
    const val LABELS_PATH  = "models/labels.txt"

    val DESTINATIONS = listOf(
        Destination(
            nameAr = "البوابة الشرقية",
            keywords = listOf(
                "بوابة","شرقية","اطلع","خروج","برا","خارج","باب",
                "exit","gate","east","طلع","بطلع","روح","امشي",
                "ابدأ","انطلق","خذني","وصلني","المسار","الطريق"
            ),
            path = listOf(
                LatLngPoint(32.538312, 35.854592),
                LatLngPoint(32.538420, 35.854840),
                LatLngPoint(32.538504, 35.855215),
                LatLngPoint(32.538604, 35.855537),
                LatLngPoint(32.538710, 35.855740),
                LatLngPoint(32.538807, 35.855985),
                LatLngPoint(32.538913, 35.856306),
                LatLngPoint(32.539017, 35.856626),
                LatLngPoint(32.539123, 35.856908),
                LatLngPoint(32.539222, 35.857185),
                LatLngPoint(32.539277, 35.857350)
            ),
            end = LatLngPoint(32.539277, 35.857350)
        )
    )

    fun findDestination(text: String): Destination? {
        val lower = text.lowercase()
        return DESTINATIONS.firstOrNull { dest ->
            dest.keywords.any { k -> lower.contains(k) }
        }
    }

    val availableDestinationsAr: String
        get() = DESTINATIONS.joinToString("، ") { it.nameAr }

    // ─── أسماء العوائق → جمل عربية ───
    fun obstacleMsg(label: String, pos: String): String {
        val msgs = mapOf(
            "car"      to mapOf("أمامك مباشرة" to "في سيارة أمامك، توقف لحظة",
                "على يسارك"    to "في سيارة على يسارك، ابتعد لليمين",
                "على يمينك"   to "في سيارة على يمينك، ابتعد لليسار"),
            "person"   to mapOf("أمامك مباشرة" to "في شخص أمامك، تمهّل",
                "على يسارك"    to "في شخص على يسارك، تابع",
                "على يمينك"   to "في شخص على يمينك، تابع"),
            "stairs"   to mapOf("أمامك مباشرة" to "توقف، في درج أمامك، مد قدمك بتأنٍ",
                "على يسارك"    to "في درج على يسارك",
                "على يمينك"   to "في درج على يمينك"),
            "hole"     to mapOf("أمامك مباشرة" to "توقف فوراً! في حفرة أمامك",
                "على يسارك"    to "في حفرة على يسارك",
                "على يمينك"   to "في حفرة على يمينك"),
            "obstacle" to mapOf("أمامك مباشرة" to "في عائق أمامك، انحرف لليمين",
                "على يسارك"    to "في عائق على يسارك",
                "على يمينك"   to "في عائق على يمينك"),
            "chair"    to mapOf("أمامك مباشرة" to "في كرسي أمامك، انحرف لليمين",
                "على يسارك"    to "في كرسي على يسارك",
                "على يمينك"   to "في كرسي على يمينك"),
            "trash"    to mapOf("أمامك مباشرة" to "في حاوية أمامك، انحرف لليمين",
                "على يسارك"    to "في حاوية على يسارك",
                "على يمينك"   to "في حاوية على يمينك"),
            "tree"     to mapOf("أمامك مباشرة" to "في شجرة أمامك، انحرف لليمين",
                "على يسارك"    to "في شجرة على يسارك",
                "على يمينك"   to "في شجرة على يمينك"),
            "sidewalk" to mapOf("أمامك مباشرة" to "أمامك رصيف، ارفع قدمك",
                "على يسارك"    to "رصيف على يسارك",
                "على يمينك"   to "رصيف على يمينك"),
            "sill"     to mapOf("أمامك مباشرة" to "في عتبة أمامك، ارفع قدمك",
                "على يسارك"    to "في عتبة على يسارك",
                "على يمينك"   to "في عتبة على يمينك")
        )
        return msgs[label.lowercase()]?.get(pos) ?: "انتبه، في $label $pos، تمهّل"
    }

    // ─── Geo helpers ───
    fun haversineDistance(a: LatLngPoint, b: LatLngPoint): Double {
        val R = 6371000.0
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lng - a.lng) * PI / 180
        val x = sin(dLat / 2).pow(2) +
                cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(x), sqrt(1 - x))
    }

    fun bearingTo(from: LatLngPoint, to: LatLngPoint): Double {
        val lat1 = from.lat * PI / 180
        val lat2 = to.lat  * PI / 180
        val dLon = (to.lng - from.lng) * PI / 180
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    fun nearestRoutePoint(current: LatLngPoint, route: List<LatLngPoint>): LatLngPoint {
        return route.minByOrNull { haversineDistance(current, it) } ?: route.first()
    }
}
