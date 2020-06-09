package org.wfanet.measurement.service.internal.kingdom

import kotlinx.coroutines.flow.Flow
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.common.toInstant
import org.wfanet.measurement.db.kingdom.KingdomRelationalDatabase
import org.wfanet.measurement.db.kingdom.streamReportsFilter
import org.wfanet.measurement.internal.kingdom.CreateNextReportRequest
import org.wfanet.measurement.internal.kingdom.Report
import org.wfanet.measurement.internal.kingdom.ReportStorageGrpcKt
import org.wfanet.measurement.internal.kingdom.StreamReportsRequest
import org.wfanet.measurement.internal.kingdom.UpdateReportStateRequest

class ReportStorageService(
  private val kingdomRelationalDatabase: KingdomRelationalDatabase
) : ReportStorageGrpcKt.ReportStorageCoroutineImplBase() {
  override suspend fun createNextReport(request: CreateNextReportRequest): Report = TODO()

  override fun streamReports(request: StreamReportsRequest): Flow<Report> =
    kingdomRelationalDatabase.streamReports(
      streamReportsFilter(
        externalAdvertiserIds = request.filter.externalAdvertiserIdsList.map(::ExternalId),
        externalReportConfigIds = request.filter.externalReportConfigIdsList.map(::ExternalId),
        externalScheduleIds = request.filter.externalReportConfigIdsList.map(::ExternalId),
        states = request.filter.statesList,
        createdAfter = request.filter.createdAfter.toInstant()
      ),
      request.limit
    )

  override suspend fun updateReportState(request: UpdateReportStateRequest): Report = TODO()
}