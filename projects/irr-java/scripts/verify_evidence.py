"""Check that the retained JMH records and untimed diagnostics satisfy the run contract.
  verify_evidence.py          full-run files + metadata + diagnostics
  verify_evidence.py --smoke  smoke-file record counts, metadata identity and diagnostics (no fork/iteration or load checks)
"""
import argparse,csv,json,math
from collections import Counter,defaultdict
from pathlib import Path
root=Path(__file__).resolve().parents[1]
# Expected number of JMH records per file (derived from scripts/study.sh).
FULL_FILES={'kernel-size':5*5,           # 5 daily sizes x 5 calls
            'kernel-schedule':4*12,      # 4 schedules x 12 calls
            'kernel-gaps':2*4,           # fixed 37-day gap and sorted irregular gaps x 4 calls
            'prep':4*2,                  # 4 schedules x {HISTORICAL, DIGEST}
            'ladder':7*12,               # 7 families x 12 stages, prepared
            'ladder-schedule':3*12,      # 3 schedules x 12 stages, standard, prepared
            'overall-dated':7*3,         # 7 families x 3 ends, dated
            'overall-seeds':2*3,         # 2 seeds x 3 ends, standard, prepared
            'overall-size':2*3,          # 2 sizes x 3 ends, standard, prepared
            'library-daily':2*2*6,       # 2 families x 2 amounts x 6 solvers (daily schedule)
            'library-irregular':2*2*3,   # 2 families x 2 amounts x HISTORICAL, EXTENDED, XIRR (irregular schedule)
            **{f'scaling-t{t}':2*2 for t in (1,2,4,8,16)}}  # 2 families x 2 ends, t threads
SMOKE_FILES={'smoke-kernel':12,'smoke-prep':2,'smoke-ladder':7*12*2,'smoke-scaling':2*2,'smoke-library-daily':2*2*6,'smoke-library-irregular':2*2*3}
# LibraryBench solvers per schedule; LibraryDiagnostics.java runs the same matrix on n256, seed 42, 64 inputs.
LIB_FAMILIES=('standard','wide')
LIB_AMOUNTS=('NORMALIZED','RAW')
LIB_SOLVERS={'DAILY':['HISTORICAL','EXTENDED','EXTENDED_PREPARED','XIRR','POI_IRR','POI_IRR_ZERO_GUESS'],'IRREGULAR':['HISTORICAL','EXTENDED','XIRR']}
LIB_AGREE=1e-5  # a root agrees with EXTENDED when |phi - phi_EXTENDED| <= 1e-5
LIB_FILE_SCHEDULE={'library-daily':'DAILY','library-irregular':'IRREGULAR','smoke-library-daily':'DAILY','smoke-library-irregular':'IRREGULAR'}
LIB_ROWS=sum(len(v) for v in LIB_SOLVERS.values())*len(LIB_FAMILIES)*len(LIB_AMOUNTS)*64
# LadderDiagnostics.java main(): the matrix of (frequency, configuration, seed, family) cases, each with 12 stages and 64 inputs.
FAMILIES=['standard','wide','jcurve','signflips','extreme-positive','extreme-negative','dominance']
CASES=({('DAILY','n256','42',f) for f in FAMILIES}
       |{('DAILY','n256',s,'standard') for s in ('20261122','8675309')}
       |{(f,'n256','42','standard') for f in ('WEEKLY','MONTHLY','IRREGULAR')}
       |{('DAILY',c,'42','standard') for c in ('n16','n4096')})
STAGES=['HISTORICAL','PRIMITIVE_ARRAYS','ANALYTIC_DERIVATIVE','PHI_DIRECT','INCREMENTAL','SMALL_POWERS','ANALYTIC_BOUNDS','NEWTON_FIRST','OPTIMIZED',
        'POWER_TABLE','TRUSTED_GUESS','LOG_NEWTON']
ANALYSED=STAGES[6:]  # stages with the existence analysis
PLANNED=STAGES[8:]   # OPTIMIZED and the extensions
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
        scaling='scaling' in name
        assert m['scoreUnit']==('ops/s' if scaling else 'ns/op') and math.isfinite(score) and score>0,(name,row['benchmark'])
        if scaling and not smoke:assert row['threads']==int(name.rsplit('t',1)[1]),(name,row['threads'])
        alloc=row['secondaryMetrics']['gc.alloc.rate.norm']
        assert alloc['scoreUnit']=='B/op' and math.isfinite(float(alloc['score'])),(name,row['benchmark'])
        if not smoke:
            assert row['forks']==3 and row['warmupIterations']==5 and row['measurementIterations']==5,(name,row['benchmark'])
            assert row['warmupTime']=='1 s' and row['measurementTime']=='1 s',(name,row['benchmark'])
            assert len(m['rawData'])==3 and all(len(x)==5 for x in m['rawData']),(name,row['benchmark'])
    if name in LIB_FILE_SCHEDULE:
        schedule=LIB_FILE_SCHEDULE[name];seen=set()
        for row in data:
            p=row['params'];assert row['benchmark'].endswith('LibraryBench.solve') and p['frequency']==schedule and p['seed']=='42',(name,p)
            assert p['configuration']==('n16' if smoke else 'n256'),(name,p)
            seen.add((p['amounts'],p['family'],p['solver']))
        assert seen=={(a,f,v) for a in LIB_AMOUNTS for f in LIB_FAMILIES for v in LIB_SOLVERS[schedule]},(name,seen)
    records+=len(data);groups+=1

