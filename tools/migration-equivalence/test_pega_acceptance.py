import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

import pega_acceptance as pega


class PegaSyntheticAcceptanceTests(unittest.TestCase):
    def mutate(self, change):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "fixtures"
            shutil.copytree(pega.DEFAULT_SUITE.parent, root)
            suite = pega.read_json(root / "suite.json")
            change(root, suite)
            (root / "suite.json").write_bytes(pega.canonical(suite))
            return pega.load_fixtures(root / "suite.json")

    def replace(self, root, suite, kind, value):
        entry = suite["workflows"][0]
        path = root / entry[kind]
        path.write_bytes(pega.canonical(value))
        field = {"definition": "definitionSha256", "fileMappings": "fileMappingsSha256"}[kind]
        entry[field] = hashlib.sha256(path.read_bytes()).hexdigest()

    def test_all_levels_preserve_every_file_and_target_node(self):
        suite, fixtures, artifacts = pega.load_fixtures(pega.DEFAULT_SUITE)
        self.assertEqual(set(fixtures), {"small", "medium", "complex"})
        self.assertEqual(suite["sourceValidation"], "syntheticFixture")
        for fixture in fixtures.values():
            self.assertEqual(set(fixture["mappings"]), pega.archive_names(fixture["archive"]))
            covered = {node for mapping in fixture["mappings"].values() for node in mapping.get("targetNodeIds", [])}
            self.assertEqual(covered, {node["id"] for node in fixture["definition"]["nodes"]})
            self.assertTrue(any(mapping["disposition"] == "excluded" for mapping in fixture["mappings"].values()))
        self.assertEqual(10, len(artifacts))

    def test_synthetic_fixture_cannot_be_relabelled_as_native_acceptance(self):
        with self.assertRaisesRegex(ValueError, "explicitly synthetic"):
            self.mutate(lambda root, suite: suite.update(sourceValidation="customerExport"))
        with self.assertRaisesRegex(ValueError, "Synthetic source version"):
            self.mutate(lambda root, suite: suite.update(sourceVersion="24.2"))

    def test_changes_require_updated_file_checksums(self):
        def tamper(root, suite):
            with (root / suite["workflows"][0]["definition"]).open("ab") as file:
                file.write(b" ")
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.mutate(tamper)

    def test_no_source_disposition_can_disappear(self):
        def omit(root, suite):
            mappings = pega.read_json(root / suite["workflows"][0]["fileMappings"])
            mappings.pop(next(iter(mappings)))
            self.replace(root, suite, "fileMappings", mappings)
        with self.assertRaisesRegex(ValueError, "Every archive file"):
            self.mutate(omit)

    def test_exclusions_cannot_claim_target_nodes(self):
        def change(root, suite):
            mappings = pega.read_json(root / suite["workflows"][0]["fileMappings"])
            excluded = next(mapping for mapping in mappings.values() if mapping["disposition"] == "excluded")
            excluded["targetNodeIds"] = ["start"]
            self.replace(root, suite, "fileMappings", mappings)
        with self.assertRaisesRegex(ValueError, "excluded files cannot"):
            self.mutate(change)

    def test_dependencies_cannot_resolve_to_unrelated_existing_workflows(self):
        with self.assertRaisesRegex(ValueError, "earlier workflows"):
            self.mutate(lambda root, suite: suite["workflows"][0].update(dependencies=["complex"]))
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            self.mutate(lambda root, suite: suite["workflows"][1].update(dependencies=[]))

    def test_path_escape_external_connections_and_duplicate_json_are_refused(self):
        with self.assertRaises(ValueError):
            self.mutate(lambda root, suite: suite["workflows"][0].update(definition="../outside.json"))
        def external(root, suite):
            definition = pega.read_json(root / suite["workflows"][0]["definition"])
            definition["dependencies"]["connections"] = [{"key": "unreviewed", "definitionVersion": 1}]
            self.replace(root, suite, "definition", definition)
        with self.assertRaisesRegex(ValueError, "external connection"):
            self.mutate(external)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"id": 1, "id": 2}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                pega.read_json(path)

    def test_archive_traversal_and_bombs_are_rejected(self):
        for name, data in (("../rules.xml", b"x"), ("rules.xml", b"x" * 1_048_577)):
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(name, data)
            with self.assertRaises(ValueError):
                pega.archive_names(buffer.getvalue())

    def test_live_adapter_refuses_remote_urls_and_embedded_credentials(self):
        for url in ("https://customer.example", "http://customer.example", "http://user:pass@localhost:18080", "http://localhost:18080/?token=secret"):
            with self.assertRaises(ValueError):
                pega.local_url(url)
        self.assertEqual(pega.local_url("http://localhost:18080/"), "http://localhost:18080")

    def test_boolean_integer_comparison_is_strict_and_pack_reproducible(self):
        checks = pega.Acceptance({}, "workspace", {})
        with redirect_stdout(io.StringIO()):
            self.assertFalse(checks.check("boolean is not integer", True, 1))
        suite, _, artifacts = pega.load_fixtures(pega.DEFAULT_SUITE)
        result = {"passed": True, "sourceRuntimeEquivalenceEstablished": False}
        with tempfile.TemporaryDirectory() as directory:
            one, two = Path(directory) / "one.zip", Path(directory) / "two.zip"
            pega.write_pack(one, suite, {}, result, artifacts)
            pega.write_pack(two, suite, {}, result, artifacts)
            self.assertEqual(one.read_bytes(), two.read_bytes())
            with zipfile.ZipFile(one) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                for name, entry in manifest["files"].items():
                    self.assertEqual(hashlib.sha256(archive.read(name)).hexdigest(), entry["sha256"])


if __name__ == "__main__":
    unittest.main()
from contextlib import redirect_stdout
