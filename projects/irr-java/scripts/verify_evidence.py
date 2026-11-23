"""Check that the retained JMH records and untimed diagnostics satisfy the run contract.
  verify_evidence.py          full-run files + metadata + diagnostics
  verify_evidence.py --smoke  smoke-file record counts, metadata identity and diagnostics (no fork/iteration or load checks)
"""
import argparse,csv,json,math
from collections import defaultdict
from pathlib import Path
root=Path(__file__).resolve().parents[1]
# Expected number of JMH records per file (derived from scripts/study.sh).
FULL_FILES={'kernel-size':5*5,           # 5 daily sizes x 5 calls
            'kernel-schedule':4*9,       # 4 schedules x 9 calls
            'prep':4*2,                  # 4 schedules x {HISTORICAL, DIGEST}
            'ladder':7*9,                # 7 families x 9 stages, prepared
            'ladder-schedule':3*9,       # 3 schedules x 9 stages, standard, prepared
            'overall-dated':7*2,         # 7 families x 2 ends, dated
            'overall-seeds':2*2,         # 2 seeds x 2 ends, standard, prepared
            'overall-size':2*2}          # 2 sizes x 2 ends, standard, prepared
SMOKE_FILES={'smoke-kernel':9,'smoke-prep':2,'smoke-ladder':7*9*2}
# LadderDiagnostics.java main(): the matrix of (frequency, configuration, seed, family) cases, each with 9 stages and 64 inputs.
FAMILIES=['standard','wide','jcurve','signflips','extreme-positive','extreme-negative','dominance']
CASES=({('DAILY','n256','42',f) for f in FAMILIES}
       |{('DAILY','n256',s,'standard') for s in ('20261122','8675309')}
       |{(f,'n256','42','standard') for f in ('WEEKLY','MONTHLY','IRREGULAR')}
       |{('DAILY',c,'42','standard') for c in ('n16','n4096')})
STAGES=['HISTORICAL','PRIMITIVE_ARRAYS','ANALYTIC_DERIVATIVE','PHI_DIRECT','INCREMENTAL','SMALL_POWERS','ANALYTIC_BOUNDS','NEWTON_FIRST','OPTIMIZED']
DIAG_ROWS=len(CASES)*len(STAGES)*64
ROOT_STATUS=('CONVERGED','RATE_UNREPRESENTABLE')
TARGET_FAMILIES=('standard','wide','jcurve','extreme-positive','extreme-negative')
ap=argparse.ArgumentParser();ap.add_argument('--smoke',action='store_true');smoke=ap.parse_args().smoke

records=0;groups=0;worst_load=0.0
for name,count in (SMOKE_FILES if smoke else FULL_FILES).items():
    data=json.loads((root/'results'/f'{name}.json').read_text())
    assert len(data)==count,(name,len(data),count)
    metadata=json.loads((root/'results'/f'{name}.metadata.json').read_text())
    assert metadata['generator']=='v3' and metadata['corpus_size']==64,name
    if not smoke:
        load=float(metadata['system_load_average'][0]);worst_load=max(worst_load,load)
        assert load<3,(name,'1-minute load average',load)
    for row in data:
        m=row['primaryMetric'];score=float(m['score'])
        assert m['scoreUnit']=='ns/op' and math.isfinite(score) and score>0,(name,row['benchmark'])
        alloc=row['secondaryMetrics']['gc.alloc.rate.norm']
        assert alloc['scoreUnit']=='B/op' and math.isfinite(float(alloc['score'])),(name,row['benchmark'])
        if not smoke:
            assert row['forks']==3 and row['warmupIterations']==5 and row['measurementIterations']==5,(name,row['benchmark'])
            assert row['warmupTime']=='1 s' and row['measurementTime']=='1 s',(name,row['benchmark'])
            assert len(m['rawData'])==3 and all(len(x)==5 for x in m['rawData']),(name,row['benchmark'])
    records+=len(data);groups+=1

n=0;returned=0;target_checked=0;no_solution=0;paired=0
by_input={};stages_seen=defaultdict(set)
with (root/'results/diagnostics.csv').open() as f:
    for r in csv.DictReader(f):
        n+=1;case=(r['frequency'],r['configuration'],r['seed'],r['family']);key=case+(r['index'],)
        assert r['generator']=='v3',r
        assert case in CASES,('unexpected case',case)
        assert r['stage'] in STAGES,r
        stages_seen[key].add(r['stage'])
        stage,family=r['stage'],r['family'];got=r['status'] in ROOT_STATUS
        if got:
            returned+=1;phi=float(r['phi']);residual=float(r['residual'])
            assert math.isfinite(phi),r
            assert residual<1e-4,r
            if r['path']=='NEWTON':assert residual<1e-8,r
            if family in TARGET_FAMILIES:
                target=float(r['target_phi']);assert abs(phi-target)<=1e-4*max(1,abs(target)),r;target_checked+=1
        if stage=='OPTIMIZED' and family!='dominance':assert got,r
        if family=='dominance' and stage in ('ANALYTIC_BOUNDS','NEWTON_FIRST','OPTIMIZED'):
            assert r['status']=='NO_SOLUTION' and r['values']=='0' and r['derivatives']=='0',r;no_solution+=1
        assert int(r['newton_iterations'])<=200,r
        if stage in ('HISTORICAL','PRIMITIVE_ARRAYS'):by_input.setdefault(key,{})[stage]=tuple(r[k] for k in ('status','path','values','derivatives'))
assert n==DIAG_ROWS,(n,DIAG_ROWS)
assert {k[:4] for k in stages_seen}==CASES,'diagnostic cases differ from LadderDiagnostics.java'
assert len(stages_seen)==len(CASES)*64 and all(v==set(STAGES) for v in stages_seen.values()),'incomplete diagnostic matrix'
for key,pair in by_input.items():
    assert len(pair)==2 and pair['HISTORICAL']==pair['PRIMITIVE_ARRAYS'],(key,pair);paired+=1
print(f"Validated {records} JMH records in {groups} files ({'smoke' if smoke else 'full'}) and {n} diagnostic rows."
      +('' if smoke else f' Worst recorded 1-minute load average {worst_load:.2f}.'))
print(f'{returned} returned roots have finite phi and residual < 1e-4 (< 1e-8 on NEWTON); {target_checked} match their target phi; '
      f'{no_solution} dominance outcomes at ANALYTIC_BOUNDS/NEWTON_FIRST/OPTIMIZED are NO_SOLUTION with zero evaluations; '
      f'{paired} inputs agree between HISTORICAL and PRIMITIVE_ARRAYS.')
