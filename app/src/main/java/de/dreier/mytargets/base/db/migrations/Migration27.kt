package de.dreier.mytargets.base.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.dreier.mytargets.shared.models.Dimension
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.models.db.Shot
import timber.log.Timber

/**
 * Backfills missing Shot records for historical ends.
 *
 * Older app versions did not pre-create all Shot slots when an end was started,
 * so some ends have fewer Shot rows than the round's [shotsPerEnd]. This migration
 * finds the exact missing indexes (0 until shotsPerEnd) and inserts them with
 * default values (scoringRing = -2 / NOTHING_SELECTED).
 *
 * Inserting shots changes the score of the affected ends: a NOTHING_SELECTED shot
 * contributes 0 reached points but still raises the reachable total, exactly as it
 * does for a freshly created end. The denormalized End score is therefore recomputed
 * for every touched end, following the pattern established by [Migration26]. Without
 * this, the stored score (shown by RoundFragment and StatisticsFragment) would
 * disagree with the score InputActivity recomputes from the shots at runtime.
 *
 * Updating the End score columns fires the triggers created in [Migration26], which
 * propagate the new sums up to Round and Training.
 */
object Migration27 : Migration(26, 27) {

    /** An end that has fewer Shot rows than its round prescribes. */
    private data class IncompleteEnd(
        val endId: Long,
        val shotsPerEnd: Int,
        val target: Target
    )

    override fun migrate(database: SupportSQLiteDatabase) {
        Timber.i("Migrating DB from version 26 to 27: backfill missing shots")

        // Materialize the result before mutating, so no cursor stays open over a
        // table that is written to while iterating.
        val incompleteEnds = mutableListOf<IncompleteEnd>()
        database.query(
            """
            SELECT e.`id`, r.`shotsPerEnd`, r.`targetId`,
                   r.`targetScoringStyleIndex`, r.`targetDiameter`
            FROM `End` e
            JOIN `Round` r ON e.`roundId` = r.`id`
            WHERE (SELECT COUNT(*) FROM `Shot` WHERE `endId` = e.`id`) < r.`shotsPerEnd`
            """
        ).useEach { cursor ->
            incompleteEnds.add(
                IncompleteEnd(
                    endId = cursor.getLong(0),
                    shotsPerEnd = cursor.getInt(1),
                    target = Target(
                        cursor.getLong(2),
                        cursor.getInt(3),
                        Dimension.parse(cursor.getString(4))
                    )
                )
            )
        }

        for (end in incompleteEnds) {
            insertMissingShots(database, end)
            recomputeEndScore(database, end)
        }

        Timber.i("Backfilled shots for ${incompleteEnds.size} ends")
    }

    private fun insertMissingShots(database: SupportSQLiteDatabase, end: IncompleteEnd) {
        val existingIndexes = mutableSetOf<Int>()
        database.query(
            "SELECT `index` FROM `Shot` WHERE `endId` = ?",
            arrayOf<Any>(end.endId)
        ).useEach { existingIndexes.add(it.getInt(0)) }

        for (i in 0 until end.shotsPerEnd) {
            if (!existingIndexes.contains(i)) {
                database.execSQL(
                    """
                    INSERT INTO `Shot` (`index`, `endId`, `x`, `y`, `scoringRing`, `arrowNumber`)
                    VALUES (?, ?, 0.0, 0.0, ${Shot.NOTHING_SELECTED}, NULL)
                    """,
                    arrayOf<Any>(i, end.endId)
                )
            }
        }
    }

    private fun recomputeEndScore(database: SupportSQLiteDatabase, end: IncompleteEnd) {
        val shots = mutableListOf<Shot>()
        database.query(
            "SELECT `index`, `scoringRing` FROM `Shot` WHERE `endId` = ? ORDER BY `index`",
            arrayOf<Any>(end.endId)
        ).useEach { cursor ->
            shots.add(Shot(index = cursor.getInt(0), scoringRing = cursor.getInt(1)))
        }

        val score = end.target.getReachedScore(shots)
        database.execSQL(
            "UPDATE `End` SET " +
                    "`reachedPoints` = ?, `totalPoints` = ?, `shotCount` = ? " +
                    "WHERE `id` = ?",
            arrayOf<Any>(score.reachedPoints, score.totalPoints, score.shotCount, end.endId)
        )
    }
}
