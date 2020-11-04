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

#ifndef WFA_MEASUREMENT_COMMON_CRYPTO_PROTOCOL_CRYPTOR_H_
#define WFA_MEASUREMENT_COMMON_CRYPTO_PROTOCOL_CRYPTOR_H_

#include <memory>

#include "absl/strings/string_view.h"
#include "wfa/measurement/common/crypto/ec_point_util.h"

namespace wfa::measurement::common::crypto {

// A cryptor dealing with basic operations in various MPC protocols.
class ProtocolCryptor {
 public:
  virtual ~ProtocolCryptor() = default;

  ProtocolCryptor(ProtocolCryptor&& other) = delete;
  ProtocolCryptor& operator=(ProtocolCryptor&& other) = delete;
  ProtocolCryptor(const ProtocolCryptor&) = delete;
  ProtocolCryptor& operator=(const ProtocolCryptor&) = delete;

  // Blinds a ciphertext, i.e., decrypts one layer of ElGamal encryption and
  // encrypts another layer of deterministic Pohlig Hellman encryption.
  virtual StatusOr<ElGamalCiphertext> Blind(
      const ElGamalCiphertext& ciphertext) = 0;
  // Decrypts one layer of ElGamal encryption.
  virtual StatusOr<std::string> DecryptLocalElGamal(
      const ElGamalCiphertext& ciphertext) = 0;
  // Encrypts the plain EcPoint using the composite ElGamal Key.
  virtual StatusOr<ElGamalCiphertext> EncryptCompositeElGamal(
      absl::string_view plain_ec_point) = 0;
  // ReRandomizes the ciphertext by adding an encrypted Zero to it.
  virtual StatusOr<ElGamalCiphertext> ReRandomize(
      const ElGamalCiphertext& ciphertext) = 0;
  // Calculates the SameKeyAggregation destructor using the provided base and
  // key
  virtual StatusOr<ElGamalEcPointPair> CalculateDestructor(
      const ElGamalEcPointPair& base, const ElGamalEcPointPair& key) = 0;
  // Hashes a string to the elliptical curve and return the string
  // representation of the obtained ECPoint.
  virtual StatusOr<std::string> MapToCurve(absl::string_view str) = 0;
  // Gets the equivalent ECPoint depiction of a ElGamalCiphertext
  virtual StatusOr<ElGamalEcPointPair> ToElGamalEcPoints(
      const ElGamalCiphertext& cipher_text) = 0;
  // Returns the key of the local PohligHellman cipher.
  virtual std::string GetLocalPohligHellmanKey() = 0;

 protected:
  ProtocolCryptor() = default;
};

// Create a ProtocolCryptor using keys required for internal ciphers.
StatusOr<std::unique_ptr<ProtocolCryptor>> CreateProtocolCryptorWithKeys(
    int curve_id, const ElGamalCiphertext& local_el_gamal_public_key,
    absl::string_view local_el_gamal_private_key,
    absl::string_view local_pohlig_hellman_private_key,
    const ElGamalCiphertext& composite_el_gamal_public_key);

}  // namespace wfa::measurement::common::crypto

#endif  // WFA_MEASUREMENT_COMMON_CRYPTO_PROTOCOL_CRYPTOR_H_