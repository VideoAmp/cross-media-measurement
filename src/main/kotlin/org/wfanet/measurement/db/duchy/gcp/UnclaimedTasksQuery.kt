package org.wfanet.measurement.db.duchy.gcp

import com.google.cloud.Timestamp
import com.google.cloud.spanner.Statement
import com.google.cloud.spanner.Struct

/** Queries for computations which may be claimed at a timestamp. */
class UnclaimedTasksQuery<StateT>(
  val parseStageEnum: (Long) -> StateT,
  timestamp: Timestamp
) : SqlBasedQuery<UnclaimedTaskQueryResult<StateT>> {
  companion object {
    private const val parameterizedQueryString =
      """
      SELECT c.ComputationId,  c.GlobalComputationId, c.ComputationStage, c.UpdateTime,
             cs.NextAttempt
      FROM Computations@{FORCE_INDEX=ComputationsByLockExpirationTime} AS c
      JOIN ComputationStages cs USING(ComputationId, ComputationStage)
      WHERE c.LockExpirationTime <= @current_time
      ORDER BY c.LockExpirationTime ASC, c.UpdateTime ASC
      LIMIT 50
      """
  }
  override val sql: Statement =
    Statement.newBuilder(parameterizedQueryString).bind("current_time").to(timestamp).build()
  override fun asResult(struct: Struct): UnclaimedTaskQueryResult<StateT> =
    UnclaimedTaskQueryResult(
      computationId = struct.getLong("ComputationId"),
      globalId = struct.getLong("GlobalComputationId"),
      computationStage = parseStageEnum(struct.getLong("ComputationStage")),
      updateTime = struct.getTimestamp("UpdateTime"),
      nextAttempt = struct.getLong("NextAttempt")
    )
}
/** @see [UnclaimedTasksQuery.asResult] .*/
data class UnclaimedTaskQueryResult<StateT>(
  val computationId: Long,
  val globalId: Long,
  val computationStage: StateT,
  val updateTime: Timestamp,
  val nextAttempt: Long
)