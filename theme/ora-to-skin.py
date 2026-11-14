#!/usr/bin/env python3
"""Convert InfiniMedia's palette-cycled OpenRaster artwork into skin layers.

The ORA extension describes indexed-palette rotations on a layer. This tool
keeps the high-color base as a scaled PNG and writes the indexed animation as
an ILBM with standard CRNG chunks, which the existing runtime renderer plays.
Requires Pillow.
"""

from __future__ import annotations

import argparse
import io
import math
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
EXTENSION_NS = "https://infinimedia.org/ns/openraster/palette-cycle/1"
MAX_ARCHIVE_ENTRIES = 64
MAX_ARCHIVE_BYTES = 24 * 1024 * 1024
MAX_IMAGE_PIXELS = 4_000_000


def archive_file(archive: zipfile.ZipFile, name: str, max_size: int) -> bytes:
    try:
        item = archive.getinfo(name)
    except KeyError as error:
        raise ValueError(f"ORA is missing {name}") from error
    if item.file_size > max_size:
        raise ValueError(f"ORA entry is too large: {name}")
    data = archive.read(item)
    if len(data) != item.file_size:
        raise ValueError(f"ORA entry is truncated: {name}")
    return data


def iff_chunk(name: bytes, data: bytes) -> bytes:
    return name + struct.pack(">I", len(data)) + data + (b"\0" if len(data) & 1 else b"")


