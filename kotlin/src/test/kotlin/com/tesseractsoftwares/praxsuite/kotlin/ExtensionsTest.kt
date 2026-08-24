package com.tesseractsoftwares.praxsuite.kotlin

import com.tesseractsoftwares.praxsuite.Filters
import com.tesseractsoftwares.praxsuite.PraxEndpoints
import com.tesseractsoftwares.praxsuite.PraxValidationError
import com.tesseractsoftwares.praxsuite.Praxsuite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The extensions are a face over the Java SDK, so what matters is that the face does not change the
 * wire shape or weaken a guardrail. These assert exactly that, offline.
 */
class ExtensionsTest {

    private val fakeSecret = "sk_live_" + "0123456789abcdef0123456789abcdef"
    private val ws = "1eb92f32-d628-4656-8c64-cd0d43c9869d"

    private fun client(): Praxsuite = Praxsuite.builder()
        .workspaceId(ws)
        .credential(fakeSecret)
        .baseUrl("https://gateway.example.invalid")
        .build()

    @Test
    @DisplayName("the where DSL produces the same wire shape as the static Filters calls")
    fun dslMatchesStaticFilters() {
        val prax = client()

        val viaDsl = prax.data.table("Scores")
            .select("Player", "Score")
            .where { gte("Score", 100); eq("Season", 3) }
            .orderByDescending("Score")
            .limit(20)
            .build()

        val viaStatic = prax.data.table("Scores")
            .select("Player", "Score")
            .where(Filters.gte("Score", 100), Filters.eq("Season", 3))
            .orderByDescending("Score")
            .limit(20)
            .build()

        // If these ever diverge, the DSL has started lying about what it sends.
        assertEquals(viaStatic, viaDsl)
    }

    @Test
    @DisplayName("a nested anyOf nests under an or key")
    fun nestedAnyOf() {
        val built = client().data.table("Items")
            .where { anyOf { eq("Rarity", "legendary"); eq("Rarity", "epic") } }
            .build()

        @Suppress("UNCHECKED_CAST")
        val query = built["query"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val where = query["where"] as List<Map<String, Any>>
        assertEquals(1, where.size)
        assertTrue(where[0].containsKey("or"))
        assertEquals(2, (where[0]["or"] as List<*>).size)
    }

    @Test
    @DisplayName("includeTotalCount still sits beside query, not inside it")
    fun totalCountPlacement() {
        val built = client().data.table("Scores").withTotalCount().build()
        assertEquals(true, built["includeTotalCount"])
        @Suppress("UNCHECKED_CAST")
        val query = built["query"] as Map<String, Any>
        assertNull(query["includeTotalCount"])
    }

    @Test
    @DisplayName("limit is still clamped up to one")
    fun limitClamped() {
        val built = client().data.table("Scores").limit(0).build()
        @Suppress("UNCHECKED_CAST")
        val query = built["query"] as Map<String, Any>
        assertEquals(1, query["limit"])
    }

    @Test
    @DisplayName("an empty where block does not smuggle an unscoped update past the guardrail")
    fun emptyWhereBlockStillRefused() {
        // The DSL must not become a way around the Java SDK's refusal. An empty block produces no
        // conditions, and the underlying update must still reject it.
        val prax = client()
        val e = assertThrows(PraxValidationError::class.java) {
            runBlocking { prax.data.updateAsync("Scores", mapOf("Score" to 0)) { } }
        }
        assertEquals("UNSCOPED_MUTATION", e.code())
    }

    @Test
    @DisplayName("an empty delete block is refused the same way")
    fun emptyDeleteBlockRefused() {
        val prax = client()
        val e = assertThrows(PraxValidationError::class.java) {
            runBlocking { prax.data.deleteAsync("Scores") { } }
        }
        assertEquals("UNSCOPED_MUTATION", e.code())
    }

    @Test
    @DisplayName("an empty inList is refused, as in every other SDK")
    fun emptyInListRefused() {
        assertThrows(PraxValidationError::class.java) {
            client().data.table("Scores").where { inList("Level", emptyList()) }
        }
    }

    @Test
    @DisplayName("the DSL cannot emit an operator the gateway lacks")
    fun onlyRealOperators() {
        val built = client().data.table("T")
            .where {
                eq("a", 1); neq("a", 1); gt("a", 1); gte("a", 1); lt("a", 1); lte("a", 1)
                like("a", "x"); ilike("a", "x"); contains("a", "x"); textSearch("a", "x")
                startsWith("a", "x"); endsWith("a", "x"); isNull("a"); isNotNull("a")
                inList("a", listOf(1)); between("a", 1, 2)
            }
            .build()

        @Suppress("UNCHECKED_CAST")
        val query = built["query"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val where = query["where"] as List<Map<String, Any>>
        where.forEach {
            assertTrue(
                Filters.SUPPORTED_OPERATORS.contains(it["op"]),
                "unsupported operator leaked: " + it["op"],
            )
        }
    }

    @Test
    @DisplayName("the property accessors reach the same objects as the methods")
    fun propertyAccessors() {
        val prax = client()
        assertEquals(prax.data(), prax.data)
        assertEquals(prax.auth(), prax.auth)
        assertEquals(prax.endpoints(), prax.endpoints)
    }

    @Test
    @DisplayName("a secret key is still refused for a client-side build")
    fun clientSideStillGuarded() {
        val e = assertThrows(PraxValidationError::class.java) {
            Praxsuite.builder().workspaceId(ws).credential(fakeSecret).clientSide(true).build()
        }
        assertEquals("SECRET_KEY_REFUSED", e.code())
    }

    @Test
    @DisplayName("a blank endpoint id is refused, and there is no GET helper to find")
    fun endpointGuards() {
        val prax = client()
        assertThrows(PraxValidationError::class.java) {
            runBlocking { prax.endpoints.callAsync("  ") }
        }
        // GET never reaches the automation - the gateway consumes it as a Meta webhook handshake.
        assertTrue(
            PraxEndpoints::class.java.methods.none { it.name == "get" },
            "PraxEndpoints must expose no GET helper",
        )
        assertNotNull(PraxEndpoints.DEFAULT_TIMEOUT)
        assertTrue(PraxEndpoints.DEFAULT_TIMEOUT.seconds >= 90)
    }

    @Test
    @DisplayName("column() reads a Long for a whole number, matching the Java codec")
    fun typedColumnAccess() {
        val row: Map<String, Any> = mapOf("Level" to 12L, "Ratio" to 1.5, "Name" to "Aria")
        assertEquals(12L, row.column<Long>("Level"))
        assertEquals(1.5, row.column<Double>("Ratio"))
        assertEquals("Aria", row.column<String>("Name"))
        // A wrong type asked for is null rather than a ClassCastException at a random later point.
        assertNull(row.column<String>("Level"))
        assertNull(row.column<Long>("missing"))
    }
}
