import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("equivalence", HERE / "equivalence.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class GoldenEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.suite = module.load_suite(HERE / "purchase-suite.json")
        self.observations = json.loads((HERE / "simulated-observations.json").read_text())

    def test_simulated_match_is_not_claimed_as_source_equivalence(self):
        result = module.compare(self.suite, self.observations)
        self.assertTrue(result["passed"])
        self.assertEqual(result["executionMode"], "simulatedFixture")
        self.assertEqual(result["sourceValidation"], "syntheticFixture")
        self.assertIn("not established", result["equivalenceClaim"])

    def test_wrong_route_outcome_effect_and_missing_case_fail(self):
        for field, value in (("approvalNodes", ["manager", "finance"]), ("outcome", "Wrong"), ("succeededEffects", 2)):
            observations = copy.deepcopy(self.observations)
            observations["cases"][0][field] = value
            self.assertFalse(module.compare(self.suite, observations)["passed"])
        self.observations["cases"].pop()
        self.assertFalse(module.compare(self.suite, self.observations)["passed"])

    def test_unexpected_or_duplicate_cases_do_not_disappear(self):
        self.observations["cases"].append({"id": "unexpected"})
        self.assertFalse(module.compare(self.suite, self.observations)["passed"])
        self.observations["cases"].append(self.observations["cases"][0])
        with self.assertRaises(ValueError):
            module.compare(self.suite, self.observations)

    def test_archive_is_reproducible_and_all_files_have_checksums(self):
        result = module.compare(self.suite, self.observations)
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / "one.zip", Path(directory) / "two.zip"
            module.write_pack(first, self.suite, self.observations, result, {"source/fixture.bpmn": b"synthetic"})
            module.write_pack(second, self.suite, self.observations, result, {"source/fixture.bpmn": b"synthetic"})
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                for name, metadata in manifest["files"].items():
                    data = archive.read(name)
                    self.assertEqual(module.hashlib.sha256(data).hexdigest(), metadata["sha256"])
                self.assertEqual(archive.namelist(), sorted(archive.namelist()))


if __name__ == "__main__":
    unittest.main()
