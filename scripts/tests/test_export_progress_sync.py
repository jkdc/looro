import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager


ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "shared/progress-sync-contract.json"
EXPORTER = ROOT / "scripts/export_progress_sync.py"
ANDROID_DATA = ROOT / "apps/android/app/src/main/java/com/jkdc/app/core/ProgressSyncContractData.kt"
IOS_DATA = ROOT / "apps/ios/Core/ProgressSyncContractData.swift"
FIXTURE_PATHS = (
    pathlib.Path("shared/progress-sync-contract.json"),
    pathlib.Path("scripts/export_progress_sync.py"),
    pathlib.Path("apps/android/app/src/main/java/com/jkdc/app/core/ProgressSyncContractData.kt"),
    pathlib.Path("apps/ios/Core/ProgressSyncContractData.swift"),
)


class ProgressSyncContractExportTests(unittest.TestCase):
    @contextmanager
    def isolated_fixture(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_root = pathlib.Path(temporary_directory)
            for relative_path in FIXTURE_PATHS:
                destination = temporary_root / relative_path
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / relative_path, destination)
            yield temporary_root

    @staticmethod
    def run_exporter(root, check=False):
        command = [sys.executable, str(root / "scripts/export_progress_sync.py")]
        if check:
            command.append("--check")
        return subprocess.run(command, cwd=root, capture_output=True, text=True, check=False)

    def read_temp_contract(self, root):
        path = root / "shared/progress-sync-contract.json"
        return json.loads(path.read_text()), path

    def test_generated_contract_constants_match_shared_fixture(self):
        contract = json.loads(CONTRACT_PATH.read_text())
        self.assertEqual(2, contract["version"])
        self.assertEqual("unix", contract["wireEpoch"])
        self.assertEqual("milliseconds", contract["wireUnit"])
        self.assertEqual("appleReference", contract["localEpoch"])
        self.assertEqual("seconds", contract["localUnit"])
        self.assertEqual(978307200, contract["appleEpochOffsetSeconds"])
        self.assertEqual(1735689600001, contract["earliestUnixMilliseconds"])
        self.assertEqual(172800000, contract["futureSkewMilliseconds"])
        self.assertEqual(200, contract["uploadBatchLimit"])
        self.assertEqual(1000, contract["downloadPageLimit"])

        for generated in (ANDROID_DATA, IOS_DATA):
            text = generated.read_text()
            for field in (
                "version",
                "appleEpochOffsetSeconds",
                "earliestUnixMilliseconds",
                "futureSkewMilliseconds",
                "uploadBatchLimit",
                "downloadPageLimit",
            ):
                self.assertIn(str(contract[field]), text)

    def test_exporter_detects_generated_drift_without_mutating_workspace(self):
        with self.isolated_fixture() as temporary_root:
            android_data = temporary_root / FIXTURE_PATHS[2]
            original = android_data.read_text()
            android_data.write_text(original + "\n// deliberate drift\n")

            drifted = self.run_exporter(temporary_root, check=True)

            self.assertNotEqual(0, drifted.returncode)
            self.assertIn("stale generated file", drifted.stderr)
        self.assertEqual(original, ANDROID_DATA.read_text())

    def test_exporter_rejects_malformed_contract_types(self):
        with self.isolated_fixture() as temporary_root:
            contract, path = self.read_temp_contract(temporary_root)
            contract["uploadBatchLimit"] = "200"
            path.write_text(json.dumps(contract))

            result = self.run_exporter(temporary_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("uploadBatchLimit must be int", result.stderr)

    def test_exporter_rejects_unsafe_transfer_limits(self):
        for field in ("uploadBatchLimit", "downloadPageLimit"):
            with self.subTest(field=field), self.isolated_fixture() as temporary_root:
                contract, path = self.read_temp_contract(temporary_root)
                contract[field] = 9_007_199_254_740_992
                path.write_text(json.dumps(contract))

                result = self.run_exporter(temporary_root)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("safe integer", result.stderr)

    def test_exporter_rejects_nonpositive_transfer_limits(self):
        for field in ("uploadBatchLimit", "downloadPageLimit"):
            with self.subTest(field=field), self.isolated_fixture() as temporary_root:
                contract, path = self.read_temp_contract(temporary_root)
                contract[field] = 0
                path.write_text(json.dumps(contract))

                result = self.run_exporter(temporary_root)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("must be a positive safe integer", result.stderr)


if __name__ == "__main__":
    unittest.main()