n=0;returned=0;target_checked=0;no_solution=0;paired=0;far_negative=0
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
        if stage in PLANNED and family!='dominance':assert got,r
        if stage=='LOG_NEWTON' and family=='extreme-negative':
            assert r['path']=='NEWTON' and int(r['newton_iterations'])<=10,r;far_negative+=1
        if family=='dominance' and stage in ANALYSED:
            assert r['status']=='NO_SOLUTION' and r['values']=='0' and r['derivatives']=='0',r;no_solution+=1
        assert int(r['newton_iterations'])<=200,r
        if stage in ('HISTORICAL','PRIMITIVE_ARRAYS'):by_input.setdefault(key,{})[stage]=tuple(r[k] for k in ('status','path','values','derivatives'))
assert n==DIAG_ROWS,(n,DIAG_ROWS)
assert {k[:4] for k in stages_seen}==CASES,'diagnostic cases differ from LadderDiagnostics.java'
assert len(stages_seen)==len(CASES)*64 and all(v==set(STAGES) for v in stages_seen.values()),'incomplete diagnostic matrix'
for key,pair in by_input.items():
    assert len(pair)==2 and pair['HISTORICAL']==pair['PRIMITIVE_ARRAYS'],(key,pair);paired+=1
# Library outcomes: LibraryDiagnostics.java, one row per (amounts, family, frequency, solver, input).
lib=defaultdict(list);lib_n=0
with (root/'results/library-diagnostics.csv').open() as f:
    for r in csv.DictReader(f):
        lib_n+=1;assert r['generator']=='v3' and r['configuration']=='n256' and r['seed']=='42',r
        assert r['amounts'] in LIB_AMOUNTS and r['family'] in LIB_FAMILIES and r['solver'] in LIB_SOLVERS.get(r['frequency'],()),('unexpected library case',r)
        assert r['status'] in ('ROOT','FAILED','EXCEPTION'),r
        lib[(r['amounts'],r['family'],r['frequency'],r['solver'])].append(r)
        if r['status']=='ROOT':
            assert math.isfinite(float(r['phi'])) and float(r['residual'])<1e-4,r
            assert math.isfinite(float(r['phi_diff_extended'])),r
        if r['solver'] in ('EXTENDED','EXTENDED_PREPARED'):assert r['status']=='ROOT' and float(r['residual'])<1e-8,r
        assert (r['iterations']!='')==(r['solver']=='XIRR'),r
assert lib_n==LIB_ROWS,(lib_n,LIB_ROWS)
assert set(lib)=={(a,f,q,v) for a in LIB_AMOUNTS for q,vs in LIB_SOLVERS.items() for f in LIB_FAMILIES for v in vs} and all(sorted(int(c['index']) for c in v)==list(range(64)) for v in lib.values()),'incomplete library matrix'
print(f"Validated {records} JMH records in {groups} files ({'smoke' if smoke else 'full'}) and {n} diagnostic rows."
      +('' if smoke else f' Worst recorded 1-minute load average {worst_load:.2f}.'))
print(f'{returned} returned roots have finite phi and residual < 1e-4 (< 1e-8 on NEWTON); {target_checked} match their target phi; '
      f'{no_solution} dominance outcomes from ANALYTIC_BOUNDS on are NO_SOLUTION with zero evaluations; '
      f'{far_negative} extreme-negative solves at LOG_NEWTON finish on Newton within 10 iterations; '
      f'{paired} inputs agree between HISTORICAL and PRIMITIVE_ARRAYS.')
print(f'{lib_n} library outcome rows; every ROOT row has a finite phi and residual < 1e-4. Agreeing = |dphi| <= {LIB_AGREE:g} from EXTENDED. Per cell of 64 inputs:')
for (amounts,family,frequency,solver),cs in sorted(lib.items()):
    roots_=[c for c in cs if c['status']=='ROOT'];agree=sum(float(c['phi_diff_extended'])<=LIB_AGREE for c in roots_)
    dphi=max((float(c['phi_diff_extended']) for c in roots_),default=math.nan);res=max((float(c['residual']) for c in roots_),default=math.nan)
    it=[int(c['iterations']) for c in cs if c['iterations']!='']
    extra=f"  iterations mean {sum(it)/len(it):.1f} max {max(it)}" if it else ''
    print(f"  {amounts:10s} {family:8s} {frequency:9s} {solver:19s} returned {len(roots_):2d} agreeing {agree:2d} failed/exception {len(cs)-len(roots_):2d}  max|dphi| {dphi:.2e}  max residual {res:.2e}{extra}")
