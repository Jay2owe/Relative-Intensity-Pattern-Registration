"""Bounded square compatibility check before native rectangular external runs."""
import os,sys,json,subprocess
from pathlib import Path
import tifffile
from complete_external_review_set import ROOT,TUNE,PREP,ROUND,read,write,sha,Tuner,ledger

def execute(_,params,dirs):
 case=next(r for r in read(TUNE/PREP/'out/cases.csv') if r['case_id']=='microglia_1432_brightfield')
 source=Path(case['source']);assert sha(source)==case['sha256']
 folder=dirs.out/'inputs/BRIGHTFIELD/square_check/INTERMITTENT_JUMPS/NATIVE_ONLY';folder.mkdir(parents=True)
 with tifffile.TiffFile(source) as t:frames=t.asarray(key=[0,1])
 tifffile.imwrite(folder/'00_input_uncorrected.tif',frames,imagej=True,metadata={'axes':'TYX'},photometric='minisblack')
 write(folder/'truth.csv',[dict(frame=i,dx=0,dy=0,theta_radians=0) for i in range(2)])
 tables=[]
 for name,classes in [('baseline',''),('rectangular',str(TUNE/PREP/'out/classes'))]:
  command=['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/run_external_parameter_sweep.ps1'),'-ProjectRoot',str(ROOT),'-Dataset','publication_final_translation','-Config','all_defaults','-PreprocessingArm','native','-RootOverride',str(dirs.out/'inputs'),'-RunRootOverride',str(dirs.out/name),'-OnlySeries','square_check','-MaxHeapGb','3']
  if classes:command.extend(['-ClassesOverride',classes])
  env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=1 -Djava.util.concurrent.ForkJoinPool.common.parallelism=1'
  with (dirs.qc/(name+'.log')).open('w') as log:subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
  csvs=list((dirs.out/name).rglob('*.csv'));assert len(csvs)==1;tables.append(read(csvs[0]))
 a={r['method_id']:r for r in tables[0]};b={r['method_id']:r for r in tables[1]};assert len(a)==len(b)==12
 checks=[]
 for method,row in a.items():
  for key in ['status','settings','transform_x_px','transform_y_px','transform_theta_rad']:
   assert row[key]==b[method][key],(method,key,row[key],b[method][key])
  checks.append(dict(method_id=method,status=row['status'],square_result_unchanged=True))
 write(dirs.out/'checks.csv',checks)
 return dict(outputs={'checks':dirs.out/'checks.csv'},summary={'square_methods_checked':12,'all_movements_status_and_settings_identical':True})

if __name__=='__main__':
 m=Tuner(TUNE).stage_run('s1_prepare_review_inputs','r26_a002_square_compatibility',params={'source':'microglia_1432_brightfield','source_frames':[1,2],'methods':'all_defaults','native_threads':1,'purpose':'adapter compatibility only'},upstream=PREP,code=[Path(__file__),ROOT/'scripts/run_external_parameter_sweep.ps1',ROOT/'library/benchmark/run_external_full_no_dialog.groovy'],fn=execute)
 ledger(m,'A001','Square adapter compatibility on two real frames; twelve matching external settings, statuses and movements')
 print(json.dumps(m['summary'],indent=2))
