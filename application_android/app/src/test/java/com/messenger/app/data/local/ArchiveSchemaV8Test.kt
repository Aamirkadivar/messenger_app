package com.messenger.app.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 3 CHECKPOINT B - migration contract, JVM.
 *
 * Room's own `MigrationTestHelper` needs a real SQLite instance and therefore a device. This test
 * covers what can be proven without one, by checking the migration statements against the schema
 * JSON that Room itself exported:
 *
 *   - the migration is additive (ADD COLUMN only, so nothing is rebuilt or dropped);
 *   - it adds exactly the columns v8 declares and no others;
 *   - the nullability and defaults in the SQL match what Room expects, which is the mismatch that
 *     would otherwise only surface as a runtime IllegalStateException on a real device.
 *
 * What it deliberately does NOT prove: that a populated v7 database opens as v8 with its rows
 * intact. That requires executing SQLite and is covered by the instrumented migration test.
 */
class ArchiveSchemaV8Test {

    private val json = Json { ignoreUnknownKeys = true }

    private fun schemaDir(): File {
        val candidates = listOf(
            File("schemas/com.messenger.app.data.local.AuthDatabase"),
            File("app/schemas/com.messenger.app.data.local.AuthDatabase"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "exported Room schemas not found; looked in " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    private fun messagesColumns(version: Int): Map<String, JsonObject> {
        val file = File(schemaDir(), "$version.json")
        assertTrue("missing exported schema $version.json", file.isFile)
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray
        val messages = entities.map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == ArchiveSchemaV8.TABLE }
        return messages["fields"]!!.jsonArray
            .map { it.jsonObject }
            .associateBy { it["columnName"]!!.jsonPrimitive.content }
    }

    @Test
    fun everyStatementIsAnAdditiveAddColumn() {
        assertTrue("the migration must do something", ArchiveSchemaV8.ADD_COLUMNS.isNotEmpty())
        for (sql in ArchiveSchemaV8.ADD_COLUMNS) {
            assertTrue(
                "non-additive statement would risk data: $sql",
                sql.startsWith("ALTER TABLE ${ArchiveSchemaV8.TABLE} ADD COLUMN ")
            )
            for (forbidden in listOf("DROP", "DELETE", "TRUNCATE", "RENAME", "CREATE TABLE", "UPDATE ")) {
                assertTrue(
                    "migration must never contain $forbidden: $sql",
                    !sql.uppercase().contains(forbidden)
                )
            }
        }
    }

    @Test
    fun statementsAndDeclaredColumnNamesAgree() {
        val fromSql = ArchiveSchemaV8.ADD_COLUMNS.map {
            it.removePrefix("ALTER TABLE ${ArchiveSchemaV8.TABLE} ADD COLUMN ").substringBefore(' ')
        }
        assertEquals(ArchiveSchemaV8.ADDED_COLUMNS, fromSql)
    }

    @Test
    fun v8AddsExactlyTheArchiveColumnsAndRemovesNothing() {
        val v7 = messagesColumns(7)
        val v8 = messagesColumns(8)

        assertEquals(
            "v8 must add exactly the archive columns",
            ArchiveSchemaV8.ADDED_COLUMNS.toSet(),
            v8.keys - v7.keys
        )
        assertEquals("v8 must not drop a column", emptySet<String>(), v7.keys - v8.keys)
    }

    /** Existing columns - `content` above all - must be byte-for-byte unchanged. */
    @Test
    fun noExistingColumnDefinitionChanges() {
        val v7 = messagesColumns(7)
        val v8 = messagesColumns(8)
        val changed = v7.keys.filter { v8[it] != v7[it] }
        assertEquals("existing column definitions must not change", emptyList<String>(), changed)
        assertTrue("the plaintext content column must survive", v8.containsKey("content"))
    }

    /**
     * The Room mismatch trap: a NOT NULL column added by ALTER TABLE needs a SQL default, so the
     * entity must declare the same default or Room's runtime validation rejects the database.
     */
    @Test
    fun nullabilityAndDefaultsMatchTheSql() {
        val v8 = messagesColumns(8)

        val ciphertext = v8.getValue("archiveCiphertext")
        assertEquals("TEXT", ciphertext["affinity"]!!.jsonPrimitive.content)
        assertEquals(false, ciphertext["notNull"]!!.jsonPrimitive.content.toBoolean())

        val state = v8.getValue("archiveState")
        assertEquals("TEXT", state["affinity"]!!.jsonPrimitive.content)
        assertEquals(false, state["notNull"]!!.jsonPrimitive.content.toBoolean())

        val version = v8.getValue("archiveRootVersion")
        assertEquals("INTEGER", version["affinity"]!!.jsonPrimitive.content)
        assertEquals(true, version["notNull"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "a NOT NULL added column must carry the same default the migration supplies",
            "0",
            version["defaultValue"]!!.jsonPrimitive.content
        )

        // And the SQL must actually say so.
        val versionSql = ArchiveSchemaV8.ADD_COLUMNS.first { it.contains("archiveRootVersion") }
        assertTrue(versionSql.contains("INTEGER NOT NULL DEFAULT 0"))
        assertTrue(
            "nullable columns must not declare a default, matching the exported schema",
            ArchiveSchemaV8.ADD_COLUMNS
                .filter { it.contains("archiveCiphertext") || it.contains("archiveState") }
                .none { it.uppercase().contains("DEFAULT") }
        )
    }

    @Test
    fun databaseVersionsAreSevenAndEight() {
        for (v in listOf(7, 8)) {
            val root = json.parseToJsonElement(File(schemaDir(), "$v.json").readText()).jsonObject
            assertEquals(
                v,
                root["database"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt()
            )
        }
    }
}
