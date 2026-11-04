#!/usr/bin/env python3
"""Builds the Jeep WK diagnostic assets from a directory holding ecus.csv and dtc_codes.csv.

usage: make_jeep_wk.py <extracted-dir> [<assets-dir>]
writes <assets-dir>/diag/profiles/jeep-wk.json and jeep-wk-dtc.csv
(default assets dir: app/src/main/assets, relative to the repo root).
"""
import csv, json, os, sys

NAMES = {
    "PCM": "Engine", "TCM": "Transmission", "ABS": "Anti-lock brakes", "ESM": "Electronic shifter",
    "FDCM": "Final drive / AWD", "FCMCGW": "Front control module / gateway",
    "WCM": "Wireless control / immobilizer", "CCN": "Instrument cluster", "SCM": "Steering column",
    "RADIO": "Radio", "ORC": "Airbags", "EOM": "Overhead console",
    "AHBM": "Headlamp beam module", "AMP": "Amplifier", "DDM": "Driver door module",
    "HFM": "Hands-free module", "HSM": "Heated seat module", "HVAC": "Climate control",
    "ITM": "Integrated telematics module", "LRSM": "Left rear seat module", "MSMD": "Memory seat module",
    "OCM": "Occupant classification module", "PDM": "Passenger door module", "PTS": "Parking assist",
    "SDAR": "Satellite radio", "SUNR": "Sunroof", "VES": "Rear entertainment",
}
SENSITIVE = {"ORC", "WCM", "PCM"}
ORDER = ["PCM", "TCM", "ABS", "ESM", "FDCM", "FCMCGW", "WCM", "CCN", "SCM", "RADIO", "ORC", "EOM"]


def main():
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join("app", "src", "main", "assets")
    d = os.path.join(out, "diag", "profiles")
    os.makedirs(d, exist_ok=True)
    rows = list(csv.DictReader(open(os.path.join(src, "ecus.csv"), encoding="utf-8")))
    by = {r["module"]: r for r in rows}
    answered = [m for m in ORDER if m in by]
    silent = sorted(r["module"] for r in rows if r["module"] not in ORDER)
    mods = []
    for m in answered + silent:
        r = by[m]
        mods.append({
            "id": m, "name": NAMES.get(m, m),
            "tx": "0x" + r["req_can_id_hex"].upper(), "rx": "0x" + r["resp_can_id_hex"].upper(),
            "protocol": "KWP_CAN", "readRequest": "1800FF00", "clearSteps": ["1092", "14FF00"],
            "clearRisk": "SENSITIVE" if m in SENSITIVE else "NORMAL",
            "probeOnly": m in silent,
        })
    prof = {"id": "jeep-wk", "label": "Jeep WK (FCA KWP2000 on CAN)", "modules": mods}
    with open(os.path.join(d, "jeep-wk.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(prof, f, indent=1)
        f.write("\n")
    seen = {}
    for r in csv.DictReader(open(os.path.join(src, "dtc_codes.csv"), encoding="utf-8")):
        key = (r["module"], r["raw_code_hex_2byte"].upper())
        seen.setdefault(key, (r["sae_code"], r["description_en"].strip()))
    with open(os.path.join(d, "jeep-wk-dtc.csv"), "w", encoding="utf-8", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["module", "raw", "sae", "text"])
        for (m, raw), (sae, text) in sorted(seen.items()):
            w.writerow([m, raw, sae, text])
    print(len(mods), "modules,", len(seen), "descriptions")


main()
