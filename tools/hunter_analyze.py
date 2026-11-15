#!/usr/bin/env python3
"""Analyse CAN captures saved by the Hunter CAN Live PWA (v1 and v2 files, or old serial_*.txt logs).

Usage:
    python3 hunter_analyze.py capture1.txt [capture2.txt ...]

For each file it prints:
  * frames per CAN ID and rate
  * capture header: vehicle profile, bitrate, ID format (v2 files)
  * known Hunter signals (only for Hunter captures; skipped for cars/custom)
  * passive OBD-II responses (0x7E8-0x7EF) if another tool produced them
  * your '# SIGNAL' byte names and user-defined decoders (min/max/last/mean)
  * the recorded '# HEALTH' line
  * connection gaps (DISCONNECT / RECONNECT markers)
  * for every control test (BASELINE / EVENT marks written by the page), the
    bytes that changed, ranked. These are CANDIDATES, not identifications.

v3 files may also contain (only when the opt-in Diagnostics mode was used):
  * '# TX <t> <ID> <n> <bytes> [isotp]'   every frame the app transmitted (isotp = OBD payload given to an ELM327)
  * '# MODE_DIAGNOSTICS <t>' / '# MODE_MONITOR <t>'   adapter mode changes
  * '# ADAPTER type=... mode=...'          which adapter produced the file
The analyzer lists the TX frames, flags a Mode 04 (clear codes) request, and decodes OBD-II
fault-code (Mode 03/07/0A) and VIN (Mode 09) responses found in the frames.

Read-only analysis of a file. This script never sends anything to a vehicle.
"""
import re
import sys
import statistics
from collections import Counter, defaultdict

GEAR = {0x00: "N", 0x08: "1", 0x10: "2", 0x18: "3", 0x20: "4", 0x28: "5", 0x38: "init"}

# ---------- parsing ----------
LIVE = re.compile(r"^(\d+\.\d+)\s+([0-9A-F]{3}|[0-9A-F]{8})\s+(\d)\s*((?:[0-9A-F]{2}\s*)*)$")
EXT = 1 << 32  # key = id for 11-bit, id + 2**32 for 29-bit (same scheme as the app)


def key_of(txt):
    return int(txt, 16) + (EXT if len(txt) == 8 else 0)


def fmt_id(k):
    return f"0x{k - EXT:08X}" if k >= EXT else f"0x{k:03X}"

TS = re.compile(r"(?:\d{4}-\d\d-\d\d\s+)?\d\d:\d\d:\d\d\.\d{3}\s?")


def parse_signal(text, meta):
    """'# SIGNAL <id> B<i> = name'  or  '# SIGNAL <id> B<start>+<len> BE|LE signed=0 scale=1 offset=0 unit=x = name'."""
    if " = " not in text:
        return
    left, name = text.split(" = ", 1)
    toks = left.split()
    if len(toks) < 2 or not toks[0].lower().startswith("0x"):
        return
    idtxt = toks[0][2:].upper()
    m = re.fullmatch(r"B(\d)(?:\+(\d))?", toks[1])
    if not m or not re.fullmatch(r"[0-9A-F]{1,8}", idtxt):
        return
    key = key_of(idtxt.zfill(8) if len(idtxt) > 3 else idtxt.zfill(3))
    if m.group(2) is None:
        meta["names"][(key, int(m.group(1)))] = name.strip()
        return
    d = {"key": key, "start": int(m.group(1)), "len": int(m.group(2)), "endian": "be", "signed": 0,
         "scale": 1.0, "offset": 0.0, "unit": "", "name": name.strip(), "unverified": False}
    for t in toks[2:]:
        if t in ("BE", "LE"):
            d["endian"] = t.lower()
        elif "=" in t:
            k, v = t.split("=", 1)
            try:
                if k == "signed":
                    d["signed"] = int(v)
                elif k in ("scale", "offset"):
                    d[k] = float(v)
                elif k == "unit":
                    d["unit"] = "" if v == "-" else v
                elif k == "unverified":
                    d["unverified"] = v == "1"
            except ValueError:
                pass
    meta["decoders"].append(d)


def decode_signal(d, data):
    if len(data) < d["start"] + d["len"]:
        return None
    chunk = data[d["start"]:d["start"] + d["len"]]
    raw = int.from_bytes(chunk, "big" if d["endian"] == "be" else "little")
    if d["signed"] and raw >= 1 << (8 * d["len"] - 1):
        raw -= 1 << (8 * d["len"])
    return raw * d["scale"] + d["offset"]


