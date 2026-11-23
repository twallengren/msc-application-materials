"""Generate every report table, figure and macro from the retained JMH JSON and untimed diagnostics.
No benchmark number is embedded in this script.

  analyze.py            full-run files -> report/generated, results/summary.json
  analyze.py --smoke    smoke files (configuration n16 is the base) -> temp dir; for development only
  analyze.py --out DIR  override the output directory
"""
import argparse,csv,json,math,os,sys,tempfile
from collections import Counter,defaultdict
from pathlib import Path
os.environ.setdefault('MPLCONFIGDIR',str(Path(tempfile.gettempdir())/'irr-mpl-cache'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

ROOT=Path(__file__).resolve().parents[1]
# Expected number of JMH records per file (derived from scripts/study.sh).
FULL_FILES={'kernel-size':5*5,           # 5 daily sizes x 5 calls
            'kernel-schedule':4*12,      # 4 schedules x 12 calls
            'kernel-gaps':2*4,           # fixed 37-day gap and sorted irregular gaps x 4 calls
            'prep':4*2,                  # 4 schedules x {HISTORICAL, DIGEST}
            'ladder':7*12,               # 7 families x 12 stages, prepared
            'ladder-schedule':3*12,      # 3 schedules x 12 stages, standard, prepared
            'overall-dated':7*3,         # 7 families x 3 ends, dated
            'overall-seeds':2*3,         # 2 seeds x 3 ends, standard, prepared
            'overall-size':2*3}          # 2 sizes x 3 ends, standard, prepared
SMOKE_FILES={'smoke-kernel':12,'smoke-prep':2,'smoke-ladder':7*12*2}
# Thread-scaling files hold throughput (ops/s), read separately: 2 families x 2 ends per thread count.
THREADS=[2] if '--smoke' in sys.argv else [1,2,4,8,16]
SCALING_FILES={t:('smoke-scaling' if '--smoke' in sys.argv else f'scaling-t{t}') for t in THREADS}
# LadderDiagnostics.java: 7 families, 2 further seeds, 3 further schedules, 2 further sizes; 12 stages; 64 inputs.
DIAG_ROWS=(7+2+3+2)*12*64
STAGES=['HISTORICAL','PRIMITIVE_ARRAYS','ANALYTIC_DERIVATIVE','PHI_DIRECT','INCREMENTAL','SMALL_POWERS','ANALYTIC_BOUNDS','NEWTON_FIRST','OPTIMIZED',
        'POWER_TABLE','TRUSTED_GUESS','LOG_NEWTON']
LABELS=['L0 Historical','L1 Primitive arrays','L2 Analytic derivative','L3 Phi, direct exp','L4 Incremental recurrence',
        'L5 Small-power cases','L6 Analytic bounds','L7 Newton first','L8 Optimized','L9 Power table','L10 Ungated guess','L11 Log-form step']
LAST='LOG_NEWTON'
FAMILIES=['standard','wide','jcurve','signflips','extreme-positive','extreme-negative','dominance']
CAMEL={'standard':'Standard','wide':'Wide','jcurve':'Jcurve','signflips':'Signflips','extreme-positive':'ExtremePositive',
       'extreme-negative':'ExtremeNegative','dominance':'Dominance'}
FIG_FAMILIES=['standard','jcurve','wide','signflips','extreme-positive','extreme-negative']
ROOT_STATUS=('CONVERGED','RATE_UNREPRESENTABLE')
WORD={'MINUS_ONE_FALLBACK':r'$-$100\% fallback','NO_SOLUTION':'no solution','FAILED':'failed','TERMINAL':'terminal'}
SIZES=[100,365,1000,3650,10000]
KSIZE_CALLS=['OLD_POW_VALUE','OLD_AUTODIFF','PHI_DIRECT_VALUE','PHI_INCREMENTAL_VALUE','PHI_INCREMENTAL_BOTH']
KSIZE_LABEL={'OLD_POW_VALUE':'Historical pow','OLD_AUTODIFF':'Historical autodiff','PHI_DIRECT_VALUE':'Phi direct value',
             'PHI_INCREMENTAL_VALUE':'Phi incr. value','PHI_INCREMENTAL_BOTH':'Phi incr. both'}
CALLS=['OLD_POW_VALUE','OLD_AUTODIFF','ARRAY_POW_VALUE','ARRAY_ANALYTIC_BOTH','PHI_DIRECT_VALUE','PHI_DIRECT_BOTH',
       'PHI_GENERIC_VALUE','PHI_INCREMENTAL_VALUE','PHI_INCREMENTAL_BOTH','PHI_TABLE_VALUE','PHI_TABLE_BOTH','PHI_TABLE_LOG']
GAP_CALLS=['PHI_DIRECT_VALUE','PHI_GENERIC_VALUE','PHI_INCREMENTAL_VALUE','PHI_TABLE_VALUE']
CALL_LABEL={'OLD_POW_VALUE':'Historical pow, value (boxed)','OLD_AUTODIFF':'Historical automatic differentiation (boxed)',
            'ARRAY_POW_VALUE':'Pow, value (arrays)','ARRAY_ANALYTIC_BOTH':'Pow, value and analytic derivative (arrays)',
            'PHI_DIRECT_VALUE':'Phi direct, value','PHI_DIRECT_BOTH':'Phi direct, value and derivative',
            'PHI_GENERIC_VALUE':'Phi incremental, generic powers, value','PHI_INCREMENTAL_VALUE':'Phi incremental, small powers, value',
            'PHI_INCREMENTAL_BOTH':'Phi incremental, small powers, value and derivative',
            'PHI_TABLE_VALUE':'Phi incremental, power table, value','PHI_TABLE_BOTH':'Phi incremental, power table, value and derivative',
            'PHI_TABLE_LOG':'Log form $\\log(P/N)$ and derivative, power table'}
SCHEDULES=[('DAILY','Daily'),('WEEKLY','Weekly'),('MONTHLY','Monthly'),('IRREGULAR','Irregular')]
PARAMS=['configuration','frequency','seed','family','stage','call','preparation']

ap=argparse.ArgumentParser();ap.add_argument('--smoke',action='store_true');ap.add_argument('--out');args=ap.parse_args()
SMOKE=args.smoke
BASE='n16' if SMOKE else 'n256'
if args.out:OUT=Path(args.out)
elif SMOKE:OUT=Path(tempfile.mkdtemp(prefix='irr-analyze-smoke-'))
else:OUT=ROOT/'report/generated'
SUMMARY=ROOT/'results/summary.json' if (not SMOKE and not args.out) else OUT/'summary.json'
OUT.mkdir(parents=True,exist_ok=True)
plt.rcParams.update({'font.size':9,'axes.spines.top':False,'axes.spines.right':False,'pdf.fonttype':42})
HUE='#176b87'

# ---------- timings ----------
rows=[]
for name,count in (SMOKE_FILES if SMOKE else FULL_FILES).items():
    path=ROOT/'results'/f'{name}.json'
    if not path.exists():raise SystemExit(f'Missing {path}')
    kept=[]
    for item in json.loads(path.read_text()):
        method='.'.join(item['benchmark'].split('.')[-2:])
        m=item['primaryMetric']
        assert m['scoreUnit']=='ns/op' and math.isfinite(float(m['score'])) and m['score']>0,(name,method)
        alloc=item['secondaryMetrics']['gc.alloc.rate.norm']
        assert alloc['scoreUnit']=='B/op',(name,method)
        if not SMOKE:
            assert item['forks']==3 and item['warmupIterations']==5 and item['measurementIterations']==5,(name,method)
            assert item['warmupTime']=='1 s' and item['measurementTime']=='1 s',(name,method)
            assert len(m['rawData'])==3 and all(len(x)==5 for x in m['rawData']),(name,method)
        # Single-fork smoke data carries the string "NaN" for errors and bounds.
        kept.append(dict(source=name,method=method,params=item['params'],ns=float(m['score']),error=float(m['scoreError']),
                         low=float(m['scoreConfidence'][0]),high=float(m['scoreConfidence'][1]),bytes=float(alloc['score'])))
    assert len(kept)==count,(name,len(kept),count)
    rows+=kept

def find(method,**p):
    return [r for r in rows if r['method']==method and all(r['params'].get(k)==v for k,v in p.items())]
def row(method,**p):
    hits=find(method,**p)
    if len(hits)!=1:raise SystemExit(f'Expected exactly one {method} {p}, found {len(hits)}')
    return hits[0]
def have(method,**p):return len(find(method,**p))==1

# ---------- diagnostics ----------
diag=defaultdict(list)
with (ROOT/'results/diagnostics.csv').open() as f:
    for r in csv.DictReader(f):diag[(r['frequency'],r['configuration'],r['seed'],r['family'],r['stage'])].append(r)
ndiag=sum(len(v) for v in diag.values())
assert ndiag==DIAG_ROWS,(ndiag,DIAG_ROWS)
def cases(frequency,configuration,seed,family,stage):
    key=(frequency,configuration,seed,family,stage)
    # Smoke timings are n16; only the standard class has n16 diagnostics.
    if key not in diag and SMOKE:key=('DAILY','n256','42',family,stage)
    if len(diag[key])!=64:raise SystemExit(f'Missing diagnostics {key}')
    return diag[key]
def rows_for(r):
    p=r['params'];return cases(p['frequency'],p['configuration'],p['seed'],p['family'],p['stage'])
def is_root(c):return c['status'] in ROOT_STATUS
def roots(cs):return sum(is_root(c) for c in cs)
def mean(cs,k):return sum(int(c[k]) for c in cs)/len(cs)
def all_roots(r):return roots(rows_for(r))==64

# ---------- formatting helpers ----------
def decimals(x):
    x=abs(x)
    return 0 if x>=1000 else 1 if x>=100 else 2 if x>=10 else 3 if x>=.1 else 4
def tm(r,scale=1000):
    d=decimals(r['ns']/scale)
    s=f"{r['ns']/scale:.{d}f}"
    return s+(f" $\\pm$ {r['error']/scale:.{d}f}" if math.isfinite(r['error']) else '')
def num(x,d=None):
    return f'{x:.{decimals(x) if d is None else d}f}'
def envelope(a,b):
    """a/b with the confidence-interval envelope (a.low/b.high, a.high/b.low); bounds are NaN without confidence data."""
    ratio=a['ns']/b['ns']
    if not all(math.isfinite(v) for v in (a['low'],a['high'],b['low'],b['high'])) or b['low']<=0:return ratio,math.nan,math.nan
    return ratio,max(0,a['low'])/b['high'],a['high']/b['low']
def rt(a,b,ok=True):
    if not ok:return '---'
    r,lo,hi=envelope(a,b)
    return f'{r:.2f}' if math.isnan(lo) else f'{r:.2f} [{lo:.2f}, {hi:.2f}]'
def sci(x):
    if not math.isfinite(x):return '---'
    if x==0:return '0'
    m,e=f'{x:.1e}'.split('e');return f'${m}\\times10^{{{int(e)}}}$'
def table(path,columns,head,body):
    text='\\begin{tabular}{'+columns+'}\n\\toprule\n'+head+r' \\'+'\n\\midrule\n'+'\n'.join(x+r' \\' for x in body)+'\n\\bottomrule\n\\end{tabular}\n'
    (OUT/path).write_text(text)
def save(fig,name,rect=None):
    fig.tight_layout(rect=rect) if rect else fig.tight_layout()
    meta={'CreationDate':None,'ModDate':None,'Producer':None}
    fig.savefig(OUT/(name+'.pdf'),metadata=meta);fig.savefig(OUT/(name+'.png'),dpi=180);plt.close(fig)
def fam(f):return f.replace('-',' ')
def nonroot_words(cs):
    return ', '.join(f"{WORD.get(k,k.lower())} {v}" for k,v in sorted(Counter(c['status'] for c in cs if not is_root(c)).items(),key=lambda kv:-kv[1]))
def outcome_cell(cs):
    n=roots(cs);w=nonroot_words(cs)
    return str(n) if not w else f'{n} ({w})'
def phrase(cs):
    n=roots(cs);bad={c['status'] for c in cs if not is_root(c)}
    if n==64:return 'root'
    if n==0 and len(bad)==1:return WORD.get(next(iter(bad)),next(iter(bad)).lower())
    return f'{n} roots, {nonroot_words(cs)}'

base=dict(configuration=BASE,frequency='DAILY',seed='42')
def lad(method,family,stage,**over):return row('LadderBench.'+method,**{**base,**over},family=family,stage=stage)
def kern(call,**over):return row('KernelBench.evaluate',**{**base,**over},call=call)

metrics=[]  # (name, display value, meaning, numeric)
def macro(name,value,meaning,digits=2):
    s=f'{value:.{digits}f}' if isinstance(value,float) else str(value)
    assert name.isalpha(),name
    metrics.append((name,s,meaning,value))
def macro_ratio(name,a,b,meaning):
    x,lo,hi=envelope(a,b);macro(name,x,meaning)
    if math.isfinite(lo):macro(name+'Low',lo,'envelope lower bound of the above');macro(name+'High',hi,'envelope upper bound of the above')

# ---------- 1. kernel by size (layer 1) ----------
sizes=[16] if SMOKE else SIZES
def ksize(n,call):return kern(call,configuration=f'n{n}')
body=[]
for n in sizes:
    r={c:ksize(n,c) for c in KSIZE_CALLS}
    body.append(f"{n:,} & "+' & '.join(tm(r[c]) for c in KSIZE_CALLS)+f" & {rt(r['OLD_POW_VALUE'],r['PHI_INCREMENTAL_VALUE'])} & {rt(r['OLD_AUTODIFF'],r['PHI_INCREMENTAL_BOTH'])}")
head=(r' & \multicolumn{5}{c}{Time per evaluation ($\mu$s)} & \multicolumn{2}{c}{Ratio [envelope]} \\'+'\n'+r'\cmidrule(lr){2-6}\cmidrule(lr){7-8}'+'\n'
      'Flows & '+' & '.join(KSIZE_LABEL[c] for c in KSIZE_CALLS)+r' & Pow/incr.\ value & Autodiff/incr.\ both')
table('kernel-size.tex','r'*8,head,body)

# ---------- 2. kernel by schedule ----------
body=[]
for c in CALLS:
    cells=[tm(kern(c,frequency=f),1) if have('KernelBench.evaluate',**{**base,'frequency':f},call=c) else '---' for f,_ in SCHEDULES]
    d=kern(c)
    body.append(f"{CALL_LABEL[c]} & "+' & '.join(cells)+f" & {num(d['bytes'])}")
table('kernel-schedule.tex','lrrrrr','Call & Daily (ns) & Weekly (ns) & Monthly (ns) & Irregular (ns) & Daily B/op',body)

# ---------- 3. kernel ratios ----------
RATIOS=[('OLD_POW_VALUE','PHI_INCREMENTAL_VALUE'),('OLD_AUTODIFF','PHI_INCREMENTAL_BOTH'),('PHI_GENERIC_VALUE','PHI_INCREMENTAL_VALUE'),('OLD_POW_VALUE','ARRAY_POW_VALUE'),
        ('PHI_INCREMENTAL_VALUE','PHI_TABLE_VALUE'),('OLD_POW_VALUE','PHI_TABLE_VALUE')]
body=[]
for f,label in SCHEDULES:
    if not have('KernelBench.evaluate',**{**base,'frequency':f},call='OLD_POW_VALUE'):continue
    body.append(f"{label} & "+' & '.join(rt(kern(a,frequency=f),kern(b,frequency=f)) for a,b in RATIOS))
table('kernel-ratios.tex','lrrrrrr','Schedule & Pow/incr.\\ value & Autodiff/incr.\\ both & Generic/small-power & Boxed/array pow & Small-power/table & Pow/table value',body)

# ---------- 3b. gap-order experiment: same kernel calls on a fixed gap, sorted gaps and random gaps ----------
GAPS=[('EVERY_37_DAYS','Fixed gap of 37 days'),('IRREGULAR_SORTED','Gaps 1--40, ascending'),('IRREGULAR','Gaps 1--40, random order')]
if have('KernelBench.evaluate',**{**base,'frequency':'IRREGULAR_SORTED'},call='PHI_TABLE_VALUE'):
    body=[f"{label} & "+' & '.join(tm(kern(c,frequency=f),1) for c in GAP_CALLS) for f,label in GAPS]
    table('kernel-gaps.tex','lrrrr','Gap pattern & Direct exp (ns) & Generic powers (ns) & Small-power cases (ns) & Power table (ns)',body)
elif not SMOKE:raise SystemExit('Missing kernel-gaps records')

# ---------- 4. preparation (layer 2) ----------
body=[]
for f,label in SCHEDULES:
    if not have('PrepBench.prepare',**{**base,'frequency':f},preparation='HISTORICAL'):continue
    h=row('PrepBench.prepare',**{**base,'frequency':f},preparation='HISTORICAL');d=row('PrepBench.prepare',**{**base,'frequency':f},preparation='DIGEST')
    body.append(f"{label} & {tm(h)} & {tm(d)} & {rt(h,d)} & {num(h['bytes'],0)} & {num(d['bytes'],0)}")
table('prep.tex','lrrrrr',r'Schedule & Three-pass ($\mu$s) & Digest ($\mu$s) & Ratio [envelope] & Three-pass B/op & Digest B/op',body)
prep_h=row('PrepBench.prepare',**base,preparation='HISTORICAL');prep_d=row('PrepBench.prepare',**base,preparation='DIGEST')

# ---------- 5. ladder per class (layer 3) ----------
for family in FAMILIES:
    rs=[lad('prepared',family,s) for s in STAGES];flags=[all_roots(r) for r in rs];body=[]
    for i,(r,label) in enumerate(zip(rs,LABELS)):
        cs=rows_for(r)
        prev=rt(rs[i-1],r,flags[i] and flags[i-1]) if i else '---'
        first=rt(rs[0],r,flags[i] and flags[0]) if i else ('1.00' if flags[0] else '---')
        body.append(f"{label} & {tm(r)} & {prev} & {first} & {outcome_cell(cs)} & {mean(cs,'values'):.1f} & {mean(cs,'derivatives'):.1f}")
    table(f'ladder-{family}.tex','lrrrrrr',r'Stage & Time ($\mu$s) & Previous/current & L0/current & Roots/64 & Mean value evals & Mean deriv. evals',body)

# ---------- 6. ladder by schedule ----------
body=[]
for stage,label in zip(STAGES,LABELS):
    cells=[]
    for f,_ in SCHEDULES:
        cells.append(tm(lad('prepared','standard',stage,frequency=f)) if have('LadderBench.prepared',**{**base,'frequency':f},family='standard',stage=stage) else '---')
    body.append(f"{label} & "+' & '.join(cells))
table('ladder-schedule.tex','lrrrr',r'Stage & Daily ($\mu$s) & Weekly ($\mu$s) & Monthly ($\mu$s) & Irregular ($\mu$s)',body)

# ---------- 7. overall: historical, optimized (L8), extended (L11) ----------
body=[];pairs={}  # (kind,family,end)->(H,X) where both sides return 64/64 roots
def overall(label,method,family,**over):
    h=lad(method,family,'HISTORICAL',**over);o=lad(method,family,'OPTIMIZED',**over);e=lad(method,family,LAST,**over)
    ch,co,ce=rows_for(h),rows_for(o),rows_for(e)
    go=roots(ch)==64 and roots(co)==64;ge=roots(ch)==64 and roots(ce)==64
    body.append(f"{label} & {tm(h)} & {tm(o)} & {tm(e)} & {rt(h,o,go)} & {rt(h,e,ge)} & {roots(ch)}/{roots(co)}/{roots(ce)}")
    return h,o,e,go,ge
for kind,method in (('Prepared','prepared'),('Dated','dated')):
    for family in FAMILIES:
        h,o,e,go,ge=overall(f'{kind}, {fam(family)}',method,family)
        if go:pairs[(kind,family,'Optimized')]=(h,o)
        if ge:pairs[(kind,family,'Extended')]=(h,e)
schedule_pairs={}
def opt(label,method,family,**over):
    if have('LadderBench.'+method,**{**base,**over},family=family,stage='HISTORICAL'):return overall(label,method,family,**over)
    elif not SMOKE:raise SystemExit(f'Missing overall row {label}')
for seed in ('20261122','8675309'):opt(f'Prepared, standard, seed {seed}','prepared','standard',seed=seed)
for f,label in SCHEDULES[1:]:
    got=opt(f'Prepared, standard, {label.lower()}','prepared','standard',frequency=f)
    if got:schedule_pairs[label]=got
for cfg in ('n16','n4096'):
    if cfg!=BASE:opt(f'Prepared, standard, {cfg}','prepared','standard',configuration=cfg)
table('overall.tex','lrrrrrr',r'Configuration & Historical ($\mu$s) & Optimized L8 ($\mu$s) & Extended L11 ($\mu$s) & H/L8 [envelope] & H/L11 [envelope] & Roots H/L8/L11',body)

# ---------- 8. accuracy: historical against each of the two optimized ends ----------
def agree(h,o):
    ph,po=float(h['phi']),float(o['phi'])
    if max(abs(ph),abs(po))>5:return abs(ph-po)<=1e-5
    rh,ro=float(h['rate']),float(o['rate'])
    return abs(rh-ro)<=1e-5*max(1,abs(rh))
acc={}
def accuracy(stage,path,letter):
    body=[]
    for family in FAMILIES:
        hs=sorted(diag[('DAILY','n256','42',family,'HISTORICAL')],key=lambda c:int(c['index']))
        os_=sorted(diag[('DAILY','n256','42',family,stage)],key=lambda c:int(c['index']))
        k=Counter()
        for h,o in zip(hs,os_):
            assert h['index']==o['index']
            rh,ro=is_root(h),is_root(o)
            if h['status']=='MINUS_ONE_FALLBACK':k['fallback']+=1
            elif rh and ro:k['agree' if agree(h,o) else 'disagree']+=1
            elif rh:k['onlyh']+=1
            elif ro:k['onlyo']+=1
            else:k['neither']+=1
        assert sum(k.values())==64
        resh=max([float(c['residual']) for c in hs if is_root(c)],default=math.nan);reso=max([float(c['residual']) for c in os_ if is_root(c)],default=math.nan)
        acc[(stage,family)]=(k,resh,reso)
        body.append(f"{fam(family).capitalize()} & {k['agree']} & {k['disagree']} & {k['onlyh']} & {k['onlyo']} & {k['fallback']} & {k['neither']} & {sci(resh)} & {sci(reso)}")
    table(path,'lrrrrrrrr',rf'Class & Agree & Disagree & Only H & Only {letter} & H $-$100\% fallback & Neither & Max residual H & Max residual {letter}',body)
accuracy('OPTIMIZED','accuracy.tex','L8')
accuracy(LAST,'accuracy-extended.tex','L11')

# ---------- 8b. thread scaling (throughput of independent solves) ----------
scaling={}  # (threads,family,stage)->(ops/s, error, B/op)
for t,name in SCALING_FILES.items():
    data=json.loads((ROOT/'results'/f'{name}.json').read_text())
    assert len(data)==4,(name,len(data))
    for item in data:
        m=item['primaryMetric'];assert m['scoreUnit']=='ops/s' and item['threads']==t,(name,m['scoreUnit'],item['threads'])
        if not SMOKE:assert item['forks']==3 and len(m['rawData'])==3 and all(len(x)==5 for x in m['rawData']),name
        p=item['params'];assert p['configuration']==BASE and p['frequency']=='DAILY' and p['seed']=='42',p
        scaling[(t,p['family'],p['stage'])]=(float(m['score']),float(m['scoreError']),float(item['secondaryMetrics']['gc.alloc.rate.norm']['score']))
SERIES=[('standard','HISTORICAL','Historical, standard'),('standard',LAST,'Extended, standard'),('wide','HISTORICAL','Historical, wide'),('wide',LAST,'Extended, wide')]
t0=THREADS[0]
body=[]
for t in THREADS:
    cells=[]
    for family,stage,_ in SERIES:
        v=scaling[(t,family,stage)][0];cells.append(f"{v/1000:,.0f} & {v/scaling[(t0,family,stage)][0]:.2f}")
    body.append(f"{t} & "+' & '.join(cells))
head=(' & '+' & '.join(rf'\multicolumn{{2}}{{c}}{{{label}}}' for _,_,label in SERIES)+r' \\'+'\n'+r'\cmidrule(lr){2-3}\cmidrule(lr){4-5}\cmidrule(lr){6-7}\cmidrule(lr){8-9}'+'\n'
      'Threads & '+' & '.join([r'$10^3$/s & $\times$']*4))
table('scaling.tex','r'*9,head,body)
# ---------- 9. figures ----------
shades=['#0b3a4a',HUE,'#3f93ad','#5fa3ba','#86b8c9']
fig,ax=plt.subplots(figsize=(6.7,3.0))
for c,shade,mk,ls in zip(KSIZE_CALLS,shades,'osD^v',['-','--','-','--','-']):
    pts=[(n,ksize(n,c)) for n in sizes]
    ax.errorbar([n for n,_ in pts],[r['ns'] for _,r in pts],yerr=[r['error'] if math.isfinite(r['error']) else 0 for _,r in pts],
                color=shade,marker=mk,ms=4,lw=1.2,ls=ls,capsize=2,label=KSIZE_LABEL[c])
ax.set_xscale('log');ax.set_yscale('log');ax.set_xlabel('Flows (daily schedule)');ax.set_ylabel('ns per evaluation')
if len(sizes)>1:ax.set_xticks(sizes,[f'{n:,}' for n in sizes]);ax.minorticks_off()
else:ax.set_xticks(sizes,[str(n) for n in sizes])
ax.grid(alpha=.2,which='major');ax.set_axisbelow(True);ax.legend(frameon=False,fontsize=8,loc='center left',bbox_to_anchor=(1.02,.5))
save(fig,'kernel')

data={f:[lad('prepared',f,s) for s in STAGES] for f in FIG_FAMILIES}
counts={f:[roots(rows_for(r)) for r in rs] for f,rs in data.items()}
ymin=min(r['ns'] for rs in data.values() for r in rs)/1000/2
ymax=max((r['ns']+(r['error'] if math.isfinite(r['error']) else 0)) for rs in data.values() for r in rs)/1000*2.5
fig,axes=plt.subplots(3,2,figsize=(6.7,5.2),sharey=True)
for ax,family in zip(axes.flat,FIG_FAMILIES):
    ax.set_yscale('log')
    for i,(r,n) in enumerate(zip(data[family],counts[family])):
        full=n==64;v=r['ns']/1000;e=r['error']/1000 if math.isfinite(r['error']) else 0
        ax.bar(i,v-ymin,bottom=ymin,width=.7,color=HUE,alpha=1 if full else .35,hatch=None if full else '////',edgecolor='none' if full else HUE,linewidth=.6)
        ax.errorbar(i,v,yerr=[[min(e,v*.999)],[e]],fmt='none',color='black',capsize=2,lw=.8)
        if not full:ax.text(i,(v+e)*1.15,f'{n}/64',ha='center',va='bottom',fontsize=7)
    ax.set_xticks(range(len(STAGES)),[str(i) for i in range(len(STAGES))]);ax.tick_params(axis='x',labelsize=7);ax.set_ylim(ymin,ymax)
    ax.axvline(8.5,color='0.5',lw=.6,ls=':');ax.set_title(fam(family).capitalize(),fontsize=9)
    ax.grid(axis='y',alpha=.2,which='major');ax.set_axisbelow(True)
for ax in axes[:,0]:ax.set_ylabel(r'$\mu$s per solve')
for ax in axes[-1,:]:ax.set_xlabel('Stage (extensions right of the dotted line)',fontsize=8)
fig.legend(handles=[Patch(facecolor=HUE,label='all 64 roots returned'),Patch(facecolor=HUE,alpha=.35,hatch='////',edgecolor=HUE,label='not all roots returned (roots count above bar)')],
           loc='lower center',ncol=2,frameon=False,fontsize=8)
save(fig,'ladder',rect=(0,.05,1,1))

fig,ax=plt.subplots(figsize=(4.6,3.0))
for (family,stage,label),shade,mk,ls in zip(SERIES,['#0b3a4a','#3f93ad','#0b3a4a','#3f93ad'],'osD^',['-','-','--','--']):
    ax.plot(THREADS,[scaling[(t,family,stage)][0]/scaling[(t0,family,stage)][0] for t in THREADS],color=shade,marker=mk,ms=4,lw=1.2,ls=ls,label=label)
ax.plot(THREADS,[t/t0 for t in THREADS],color='0.6',lw=.8,ls=':',label='Linear')
ax.axvline(8,color='0.8',lw=.6);ax.set_xscale('log',base=2);ax.set_yscale('log',base=2)
ax.set_xticks(THREADS,[str(t) for t in THREADS]);ax.set_yticks(THREADS,[str(t) for t in THREADS]);ax.minorticks_off()
ax.set_xlabel('Threads (8 cores, 16 hardware threads)');ax.set_ylabel('Throughput relative to one thread')
ax.grid(alpha=.2);ax.set_axisbelow(True);ax.legend(frameon=False,fontsize=7)
save(fig,'scaling')

# ---------- 10. metrics, measurements.csv, summary.json ----------
NUMBER={1:'One',2:'Two',4:'Four',8:'Eight',16:'Sixteen'}
for family,stage,_ in SERIES:
    tag=('Historical' if stage=='HISTORICAL' else 'Extended')+CAMEL[family]
    for t in THREADS:
        if t!=t0:macro(f'Scaling{tag}{NUMBER[t]}',scaling[(t,family,stage)][0]/scaling[(t0,family,stage)][0],f'throughput on {t} threads / on {t0}, {stage}, {family}')
    macro(f'Throughput{tag}Max',max(scaling[(t,family,stage)][0] for t in THREADS)/1e6,f'highest throughput, million solves per second, {stage}, {family}',3)
for label,(h,o,e,go,ge) in schedule_pairs.items():
    if go:macro_ratio(f'Speedup{label}Optimized',h,o,f'prepared historical/OPTIMIZED time ratio, standard, {label.lower()}')
    if ge:macro_ratio(f'Speedup{label}Extended',h,e,f'prepared historical/{LAST} time ratio, standard, {label.lower()}')
if have('KernelBench.evaluate',**{**base,'frequency':'IRREGULAR_SORTED'},call='PHI_INCREMENTAL_VALUE'):
    macro_ratio('GapRandomOverSorted',kern('PHI_INCREMENTAL_VALUE',frequency='IRREGULAR'),kern('PHI_INCREMENTAL_VALUE',frequency='IRREGULAR_SORTED'),'small-power kernel time, random gap order / ascending gap order')
    macro_ratio('GapTableGainIrregular',kern('PHI_INCREMENTAL_VALUE',frequency='IRREGULAR'),kern('PHI_TABLE_VALUE',frequency='IRREGULAR'),'small-power kernel time / power-table kernel time, irregular gaps')
    macro_ratio('GapDirectOverTableIrregular',kern('PHI_DIRECT_VALUE',frequency='IRREGULAR'),kern('PHI_TABLE_VALUE',frequency='IRREGULAR'),'direct-exp kernel time / power-table kernel time, irregular gaps')
msizes={'Year':16 if SMOKE else 365,'Decade':16 if SMOKE else 3650}
for tag,n in msizes.items():
    macro_ratio(f'KernelPowIncr{tag}',ksize(n,'OLD_POW_VALUE'),ksize(n,'PHI_INCREMENTAL_VALUE'),f'kernel time ratio OLD_POW_VALUE/PHI_INCREMENTAL_VALUE, daily n={n}')
    macro_ratio(f'KernelAutodiffIncr{tag}',ksize(n,'OLD_AUTODIFF'),ksize(n,'PHI_INCREMENTAL_BOTH'),f'kernel time ratio OLD_AUTODIFF/PHI_INCREMENTAL_BOTH, daily n={n}')
macro_ratio('PrepRatioDaily',prep_h,prep_d,f'three-pass preparation time / digest time, daily {BASE}')
macro('PrepHistoricalTime',prep_h['ns']/1000,f'microseconds per three-pass preparation, daily {BASE}',3)
macro('PrepDigestTime',prep_d['ns']/1000,f'microseconds per digest, daily {BASE}',3)
for (kind,family,end),(h,o) in pairs.items():
    if family=='dominance':continue
    macro_ratio(f"Speedup{kind}{CAMEL[family]}{'' if end=='Optimized' else end}",h,o,f'{kind.lower()} historical/{end.lower()} time ratio, {fam(family)} (64/64 roots both sides)')
for family in FAMILIES:
    macro(f'RootsHistorical{CAMEL[family]}',roots(rows_for(lad('prepared',family,'HISTORICAL'))),f'roots returned of 64 by HISTORICAL, {fam(family)}')
    macro(f'RootsOptimized{CAMEL[family]}',roots(rows_for(lad('prepared',family,'OPTIMIZED'))),f'roots returned of 64 by OPTIMIZED, {fam(family)}')
for family in FAMILIES:
    macro(f'RootsExtended{CAMEL[family]}',roots(rows_for(lad('prepared',family,LAST))),f'roots returned of 64 by {LAST}, {fam(family)}')
for family in ('extreme-negative','extreme-positive','wide'):
    for stage,tag in (('OPTIMIZED','Optimized'),(LAST,'Extended')):
        cs=rows_for(lad('prepared',family,stage))
        macro(f'MeanEvaluations{CAMEL[family]}{tag}',mean(cs,'values')+mean(cs,'derivatives'),f'mean evaluations per solve, {stage}, {fam(family)}',1)
for stage,tag in (('HISTORICAL','Historical'),('OPTIMIZED','Optimized'),(LAST,'Extended')):
    cs=rows_for(lad('prepared','standard',stage))
    macro(f'MeanValuesStandard{tag}',mean(cs,'values'),f'mean value-only evaluations per solve, {stage}, standard',1)
    macro(f'MeanDerivativesStandard{tag}',mean(cs,'derivatives'),f'mean value-plus-derivative evaluations per solve, {stage}, standard',1)
macro('AllocAutodiff',kern('OLD_AUTODIFF')['bytes'],f'B per evaluation, OLD_AUTODIFF, daily {BASE}',1)
macro('AllocIncrementalBoth',kern('PHI_INCREMENTAL_BOTH')['bytes'],f'B per evaluation, PHI_INCREMENTAL_BOTH, daily {BASE}',1)
macro('JmhRecords',len(rows),'number of JMH records read')
macro('DiagnosticRows',ndiag,'rows in diagnostics.csv')
(OUT/'metrics.tex').write_text('% generated by scripts/analyze.py\n'+''.join(f'\\newcommand{{\\{n}}}{{{s}}}\n' for n,s,_,_ in metrics))
fields=['source','method']+PARAMS+['ns','error','low','high','bytes']
with (OUT/'measurements.csv').open('w',newline='') as f:
    w=csv.DictWriter(f,fields,extrasaction='ignore');w.writeheader()
    for r in rows:w.writerow({**r,**{k:r['params'].get(k,'') for k in PARAMS}})
clean=lambda v:None if isinstance(v,float) and not math.isfinite(v) else v
SUMMARY.write_text(json.dumps({n:clean(v) for n,_,_,v in metrics},indent=2)+'\n')
print(f"Generated {len(list(OUT.iterdir()))} files in {OUT} from {len(rows)} JMH records and {ndiag} diagnostic rows ({'smoke' if SMOKE else 'full'}).")
