"""Host-only contract checks for the SPMQTT2 canonical HMAC envelope.

This deliberately does not emulate an ESP8266 or upload firmware.  It pins a
known SHA-256 HMAC vector and checks that changing a security-bound canonical
field (topic, payload, boot epoch, or nonce) invalidates that signature.  The
small source guards prevent the product implementation from silently dropping
the canonical direction/topic/device bindings while a device-target test is
compile-only.
"""

import hashlib
import hmac
from pathlib import Path
import unittest


KEY = "00112233445566778899aabbccddeeff"
TOPIC = "smartplug/SP-A1B2C3D4/measurement/allparameters"
DEVICE = "SP-A1B2C3D4"
BOOT = 305419896
NONCE = 42
PAYLOAD = '{"voltage":220.5,"current":0.123,"active_power":27.1,"relay":"on"}'
EXPECTED_SIGNATURE = "446a93a34400625a72d7f73639bc48472358bf6e68a18024c69f7b47614201a9"


def canonical(direction: str, topic: str, device: str, boot: int, nonce: int,
              payload: str) -> str:
    return (f"SPMQTT2|v=2|dir={direction}|topic={topic}|device={device}"
            f"|boot={boot}|nonce={nonce}|payload={payload}")


def signature(value: str) -> str:
    return hmac.new(KEY.encode("utf-8"), value.encode("utf-8"),
                    hashlib.sha256).hexdigest()


class MqttSignatureContractTests(unittest.TestCase):
    def setUp(self):
        self.canonical = canonical("up", TOPIC, DEVICE, BOOT, NONCE, PAYLOAD)

    def test_fixed_canonical_hmac_vector(self):
        self.assertEqual(signature(self.canonical), EXPECTED_SIGNATURE)

    def test_topic_is_bound_to_signature(self):
        changed = canonical("up", TOPIC.replace("measurement", "state"),
                            DEVICE, BOOT, NONCE, PAYLOAD)
        self.assertNotEqual(signature(changed), EXPECTED_SIGNATURE)

    def test_payload_is_bound_to_signature(self):
        changed = canonical("up", TOPIC, DEVICE, BOOT, NONCE,
                            PAYLOAD.replace("27.1", "27.2"))
        self.assertNotEqual(signature(changed), EXPECTED_SIGNATURE)

    def test_boot_epoch_is_bound_to_signature(self):
        changed = canonical("up", TOPIC, DEVICE, BOOT + 1, NONCE, PAYLOAD)
        self.assertNotEqual(signature(changed), EXPECTED_SIGNATURE)

    def test_nonce_is_bound_to_signature(self):
        changed = canonical("up", TOPIC, DEVICE, BOOT, NONCE + 1, PAYLOAD)
        self.assertNotEqual(signature(changed), EXPECTED_SIGNATURE)

    def test_firmware_keeps_required_canonical_bindings(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "src" / "main.cpp").read_text(encoding="utf-8")
        self.assertIn('SPMQTT2|v=2|dir=up|topic=', source)
        self.assertIn('SPMQTT2|v=2|dir=down|topic=', source)
        self.assertIn('"|device=" + deviceId()', source)
        self.assertIn('"|boot=" + String(boot) + "|nonce="', source)
        self.assertIn('"|payload=" + body', source)
        self.assertIn('mqttSignHmac(canonical, mqtt_auth::secret())', source)


if __name__ == "__main__":
    unittest.main()
