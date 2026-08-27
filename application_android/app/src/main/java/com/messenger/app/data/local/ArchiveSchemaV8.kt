package com.messenger.app.data.local

/**
 * The exact SQL that takes `messages` from schema v7 to v8.
 *
 * Kept in a plain object with no Android dependency so the statements can be asserted against the
 * exported Room schema JSON from an ordinary JVM test - `MigrationTestHelper` needs a real SQLite
 * instance and can only run on a device, which would otherwise leave the migration unverified
 * until device time.
 *
 * Every statement must stay an `ALTER TABLE ... ADD COLUMN`. That is what makes the migration
 * non-destructive: no table rebuild, no data copy, no existing column touched. A test enforces it.
 */
internal object ArchiveSchemaV8 {

    const val TABLE = "messages"

    val ADD_COLUMNS: List<String> = listOf(
        "ALTER TABLE messages ADD COLUMN archiveCiphertext TEXT",
        "ALTER TABLE messages ADD COLUMN archiveRootVersion INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE messages ADD COLUMN archiveState TEXT",
    )

    /** Column names this migration introduces, in statement order. */
    val ADDED_COLUMNS: List<String> = listOf(
        "archiveCiphertext",
        "archiveRootVersion",
        "archiveState",
    )
}
