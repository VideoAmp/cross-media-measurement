package org.wfanet.measurement.db.kingdom.gcp

import com.google.cloud.spanner.Mutation
import com.google.cloud.spanner.TransactionContext
import com.google.cloud.spanner.Value
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.db.gcp.executeSqlQuery
import org.wfanet.measurement.db.gcp.spannerDispatcher
import org.wfanet.measurement.db.gcp.toGcpTimestamp
import org.wfanet.measurement.db.gcp.toSpannerByteArray
import org.wfanet.measurement.internal.kingdom.Requisition

/**
 * Persists a Requisition in Spanner if it doesn't yet exist.
 *
 * Idempotency is determined by the Data Provider, Campaign, time window, and RequisitionDetails.
 */
class CreateRequisitionTransaction {
  /**
   * Runs the transaction body.
   *
   * This does not enforce any preconditions on [requisition]. For example, there is no guarantee
   * that the startTime is before the endTime or the state is valid.
   *
   * @param[transactionContext] the transaction to use
   * @param[requisition] the new [Requisition]
   * @return the existing [Requisition] or null
   */
  fun execute(
    transactionContext: TransactionContext,
    requisition: Requisition
  ): Requisition? = runBlocking(spannerDispatcher()) {
    val existing = findExistingRequisition(transactionContext, requisition)
    if (existing == null) transactionContext.buffer(requisition.toInsertMutation())
    existing
  }

  private suspend fun findExistingRequisition(
    transactionContext: TransactionContext,
    newRequisition: Requisition
  ): Requisition? {
    val whereClause =
      """
      WHERE DataProviders.ExternalDataProviderId = @external_data_provider_id
        AND Campaigns.ExternalCampaignId = @external_campaign_id
        AND Requisitions.WindowStartTime = @window_start_time
        AND Requisitions.WindowEndTime = @window_end_time
      """.trimIndent()

    val statement = REQUISITION_READ_QUERY
      .toBuilder()
      .append(whereClause)
      .bind("external_data_provider_id").to(newRequisition.externalDataProviderId)
      .bind("external_campaign_id").to(newRequisition.externalCampaignId)
      .bind("window_start_time").to(newRequisition.windowStartTime.toGcpTimestamp())
      .bind("window_end_time").to(newRequisition.windowEndTime.toGcpTimestamp())
      .build()

    return transactionContext
      .executeSqlQuery(statement)
      .map { it.toRequisition() }
      .filter { it.requisitionDetails == newRequisition.requisitionDetails }
      .firstOrNull()
  }

  private fun Requisition.toInsertMutation(): Mutation =
    Mutation.newInsertBuilder("Requisitions")
      .addPrimaryKey(this)
      .set("ExternalRequisitionId").to(externalRequisitionId)
      .set("WindowStartTime").to(windowStartTime.toGcpTimestamp())
      .set("WindowEndTime").to(windowEndTime.toGcpTimestamp())
      .set("CreateTime").to(Value.COMMIT_TIMESTAMP)
      .set("State").to(state.ordinal.toLong())
      .set("RequisitionDetails").to(requisitionDetails.toSpannerByteArray())
      .set("RequisitionDetailsJson").to(requisitionDetailsJson)
      .build()
}