def planar_body(indices: bytes, alpha: bytes, width: int, height: int) -> bytes:
    row_bytes = ((width + 15) // 16) * 2
    body = bytearray()
    for y in range(height):
        row = y * width
        for plane in range(8):
            mask = 1 << plane
            for start in range(0, row_bytes * 8, 8):
                packed = 0
                for bit in range(8):
                    x = start + bit
                    if x < width and indices[row + x] & mask:
                        packed |= 0x80 >> bit
                body.append(packed)
        for start in range(0, row_bytes * 8, 8):
            packed = 0
            for bit in range(8):
                x = start + bit
                if x < width and alpha[row + x] >= 128:
                    packed |= 0x80 >> bit
            body.append(packed)
    return bytes(body)


def write_ilbm(path: Path, width: int, height: int, palette: list[tuple[int, int, int]],
               cycles: list[bytes], body: bytes) -> None:
    header = struct.pack(">HHhhBBBBHBBhh", width, height, 0, 0, 8, 1, 0, 0, 0,
                         1, 1, width, height)
    color_map = bytes(component for color in palette for component in color)
    payload = bytearray(b"ILBM")
    payload.extend(iff_chunk(b"BMHD", header))
    payload.extend(iff_chunk(b"CMAP", color_map))
    for cycle in cycles:
        payload.extend(iff_chunk(b"CRNG", cycle))
    payload.extend(iff_chunk(b"BODY", body))
    path.write_bytes(b"FORM" + struct.pack(">I", len(payload)) + payload)


def read_cycle_metadata(root: ET.Element, palette_size: int) -> tuple[str, list[tuple[int, int, int, bool]]]:
    layer = next((node for node in root.iter("layer")
                  if node.find(f"{{{EXTENSION_NS}}}palette-cycle") is not None), None)
    if layer is None:
        raise ValueError("ORA has no palette-cycle layer metadata")
    node = layer.find(f"{{{EXTENSION_NS}}}palette-cycle")
    assert node is not None
    if node.get("enabled", "true").lower() != "true" or node.get("mode") != "rotate":
        raise ValueError("Only enabled rotate palette cycles are supported")
    if node.get("loop", "true").lower() != "true":
        raise ValueError("Non-looping palette cycles cannot be represented by ILBM CRNG")
    interval = float(node.get("interval-ms", "60"))
    step = int(node.get("step", "1"))
    if not math.isfinite(interval) or interval <= 0 or step <= 0:
        raise ValueError("Invalid palette-cycle interval or step")
    direction = node.get("direction", "forward")
    if direction not in {"forward", "reverse"}:
        raise ValueError(f"Unsupported palette-cycle direction: {direction}")
    transparent = int(node.get("transparent-index", "-1"))
    declared_count = int(node.get("palette-entry-count", str(palette_size)))
    if declared_count > palette_size or declared_count > 256 or declared_count < 1:
        raise ValueError("Palette-cycle metadata exceeds the indexed PNG palette")
    ranges = []
    occupied: set[int] = set()
    for item in node.findall(f"{{{EXTENSION_NS}}}range"):
        first, last = int(item.attrib["start"]), int(item.attrib["end"])
        if first < 0 or last < first or last >= declared_count:
            raise ValueError(f"Palette range {first}..{last} is out of bounds")
        if first <= transparent <= last:
            raise ValueError("A cycling range cannot include the transparent palette index")
        positions = set(range(first, last + 1))
        if positions & occupied:
            raise ValueError("Palette-cycle ranges cannot overlap")
        occupied.update(positions)
        ranges.append((first, last, interval, direction == "reverse"))
    if not ranges:
        raise ValueError("ORA palette-cycle layer has no ranges")
    return layer.attrib["src"], ranges


def convert(source: Path, base_output: Path, cycle_output: Path,
            width: int = 275, height: int = 116) -> int:
    if width <= 0 or height <= 0 or width * height > MAX_IMAGE_PIXELS:
        raise ValueError("Output dimensions are invalid or exceed the pixel limit")
    with zipfile.ZipFile(source) as archive:
        if len(archive.infolist()) > MAX_ARCHIVE_ENTRIES:
            raise ValueError("ORA contains too many entries")
        if sum(item.file_size for item in archive.infolist()) > MAX_ARCHIVE_BYTES:
            raise ValueError("ORA expanded data exceeds the size limit")
        stack_bytes = archive_file(archive, "stack.xml", 1 * 1024 * 1024)
        root = ET.fromstring(stack_bytes)
        document_width, document_height = int(root.attrib["w"]), int(root.attrib["h"])
        if document_width <= 0 or document_height <= 0 or document_width * document_height > MAX_IMAGE_PIXELS:
            raise ValueError("ORA canvas dimensions are invalid or exceed the pixel limit")
        cycle_source, ranges = read_cycle_metadata(root, 256)
        cycle_layer = next(node for node in root.iter("layer")
                           if node.get("src") == cycle_source)
        static_layer = next((node for node in root.iter("layer")
                             if node is not cycle_layer and node.get("visibility", "visible") == "visible"), None)
        if static_layer is None:
            raise ValueError("ORA has no visible static base layer")
        if float(cycle_layer.get("opacity", "1")) != 1 or float(static_layer.get("opacity", "1")) != 1:
            raise ValueError("Layer opacity other than 1 is not supported")

        with Image.open(io.BytesIO(archive_file(archive, static_layer.attrib["src"], 16 * 1024 * 1024))) as image:
            base = image.convert("RGBA")
        if base.size != (document_width, document_height):
            raise ValueError("Static base layer must fill the ORA canvas")

        with Image.open(io.BytesIO(archive_file(archive, cycle_source, 8 * 1024 * 1024))) as source_layer:
            if source_layer.mode != "P":
                raise ValueError("Palette-cycle layer must be an indexed PNG")
            palette_values = source_layer.getpalette()
            if not palette_values:
                raise ValueError("Indexed PNG has no RGB palette")
            palette_size = min(len(palette_values) // 3, 256)
            indexed = source_layer.copy()
            indexed.load()
            alpha = indexed.convert("RGBA").getchannel("A")
            indices_source = indexed.tobytes()
            palette = [tuple(palette_values[i * 3:i * 3 + 3]) for i in range(palette_size)]
            if len(palette) < 256:
                palette.extend([palette[-1]] * (256 - len(palette)))
            cycle_src = cycle_layer.attrib["src"]
            indexed_width, indexed_height = indexed.size

        cycle_source_name, ranges = read_cycle_metadata(root, palette_size)
        if cycle_source_name != cycle_src:
            raise ValueError("ORA palette metadata references a different layer")

        scale = min(width / document_width, height / document_height)
        scaled_width = max(1, round(document_width * scale))
        scaled_height = max(1, round(document_height * scale))
        offset_x, offset_y = (width - scaled_width) // 2, (height - scaled_height) // 2
        base = base.resize((scaled_width, scaled_height), Image.Resampling.LANCZOS)
        base_canvas = Image.new("RGBA", (width, height), (0, 0, 0, 255))
        base_canvas.alpha_composite(base, (offset_x, offset_y))

        layer_width = max(1, round(indexed_width * scale))
        layer_height = max(1, round(indexed_height * scale))
        indexed = indexed.resize((layer_width, layer_height), Image.Resampling.NEAREST)
        alpha = alpha.resize((layer_width, layer_height), Image.Resampling.NEAREST)
        layer_x = round((int(cycle_layer.get("x", "0")) * scale)) + offset_x
        layer_y = round((int(cycle_layer.get("y", "0")) * scale)) + offset_y
        full_indices = bytearray(width * height)
        full_alpha = bytearray(width * height)
        src_indices, src_alpha = indexed.tobytes(), alpha.tobytes()
        for y in range(layer_height):
            target_y = layer_y + y
            if not 0 <= target_y < height:
                continue
            for x in range(layer_width):
                target_x = layer_x + x
                if not 0 <= target_x < width:
                    continue
                source_index = y * layer_width + x
                destination_index = target_y * width + target_x
                full_indices[destination_index] = src_indices[source_index]
                full_alpha[destination_index] = src_alpha[source_index]
        base_output.parent.mkdir(parents=True, exist_ok=True)
        cycle_output.parent.mkdir(parents=True, exist_ok=True)
        base_canvas.convert("RGB").save(base_output, format="PNG", optimize=True)

        cycle_chunks = []
        for first, last, interval, reverse in ranges:
            rate = round((1000.0 / interval) * 16384.0 / 60.0)
            if not 1 <= rate <= 65535:
                raise ValueError("Palette cycle speed cannot be represented in IFF")
            flags = 1 | (2 if reverse else 0)
            cycle_chunks.append(struct.pack(">HHHBB", 0, rate, flags, first, last))
        write_ilbm(cycle_output, width, height, palette, cycle_chunks,
                   planar_body(bytes(full_indices), bytes(full_alpha), width, height))
        return len(cycle_chunks)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=ROOT / "main.ora")
    parser.add_argument("--base-output", type=Path, default=ROOT / "main-base.png")
    parser.add_argument("--cycle-output", type=Path, default=ROOT / "main-cycle.iff")
    parser.add_argument("--width", type=int, default=275)
    parser.add_argument("--height", type=int, default=116)
    args = parser.parse_args()
    try:
        count = convert(args.source, args.base_output, args.cycle_output, args.width, args.height)
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, struct.error) as error:
        print(f"ORA to skin conversion failed: {error}", file=sys.stderr)
        return 1
    print(f"Wrote {args.width}x{args.height} skin layers with {count} palette-cycle ranges")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
