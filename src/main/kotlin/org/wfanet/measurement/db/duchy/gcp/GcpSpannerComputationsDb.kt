package org.wfanet.measurement.db.duchy.gcp

import com.google.cloud.Timestamp
import com.google.cloud.spanner.DatabaseClient
import com.google.cloud.spanner.DatabaseId
import com.google.cloud.spanner.Key
import com.google.cloud.spanner.Mutation
import com.google.cloud.spanner.Spanner
import com.google.cloud.spanner.Statement
import com.google.cloud.spanner.TransactionContext
import java.time.Clock
import org.wfanet.measurement.common.DuchyOrder
import org.wfanet.measurement.common.DuchyRole
import org.wfanet.measurement.db.duchy.AfterTransition
import org.wfanet.measurement.db.duchy.BlobDependencyType
import org.wfanet.measurement.db.duchy.BlobId
import org.wfanet.measurement.db.duchy.BlobRef
import org.wfanet.measurement.db.duchy.ComputationToken
import org.wfanet.measurement.db.duchy.ComputationsRelationalDb
import org.wfanet.measurement.db.duchy.ProtocolStateEnumHelper
import org.wfanet.measurement.db.gcp.gcpTimestamp
import org.wfanet.measurement.db.gcp.getNullableString
import org.wfanet.measurement.db.gcp.getProtoBufMessage
import org.wfanet.measurement.db.gcp.sequence
import org.wfanet.measurement.db.gcp.singleOrNull
import org.wfanet.measurement.db.gcp.toGcpTimestamp
import org.wfanet.measurement.db.gcp.toMillis
import org.wfanet.measurement.db.gcp.toProtoBytes
import org.wfanet.measurement.db.gcp.toProtoJson
import org.wfanet.measurement.db.gcp.toProtobufMessage
import org.wfanet.measurement.internal.ComputationBlobDependency
import org.wfanet.measurement.internal.db.gcp.ComputationDetails
import org.wfanet.measurement.internal.db.gcp.ComputationStageDetails

/**
 * Implementation of [ComputationsRelationalDb] using GCP Spanner Database.
 */
