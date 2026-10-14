package com.example.shareadd

import java.util.UUID

data class IdAssignment(
    val places: List<Place>,
    /** records that had no id and received one */
    val generated: Int,
    /** records whose id was already used by an earlier record and received a new one */
    val repaired: Int
)

fun newPlaceId(): String = "pl-" + UUID.randomUUID().toString()

/**
 * Gives every place a unique id while keeping order and every other field unchanged.
 * Missing ids are generated; a duplicate id keeps its first owner and later records get new ids
 * (two different records are never merged).
 */
fun assignPlaceIds(places: List<Place>, newId: () -> String = ::newPlaceId): IdAssignment {
    val seen = HashSet<String>()
    var generated = 0
    var repaired = 0
    val result = places.map { place ->
        val id = place.id.trim()
        when {
            id.isEmpty() -> {
                generated++
                var fresh = newId()
                while (!seen.add(fresh)) fresh = newId()
                place.copy(id = fresh)
            }
            !seen.add(id) -> {
                repaired++
                var fresh = newId()
                while (!seen.add(fresh)) fresh = newId()
                place.copy(id = fresh)
            }
            else -> if (id != place.id) place.copy(id = id) else place
        }
    }
    return IdAssignment(result, generated, repaired)
}
