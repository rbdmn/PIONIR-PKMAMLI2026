#include <unity.h>
#include <cstring>
#include <initializer_list>
#include "SmartPlugPowerRestore.h"
using namespace smartplug_power_restore;

void setUp() {}
void tearDown() {}

void roundTripBothStates() {
  for (bool on : {false, true}) {
    const auto r = encode(7U, on);
    uint32_t seq = 0; bool got = !on;
    TEST_ASSERT_TRUE(decode(r, seq, got));
    TEST_ASSERT_EQUAL_UINT32(7U, seq);
    TEST_ASSERT_EQUAL(on, got);
  }
}

void everyCorruptedByteIsRejected() {
  const auto r = encode(9U, true);
  for (size_t i = 0; i < sizeof(r.bytes); ++i) {
    auto broken = r;
    broken.bytes[i] ^= 1U;
    uint32_t seq; bool on;
    TEST_ASSERT_FALSE(decode(broken, seq, on));
  }
}

// restore decision: policy x record validity x last state (fail-safe OFF)
void restoreDecisionAllCombinations() {
  TEST_ASSERT_TRUE(shouldRestoreOn(true, true));
  TEST_ASSERT_FALSE(shouldRestoreOn(true, false));
  TEST_ASSERT_FALSE(shouldRestoreOn(false, true));   // corrupt/missing record -> OFF
  TEST_ASSERT_FALSE(shouldRestoreOn(false, false));
}

void survivesOneCorruptSlot() {
  const auto a = encode(10U, true);
  auto b = encode(11U, false);
  b.bytes[12] ^= 1U;  // torn/corrupt newest slot
  uint32_t sa = 0, sb = 0; bool oa = false, ob = false;
  const bool va = decode(a, sa, oa), vb = decode(b, sb, ob);
  const auto chosen = chooseNewest(va, sa, oa, vb, sb, ob);
  TEST_ASSERT_TRUE(chosen.valid);
  TEST_ASSERT_EQUAL_INT(0, chosen.slot);
  TEST_ASSERT_TRUE(chosen.on);
  TEST_ASSERT_EQUAL_INT(1, nextSlot(chosen.slot));  // next write never touches the good slot
}

void newestValidSlotWins() {
  const auto chosen = chooseNewest(true, 5U, false, true, 6U, true);
  TEST_ASSERT_EQUAL_INT(1, chosen.slot);
  TEST_ASSERT_TRUE(chosen.on);
  TEST_ASSERT_EQUAL_UINT32(6U, chosen.sequence);
}

void bothSlotsCorruptMeansOff() {
  const auto chosen = chooseNewest(false, 0U, false, false, 0U, false);
  TEST_ASSERT_FALSE(chosen.valid);
  TEST_ASSERT_FALSE(shouldRestoreOn(chosen.valid, chosen.on));
}

void truncatedWriteNeverReadsAsValid() {
  const auto next = encode(21U, true);
  for (size_t count = 0; count < sizeof(next.bytes); ++count) {
    StateRecord partial; std::memset(&partial, 0xff, sizeof(partial));
    std::memcpy(&partial, &next, count);
    uint32_t seq; bool on;
    TEST_ASSERT_FALSE(decode(partial, seq, on));
  }
}

void restoreGateWaitsThenFiresOnce() {
  RestoreGate gate;
  TEST_ASSERT_FALSE(gate.due(0U, true));
  TEST_ASSERT_FALSE(gate.due(kRestoreDelayMs - 1U, true));
  TEST_ASSERT_TRUE(gate.due(kRestoreDelayMs, true));
  TEST_ASSERT_FALSE(gate.due(kRestoreDelayMs + 1000U, true));  // once per boot
  TEST_ASSERT_TRUE(gate.done());
}

void restoreGateNothingToDoFinishesWithoutFiring() {
  RestoreGate gate;
  TEST_ASSERT_FALSE(gate.due(10000U, false));
  TEST_ASSERT_TRUE(gate.done());
}

void restoreGateRetryAfterRefusal() {
  RestoreGate gate;
  TEST_ASSERT_TRUE(gate.due(5000U, true));
  gate.retry();
  TEST_ASSERT_TRUE(gate.due(5100U, true));
}

void debounceWaitsForStableState() {
  SaveDebouncer d;
  d.setPersisted(true, false);
  TEST_ASSERT_FALSE(d.observe(true, true, 1000U));             // first sight starts the timer
  TEST_ASSERT_FALSE(d.observe(true, true, 1000U + kSaveDebounceMs - 1U));
  TEST_ASSERT_TRUE(d.observe(true, true, 1000U + kSaveDebounceMs));
  d.saved(true);
  TEST_ASSERT_FALSE(d.observe(true, true, 9000U));             // already stored
}

void debounceRapidTogglesNeverSave() {
  SaveDebouncer d;
  d.setPersisted(true, false);
  uint32_t t = 0;
  for (int i = 0; i < 20; ++i) {
    t += 500U;
    TEST_ASSERT_FALSE(d.observe(true, (i % 2) == 0, t));       // flips faster than the debounce window
  }
}

void debounceIgnoresUnknownAndRestartsTimer() {
  SaveDebouncer d;
  d.setPersisted(false, false);
  TEST_ASSERT_FALSE(d.observe(true, true, 0U));
  TEST_ASSERT_FALSE(d.observe(false, false, 1000U));           // "transitioning/unknown" resets stability
  TEST_ASSERT_FALSE(d.observe(true, true, 1100U));
  TEST_ASSERT_FALSE(d.observe(true, true, 1100U + kSaveDebounceMs - 1U));
  TEST_ASSERT_TRUE(d.observe(true, true, 1100U + kSaveDebounceMs));
}

void debounceSavesWhenNothingPersistedYet() {
  SaveDebouncer d;
  d.setPersisted(false, false);
  d.observe(true, false, 0U);
  TEST_ASSERT_TRUE(d.observe(true, false, kSaveDebounceMs));
}

void debounceSurvivesMillisWrap() {
  SaveDebouncer d;
  d.setPersisted(true, false);
  const uint32_t start = 0xFFFFFF00U;
  d.observe(true, true, start);
  TEST_ASSERT_TRUE(d.observe(true, true, start + kSaveDebounceMs));  // wraps past zero
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(roundTripBothStates);
  RUN_TEST(everyCorruptedByteIsRejected);
  RUN_TEST(restoreDecisionAllCombinations);
  RUN_TEST(survivesOneCorruptSlot);
  RUN_TEST(newestValidSlotWins);
  RUN_TEST(bothSlotsCorruptMeansOff);
  RUN_TEST(truncatedWriteNeverReadsAsValid);
  RUN_TEST(restoreGateWaitsThenFiresOnce);
  RUN_TEST(restoreGateNothingToDoFinishesWithoutFiring);
  RUN_TEST(restoreGateRetryAfterRefusal);
  RUN_TEST(debounceWaitsForStableState);
  RUN_TEST(debounceRapidTogglesNeverSave);
  RUN_TEST(debounceIgnoresUnknownAndRestartsTimer);
  RUN_TEST(debounceSavesWhenNothingPersistedYet);
  RUN_TEST(debounceSurvivesMillisWrap);
  return UNITY_END();
}
