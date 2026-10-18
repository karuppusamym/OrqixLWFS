import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_evidence import ARTIFACTS, digest, encode, package, reports


class EvidencePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        payloads = {name: b"{}" for name in ARTIFACTS}
        for name in ("frontend-sbom.json", "runtime-sbom-patched.json"):
            payloads[name] = encode({"components": [{"name": "synthetic", "version": "1", "licenses": []}]})
        payloads["runtime-cves-patched.sarif"] = encode({"runs": []})
        payloads["load.json"] = encode({"fixture": "C:/private/path", "passed": True})
        for name, data in payloads.items():
            (self.source / name).write_bytes(data)
        self.receipt = {
            "artifacts": {name: digest(payloads[name]) for name in ARTIFACTS},
            "loadResultSha256": digest(payloads["load.json"]),
            "sourceCommit": "a" * 40, "qualificationHarnessCommit": "b" * 40,
            "image": "synthetic", "imageId": "sha256:" + "c" * 64,
            "sourceSha256": {}, "checks": {}, "hostHardware": {},
            "completedAtUtc": "2026-10-18T00:00:00Z",
        }
        self.save_receipt()

    def save_receipt(self):
        (self.source / "candidate.json").write_bytes(encode(self.receipt))

    def test_deterministic_package_excludes_unselected_files_and_preserves_provenance(self):
        (self.source / ".env").write_text("PRIVATE=value")
        first, second = self.root / "one.zip", self.root / "two.zip"
        self.assertEqual(package(self.source, first), package(self.source, second))
        with zipfile.ZipFile(first) as archive:
            self.assertIsNone(archive.testzip())
            self.assertNotIn(".env", archive.namelist())
            manifest = json.loads(archive.read("qualification-manifest.json"))
            self.assertEqual(manifest["sourceCommit"], "a" * 40)
            self.assertNotIn("fixture", manifest["load"])
            for name, checksum in manifest["artifactSha256"].items():
                self.assertEqual(digest(archive.read(name)), checksum)
        with self.assertRaisesRegex(ValueError, "already exists"):
            package(self.source, first)

    def test_modified_or_missing_evidence_never_creates_package(self):
        output = self.root / "out.zip"
        (self.source / "load.json").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch: load.json"):
            package(self.source, output)
        self.assertFalse(output.exists())
        (self.source / "load.json").unlink()
        with self.assertRaises(FileNotFoundError):
            package(self.source, output)
        self.assertFalse(output.exists())

    def test_receipt_cannot_add_unreviewed_or_outside_files(self):
        self.receipt["artifacts"]["../outside"] = "d" * 64
        self.save_receipt()
        output = self.root / "out.zip"
        with self.assertRaisesRegex(ValueError, "supported qualification artifacts"):
            package(self.source, output)
        self.assertFalse(output.exists())

    def test_same_advisory_preserves_each_affected_package(self):
        files = {name: (self.source / name).read_bytes() for name in ARTIFACTS}
        files["runtime-cves-patched.sarif"] = encode({"runs": [{
            "tool": {"driver": {"rules": [
                {"id": "CVE-synthetic", "properties": {"purls": ["pkg:rpm/first@1"]}},
                {"id": "CVE-synthetic", "properties": {"purls": ["pkg:rpm/second@2"]}},
            ]}},
            "results": [{"ruleId": "CVE-synthetic", "ruleIndex": i} for i in range(2)],
        }]})
        findings = json.loads(reports(files)["security-findings.json"])["findings"]
        self.assertEqual([item["packages"] for item in findings], [["pkg:rpm/first@1"], ["pkg:rpm/second@2"]])


if __name__ == "__main__":
    unittest.main()
