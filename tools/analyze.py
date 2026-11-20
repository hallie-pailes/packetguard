#!/usr/bin/env python3
"""Join attack logs with mod / proxy event logs and summarise detection, false positives, overhead. Matches by time window, plus or minus 1.5 seconds, and by packet kind.

Usage
  analyze.py attacks  --attack-log attack_log.jsonl [--mod-events events.jsonl] [--proxy-events events.jsonl]
                      [--slack-ms 1500]
  analyze.py benign   --metrics metrics.csv [--label NAME]
  analyze.py latency  --metrics metrics.csv [--label NAME]

An attack counts as DETECTED by a system when at least one event of the same packet kind from that
system falls in the attack's time window. 'blocked' counts events where the packet was actually
stopped (ENFORCE / filter). For the mod, events also carry minVisibility, so one run of the mod in
MONITOR + shadowEvaluation shows what a proxy could in principle have caught (PACKET/CONNECTION)
versus what needs game state.
"""
import argparse, csv, json, sys
from collections import Counter, defaultdict


def jl(path):
    if not path:
        return []
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip() and "event" not in json.loads(l)]


def in_window(ev, a, slack):
    return a["startMillis"] - slack <= ev.get("timeMillis", 0) <= a["endMillis"] + slack


def summarise(attacks, events, slack):
    out = {}
    for a in attacks:
        hits = [e for e in events if e.get("kind") == a["kind"] and in_window(e, a, slack)]
        vis = Counter(e.get("minVisibility") for e in hits)
        out[a["attack"]] = {"flagged": len(hits), "blocked": sum(1 for e in hits if e.get("blocked")),
                            "minVisibility": dict(vis), "rules": sorted({e.get("rule") for e in hits if e.get("rule")})}
    return out


def cmd_attacks(a):
    attacks = jl(a.attack_log)
    mod = summarise(attacks, jl(a.mod_events), a.slack_ms) if a.mod_events else None
    prx = summarise(attacks, jl(a.proxy_events), a.slack_ms) if a.proxy_events else None
    cols = ["attack", "class", "sent"]
    if prx is not None: cols += ["proxy_flagged", "proxy_blocked"]
    if mod is not None: cols += ["mod_flagged", "mod_blocked", "mod_min_visibility"]
    w = csv.writer(sys.stdout); w.writerow(cols)
    for at in attacks:
        n = at["attack"]; row = [n, at.get("class", ""), at["sent"]]
        if prx is not None: row += [prx[n]["flagged"], prx[n]["blocked"]]
        if mod is not None:
            mv = "+".join(sorted(mod[n]["minVisibility"])) or "-"
            row += [mod[n]["flagged"], mod[n]["blocked"], mv]
        w.writerow(row)
    # summary
    def rate(s, key): return sum(1 for at in attacks if s[at["attack"]][key] > 0) / max(1, len(attacks))
    print(file=sys.stderr)
    if prx is not None: print(f"proxy detects {rate(prx,'flagged'):.0%} of attack classes, blocks {rate(prx,'blocked'):.0%}", file=sys.stderr)
    if mod is not None: print(f"mod   detects {rate(mod,'flagged'):.0%} of attack classes, blocks {rate(mod,'blocked'):.0%}", file=sys.stderr)


def read_metrics(path):
    d = defaultdict(dict)
    with open(path) as f:
        for r in csv.DictReader(f):
            d[r["section"]][r["key"]] = int(r["value"])
    return d


def cmd_benign(a):
    m = read_metrics(a.metrics)
    ev, rj = sum(m["evaluated"].values()), sum(m["rejected"].values())
    print(f"{a.label or a.metrics}: evaluated={ev} rejected={rj} false_positive_rate={rj / max(1, ev):.6f}")
    for rule, n in sorted(m["rejected_by_rule"].items()):
        print(f"  {rule}: {n}")


def cmd_latency(a):
    m = read_metrics(a.metrics)
    print(f"{a.label or a.metrics}: p50={m['latency_ns'].get('p50',0)} ns  p99={m['latency_ns'].get('p99',0)} ns  "
          f"packets={sum(m['evaluated'].values())}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("attacks"); s.add_argument("--attack-log", required=True)
    s.add_argument("--mod-events"); s.add_argument("--proxy-events"); s.add_argument("--slack-ms", type=int, default=1500)
    s.set_defaults(fn=cmd_attacks)
    for n, fn in (("benign", cmd_benign), ("latency", cmd_latency)):
        s = sp.add_parser(n); s.add_argument("--metrics", required=True); s.add_argument("--label"); s.set_defaults(fn=fn)
    a = ap.parse_args(); a.fn(a)


if __name__ == "__main__":
    main()
