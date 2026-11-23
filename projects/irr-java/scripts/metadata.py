"""Record nonsecret environment and exact command for every retained JMH run."""
import hashlib,json,os,platform,subprocess,sys
from datetime import datetime,timezone
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def command(*args):
    return subprocess.run(args,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT).stdout.strip()
files=sorted([*root.glob('**/*.java'),*root.glob('**/pom.xml'),*root.glob('scripts/*.py'),*root.glob('scripts/*.sh')])
hashes={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files if 'target' not in p.parts}
hashes['benchmarks/target/benchmarks.jar']=hashlib.sha256((root/'benchmarks/target/benchmarks.jar').read_bytes()).hexdigest()
metadata=dict(system_load_average=os.getloadavg(),timestamp=datetime.now(timezone.utc).isoformat(),platform=platform.platform(),machine=platform.machine(),processor=command('sysctl','-n','machdep.cpu.brand_string') if sys.platform=='darwin' else platform.processor(),hardware=command('sysctl','hw.memsize','hw.ncpu') if sys.platform=='darwin' else command('uname','-a'),java=command('java','-version'),commit=command('git','rev-parse','HEAD'),git_status=command('git','status','--short'),source_sha256=hashes,generator='v3',corpus_size=64,seeds=[42,20261122,8675309],target_phi={'standard':'uniform [-0.3,0.5)','jcurve':'uniform [-0.3,0.5), first eighth of flows are contributions','wide':'uniform [-2,2)','signflips':'uniform [-1,1), 25-50% of later amounts sign-flipped','extreme-positive':'uniform [7.5,30)','extreme-negative':'uniform [-30,-7.5)','dominance':'no root','evaluation points':'uniform [-2,2)'},amount_distribution='alternating uniform [0.1,1.1) / log-uniform exp([-6,6)); scale 10^uniform([-6,6))',anchor='2024-01-01',irregular_gaps='uniform integer [1,40]; IRREGULAR_SORTED has the same gaps ascending; EVERY_37_DAYS a fixed gap of 37',jvm_options=['-Xms256m','-Xmx512m'],command=['java','-jar','benchmarks/target/benchmarks.jar',*sys.argv[2:],'-rf','json','-rff',f'results/{sys.argv[1]}.json'])
(root/'results'/f'{sys.argv[1]}.metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