def new_meta():
    return {"profile": {}, "names": {}, "decoders": [], "health": None, "app": None, "tx": [], "adapter": None}


TXLINE = re.compile(r"^# TX (\d+\.\d+) ([0-9A-F]{3}|[0-9A-F]{8}) (\d) ?((?:[0-9A-F]{2} ?)*?)\s*(isotp)?\s*$")


def parse_live(path):
    frames, marks, meta = [], [], new_meta()  # frames: (t, key, bytes); marks: (t, text)
    for line in open(path, encoding="latin1"):
        line = line.rstrip("\r\n")
        if line.startswith("# MARK "):
            p = line.split(" ", 3)
            marks.append((float(p[2]), p[3] if len(p) > 3 else ""))
        elif line.startswith("# TX "):
            m = TXLINE.match(line)
            if m:
                d = bytes.fromhex(m.group(4).replace(" ", ""))
                if len(d) == int(m.group(3)):
                    meta["tx"].append((float(m.group(1)), key_of(m.group(2)), d, bool(m.group(5))))
        elif line.startswith("# ADAPTER "):
            meta["adapter"] = line[10:].strip()
        elif line.startswith(("# DISCONNECT ", "# RECONNECT ", "# USER_DISCONNECT ", "# MODE_DIAGNOSTICS ", "# MODE_MONITOR ")):
            p = line.split()
            marks.append((float(p[2]), p[1]))
        elif line.startswith("# PROFILE "):
            meta["profile"] = dict(t.split("=", 1) for t in line[10:].split() if "=" in t)
        elif line.startswith("# APP "):
            meta["app"] = line[6:].strip()
        elif line.startswith("# SIGNAL "):
            parse_signal(line[9:], meta)
        elif line.startswith("# HEALTH"):
            meta["health"] = line[2:].strip()
        elif line.startswith("#"):
            continue
        else:
            m = LIVE.match(line.strip())
            if m:
                n = int(m.group(3))
                d = bytes.fromhex(m.group(4).replace(" ", ""))
                if len(d) == n:
                    frames.append((float(m.group(1)), key_of(m.group(2)), d))
    return frames, marks, meta


def parse_serial_log(path):
    """Old serial_*.txt logs (text SLCAN or hex dump). No per-frame time: use index."""
    raw = open(path, "rb").read().decode("latin1")
    out = []
    for chunk in TS.split(raw):
        t = chunk.strip("\r\n")
        s = t.strip()
        if s and " " in s and re.fullmatch(r"(?:[0-9A-F]{2} ?)+", s):
            out.append(bytes.fromhex(s.replace(" ", "")).decode("latin1"))
        else:
            out.append(t.replace("^M", "\r"))
    stream = "".join(out)
    frames = []
    for m in re.finditer(r"(?:t([0-9A-F]{3})|T([0-9A-F]{8}))([0-8])([0-9A-F]*?)(?=\r|[tT][0-9A-F]{3}[0-8]|$)", stream):
        n, h = int(m.group(3)), m.group(4)
        if len(h) == 2 * n:
            k = int(m.group(1), 16) if m.group(1) else int(m.group(2), 16) + EXT
            frames.append((len(frames) * 0.0037, k, bytes.fromhex(h)))
    return frames, [], new_meta()


def load(path):
    head = open(path, encoding="latin1").read(2000)
    if head.startswith("# Hunter live capture") or head.startswith("# ") or LIVE.match(head.split("\n")[0].strip() or "x"):
        return parse_live(path)
    return parse_serial_log(path)


# ---------- reporting ----------
def per_id(frames):
    by = defaultdict(list)
    for t, i, d in frames:
        by[i].append((t, d))
    return by


def summary(by, dur):
    print("  ID     frames    Hz")
    for i in sorted(by):
        n = len(by[i])
        print(f"  {fmt_id(i)} {n:8d} {n / dur if dur else 0:6.1f}")


def rng(vals):
    return (min(vals), max(vals)) if vals else (None, None)


