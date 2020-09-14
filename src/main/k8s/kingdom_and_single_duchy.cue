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

// cue cmd dump src/main/k8s/kingdom_and_single_duchy.cue >
// src/main/k8s/kingdom_and_single_duchy_from_cue.yaml

package kingdom_and_single_duchy

import (
	"encoding/yaml"
	"tool/cli"
)

command: dump: task: print: cli.Print & {
	text: """
          # Do NOT edit this file by hand.
          # This file is generated by kingdom_and_single_duchy.cue\n\n
          """ + yaml.MarshalStream(objects)
}

objects: [ for v in objectSets for x in v {x}]

objectSets: [
	fake_service,
	duchy_service,
	kingdom_service,
	fake_pod,
	duchy_pod,
	kingdom_pod,
	setup_job,
]

#Port: {
	name:       string
	port:       uint16
	protocol:   "TCP" | "UDP"
	targetPort: uint16
}

#GrpcService: {
	_name:      string
	apiVersion: "v1"
	kind:       "Service"
	metadata: {
		name: _name
		annotations?: system?: string
	}
	spec: {
		selector: app: _name + "-app"
		type: "ClusterIP"
		ports: [{
			name:       "port"
			port:       8080
			protocol:   "TCP"
			targetPort: 8080
		}]
	}
}

#Pod: {
	_name:  string
	_image: string
	_args: [...string]
	_ports:     [{containerPort: 8080}] | *[]
	_restartPolicy: string | *"Always"
	apiVersion: "v1"
	kind:       "Pod"
	metadata: {
		name: _name + "-pod"
		labels: app:           _name + "-app"
		annotations?: system?: string
	}
	spec: {
		containers: [{
			name:            _name + "-container"
			image:           _image
			imagePullPolicy: "Never"
			args:            _args
			ports:           _ports
		}]
		restartPolicy: _restartPolicy
	}
}

#ServerPod: #Pod & {
	_ports: [{containerPort: 8080}]

}

