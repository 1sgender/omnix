"""Самопроверка замера: подменённые провайдеры, без сети и ключей.

Запуск: python tools/stt_eval/test_eval_stt.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import eval_stt as ev  # noqa: E402


class MetricsTest(unittest.TestCase):
    def test_normalize_drops_case_and_punctuation(self):
        self.assertEqual(ev.normalize("Salam,  Nähili?!"), "salam nähili")

    def test_wer_and_cer(self):
        self.assertEqual(ev.wer_counts("a b c d", "a x c"), (2, 4))
        self.assertEqual(ev.cer_counts("abc", "abd"), (1, 3))
        self.assertEqual(ev.wer_counts("a b", ""), (2, 2))

    def test_references_both_formats(self):
        with tempfile.TemporaryDirectory() as d:
            tsv = Path(d) / "r.tsv"
            tsv.write_text("one.wav\tSalam\ntwo|Hi there\n", encoding="utf-8")
            self.assertEqual(ev.load_references(tsv), {"one": "Salam", "two": "Hi there"})


class EvaluateTest(unittest.TestCase):
    def test_fake_providers(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "a.wav").write_bytes(b"RIFF")
            (root / "b.wav").write_bytes(b"RIFF")
            refs = {"a": "salam dünýä", "b": "ýagşy gün", "missing": "no file"}

            answers = iter(["Salam dünýä.", "ýagşy"])

            def good(wav, lang):
                return next(answers)

            def broken(wav, lang):
                raise RuntimeError("HTTP 500")

            res = ev.evaluate(root, refs, {"good": good, "broken": broken}, "tk")
            self.assertEqual(res["good"]["files"], 2)
            self.assertAlmostEqual(res["good"]["wer"], 1 / 4)
            self.assertEqual(res["broken"]["errors"], 2)
            self.assertIsNone(res["broken"]["wer"])
            report = ev.render_report(res, "tk", root)
            self.assertIn("| good | 2 | 0 | 25.0% |", report)
            self.assertIn("| broken | 2 | 2 | - |", report)

    def test_missing_key_is_reported(self):
        import os
        old = os.environ.pop("GROQ_API_KEY", None)
        try:
            with self.assertRaises(SystemExit):
                ev.build_providers(["groq"])
        finally:
            if old is not None:
                os.environ["GROQ_API_KEY"] = old


if __name__ == "__main__":
    unittest.main()
