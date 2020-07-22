// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.wfanet.measurement.db.duchy

import org.wfanet.measurement.common.DuchyRole

/**
 * Information about a computation.
 */
data class ComputationToken<StageT : Enum<StageT>>(
  /** The identifier for the computation used locally. */
  val localId: Long,
  /** The identifier for the computation used across all systems. */
  val globalId: Long,
  /** The stage of the computation when the token was created. */
  val stage: StageT,
  /** Name of mill that owns the lock on the computation. */
  val owner: String?,
  /** Identifier of the duchy that receives work for this computation. */
  val nextWorker: String,
  /** The role this worker is playing for this computation. */
  val role: DuchyRole,
  /** The number of the current attempt of this stage for this computation. */
  val attempt: Long,
  /** The last time the computation was updated in number of milliseconds since the epoch. */
  val lastUpdateTime: Long
)

/**
 * Specifies what to do with the lock on a computation after transitioning to a new stage.
 *
 * @see[ComputationsRelationalDb.updateComputationStage]
 */
enum class AfterTransition {
  /** Retain and extend the lock for the current owner. */
  CONTINUE_WORKING,

  /**
   * Add the computation to the work queue, but in an unclaimed stage for some
   * worker to claim at a later time.
   */
  ADD_UNCLAIMED_TO_QUEUE,

  /**
   * Do not add to the work queue, and release any lock on the computation.
   * There is no work to be done on the computation at this time.
   * Examples for when to set this include the computation finished or
   * input from another source is required before continuing.
   */
  DO_NOT_ADD_TO_QUEUE
}

/**
 * Specifies why a computation has ended.
 *
 * @see[ComputationsRelationalDb.endComputation]
 */
enum class EndComputationReason {
  /** Computation went the expected execution and succeeded. */
  SUCCEEDED,
  /** Computation failed and will not be retried again. */
  FAILED,
  /**
   * The computation was canceled. There were not known issues when it was ended, but results
   * will not be obtained.
   */
  CANCELED
}

/**
 * Relational database for keeping track of stages of a Computation within an
 * MPC Node.
 *
 * The database must have strong consistency guarantees as it is used to
 * coordinate assignment of work by pulling jobs.
 */
interface ComputationsRelationalDb<StageT : Enum<StageT>, StageDetailsT> {

  /**
   * Inserts a new computation for the global identifier.
   *
   * A new local identifier is created and returned in the [ComputationToken]. The computation is
   * not added to the queue.
   */
  suspend fun insertComputation(globalId: Long, initialStage: StageT): ComputationToken<StageT>

  /** Returns a [ComputationToken] for the most recent computation for a [globalId]. */
  suspend fun getToken(globalId: Long): ComputationToken<StageT>?

  /**
   * Adds a computation to the work queue, saying it can be worked on by a worker job.
   *
   * This will release any ownership and locks associated with the computation.
   */
  suspend fun enqueue(token: ComputationToken<StageT>)

  /**
   * Query for Computations with tasks ready for processing, and claim one for an owner.
   *
   * @param[ownerId] The identifier of the worker process that will own the lock.
   * @return [ComputationToken] work that was claimed. When null, no work was claimed.
   */
  suspend fun claimTask(ownerId: String): ComputationToken<StageT>?

  /** Extends the time a computation is locked. */
  suspend fun renewTask(token: ComputationToken<StageT>): ComputationToken<StageT>

  /**
   * Transitions a computation to a new stage.
   *
   * @param[token] The token for the computation
   * @param[to] Stage this computation should transition to.
   * @param[inputBlobPaths] References to BLOBs that are inputs to this computation stage, all
   *    inputs should be written on transition and should not change.
   * @param[outputBlobs] Number of BLOBs this computation outputs. These are created as
   *    part of the computation so they do not have a reference to the real storage location.
   * @param[afterTransition] The work to be do with the computation after a successful transition.
   */
  suspend fun updateComputationStage(
    token: ComputationToken<StageT>,
    to: StageT,
    inputBlobPaths: List<String>,
    outputBlobs: Int,
    afterTransition: AfterTransition
  ): ComputationToken<StageT>

  /** Moves a computation to a terminal state and records the reason why it ended. */
  suspend fun endComputation(
    token: ComputationToken<StageT>,
    endingStage: StageT,
    endComputationReason: EndComputationReason
  )

  /** Reads mappings of blob names to paths in blob storage. */
  suspend fun readBlobReferences(
    token: ComputationToken<StageT>,
    dependencyType: BlobDependencyType = BlobDependencyType.INPUT
  ): Map<BlobId, String?>

  /** Reads details for a specific stage of a computation. */
  suspend fun readStageSpecificDetails(token: ComputationToken<StageT>): StageDetailsT

  /** Writes the reference to a BLOB needed for [BlobDependencyType.OUTPUT] from a stage. */
  suspend fun writeOutputBlobReference(token: ComputationToken<StageT>, blobName: BlobRef)

  /**
   * Gets the global computation ids based on stage.
   *
   * @param [stages] return ids for computations only if they are in this stage
   */
  suspend fun readGlobalComputationIds(stages: Set<StageT>): Set<Long>
}

/**
 * The identifier of a Blob */
typealias BlobId = Long

/** Reference to a named BLOB's storage location. */
data class BlobRef(val name: BlobId, val pathToBlob: String)

/** BLOBs storage used by a computation. */
interface ComputationsBlobDb<StageT : Enum<StageT>> {

  /** Reads and returns a BLOB from storage */
  suspend fun read(reference: BlobRef): ByteArray

  /** Write a BLOB and ensure it is fully written before returning. */
  suspend fun blockingWrite(blob: BlobRef, bytes: ByteArray) = blockingWrite(blob.pathToBlob, bytes)

  /** Write a BLOB and ensure it is fully written before returning. */
  suspend fun blockingWrite(path: String, bytes: ByteArray)

  /** Deletes a BLOB */
  suspend fun delete(reference: BlobRef)

  /** Returns a path where to write a blob for a computation stage. */
  suspend fun newBlobPath(token: ComputationToken<StageT>, name: String): String
}

/** The way in which a stage depends upon a BLOB. */
enum class BlobDependencyType {
  /** BLOB is used as an input to the computation stage. */
  INPUT,

  /** BLOB is an output of the computation stage. */
  OUTPUT,

  /**
   * ONLY makes sense when reading blobs from the database. Read all blobs
   * no mater the type.
   */
  ANY;
}