class GcpSpannerComputationsDb<T : Enum<T>>(
  private val spanner: Spanner,
  private val databaseId: DatabaseId,
  private val duchyName: String,
  private val duchyOrder: DuchyOrder,
  private val blobStorageBucket: String = "knight-computation-stage-storage",
  private val stateEnumHelper: ProtocolStateEnumHelper<T>,
  private val clock: Clock = Clock.systemUTC()
) : ComputationsRelationalDb<T> {

  private val localComputationIdGenerator: LocalComputationIdGenerator =
    HalfOfGlobalBitsAndTimeStampIdGenerator(clock)

  private val databaseClient: DatabaseClient by lazy {
    spanner.getDatabaseClient(databaseId)
  }

  override fun insertComputation(globalId: Long, initialState: T): ComputationToken<T> {
    require(
      stateEnumHelper.validInitialState(initialState)
    ) { "Invalid initial state $initialState" }

    val localId: Long = localComputationIdGenerator.localId(globalId)
    val computationAtThisDuchy = duchyOrder.positionFor(globalId, duchyName)

    val details = ComputationDetails.newBuilder().apply {
      role = when (computationAtThisDuchy.role) {
        DuchyRole.PRIMARY -> ComputationDetails.RoleInComputation.PRIMARY
        else -> ComputationDetails.RoleInComputation.SECONDARY
      }
      incomingNodeId = computationAtThisDuchy.prev
      outgoingNodeId = computationAtThisDuchy.next
      blobsStoragePrefix = "$blobStorageBucket/$localId"
    }.build()

    val writeTimestamp = clock.gcpTimestamp()
    val initialStateAsInt64 = stateEnumHelper.enumToLong(initialState)
    val computationRow = Mutation.newInsertBuilder("Computations")
      .set("ComputationId").to(localId)
      .set("ComputationStage").to(initialStateAsInt64)
      .set("UpdateTime").to(writeTimestamp)
      .set("GlobalComputationId").to(globalId)
      .set("ComputationDetails").toProtoBytes(details)
      .set("ComputationDetailsJSON").toProtoJson(details)
      .build()

    // There are not any details for the initial stage when the record is being created.
    val stageDetails = ComputationStageDetails.getDefaultInstance()
    val computationStageRow = Mutation.newInsertBuilder("ComputationStages")
      .set("ComputationId").to(localId)
      .set("ComputationStage").to(initialStateAsInt64)
      .set("CreationTime").to(writeTimestamp)
      // The stage is being attempted right now.
      .set("NextAttempt").to(2)
      .set("Details").toProtoBytes(stageDetails)
      .set("DetailsJSON").toProtoJson(stageDetails)
      .build()

    val computationStageAttemptRow = Mutation.newInsertBuilder("ComputationStageAttempts")
      .set("ComputationId").to(localId)
      .set("ComputationStage").to(initialStateAsInt64)
      // The stage is being attempted right now.
      .set("Attempt").to(1)
      .set("BeginTime").to(writeTimestamp)
      .build()

    databaseClient.write(
      listOf(computationRow, computationStageRow, computationStageAttemptRow)
    )

    return ComputationToken(
      localId = localId,
      globalId = globalId,
      state = initialState,
      attempt = 1,
      owner = null,
      lastUpdateTime = writeTimestamp.toMillis(),
      role = computationAtThisDuchy.role,
      nextWorker = details.outgoingNodeId
    )
  }

  override fun getToken(globalId: Long): ComputationToken<T>? {
    val sql =
      """
      SELECT c.ComputationId, c.LockOwner, c.ComputationStage, c.ComputationDetails,
             c.UpdateTime, cs.NextAttempt
      FROM Computations AS c
      JOIN ComputationStages AS cs USING (ComputationId, ComputationStage)
      WHERE c.GlobalComputationId = @global_id
      """.trimIndent()

    val query: Statement =
      Statement.newBuilder(sql)
        .bind("global_id").to(globalId)
        .build()

    val struct = databaseClient.singleUse().executeQuery(query).singleOrNull() ?: return null

    val computationDetails =
      struct
        .getBytes("ComputationDetails")
        .toProtobufMessage(ComputationDetails.parser())

    return ComputationToken(
      globalId = globalId,
      // From ComputationsByGlobalId index
      localId = struct.getLong("ComputationId"),
      // From Computations
      state = stateEnumHelper.longToEnum(struct.getLong("ComputationStage")),
      owner = struct.getNullableString("LockOwner"),
      lastUpdateTime = struct.getTimestamp("UpdateTime").toMillis(),
      role = computationDetails.role.toDuchyRole(),
      nextWorker = computationDetails.outgoingNodeId,
      // From ComputationStages
      attempt = struct.getLong("NextAttempt") - 1
    )
  }

  override fun enqueue(token: ComputationToken<T>) {
    runIfTokenFromLastUpdate(token) { ctx ->
      ctx.buffer(
        Mutation.newUpdateBuilder("Computations")
          .set("ComputationId").to(token.localId)
          .set("UpdateTime").to(clock.gcpTimestamp())
          // Release any lock on this computation. The owner says who has the current
          // lock on the computation, and the expiration time states both if and when the
          // computation can be worked on. When LockOwner is null the computation is not being
          // worked on, but that is not enough to say a knight should pick up the computation
          // as its quest as there are stages which waiting for inputs from other nodes.
          // A non-null LockExpirationTime states when a computation can be be taken up
          // by a knight, and by using the commit timestamp we pretty much get the behaviour
          // of a FIFO queue by querying the ComputationsByLockExpirationTime secondary index.
          .set("LockOwner").to(null as String?)
          .set("LockExpirationTime").to(clock.gcpTimestamp())
          .build()
      )
    }
  }

  override fun claimTask(ownerId: String): ComputationToken<T>? {
    // First the possible tasks to claim are selected from the computations table, then for each
    // item in the list we try to claim the lock in a transaction which will only succeed if the
    // lock is still available. This pattern means only the item which is being updated
    // would need to be locked and not every possible computation that can be worked on.
    val sql =
      """
      SELECT c.ComputationId,  c.GlobalComputationId, c.UpdateTime
      FROM Computations@{FORCE_INDEX=ComputationsByLockExpirationTime} AS c
      WHERE c.LockExpirationTime <= @current_time
      ORDER BY c.LockExpirationTime ASC, c.UpdateTime ASC
      LIMIT 50
      """.trimIndent()

    databaseClient
      .singleUse()
      .executeQuery(Statement.newBuilder(sql).bind("current_time").to(clock.gcpTimestamp()).build())
      .sequence()
      .forEach { struct ->
        if (claim(struct.getLong("ComputationId"), struct.getTimestamp("UpdateTime"), ownerId)) {
          return getToken(struct.getLong("GlobalComputationId"))
        }
      }

    // Did not acquire the lock on any computation.
    return null
  }

  /**
   * Claims a specific computation for an owner.
   *
   * @return true if the lock was acquired. */
  private fun claim(computationId: Long, lastUpdate: Timestamp, ownerId: String): Boolean {
    return databaseClient.readWriteTransaction().run { ctx ->
      val currentLockOwnerStruct =
        ctx.readRow("Computations", Key.of(computationId), listOf("UpdateTime"))
          ?: error("Failed to claim computation $computationId. It does not exist.")
      // Verify that the row hasn't been updated since the previous, non-transactional read.
      if (currentLockOwnerStruct.getTimestamp("UpdateTime") == lastUpdate) {
        ctx.buffer(setLockMutation(computationId, ownerId))
        // TODO(fryej): Set the Begin time in ComputationStageAttempts table.
        return@run true
      }
      return@run false
    } ?: error("claim for a specific computation ($computationId) returned a null value")
  }

  private fun setLockMutation(computationId: Long, ownerId: String): Mutation {
    return Mutation.newUpdateBuilder("Computations")
      .set("ComputationId").to(computationId)
      .set("LockOwner").to(ownerId)
      .set("LockExpirationTime").to(fiveMinutesInTheFuture())
      .set("UpdateTime").to(clock.gcpTimestamp())
      .build()
  }

  private fun fiveMinutesInTheFuture() = clock.instant().plusSeconds(300).toGcpTimestamp()

  override fun renewTask(token: ComputationToken<T>): ComputationToken<T> {
    val owner = checkNotNull(token.owner) { "Cannot renew lock for computation with no owner." }
    runIfTokenFromLastUpdate(token) { it.buffer(setLockMutation(token.localId, owner)) }
    return getToken(token.globalId)
      ?: error("Failed to renew lock on computation (${token.globalId}, it does not exist.")
  }

  override fun updateComputationState(
    token: ComputationToken<T>,
    to: T,
    blobInputRefs: Collection<BlobRef>,
    blobOutputRefs: Collection<BlobId>,
    afterTransition: AfterTransition
  ): ComputationToken<T> {
    require(
      stateEnumHelper.validTransition(token.state, to)
    ) { "Invalid state transition ${token.state} -> $to" }

    runIfTokenFromLastUpdate(token) { ctx ->
      val writeTime = clock.gcpTimestamp()
      val newStageAsInt64 = stateEnumHelper.enumToLong(to)

      ctx.buffer(
        mutationsToChangeStages(
          ctx,
          token,
          newStageAsInt64,
          writeTime,
          afterTransition
        )
      )

      ctx.buffer(
        mutationsToMakeBlobRefsForNewStage(
          token.localId,
          newStageAsInt64,
          blobInputRefs,
          blobOutputRefs
        )
      )
    }
    return getToken(token.globalId) ?: error("Computation $token no longer exists.")
  }

  private fun mutationsToChangeStages(
    ctx: TransactionContext,
    token: ComputationToken<T>,
    newStageAsInt64: Long,
    writeTime: Timestamp,
    afterTransition: AfterTransition
  ): List<Mutation> {
    val currentStageAsInt64 = stateEnumHelper.enumToLong(token.state)
    val currentStageDetails = ctx.readRow(
      "ComputationStages",
      Key.of(token.localId, currentStageAsInt64),
      listOf("Details")
    ) ?: error("No row for (${token.localId}, $currentStageAsInt64)")

    val existingStageDetails =
      currentStageDetails.getProtoBufMessage("Details", ComputationStageDetails.parser())
        .toBuilder()
        .setFollowingStageValue(newStageAsInt64.toInt())
        .build()

    val computation = Mutation.newInsertOrUpdateBuilder("Computations")
      .set("ComputationId").to(token.localId)
      .set("ComputationStage").to(newStageAsInt64)
      .set("UpdateTime").to(writeTime)
    val existingStage = Mutation.newUpdateBuilder("ComputationStages")
      .set("ComputationId").to(token.localId)
      .set("ComputationStage").to(currentStageAsInt64)
      .set("EndTime").to(writeTime)
      .set("Details").toProtoBytes(existingStageDetails)
      .set("DetailsJSON").toProtoJson(existingStageDetails)
    val existingAttempt = Mutation.newUpdateBuilder("ComputationStageAttempts")
      .set("ComputationId").to(token.localId)
      .set("ComputationStage").to(currentStageAsInt64)
      .set("Attempt").to(token.attempt)
      .set("EndTime").to(writeTime)

    val newStageDetails = ComputationStageDetails.newBuilder().apply {
      previousStageValue = currentStageAsInt64.toInt()
      // TODO(fryej): Set stage_specific field.
    }.build()
    val newStage = Mutation.newInsertBuilder("ComputationStages")
      .set("ComputationId").to(token.localId)
      .set("ComputationStage").to(newStageAsInt64)
      .set("NextAttempt").to(2L)
      .set("CreationTime").to(writeTime)
      .set("Details").toProtoBytes(newStageDetails)
      .set("DetailsJSON").toProtoJson(newStageDetails)
    val newAttempt = Mutation.newInsertBuilder("ComputationStageAttempts")
      .set("ComputationId").to(token.localId)
      .set("ComputationStage").to(newStageAsInt64)
      .set("Attempt").to(1)

    when (afterTransition) {
      AfterTransition.CONTINUE_WORKING -> {
        /** like [claimTask] on the computation in same transaction. */
        newAttempt.set("BeginTime").to(writeTime)
        computation.set("LockExpirationTime").to(fiveMinutesInTheFuture())
      }
      AfterTransition.ADD_UNCLAIMED_TO_QUEUE -> {
        /** like [enqueue] the computation in same transaction. */
        computation
          .set("LockOwner").to(null as String?)
          .set("LockExpirationTime").to(writeTime)
      }
      AfterTransition.DO_NOT_ADD_TO_QUEUE -> {
        // Release the lock and remove from queue.
        computation
          .set("LockOwner").to(null as String?)
          .set("LockExpirationTime").to(null as Timestamp?)
      }
    }
    return listOf(
      computation.build(),
      existingStage.build(),
      existingAttempt.build(),
      newStage.build(),
      newAttempt.build()
    )
  }

  private fun mutationsToMakeBlobRefsForNewStage(
    localId: Long,
    stageAsInt64: Long,
    blobInputRefs: Collection<BlobRef>,
    blobOutputRefs: Collection<BlobId>
  ): List<Mutation> {
    val mutations = ArrayList<Mutation>()
    blobInputRefs.mapIndexedTo(mutations) { index, blobRef ->
      Mutation.newInsertBuilder("ComputationBlobReferences")
        .set("ComputationId").to(localId)
        .set("ComputationStage").to(stageAsInt64)
        .set("BlobId").to(index.toLong())
        .set("PathToBlob").to(blobRef.pathToBlob)
        .set("DependencyType").to(ComputationBlobDependency.INPUT.ordinal.toLong())
        .build()
    }

    blobOutputRefs.mapIndexedTo(mutations) { index, _ ->
      Mutation.newInsertBuilder("ComputationBlobReferences")
        .set("ComputationId").to(localId)
        .set("ComputationStage").to(stageAsInt64)
        .set("BlobId").to(index.toLong() + blobInputRefs.size)
        .set("DependencyType").to(ComputationBlobDependency.OUTPUT.ordinal.toLong())
        .build()
    }
    return mutations
  }

  override fun readBlobReferences(
    token: ComputationToken<T>,
    dependencyType: BlobDependencyType
  ): Map<BlobId, String?> {
    val sql =
      """
      SELECT BlobId, PathToBlob, DependencyType 
      FROM ComputationBlobReferences
      WHERE ComputationId = @local_id AND ComputationStage = @stage_as_int_64
      """.trimMargin()
    val blobRefsForStageQuery =
      Statement.newBuilder(sql)
        .bind("local_id").to(token.localId)
        .bind("stage_as_int_64").to(stateEnumHelper.enumToLong(token.state))
        .build()

    return runIfTokenFromLastUpdate(token) { ctx ->
      ctx
        .executeQuery(blobRefsForStageQuery)
        .sequence()
        .filter {
          val dep = ComputationBlobDependency.forNumber(it.getLong("DependencyType").toInt())
          when (dependencyType) {
            BlobDependencyType.ANY -> true
            BlobDependencyType.OUTPUT -> dep == ComputationBlobDependency.OUTPUT
            BlobDependencyType.INPUT -> dep == ComputationBlobDependency.INPUT
          }
        }
        .map { it.getLong("BlobId") to it.getNullableString("PathToBlob") }
        .toMap()
    }!!
  }

  override fun writeOutputBlobReference(token: ComputationToken<T>, blobName: BlobRef) {
    TODO("Not yet implemented")
  }

  /**
   * Runs the readWriteTransactionFunction if the ComputationToken is from the most recent
   * update to a computation. This is done atomically with in read/write transaction.
   *
   * @return [R] which is the result of the readWriteTransactionBlock
   * @throws IllegalStateException if the token is not for the most recent update.
   */
  private fun <R> runIfTokenFromLastUpdate(
    token: ComputationToken<T>,
    readWriteTransactionBlock: (TransactionContext) -> R
  ): R? {
    return databaseClient.readWriteTransaction().run { ctx ->
      val current = ctx.readRow("Computations", Key.of(token.localId), listOf("UpdateTime"))
        ?: error("No row for computation (${token.localId})")
      if (current.getTimestamp("UpdateTime").toMillis() == token.lastUpdateTime) {
        readWriteTransactionBlock(ctx)
      } else {
        error("Failed to update, token is from older update time.")
      }
    }
  }
}

private fun ComputationDetails.RoleInComputation.toDuchyRole(): DuchyRole {
  return when (this) {
    ComputationDetails.RoleInComputation.PRIMARY -> DuchyRole.PRIMARY
    ComputationDetails.RoleInComputation.SECONDARY -> DuchyRole.SECONDARY
    else -> error("Unknown role $this")
  }
}