def decode(by):
    print("  Known Hunter signals (confidence labels from the signal map):")
    if 0x100 in by:
        r = [d[4] << 8 | d[5] for _, d in by[0x100]]
        print(f"   RPM (0x100 B4:B5, confirmed): min {min(r)} max {max(r)}")
    if 0x150 in by:
        g = Counter(GEAR.get(d[0], f"0x{d[0]:02X}") for _, d in by[0x150])
        print(f"   Gear (0x150 B0, confirmed): {dict(g)}")
    if 0x12B in by:
        a = [(d[0] << 8 | d[1]) / 10 for _, d in by[0x12B] if not (d[0] == 0xFF and d[1] == 0xFF)]
        b = [(d[2] << 8 | d[3]) / 10 for _, d in by[0x12B] if not (d[2] == 0xFF and d[3] == 0xFF)]
        print(f"   Wheel A km/h (0x12B B0:B1): max {max(a) if a else '-'} | Wheel B (B2:B3) max {max(b) if b else '-'}"
              "  [front/rear not confirmed; B probably rear]")
    if 0x80 in by:
        L = [d for _, d in by[0x80]]
        fb = sum(1 for d in L if d[0] & 1)
        cl = sum(1 for d in L if d[7] & 4)
        ss = sum(1 for d in L if d[7] & 0x20)
        print(f"   0x080: front brake on {fb}/{len(L)}, clutch {cl}/{len(L)}, side stand {ss}/{len(L)} frames;"
              f" B1 (analog candidate) max {max(d[1] for d in L)}")
    if 0x140 in by:
        v = [d[1] << 16 | d[2] << 8 | d[3] for _, d in by[0x140]]
        print(f"   0x140 24-bit counter (hypothesis, ECU-on time): {v[0]} -> {v[-1]}")
    if 0x211 in by:
        L = [d for _, d in by[0x211]]
        print("   0x211 B0/B1/B2 deg C (raw-40, provisional): "
              + " / ".join(f"{min(d[i] for d in L) - 40}..{max(d[i] for d in L) - 40}" for i in range(3)))
    for i in (0x280, 0x290, 0x450):
        if i in by:
            pay = Counter(d for _, d in by[i])
            print(f"   0x{i:03X}: {len(pay)} distinct payload(s)" + (" (all zero)" if list(pay) == [bytes(8)] else ""))


OBD = {0x04: ("Engine load", "%", 1, lambda a, b: a * 100 / 255), 0x05: ("Coolant temp", "C", 1, lambda a, b: a - 40),
       0x0B: ("Intake MAP", "kPa", 1, lambda a, b: a), 0x0C: ("Engine speed", "rpm", 2, lambda a, b: (a * 256 + b) / 4),
       0x0D: ("Vehicle speed", "km/h", 1, lambda a, b: a), 0x0F: ("Intake air temp", "C", 1, lambda a, b: a - 40),
       0x10: ("MAF rate", "g/s", 2, lambda a, b: (a * 256 + b) / 100), 0x11: ("Throttle", "%", 1, lambda a, b: a * 100 / 255),
       0x2F: ("Fuel level", "%", 1, lambda a, b: a * 100 / 255), 0x42: ("Module voltage", "V", 2, lambda a, b: (a * 256 + b) / 1000),
       0x46: ("Ambient temp", "C", 1, lambda a, b: a - 40), 0x5C: ("Oil temp", "C", 1, lambda a, b: a - 40)}


def decode_obd(by):
    """Passive: only responses another tool already produced (0x7E8-0x7EF, mode 01). Nothing is ever requested."""
    vals = defaultdict(list)
    dtc = 0
    for k in range(0x7E8, 0x7F0):
        for _, d in by.get(k, []):
            if len(d) >= 3 and 2 <= d[0] <= 7:
                if d[1] == 0x41 and d[2] in OBD:
                    n, u, ln, f = OBD[d[2]]
                    if len(d) >= 3 + ln and d[0] >= 2 + ln:
                        vals[d[2]].append(f(d[3], d[4] if len(d) > 4 else 0))
                elif d[1] == 0x43:
                    dtc += 1
    if vals or dtc:
        print("  Passive OBD-II responses (standard formulas; app never requested them):")
        for pid in sorted(vals):
            n, u, _, _ = OBD[pid]
            v = vals[pid]
            print(f"   PID {pid:02X} {n}: min {min(v):.2f} max {max(v):.2f} {u} ({len(v)} samples)")
        if dtc:
            print(f"   {dtc} stored-code response frame(s) seen (another tool read codes; not decoded here)")


