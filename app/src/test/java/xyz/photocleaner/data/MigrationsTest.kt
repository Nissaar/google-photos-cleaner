package xyz.photocleaner.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Upgrades a real SQLite database built from the schema that shipped, and checks
 * the result against the schema Room now expects.
 *
 * People's verdicts exist only in this database, so an upgrade that loses a row or
 * leaves a column Room disagrees with (which makes it refuse to open) is the worst
 * bug this app can ship. SQLCipher encrypts pages, not SQL, so plain SQLite is a
 * faithful stand-in for what the migration statements do.
 */
class MigrationsTest {

    private val schemaDir = File("schemas/xyz.photocleaner.data.AppDatabase")
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun close() = db.close()

    @Test
    fun `every version since the first release has a migration to the next`() {
        val versions = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
        val latest = versions.last()
        val steps = Migrations.ALL.associate { it.startVersion to it.endVersion }
        for (v in versions.first() until latest) {
            assertEquals("no migration from version $v", v + 1, steps[v])
        }
    }

    @Test
    fun `3 to 4 keeps every row and matches the v4 schema`() {
        createFrom(schema(3))
        exec(
            "INSERT INTO decisions VALUES ('pend','m1',1000,'DELETE',2000,'https://t/1',0,0,NULL)",
            "INSERT INTO decisions VALUES ('kept','m2',1100,'KEEP',2100,'https://t/2',1,0,NULL)",
            "INSERT INTO decisions VALUES ('done','m3',1200,'DELETE',2200,'https://t/3',0,1,3000)",
            "INSERT INTO month_counts VALUES ('2024-07',42)",
            "INSERT INTO scan_state VALUES (0,5000,4000,'k',1,6000)",
        )

        Migrations.SQL_3_4.forEach { exec(it) }

        assertMatches(schema(4))
        assertEquals(3, count("decisions"))
        assertEquals(1, count("month_counts"))
        assertEquals(1, count("scan_state"))

        // Already-applied rows are labelled as trash, which is how v3 treated them.
        assertEquals("TRASH", appliedMode("done"))
        assertNull(appliedMode("pend"))
        assertNull(appliedMode("kept"))

        // And the rest of each row came through untouched.
        db.createStatement().executeQuery(
            "SELECT mediaKey, takenAt, verdict, applied, appliedAt FROM decisions WHERE dedupKey = 'done'",
        ).use { r ->
            assertTrue(r.next())
            assertEquals("m3", r.getString(1))
            assertEquals(1200L, r.getLong(2))
            assertEquals("DELETE", r.getString(3))
            assertEquals(1, r.getInt(4))
            assertEquals(3000L, r.getLong(5))
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
            .jsonObject["database"]!!.jsonObject

    private fun entities(schema: JsonObject) = schema["entities"]!!.jsonArray.map { it.jsonObject }

    private fun createFrom(schema: JsonObject) {
        for (entity in entities(schema)) {
            val table = entity["tableName"]!!.jsonPrimitive.content
            exec(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { index ->
                exec(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
    }

    /** Compares what Room validates on open: columns, types, nullability, defaults, indices. */
    private fun assertMatches(schema: JsonObject) {
        for (entity in entities(schema)) {
            val table = entity["tableName"]!!.jsonPrimitive.content

            val expected = entity["fields"]!!.jsonArray.map { it.jsonObject }.associate { f ->
                f["columnName"]!!.jsonPrimitive.content to Triple(
                    f["affinity"]!!.jsonPrimitive.content,
                    f["notNull"]!!.jsonPrimitive.boolean,
                    f["defaultValue"]?.jsonPrimitive?.content,
                )
            }
            val actual = mutableMapOf<String, Triple<String, Boolean, String?>>()
            db.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { r ->
                while (r.next()) {
                    actual[r.getString("name")] =
                        Triple(r.getString("type"), r.getInt("notnull") == 1, r.getString("dflt_value"))
                }
            }
            assertEquals("columns of $table", expected, actual)

            val expectedIndices = entity["indices"]?.jsonArray.orEmpty()
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            val actualIndices = mutableSetOf<String>()
            db.createStatement()
                .executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND sql IS NOT NULL")
                .use { r -> while (r.next()) actualIndices += r.getString(1) }
            assertEquals("indices of $table", expectedIndices, actualIndices)

            val pk = entity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
            val actualPk = mutableListOf<Pair<Int, String>>()
            db.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { r ->
                while (r.next()) if (r.getInt("pk") > 0) actualPk += r.getInt("pk") to r.getString("name")
            }
            assertEquals("primary key of $table", pk, actualPk.sortedBy { it.first }.map { it.second })
        }
    }

    private fun exec(vararg sql: String) = sql.forEach { db.createStatement().use { s -> s.execute(it) } }

    private fun count(table: String): Int =
        db.createStatement().executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) }

    private fun appliedMode(key: String): String? =
        db.createStatement().executeQuery("SELECT appliedMode FROM decisions WHERE dedupKey = '$key'")
            .use { it.next(); it.getString(1) }
}
