"""End-to-end test of the orqix CLI against a running Orqix stack (docker compose in deploy/compose).

Skipped unless ORQIX_E2E_URL is set. The token must be a CLI token (audience orqix-cli) of a designer who may also
manage connector packages, for example from `orqix login` as dana.designer (copy access_token from the config file)
or a token file:

    ORQIX_E2E_URL=http://localhost:8080 ORQIX_E2E_TOKEN_FILE=/path/token ORQIX_E2E_WORKSPACE=purchasing \
        python -m unittest tests/e2e_live.py

It creates a workflow from the purchase template through the API, exports it, re-imports it, validates it, runs a test
file against it (one passing and one failing case, so the exit code and the JUnit XML are checked), and runs the
package contract test of examples/packages/servicenow-itsm (packed from the folder by the CLI, unsigned).
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from orqix_cli import commands  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
URL = os.environ.get("ORQIX_E2E_URL")


@unittest.skipUnless(URL, "set ORQIX_E2E_URL (and ORQIX_E2E_TOKEN_FILE, ORQIX_E2E_WORKSPACE) to run against a stack")
class LiveEndToEndTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.dir.name)
        os.environ["ORQIX_CONFIG"] = str(self.tmp / "config.json")
        self.assertEqual(self.orqix("--server", URL, "login", "--token-file", os.environ["ORQIX_E2E_TOKEN_FILE"])[0], 0)
        self.assertEqual(self.orqix("workspace", "use", os.environ.get("ORQIX_E2E_WORKSPACE", "purchasing"))[0], 0)

    def tearDown(self):
        del os.environ["ORQIX_CONFIG"]
        self.dir.cleanup()

    def orqix(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        code = commands.main(list(argv), out=out, err=err)
        return code, out.getvalue(), err.getvalue()

    def test_export_import_validate_and_test_a_workflow(self):
        ctx = commands.Context(commands.parser().parse_args(["whoami"]), io.StringIO(), io.StringIO())
        key = "cli-e2e-" + uuid.uuid4().hex[:8]
        ws = ctx.workspace()
        ctx.client().post(f"/workspaces/{ws}/workflows", {"key": key, "name": "CLI end to end",
                                                         "templateId": "purchase-approval"})
        exported = self.tmp / f"{key}.json"
        code, out, err = self.orqix("workflow", "export", key, "-o", str(exported))
        self.assertEqual(code, 0, err)
        definition = json.loads(exported.read_text())
        definition["name"] = "CLI end to end (edited)"
        exported.write_text(json.dumps(definition))
        code, out, err = self.orqix("workflow", "validate", str(exported))
        self.assertEqual(code, 0, out + err)
        self.assertIn("draft revision 2", out)

        small = {"title": "Laptop", "purpose": "Replacement", "costCenter": "IT", "amount": 1800}
        suite = {"schemaVersion": "orqix.workflow-tests/v1", "workflowKey": key, "cases": [
            {"id": "small", "name": "Small purchase", "input": small, "expect": "Fulfilled"},
            {"id": "wrong", "name": "Wrong expectation", "input": small, "expect": "Rejected"}]}
        suite_path = self.tmp / "suite.json"
        suite_path.write_text(json.dumps(suite))
        code, out, err = self.orqix("test", "run", str(suite_path), "--junit", str(self.tmp / "junit.xml"))
        self.assertEqual(code, 1, out + err)
        root = ET.parse(self.tmp / "junit.xml").getroot()
        self.assertEqual((root.get("tests"), root.get("failures")), ("2", "1"))
        self.assertIn("Together the cases cover", out)

    def test_package_contract_test_of_the_example_folder(self):
        code, out, err = self.orqix("package", "test", str(REPO / "examples/packages/servicenow-itsm"),
                                    "--junit", str(self.tmp / "pkg.xml"))
        self.assertEqual(code, 0, out + err)
        self.assertIn("(unsigned)", out)
        self.assertIn("5 of 5 fixtures passed", out)


if __name__ == "__main__":
    unittest.main()
