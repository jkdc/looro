package com.jkdc.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Opens the learner's learning database (schema v2, shared/db/schema.sql via
 * [SchemaData]). Android builds it through SQLiteOpenHelper; JVM tests build
 * the same statements through LearningDb.openJvm (test source set,
 * sqlite-jdbc). Foreign keys are enforced on every connection, as the schema
 * header requires.
 *
 * Fresh installs run the whole statement list in [onCreate] (the list ends
 * with `PRAGMA user_version = 2`, which the helper records again as
 * [VERSION]); an installed database upgrades through [upgrade], which
 * applies the pinned per-version deltas ([SchemaData.SCHEMA_UPGRADES]) so
 * both paths converge on the same end state from the one source.
 */
object LearningDb {
    /** Bump only together with a migration; shared/db/schema.sql pins the shape. */
    const val VERSION = 2
    const val NAME = "learning.db"

    /**
     * Brings a database opened at [fromVersion] up to [VERSION] by applying
     * each version's delta in order. The caller owns the surrounding
     * transaction and the final `PRAGMA user_version` (SQLiteOpenHelper
     * wraps onUpgrade in one and sets the version itself); the deltas
     * deliberately exclude the PRAGMA statements. Every intermediate version
     * must have a pinned delta — a gap is a development error and fails
     * loudly. Idempotence is NOT attempted: a delta runs once, guarded by
     * the recorded user_version.
     */
    fun upgrade(db: SqlDatabase, fromVersion: Int) {
        require(fromVersion in 1 until VERSION) {
            "Learning schema cannot upgrade from version $fromVersion"
        }
        for (version in (fromVersion + 1)..VERSION) {
            val statements = SchemaData.SCHEMA_UPGRADES[version]
                ?: error("Learning schema has no upgrade path to version $version")
            for (statement in statements) db.exec(statement)
        }
    }

    /** Opens the learning database, creating the schema on first use. */
    fun open(context: Context, name: String = NAME): SqlDatabase =
        AndroidSqlDatabase(Helper(context.applicationContext, name).writableDatabase)

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, VERSION) {

        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

        override fun onCreate(db: SQLiteDatabase) {
            for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) db.execSQL(statement)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            upgrade(AndroidSqlDatabase(db), oldVersion)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("Learning schema never downgrades (found $oldVersion, want $newVersion)")
    }
}
