package org.wfanet.measurement.db.duchy.gcp

import com.google.cloud.Timestamp
import com.google.cloud.spanner.Mutation
import org.wfanet.measurement.db.gcp.toProtoBytes
import org.wfanet.measurement.db.gcp.toProtoJson
import org.wfanet.measurement.internal.db.gcp.ComputationDetails
import org.wfanet.measurement.internal.db.gcp.ComputationStageDetails

/** Tells the mutation to write a null value to a string column. */
const val WRITE_NULL_STRING = ""
/** Returns null if a string equals [WRITE_NULL_STRING]. */
private fun stringOrNull(s: String) = if (s == WRITE_NULL_STRING) null else s

/** Tells the mutation to write a null value to a Timestamp column. */
val WRITE_NULL_TIMESTAMP = Timestamp.ofTimeMicroseconds(0)
/** Returns null if a [Timestamp] equals [WRITE_NULL_TIMESTAMP]. */
private fun timestampOrNull(t: Timestamp) = if (t == WRITE_NULL_TIMESTAMP) null else t
/** Ensures a [Timestamp] does not equal [WRITE_NULL_TIMESTAMP]. */
private fun nonNullValueTimestamp(t: Timestamp) = requireNotNull(timestampOrNull(t))

typealias MutationBuilderFunction = (String) -> Mutation.WriteBuilder

/**
 * Appends fields to write in a mutation of the Computations spanner table.
 */
private fun computation(
  newBuilderFunction: MutationBuilderFunction,
  localId: Long,
  updateTime: Timestamp,
  globalId: Long? = null,
  stage: Long? = null,
  lockOwner: String? = null,
  lockExpirationTime: Timestamp? = null,
  details: ComputationDetails? = null
): Mutation {
  val m = newBuilderFunction("Computations")
  m.set("ComputationId").to(localId)
  m.set("UpdateTime").to(nonNullValueTimestamp(updateTime))
  globalId?.let { m.set("GlobalComputationId").to(it) }
  stage?.let { m.set("ComputationStage").to(it) }
  lockOwner?.let { m.set("LockOwner").to(stringOrNull(it)) }
  lockExpirationTime?.let {
    m.set("LockExpirationTime").to(timestampOrNull(it))
  }
  details?.let {
    m.set("ComputationDetails").toProtoBytes(details)
    m.set("ComputationDetailsJSON").toProtoJson(details)
  }
  return m.build()
}

/**
 * Creates an insertion to the Computations table.
 *
 * Fields required for the write are non-nullable. Any param set to null will be excluded from the
 * update mutation. When the desired value to write to the column is null the parameter should
 * be set to the WRITE_NULL_* value of the column type.
 */
fun insertComputation(
  localId: Long,
  updateTime: Timestamp,
  globalId: Long,
  stage: Long,
  lockOwner: String = WRITE_NULL_STRING,
  lockExpirationTime: Timestamp = WRITE_NULL_TIMESTAMP,
  details: ComputationDetails
): Mutation {
  return computation(
    Mutation::newInsertBuilder,
    localId,
    updateTime,
    globalId,
    stage,
    lockOwner,
    lockExpirationTime,
    details
  )
}

/**
 * Creates an update to the Computations table.
 *
 * Fields required for the write are non-nullable. Any param set to null will be excluded from the
 * update mutation. When the desired value to write to the column is null the parameter should
 * be set to the WRITE_NULL_* value of the column type.
 */
fun updateComputation(
  localId: Long,
  updateTime: Timestamp,
  globalId: Long? = null,
  stage: Long? = null,
  lockOwner: String? = null,
  lockExpirationTime: Timestamp? = null,
  details: ComputationDetails? = null
): Mutation {
  return computation(
    Mutation::newUpdateBuilder,
    localId,
    updateTime,
    globalId,
    stage,
    lockOwner,
    lockExpirationTime,
    details
  )
}

/**
 * Appends fields to write in a mutation of the ComputationStages spanner table.
 */
fun computationStage(
  newBuilderFunction: MutationBuilderFunction,
  localId: Long,
  stage: Long,
  nextAttempt: Long? = null,
  creationTime: Timestamp? = null,
  endTime: Timestamp? = null,
  details: ComputationStageDetails? = null
): Mutation {
  val m = newBuilderFunction("ComputationStages")
  m.set("ComputationId").to(localId)
  m.set("ComputationStage").to(stage)
  creationTime?.let { m.set("CreationTime").to(nonNullValueTimestamp(it)) }
  endTime?.let { m.set("EndTime").to(nonNullValueTimestamp(it)) }
  nextAttempt?.let { m.set("NextAttempt").to(it) }
  details?.let { m.set("Details").toProtoBytes(details).set("DetailsJSON").toProtoJson(details) }
  return m.build()
}

/**
 * Creates an insertion to the ComputationStages table.
 *
 * Fields required for the write are non-nullable. Any param set to null will be excluded from the
 * update mutation. Writing null values to the column is not supported
 */
fun insertComputationStage(
  localId: Long,
  stage: Long,
  nextAttempt: Long,
  creationTime: Timestamp,
  endTime: Timestamp? = null,
  details: ComputationStageDetails
): Mutation {
  return computationStage(
    Mutation::newInsertBuilder, localId, stage, nextAttempt, creationTime, endTime, details)
}

/**
 * Creates an update to the ComputationStages table.
 *
 * Fields required for the write are non-nullable. Any param set to null will be excluded from the
 * update mutation. Writing null values to the column is not supported
 */
fun updateComputationStage(
  localId: Long,
  stage: Long,
  nextAttempt: Long? = null,
  creationTime: Timestamp? = null,
  endTime: Timestamp? = null,
  details: ComputationStageDetails? = null
): Mutation {
  return computationStage(
      Mutation::newUpdateBuilder, localId, stage, nextAttempt, creationTime, endTime, details)
}

/** Creates a write a mutation for the ComputationStageAttempts spanner table. */
fun computationStageAttempt(
  newBuilderFunction: MutationBuilderFunction,
  localId: Long,
  stage: Long,
  attempt: Long,
  beginTime: Timestamp? = null,
  endTime: Timestamp? = null
): Mutation {
  val m = newBuilderFunction("ComputationStageAttempts")
  m.set("ComputationId").to(localId)
  m.set("ComputationStage").to(stage)
  m.set("Attempt").to(attempt)
  beginTime?.let { m.set("BeginTime").to(nonNullValueTimestamp(it)) }
  endTime?.let { m.set("EndTime").to(nonNullValueTimestamp(it)) }
  return m.build()
}

/**
 * Creates an insertion to the ComputationStages table.
 *
 * Fields required for the write are non-nullable. Any param set to null will be excluded from the
 * update mutation. Writing null values to the column is not supported
 */
fun insertComputationStageAttempt(
  localId: Long,
  stage: Long,
  attempt: Long,
  beginTime: Timestamp,
  endTime: Timestamp? = null
): Mutation {
  return computationStageAttempt(
    Mutation::newInsertBuilder, localId, stage, attempt, beginTime, endTime)
}

/**
 * Creates an update to the ComputationStages table.
 *
 * Fields required for the write are non-nullable. Any param set to null will be excluded from the
 * update mutation. Writing null values to the column is not supported
 */
fun updateComputationStageAttempt(
  localId: Long,
  stage: Long,
  attempt: Long,
  beginTime: Timestamp? = null,
  endTime: Timestamp? = null
): Mutation {
  return computationStageAttempt(
    Mutation::newUpdateBuilder, localId, stage, attempt, beginTime, endTime)
}