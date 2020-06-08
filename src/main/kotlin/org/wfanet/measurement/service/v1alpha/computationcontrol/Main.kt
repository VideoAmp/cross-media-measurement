package org.wfanet.measurement.service.v1alpha.computationcontrol

import org.wfanet.measurement.common.CommonServer
import org.wfanet.measurement.common.Flags
import org.wfanet.measurement.common.intFlag
import org.wfanet.measurement.common.stringFlag

fun main(args: Array<String>) {
  val port = intFlag("port", 8080)
  val nameForLogging = stringFlag("name-for-logging", "ComputationControl")
  Flags.parse(args.asIterable())

  CommonServer(nameForLogging.value, port.value, ComputationControlImpl())
    .start()
    .blockUntilShutdown()
}
