#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

// Portable fixed-layout credential-vault record. It deliberately has no
// Arduino, filesystem, or cryptography dependency so corruption/rollover
// handling can be tested on the host and the ESP8266 uses identical bytes.
namespace smartplug_credentials {

constexpr uint32_t kMagic = 0x53504356U;  // SPCV
constexpr uint32_t kSchema = 1U;
constexpr uint8_t kMaxCredentials = 4U;
constexpr size_t kCredentialIdBytes = 13U;  // 12 visible ASCII chars + NUL
constexpr size_t kSaltBytes = 17U;          // 16 hex chars + NUL
constexpr size_t kHashBytes = 65U;          // SHA-256 hex + NUL
constexpr size_t kCredentialBytes = kCredentialIdBytes + 1U + kSaltBytes + kHashBytes;
constexpr size_t kHeaderBytes = 16U;
constexpr size_t kPayloadBytes = kCredentialBytes * kMaxCredentials;
constexpr size_t kRecordBytes = kHeaderBytes + kPayloadBytes + 4U;

enum class Role : uint8_t { none = 0U, owner = 1U, member = 2U };

struct Credential {
  char id[kCredentialIdBytes] = {};
  Role role = Role::none;
  char salt[kSaltBytes] = {};
  char verifier[kHashBytes] = {};
};

struct Record { uint8_t bytes[kRecordBytes] = {}; };

inline uint32_t crc32(const uint8_t* bytes, const size_t size) {
  uint32_t crc = 0xffffffffU;
  for (size_t i = 0; i < size; ++i) {
    crc ^= bytes[i];
    for (uint8_t bit = 0; bit < 8U; ++bit) {
      crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
    }
  }
  return ~crc;
}

inline bool newer(const uint32_t candidate, const uint32_t previous) {
  const uint32_t delta = candidate - previous;
  return delta != 0U && delta < 0x80000000U;
}

inline int newestSlot(const bool validA, const uint32_t sequenceA,
                      const bool validB, const uint32_t sequenceB) {
  if (!validA) return validB ? 1 : -1;
  return validB && newer(sequenceB, sequenceA) ? 1 : 0;
}

inline bool isTerminated(const char* value, const size_t capacity) {
  return value != nullptr && std::memchr(value, '\0', capacity) != nullptr;
}

inline bool validCredential(const Credential& credential) {
  if (credential.role != Role::owner && credential.role != Role::member) return false;
  if (!isTerminated(credential.id, sizeof(credential.id)) || credential.id[0] == '\0') return false;
  if (!isTerminated(credential.salt, sizeof(credential.salt)) || credential.salt[0] == '\0') return false;
  if (!isTerminated(credential.verifier, sizeof(credential.verifier)) || credential.verifier[0] == '\0') return false;
  return true;
}

inline void write32(uint8_t* bytes, const uint32_t value) { std::memcpy(bytes, &value, sizeof(value)); }
inline uint32_t read32(const uint8_t* bytes) { uint32_t value = 0U; std::memcpy(&value, bytes, sizeof(value)); return value; }

inline void encodeCredential(uint8_t* output, const Credential& credential) {
  std::memcpy(output, credential.id, kCredentialIdBytes);
  output[kCredentialIdBytes] = static_cast<uint8_t>(credential.role);
  std::memcpy(output + kCredentialIdBytes + 1U, credential.salt, kSaltBytes);
  std::memcpy(output + kCredentialIdBytes + 1U + kSaltBytes, credential.verifier, kHashBytes);
}

inline Credential decodeCredential(const uint8_t* input) {
  Credential credential = {};
  std::memcpy(credential.id, input, kCredentialIdBytes);
  credential.role = static_cast<Role>(input[kCredentialIdBytes]);
  std::memcpy(credential.salt, input + kCredentialIdBytes + 1U, kSaltBytes);
  std::memcpy(credential.verifier, input + kCredentialIdBytes + 1U + kSaltBytes, kHashBytes);
  return credential;
}

inline Record makeRecord(const uint32_t sequence, const Credential* credentials,
                         const uint8_t count) {
  Record record = {};
  write32(record.bytes, kMagic);
  write32(record.bytes + 4U, kSchema);
  write32(record.bytes + 8U, sequence);
  record.bytes[12U] = count;
  for (uint8_t index = 0; index < count && index < kMaxCredentials; ++index) {
    encodeCredential(record.bytes + kHeaderBytes + kCredentialBytes * index, credentials[index]);
  }
  write32(record.bytes + kRecordBytes - 4U, crc32(record.bytes, kRecordBytes - 4U));
  return record;
}

inline bool decode(const Record& record, uint32_t& sequence, Credential* credentials,
                   uint8_t& count) {
  if (read32(record.bytes) != kMagic || read32(record.bytes + 4U) != kSchema ||
      read32(record.bytes + kRecordBytes - 4U) != crc32(record.bytes, kRecordBytes - 4U)) return false;
  const uint8_t storedCount = record.bytes[12U];
  if (storedCount == 0U || storedCount > kMaxCredentials) return false;
  Credential decoded[kMaxCredentials] = {};
  uint8_t ownerCount = 0U;
  for (uint8_t index = 0; index < storedCount; ++index) {
    decoded[index] = decodeCredential(record.bytes + kHeaderBytes + kCredentialBytes * index);
    if (!validCredential(decoded[index])) return false;
    if (decoded[index].role == Role::owner) ++ownerCount;
    for (uint8_t previous = 0; previous < index; ++previous) {
      if (std::strcmp(decoded[index].id, decoded[previous].id) == 0) return false;
    }
  }
  if (ownerCount != 1U) return false;
  sequence = read32(record.bytes + 8U);
  count = storedCount;
  for (uint8_t index = 0; index < count; ++index) credentials[index] = decoded[index];
  return true;
}

inline int findCredential(const Credential* credentials, const uint8_t count,
                          const char* id) {
  if (!id) return -1;
  for (uint8_t index = 0; index < count; ++index) {
    if (std::strcmp(credentials[index].id, id) == 0) return static_cast<int>(index);
  }
  return -1;
}

}  // namespace smartplug_credentials
