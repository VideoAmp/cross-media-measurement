package org.wfanet.measurement.db.duchy

import org.wfanet.measurement.internal.SketchAggregationState
import org.wfanet.measurement.internal.SketchAggregationState.*

/**
 * Implementation of [ProtocolStateEnumHelper] for [SketchAggregationState].
 */
object SketchAggregationStates :
  ProtocolStateEnumHelper<SketchAggregationState> {
  override val validInitialStates = setOf(STARTING)

  override val validSuccessors =
    mapOf(
      STARTING to setOf(WAIT_SKETCHES, ADDING_NOISE, CLEAN_UP),
      WAIT_SKETCHES to setOf(RECEIVED_SKETCHES, CLEAN_UP),
      RECEIVED_SKETCHES to setOf(ADDING_NOISE, CLEAN_UP),
      ADDING_NOISE to setOf(NOISE_ADDED, CLEAN_UP),
      NOISE_ADDED to setOf(WAIT_CONCATENATED, TRANSMITTED_SKETCH, CLEAN_UP),
      TRANSMITTED_SKETCH to setOf(WAIT_CONCATENATED, CLEAN_UP),
      WAIT_CONCATENATED to setOf(RECEIVED_CONCATENATED, CLEAN_UP),
      RECEIVED_CONCATENATED to setOf(BLINDING_POSITIONS, CLEAN_UP),
      BLINDING_POSITIONS to setOf(POSITIONS_BLINDED, CLEAN_UP),
      POSITIONS_BLINDED to setOf(COMBINING_REGISTERS, WAIT_JOINED, CLEAN_UP),
      COMBINING_REGISTERS to setOf(REGISTERS_COMBINED, CLEAN_UP),
      REGISTERS_COMBINED to setOf(WAIT_JOINED, CLEAN_UP),
      WAIT_JOINED to setOf(RECEIVED_JOINED, CLEAN_UP),
      RECEIVED_JOINED to setOf(BLINDING_COUNTS, CLEAN_UP),
      BLINDING_COUNTS to setOf(COUNTS_BLINDED, CLEAN_UP),
      COUNTS_BLINDED to setOf(COMPUTING_METRICS, WAIT_FINISHED, CLEAN_UP),
      COMPUTING_METRICS to setOf(METRICS_COMPUTED, CLEAN_UP),
      METRICS_COMPUTED to setOf(WAIT_FINISHED, CLEAN_UP),
      WAIT_FINISHED to setOf(CLEAN_UP),
      CLEAN_UP to setOf(FINISHED)
    ).withDefault { setOf() }

  override fun enumToLong(value: SketchAggregationState): Long {
    return value.ordinal.toLong()
  }

  override fun longToEnum(value: Long): SketchAggregationState {
    // forNumber() returns null for unrecognized enum values for the proto.
    return forNumber(value.toInt()) ?: UNRECOGNIZED
  }
}