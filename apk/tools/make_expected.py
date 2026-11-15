#!/usr/bin/env python3
"""Generate reference data from hunter_analyze.py for the Java core tests.
Usage: python3 -I make_expected.py <hunter_analyze.py> <sample.txt> <synth.txt> <outdir>
"""
import importlib.util, os, sys, zlib, re

an_path, sample, synth, out = sys.argv[1:5]
spec = importlib.util.spec_from_file_location("hunter_analyze", an_path)
ha = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ha)
os.makedirs(out, exist_ok=True)

# ---- sample: old serial log -> SLCAN text via parse_serial_log
frames, _m, _meta = ha.parse_serial_log(sample)
with open(os.path.join(out, "sample_slcan.txt"), "w", newline="") as f:
    for t, i, d in frames:
        f.write("t%03X%d%s\r" % (i, len(d), d.hex().upper()))
crc = 0
per = {}
for t, i, d in frames:
    crc = zlib.crc32(("%03X%s\n" % (i, d.hex().upper())).encode(), crc)
    per[i] = per.get(i, 0) + 1
rpm = max(d[4] << 8 | d[5] for t, i, d in frames if i == 0x100)
with open(os.path.join(out, "sample_expected.txt"), "w") as f:
    f.write("frames %d\nrpmmax %d\ncrc %d\n" % (len(frames), rpm, crc))
    for i in sorted(per):
        f.write("id %03X %d\n" % (i, per[i]))

# ---- synth: compare() ranking using exactly the window logic of tests()
frames, marks, _meta = ha.load(synth)
names = {}
prep = {}
for t, m in marks:
    mm = re.match(r"(BASELINE start|EVENT start|EVENT end) (.+)", m)
    if mm:
        names.setdefault(mm.group(2), {})[mm.group(1)] = t
    mm = re.match(r"EVENT prepare (.+)", m)
    if mm:
        prep[mm.group(1)] = t
with open(os.path.join(out, "synth_expected.txt"), "w") as f:
    f.write("frames %d\nmarks %d\n" % (len(frames), len(marks)))
    for name, d in names.items():
        base = [x for x in frames if d["BASELINE start"] <= x[0] < prep.get(name, d["EVENT start"])]
        ev = [x for x in frames if d["EVENT start"] <= x[0] <= d["EVENT end"]]
        f.write("test %s|%s|%s|%s|%s|%d|%d\n" % (name, d["BASELINE start"], prep[name], d["EVENT start"], d["EVENT end"], len(base), len(ev)))
        for score, i, k, bs, es in ha.compare(base, ev):
            f.write("rank %.6f %03X %d %s | %s\n" % (score, i, k, ",".join("%02X" % x for x in bs), ",".join("%02X" % x for x in es)))
print("expected data written to", out)