def decode_user(by, meta):
    if meta["names"]:
        print("  Your named bytes (# SIGNAL):")
        for (k, i), nm in sorted(meta["names"].items()):
            print(f"   {fmt_id(k)} B{i} = {nm}")
    if meta["decoders"]:
        print("  Your decoders (# SIGNAL):")
        for d in meta["decoders"]:
            v = [x for x in (decode_signal(d, p) for _, p in by.get(d["key"], [])) if x is not None]
            tag = " [unverified]" if d["unverified"] else ""
            if v:
                print(f"   {d['name']}{tag}: {fmt_id(d['key'])} B{d['start']}+{d['len']} {d['endian'].upper()} -> "
                      f"min {min(v):.3f} max {max(v):.3f} mean {statistics.mean(v):.3f} last {v[-1]:.3f} {d['unit']} ({len(v)} samples)")
            else:
                print(f"   {d['name']}{tag}: {fmt_id(d['key'])} no matching frames")


def compare(base, ev, skip=frozenset({0x140})):
    B, E = defaultdict(list), defaultdict(list)
    for _, i, d in base:
        B[i].append(d)
    for _, i, d in ev:
        E[i].append(d)
    out = []
    for i in (set(B) | set(E)) - set(skip):  # 0x140 (Hunter) is a running counter, always "changes"
        if not B[i] or not E[i]:
            continue
        for k in range(8):
            bv = [x[k] if len(x) > k else 0 for x in B[i]]
            ev_ = [x[k] if len(x) > k else 0 for x in E[i]]
            bs, es = set(bv), set(ev_)
            if bs != es:
                sym = len(bs ^ es)
                score = sym * 10 + abs(statistics.mean(ev_) - statistics.mean(bv)) + (max(ev_) - min(ev_))
                out.append((score, i, k, sorted(bs)[:5], sorted(es)[:5]))
    out.sort(reverse=True)
    return out[:12]


def tests(frames, marks, skip=frozenset({0x140})):
    tags = [(t, m) for t, m in marks]
    names = {}
    for t, m in tags:
        mm = re.match(r"(BASELINE start|EVENT start|EVENT end) (.+)", m)
        if mm:
            names.setdefault(mm.group(2), {})[mm.group(1)] = t
    # BASELINE start -> EVENT prepare -> EVENT start -> EVENT end
    prep = {}
    for t, m in tags:
        mm = re.match(r"EVENT prepare (.+)", m)
        if mm:
            prep[mm.group(1)] = t
    for name, d in names.items():
        if not {"BASELINE start", "EVENT start", "EVENT end"} <= set(d):
            continue
        base = [f for f in frames if d["BASELINE start"] <= f[0] < prep.get(name, d["EVENT start"])]
        ev = [f for f in frames if d["EVENT start"] <= f[0] <= d["EVENT end"]]
        print(f"  Control test: {name}  (baseline {len(base)} frames, event {len(ev)} frames)")
        res = compare(base, ev, skip)
        if not res:
            print("    no byte changed (control may not be on CAN, or not operated)")
        for score, i, k, bs, es in res:
            print(f"    {fmt_id(i)} B{k}: {[f'{x:02X}' for x in bs]} -> {[f'{x:02X}' for x in es]}   score {score:.0f}")
    if names:
        print("  (candidates only, not identifications)")


def dtc_decode(a, b):
    """First byte bits 7-6: 00 P, 01 C, 10 B, 11 U; bits 5-4 first digit; bits 3-0 second digit; second byte = last two digits."""
    return "PCBU"[a >> 6] + f"{(a >> 4) & 3:X}{a & 15:X}{b:02X}"


def isotp_messages(frames):
    """Reassemble ISO-TP messages sent by ECUs (0x7E8-0x7EF). Returns [(t, ecu_key, payload)]."""
    st, out = {}, []
    for t, k, d in frames:
        if not (0x7E8 <= k <= 0x7EF) or not d:
            continue
        pci = d[0] >> 4
        if pci == 0:
            n = d[0] & 15
            if 1 <= n <= 7 and len(d) >= 1 + n:
                out.append((t, k, d[1:1 + n]))
        elif pci == 1 and len(d) >= 3:
            ln = ((d[0] & 15) << 8) | d[1]
            if ln >= 8:
                st[k] = [ln, bytearray(d[2:2 + ln]), 1, t]
        elif pci == 2 and k in st:
            ln, buf, sn, t0 = st[k]
            if (d[0] & 15) != sn:
                del st[k]
                continue
            buf.extend(d[1:1 + ln - len(buf)])
            st[k][2] = (sn + 1) & 15
            if len(buf) >= ln:
                out.append((t0, k, bytes(buf)))
                del st[k]
    return out


