#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

import verify_gametest_count as verifier


class VerifyGameTestCountTest(unittest.TestCase):
    def test_matching_count_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "src"
            src.mkdir()
            (src / "Example.java").write_text("@GameTest\n@Test\n@GameTest(batch=\"x\")\n", encoding="utf-8")
            log = root / "latest.log"
            log.write_text("[Server] All 2 required tests passed :)\n", encoding="utf-8")
            self.assertEqual(2, verifier.declared_count(src))
            self.assertEqual(2, verifier.reported_count(log))

    def test_missing_summary_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("All 0 required tests passed :(\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                verifier.reported_count(log)


if __name__ == "__main__":
    unittest.main()
