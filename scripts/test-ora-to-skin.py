#!/usr/bin/env python3
from pathlib import Path
import importlib.util
import struct
import tempfile
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ora_to_skin", root / "theme/ora-to-skin.py")
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)


class OraToSkinTest(unittest.TestCase):
    def test_default_layers_reproduce_from_ora_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "main-base.png"
            cycle = Path(directory) / "main-cycle.iff"
            count = converter.convert(root / "theme/main.ora", base, cycle)
            self.assertEqual(7, count)
            self.assertEqual((root / "theme/main-base.png").read_bytes(), base.read_bytes())
            encoded = cycle.read_bytes()
            self.assertEqual((root / "theme/main-cycle.iff").read_bytes(), encoded)
            self.assertEqual(b"FORM", encoded[:4])
            chunks = []
            position = 12
            while position + 8 <= len(encoded):
                name = encoded[position:position + 4]
                size = struct.unpack_from(">I", encoded, position + 4)[0]
                payload = encoded[position + 8:position + 8 + size]
                chunks.append((name, payload))
                position += 8 + size + (size & 1)
            cycles = [struct.unpack(">HHHBB", payload) for name, payload in chunks if name == b"CRNG"]
            self.assertEqual(7, len(cycles))
            self.assertEqual([(1, 32), (33, 64), (65, 96), (97, 128),
                              (129, 160), (161, 192), (193, 224)],
                             [(cycle[3], cycle[4]) for cycle in cycles])
            header = next(payload for name, payload in chunks if name == b"BMHD")
            self.assertEqual((275, 116), struct.unpack_from(">HH", header, 0))


if __name__ == "__main__":
    unittest.main()