TXSVC = {0x01: "Mode 01 live data", 0x03: "Mode 03 read stored codes", 0x04: "Mode 04 CLEAR codes", 0x07: "Mode 07 read pending codes",
         0x09: "Mode 09 vehicle info", 0x0A: "Mode 0A read permanent codes"}


def diag_report(frames, marks, meta):
    tx = meta["tx"]
    modes = [(t, m) for t, m in marks if m in ("MODE_DIAGNOSTICS", "MODE_MONITOR")]
    if not tx and not modes:
        return
    print(f"  Active diagnostics: {len(tx)} transmitted frame(s) (# TX). This capture is NOT purely listen-only"
          " (the profile line describes the start of the recording).")
    if meta["adapter"]:
        print(f"   Adapter: {meta['adapter']}")
    if modes:
        print("   Mode changes: " + ", ".join(f"{m}@{t:.1f}s" for t, m in modes))
    if tx:
        c = Counter(fmt_id(k) for _, k, _, _ in tx)
        print("   TX by ID: " + ", ".join(f"{i} x{n}" for i, n in sorted(c.items())))
        for t, k, d, iso in tx[:20]:
            body = d[1:1 + (d[0] & 15)] if (not iso and d and d[0] >> 4 == 0) else d
            svc = TXSVC.get(body[0], "service 0x%02X" % body[0]) if body and not (d and d[0] >> 4 == 3 and not iso) else "ISO-TP flow control"
            print(f"    {t:9.3f}s TX {fmt_id(k)} {' '.join(f'{x:02X}' for x in d)}  [{svc}]")
        if len(tx) > 20:
            print(f"    ... {len(tx) - 20} more")
        clears = [t for t, k, d, iso in tx if (d[:2] == b"\x01\x04" and not iso) or (iso and d[:1] == b"\x04")]
        if clears:
            print("   WARNING: Mode 04 (clear fault codes) was requested at " + ", ".join(f"{t:.1f}s" for t in clears)
                  + ". Readiness monitors and freeze-frame data were reset on the vehicle.")
    kinds = {0x43: "stored", 0x47: "pending", 0x4A: "permanent"}
    for t, k, p in isotp_messages(frames):
        if p and p[0] in kinds:
            body = p[1:]
            if len(body) % 2:
                body = body[1:]
            codes = [dtc_decode(body[i], body[i + 1]) for i in range(0, len(body) - 1, 2) if body[i] or body[i + 1]]
            print(f"   {kinds[p[0]]} codes from ECU {fmt_id(k)} at {t:.1f}s: {', '.join(codes) if codes else 'none'}")
        elif p and p[:2] == b"\x49\x02":
            print(f"   VIN from ECU {fmt_id(k)}: {bytes(x for x in p[3:] if 32 <= x < 127).decode()}")
        elif p and p[0] == 0x44:
            print(f"   Mode 04 positive response from ECU {fmt_id(k)} at {t:.1f}s")


def gaps(marks):
    ev = [(t, m) for t, m in marks if m in ("DISCONNECT", "RECONNECT", "USER_DISCONNECT")]
    if ev:
        print("  Connection events: " + ", ".join(f"{m}@{t:.1f}s" for t, m in ev))


def main(paths):
    for p in paths:
        frames, marks, meta = load(p)
        print(f"\n=== {p}")
        if not frames:
            print("  no frames found")
            continue
        prof = meta["profile"]
        if prof:
            print(f"  Profile: {prof.get('name', prof.get('id', '?')).replace('_', ' ')}, {int(prof.get('bitrate', 0)) // 1000} kbps, "
                  f"ID format {prof.get('idfmt', '?')}, listen-only {prof.get('listen_only', '?')}")
        dur = frames[-1][0] - frames[0][0]
        print(f"  {len(frames)} frames over {dur:.1f} s")
        by = per_id(frames)
        summary(by, dur)
        hunter = prof.get("id", "hunter") == "hunter"  # old files have no profile line: assume Hunter
        if hunter:
            decode(by)
        else:
            print(f"  Hunter signal decoders skipped (profile: {prof.get('name', prof.get('id')).replace('_', ' ')}).")
        decode_obd(by)
        decode_user(by, meta)
        if meta["health"]:
            print("  Recorded " + meta["health"])
        gaps(marks)
        diag_report(frames, marks, meta)
        tests(frames, marks, frozenset({0x140}) if hunter else frozenset())


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
