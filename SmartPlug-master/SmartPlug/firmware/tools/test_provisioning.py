import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('provision',Path(__file__).with_name('provision_unit.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class ProvisioningTests(unittest.TestCase):
    def test_station_mac_normalization(self):
        self.assertEqual(module.normalize_mac('84:f3:eb:12:34:56'),'84F3EB123456')
        self.assertEqual(module.normalize_mac('84-F3-EB-12-34-56'),'84F3EB123456')
    def test_rejects_invalid_identity(self):
        for value in ('','12345','GGF3EB123456','FFFFFFFFFFFF','000000000000','01:00:00:00:00:01'):
            with self.subTest(value=value),self.assertRaises(ValueError): module.normalize_mac(value)
    def test_passwords_distinct_and_qr_safe(self):
        values=[module.make_password() for _ in range(100)]
        self.assertEqual(len(set(values)),100)
        for value in values: self.assertRegex(value,r'^[A-Za-z0-9]{20}$')
if __name__=='__main__': unittest.main()