#DuchyPublicKeysConfig:
	#"""
		entries {
		  key: "combined-public-key-1"
		  value {
			combined_public_key_version: 1
			elliptic_curve_id: 415
			el_gamal_generator: "\x03\x6B\x17\xD1\xF2\xE1\x2C\x42\x47\xF8\xBC\xE6\xE5\x63\xA4\x40\xF2\x77\x03\x7D\x81\x2D\xEB\x33\xA0\xF4\xA1\x39\x45\xD8\x98\xC2\x96"
			el_gamal_elements {
			  key: "test-duchy-1"
			  value: "\x02\xD1\x43\x2C\xA0\x07\xA6\xC6\xD7\x39\xFC\xE2\xD2\x1F\xEB\x56\xD9\xA2\xC3\x5C\xF9\x68\x26\x5F\x90\x93\xC4\xB6\x91\xE1\x13\x86\xB3"
			}
			el_gamal_elements {
			  key: "test-duchy-2"
			  value: "\x03\x9E\xF3\x70\xFF\x4D\x21\x62\x25\x40\x17\x81\xD8\x8A\x03\xF5\xA6\x70\xA5\x04\x0E\x63\x33\x49\x2C\xB4\xE0\xCD\x99\x1A\xBB\xD5\xA3"
			}
			el_gamal_elements {
			  key: "test-duchy-3"
			  value: "\x02\xD0\xF2\x5A\xB4\x45\xFC\x9C\x29\xE7\xE2\x50\x9A\xDC\x93\x30\x84\x30\xF4\x32\x52\x2F\xFA\x93\xC2\xAE\x73\x7C\xEB\x48\x0B\x66\xD7"
			}
			combined_el_gamal_element: "\x02\x50\x5D\x7B\x3A\xC4\xC3\xC3\x87\xC7\x41\x32\xAB\x67\x7A\x34\x21\xE8\x83\xB9\x0D\x4C\x83\xDC\x76\x6E\x40\x0F\xE6\x7A\xCC\x1F\x04"
		  }
		}
		"""#

fake_service: "spanner-emulator": {
	apiVersion: "v1"
	kind:       "Service"
	metadata: name: "spanner-emulator"
	spec: {
		selector: app: "spanner-emulator-app"
		type: "ClusterIP"
		ports: [{
			name:       "grpc"
			port:       9010
			protocol:   "TCP"
			targetPort: 9010
		}, {
			name:       "http"
			port:       9020
			protocol:   "TCP"
			targetPort: 9020
		}]
	}
}

fake_service: "fake-storage-server": #GrpcService & {
	_name: "fake-storage-server"
	metadata: annotations: system: "testing"
}

duchy_service: "gcs-liquid-legions-server": #GrpcService & {
	_name: "gcs-liquid-legions-server"
	metadata: annotations: system: "duchy"
}

duchy_service: "spanner-liquid-legions-computation-storage-server": #GrpcService & {
	_name: "spanner-liquid-legions-computation-storage-server"
	metadata: annotations: system: "duchy"
}

duchy_service: "gcp-server": #GrpcService & {
	_name: "gcp-server"
	metadata: annotations: system: "duchy"
}

duchy_service: "publisher-data-server": #GrpcService & {
	_name: "publisher-data-server"
	metadata: annotations: system: "duchy"
}

kingdom_service: "gcp-kingdom-storage-server": #GrpcService & {
	_name: "gcp-kingdom-storage-server"
	metadata: annotations: system: "kingdom"
}

kingdom_service: "global-computation-server": #GrpcService & {
	_name: "global-computation-server"
	metadata: annotations: system: "kingdom"
}

kingdom_service: "requisition-server": #GrpcService & {
	_name: "requisition-server"
	metadata: annotations: system: "kingdom"
}

fake_pod: "spanner-emulator-pod": {
	apiVersion: "v1"
	kind:       "Pod"
	metadata: {
		name: "spanner-emulator-pod"
		labels: app: "spanner-emulator-app"
	}
	spec: containers: [{
		name:  "spanner-emulator-container"
		image: "gcr.io/cloud-spanner-emulator/emulator"
	}]
}

duchy_pod: "liquid-legions-herald-daemon-pod": #Pod & {
	_name:  "liquid-legions-herald-daemon"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/duchy/herald:liquid_legions_herald_daemon_image"
	metadata: annotations: system: "duchy"
	_args: [
		"--channel-shutdown-timeout=3s",
		"--computation-storage-service-target=$(SPANNER_LIQUID_LEGIONS_COMPUTATION_STORAGE_SERVER_SERVICE_HOST):$(SPANNER_LIQUID_LEGIONS_COMPUTATION_STORAGE_SERVER_SERVICE_PORT)",
		"--duchy-name=test-duchy-1",
		"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
		"--global-computation-service-target=$(GLOBAL_COMPUTATION_SERVER_SERVICE_HOST):$(GLOBAL_COMPUTATION_SERVER_SERVICE_PORT)",
		"--polling-interval=1m",
	]
}
duchy_pod: "gcs-liquid-legions-mill-daemon-pod": #Pod & {
	_name:  "gcs-liquid-legions-mill-daemon"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/duchy/mill:gcs_liquid_legions_mill_daemon_image"
	metadata: annotations: system: "duchy"
	_args: [
		"--bytes-per-chunk=2000000",
		"--channel-shutdown-timeout=3s",
		"--computation-control-service-target=test-duchy-2=localhost:9002",
		"--computation-control-service-target=test-duchy-3=localhost:9003",
		"--computation-storage-service-target=$(SPANNER_LIQUID_LEGIONS_COMPUTATION_STORAGE_SERVER_SERVICE_HOST):$(SPANNER_LIQUID_LEGIONS_COMPUTATION_STORAGE_SERVER_SERVICE_PORT)",
		"--duchy-name=test-duchy-1",
		"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
		"--duchy-secret-key=057b22ef9c4e9626c22c13daed1363a1e6a5b309a930409f8d131f96ea2fa888",
		"--global-computation-service-target=$(GLOBAL_COMPUTATION_SERVER_SERVICE_HOST):$(GLOBAL_COMPUTATION_SERVER_SERVICE_PORT)",
		"--google-cloud-storage-bucket=",
		"--google-cloud-storage-project=",
		"--liquid-legions-decay-rate=12.0",
		"--liquid-legions-size=100000",
		"--metric-values-service-target=$(GCP_SERVER_SERVICE_HOST):$(GCP_SERVER_SERVICE_PORT)",
		"--mill-id=test-mill-1",
		"--polling-interval=1s",
	]
}
kingdom_pod: "report-maker-daemon-pod": #Pod & {
	_name:  "report-maker-daemon"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/kingdom:report_maker_daemon_image"
	metadata: annotations: system: "kingdom"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_HOST):$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_PORT)",
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
}
kingdom_pod: "report-starter-daemon-pod": #Pod & {
	_name:  "report-starter-daemon-pod"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/kingdom:report_starter_daemon_image"
	metadata: annotations: system: "kingdom"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_HOST):$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_PORT)",
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
}
kingdom_pod: "requisition-linker-daemon-pod": #Pod & {
	_name:  "requisition-linker-daemon"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/kingdom:requisition_linker_daemon_image"
	metadata: annotations: system: "kingdom"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_HOST):$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_PORT)",
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
}
duchy_pod: "gcs-liquid-legions-server-pod": #ServerPod & {
	_name:  "gcs-liquid-legions-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/duchy/computation/control:gcs_liquid_legions_server_image"
	metadata: annotations: system: "duchy"
	_args: [
		"--computation-storage-service-target=$(SPANNER_LIQUID_LEGIONS_COMPUTATION_STORAGE_SERVER_SERVICE_HOST):$(SPANNER_LIQUID_LEGIONS_COMPUTATION_STORAGE_SERVER_SERVICE_PORT)",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-name=test-duchy-1",
		"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
		"--google-cloud-storage-bucket=",
		"--google-cloud-storage-project=",
		"--port=8080",
	]
}
duchy_pod: "spanner-liquid-legions-computation-storage-server-pod": #ServerPod & {
	_name:  "spanner-liquid-legions-computation-storage-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/duchy/computation/storage:spanner_liquid_legions_computation_storage_server_image"
	metadata: annotations: system: "duchy"
	_args: [
		"--channel-shutdown-timeout=3s",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-name=test-duchy-1",
		"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
		"--global-computation-service-target=$(GLOBAL_COMPUTATION_SERVER_SERVICE_HOST):$(GLOBAL_COMPUTATION_SERVER_SERVICE_PORT)",
		"--port=8080",
		"--spanner-database=duchy_computations",
		"--spanner-emulator-host=$(SPANNER_EMULATOR_SERVICE_HOST):$(SPANNER_EMULATOR_SERVICE_PORT)",
		"--spanner-instance=emulator-instance",
		"--spanner-project=PrivateReachAndFrequencyEstimator",
	]
}
duchy_pod: "gcp-server-pod": #ServerPod & {
	_name:  "gcp-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/duchy/metricvalues:gcp_server_image"
	metadata: annotations: system: "duchy"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--google-cloud-storage-bucket=",
		"--google-cloud-storage-project=",
		"--port=8080",
		"--spanner-database=duchy_computations",
		"--spanner-emulator-host=$(SPANNER_EMULATOR_SERVICE_HOST):$(SPANNER_EMULATOR_SERVICE_PORT)",
		"--spanner-instance=emulator-instance",
		"--spanner-project=PrivateReachAndFrequencyEstimator",
	]
}
kingdom_pod: "gcp-kingdom-storage-server-pod": #ServerPod & {
	_name:  "gcp-kingdom-storage-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/kingdom:gcp_kingdom_storage_server_image"
	metadata: annotations: system: "kingdom"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=test-duchy-1",
		"--port=8080",
		"--spanner-database=kingdom",
		"--spanner-emulator-host=$(SPANNER_EMULATOR_SERVICE_HOST):$(SPANNER_EMULATOR_SERVICE_PORT)",
		"--spanner-instance=emulator-instance",
		"--spanner-project=PrivateReachAndFrequencyEstimator",
	]
}
fake_pod: "fake-storage-server-pod": #ServerPod & {
	metadata: annotations: system: "testing"
	_name:  "fake-storage-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/testing/storage:fake_storage_server_image"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--port=8080",
	]
}
kingdom_pod: "global-computation-server-pod": #ServerPod & {
	_name:  "global-computation-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/v1alpha/globalcomputation:global_computation_server_image"
	metadata: annotations: system: "kingdom"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=test-duchy-1",
		"--duchy-ids=test-duchy-2",
		"--duchy-ids=test-duchy-3",
		"--internal-api-target=$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_HOST):$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_PORT)",
		"--port=8080",
	]
}
duchy_pod: "publisher-data-server-pod": #ServerPod & {
	_name:  "publisher-data-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/v1alpha/publisherdata:publisher_data_server_image"
	metadata: annotations: system: "duchy"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-name=test-duchy-1",
		"--metric-values-service-target=$(GCP_SERVER_SERVICE_HOST):$(GCP_SERVER_SERVICE_PORT)",
		"--port=8080",
		"--registration-service-target=127.0.0.1:9000", // TODO: change once implemented.
		"--requisition-service-target=$(REQUISITION_SERVER_SERVICE_HOST):$(REQUISITION_SERVER_SERVICE_PORT)",
	]
}
kingdom_pod: "requisition-server-pod": #ServerPod & {
	_name:  "requisition-server"
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/v1alpha/requisition:requisition_server_image"
	metadata: annotations: system: "kingdom"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=test-duchy-1",
		"--duchy-ids=test-duchy-2",
		"--duchy-ids=test-duchy-3",
		"--internal-api-target=$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_HOST):$(GCP_KINGDOM_STORAGE_SERVER_SERVICE_PORT)",
		"--port=8080",
	]
}

setup_job: "push-spanner-schema-job": {
	apiVersion: "batch/v1"
	kind:       "Job"
	metadata: name: "push-spanner-schema-job"
	spec: template: spec: {
		containers: [{
			name:            "push-spanner-schema-container"
			image:           "bazel/src/main/kotlin/org/wfanet/measurement/tools:push_spanner_schema_image"
			imagePullPolicy: "Never"
			args: [
				"--create-instance",
				"--databases=duchy_computations=/app/wfa_measurement_system/src/main/db/gcp/computations.sdl",
				"--databases=duchy_metric_values=/app/wfa_measurement_system/src/main/db/gcp/metric_values.sdl",
				"--databases=kingdom=/app/wfa_measurement_system/src/main/db/gcp/kingdom.sdl",
				"--emulator-host=$(SPANNER_EMULATOR_SERVICE_HOST):$(SPANNER_EMULATOR_SERVICE_PORT)",
				"--instance-config-id=spanner-emulator",
				"--instance-display-name=EmulatorInstance",
				"--instance-name=emulator-instance",
				"--instance-node-count=1",
				"--project-name=PrivateReachAndFrequencyEstimator",
			]
		}]
		restartPolicy: "OnFailure"
	}
}
