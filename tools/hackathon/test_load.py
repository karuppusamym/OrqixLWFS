"""Offline invariants for resuming a concurrent local load fixture."""
import unittest
import json
import uuid
from pathlib import Path
from load import assigned_cases


class ResumeOwnershipTest(unittest.TestCase):
    def test_development_realm_recreation_keeps_explicit_unique_subject_ids(self):
        realm = json.loads((Path(__file__).resolve().parents[2] / "deploy/compose/keycloak/orqix-dev-realm.json").read_text(encoding="utf-8"))
        ids = [str(uuid.UUID(user["id"])) for user in realm["users"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreater(len(ids), 0)

    def test_fifty_users_cover_a_skewed_census_once_without_shared_case_ownership(self):
        workflows = [f"workflow-{i}" for i in range(10)]
        census = {wf: [f"{wf}:case-{n}" for n in range(401 + i)] for i, wf in enumerate(workflows)}
        owned = [assigned_cases(census, workflows, 50, i) for i in range(50)]
        flattened = [case for cases in owned for case in cases]
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertEqual(set(flattened), {case for cases in census.values() for case in cases})
        for i, wf in enumerate(workflows):
            sizes = [len(owned[owner]) for owner in range(i, 50, 10)]
            self.assertLessEqual(max(sizes) - min(sizes), 1)
            self.assertTrue(all(case.startswith(wf + ":") for owner in range(i, 50, 10) for case in owned[owner]))


if __name__ == "__main__":
    unittest.main()
