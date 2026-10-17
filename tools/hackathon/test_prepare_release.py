import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from prepare_release import prepare


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="orqix-release-test-")
        self.root = Path(self.temp.name)
        repository = Path(__file__).resolve().parents[2]
        (self.root / "contracts").mkdir()
        (self.root / "contracts/workflow-v0.schema.json").write_bytes((repository / "contracts/workflow-v0.schema.json").read_bytes())
        (self.root / "workflow.json").write_bytes((repository / "examples/hackathon/tier1-approval.json").read_bytes())
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Synthetic test", "-c", "user.email=test@example.invalid", "commit", "-qm", "Synthetic source")
        self.commit = self.git("rev-parse", "HEAD").strip()

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.PIPE, text=True)

    def test_prepares_only_real_committed_content_and_keeps_source_unchanged(self):
        before = (self.root / "workflow.json").read_bytes()
        definition, manifest = prepare(self.root, "workflow.json", self.commit, "local review")
        self.assertEqual(definition["provenance"]["gitCommit"], self.commit)
        self.assertEqual(manifest["sourceCommit"], self.commit)
        self.assertEqual((self.root / "workflow.json").read_bytes(), before)
        self.assertIn("independent publisher", manifest["pendingGates"])

    def test_refuses_unreviewed_working_changes(self):
        path = self.root / "workflow.json"
        definition = json.loads(path.read_text())
        definition["nodes"][1]["config"]["dueAfterMinutes"] = 60
        path.write_text(json.dumps(definition))
        with self.assertRaisesRegex(ValueError, "differs from the reviewed"):
            prepare(self.root, "workflow.json", self.commit)

    def test_refuses_outside_paths_and_non_commit_arguments(self):
        with self.assertRaisesRegex(ValueError, "inside the repository"):
            prepare(self.root, "../outside.json", self.commit)
        with self.assertRaisesRegex(ValueError, "full reviewed Git commit"):
            prepare(self.root, "workflow.json", "--help")


if __name__ == "__main__":
    unittest.main()
