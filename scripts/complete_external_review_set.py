"""Complete the twelve frozen external comparisons on the three missing inputs."""
from __future__ import annotations
import argparse, csv, hashlib, json, os, shutil, subprocess, sys
from pathlib import Path
from datetime import datetime, timezone
import numpy as np
import tifffile

ROOT=Path(__file__).resolve().parents[1]
TUNE=ROOT.parent/'Auto-Organotypic/test set/single_channel_pulsing_lowlight_registration_tuning'
SKILL=Path.home()/'.claude/skills/analysis-tuner/scripts'
sys.path.insert(0,str(SKILL))
from tuner import Tuner
ROUND='R26_complete_external_comparisons'
PREP='s1_prepare_review_inputs/r26_a001_complete_external_inputs'
METHODS=[
 '21_real_stackreg_turboreg_translation_chain','22_real_turboreg_translation_multilag_rcc',
 '23_real_multistackreg_translation_same_engine','24_real_image_stabilizer_lucas_kanade_rolling_template',
 '25_real_fast4dreg_nanoj_previous_frame','26_real_fast4dreg_nanoj_first_frame',
 '27_real_correct_3d_drift_phase_correlation_standard','28_real_correct_3d_drift_phase_correlation_multitime',
 '29_real_linear_stack_alignment_sift_translation_chain','30_real_sift_translation_multilag_rcc',
 '31_register_virtual_stack_slices_same_sift_engine','32_real_descriptor_based_series_translation']
GROUPS=[[METHODS[0],METHODS[2]],[METHODS[1]],[METHODS[3]],[METHODS[4]],[METHODS[5]],
 [METHODS[6]],[METHODS[7]],[METHODS[8],METHODS[10]],[METHODS[9]],[METHODS[11]]]
IDS=['microglia_1432_brightfield','incucyte_vid74_a1_green','incucyte_vid74_a2_green']
JAVA=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin/java.exe')
IJ=Path.home()/'.m2/repository/net/imagej/ij/1.54p/ij-1.54p.jar'
JUNIT=Path.home()/'.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar'
HAMCREST=Path.home()/'.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'

def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(2**20),b''):h.update(b)
 return h.hexdigest()
def read(p):
 with Path(p).open(encoding='utf-8-sig',newline='') as f:return list(csv.DictReader(f))
def write(p,r):
 p=Path(p);p.parent.mkdir(parents=True,exist_ok=True)
 with p.open('w',encoding='utf-8',newline='') as f:
  w=csv.DictWriter(f,fieldnames=list(r[0]));w.writeheader();w.writerows(r)
def resolve(p):
 return Path(str(p).replace('\\','/').replace('/PySCNSlice/','/Auto-Organotypic/').replace('/Log-Ratio Registration/','/'+ROOT.name+'/'))
def ledger(m,attempt,description):
 tuner=Tuner(TUNE)
 if any(r['attempt_id']==attempt for r in tuner.attempts(ROUND)):return
 tuner.append_attempt(ROUND,dict(attempt_id=attempt,date=datetime.now(timezone.utc).isoformat(),approaches=description,
  stage_runs=m['dir'],outputs_sha256=m['outputs_sha256'],primary='complete exact-input method coverage',
  controls='35 cached comparisons reused',regressions='no recipe tuning or altered source pixels',
  guardrails='one owned Java calculation; all actual failures retained',elapsed_s=m['elapsed_seconds'],
  review_status='pending final TIFF/video review',decision='presentation completion',notes='No ground-truth accuracy or controlled timing claim'))

def prepare():
 frozen=TUNE/'s1_prepare_review_inputs/r25_a000_frozen_routes/out/cases.csv'
 cases=[r for r in read(frozen) if r['case_id'] in IDS];assert len(cases)==3
 rd=TUNE/'rounds'/ROUND;rd.mkdir(exist_ok=True)
 if not (rd/'round.md').exists():
  (rd/'round.md').write_text('''# Complete external comparison coverage

## Jamie's words
2026-09-09: "i need them all with all the same external comparisons"

## Question
Complete the missing twelve-method external panel set on both native 143-frame Incucyte green recordings and the 40-frame microglia brightfield window. Existing 35 comparison TIFFs and all accepted Java results are the frozen reused baseline A000.

## Diagnosis
The original delivery contains 35 complete external comparisons. The three added native inputs have verified Java results but no matching complete-input external results. The external adapter assumes square dimensions; Incucyte is 1536 by 1152 pixels.

## Approaches and gates
The user explicitly requires all twelve existing external routes, authorising the complete fixed-method execution. No recipe selection, tuning, new model or accepted default change. Use the existing s1_prepare_review_inputs, s2_estimate_motion, s3_score_alignment and s4_render_review stages from WORKFLOW_CONTRACT.md. Original 35 TIFF hashes and cached Java pixels must remain unchanged.
First validate rectangular processor/rotation geometry and unchanged square arithmetic, then a bounded square adapter compatibility check. Run the twelve frozen installed-default routes per complete input, checkpointing each independent method (aliases share their canonical result exactly as before). One Java numerical process, native input intensities, no crop or padding, one warm-up excluded per independent method run. The display producer preserves labels, guide/time formatting and FAILED warnings. Absent ground truth stays unavailable; zero-valued runner placeholders are never presented as accuracy.
The three inputs were already frozen in the prior 38-case manifest and cannot support a new held-out accuracy claim. No lattice is needed: the method set and parameters are settled and explicitly requested. Application-only engine geometry support is checked before execution. Historical Java-only limitations applied to earlier speed/routing work; numerical external registration remains Java, while Python performs input bookkeeping and the previously authorised media export.

## Verdict
In progress. No accepted recipe or production default is being replaced.
''',encoding='utf-8')
  write(rd/'cases.csv',cases)
 source_files=[ROOT/'src/test/java/ripr/ExternalPluginComparisonStacks.java',ROOT/'src/test/java/ripr/ExternalPluginComparisonStacksTest.java',ROOT/'scripts/run_external_parameter_sweep.ps1',ROOT/'library/benchmark/run_external_full_no_dialog.groovy',Path(__file__)]
 def execute(_,params,dirs):
  rows=[]
  for c in cases:
   source=resolve(c['source']);assert sha(source)==c['sha256']
   with tifffile.TiffFile(source) as t:shape=t.series[0].shape;assert len(shape)==3 and shape[0]==int(c['frames'])
   image_class='BRIGHTFIELD' if 'brightfield' in c['case_id'] else 'DENSE_FLUOR'
   dest=dirs.out/'inputs'/image_class/c['case_id']/'INTERMITTENT_JUMPS'/'NATIVE_ONLY'/'00_input_uncorrected.tif'
   dest.parent.mkdir(parents=True);os.link(source,dest)
   write(dest.parent/'truth.csv',[dict(frame=i,dx=0,dy=0,theta_radians=0) for i in range(shape[0])])
   rows.append(dict(case_id=c['case_id'],source=str(source),sha256=c['sha256'],frames=shape[0],height=shape[1],width=shape[2],input_path=str(dest),truth_meaning='unscored runner placeholder; real movement unknown'))
  write(dirs.out/'cases.csv',rows)
  snapshots=dirs.out/'source';snapshots.mkdir()
  for source in source_files:shutil.copy2(source,snapshots/source.name)
  classes=dirs.out/'classes';classes.mkdir()
  cp=os.pathsep.join(map(str,[ROOT/'target/classes',ROOT/'target/test-classes',IJ,JUNIT,HAMCREST]))
  compile_cmd=[str(JAVA.parent/'javac.exe'),'--release','11','-cp',cp,'-d',str(classes),str(snapshots/'ExternalPluginComparisonStacks.java'),str(snapshots/'ExternalPluginComparisonStacksTest.java')]
  with (dirs.qc/'compile.log').open('w') as log:subprocess.run(compile_cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
  with (dirs.qc/'tests.log').open('w') as log:subprocess.run([str(JAVA),'-Djava.awt.headless=true','-cp',str(classes)+os.pathsep+cp,'org.junit.runner.JUnitCore','ripr.ExternalPluginComparisonStacksTest'],stdout=log,stderr=subprocess.STDOUT,check=True)
  write(dirs.out/'source_hashes.csv',[dict(path=str(p),sha256=sha(p),bytes=p.stat().st_size) for p in source_files+[IJ,JUNIT,HAMCREST,JAVA]])
  (dirs.out/'baseline.json').write_text(json.dumps(dict(source_manifest_sha256=sha(frozen),existing_index_sha256=sha(ROOT/'RIPR_Java_external_comparisons/Video_audit/src_index.csv'),existing_cases=35,cached_java_cases=3,reruns=0),indent=2))
  return dict(outputs={'cases':dirs.out/'cases.csv','source_hashes':dirs.out/'source_hashes.csv','tests':dirs.qc/'tests.log','baseline':dirs.out/'baseline.json'},summary={'cases':3,'external_routes':12,'unit_tests_passed':True,'source_pixels_changed':False})
 m=Tuner(TUNE).stage_run('s1_prepare_review_inputs',PREP.split('/')[1],params=dict(source_manifest=str(frozen),source_manifest_sha256=sha(frozen),methods=METHODS,config='all_defaults',preprocessing='native',input_ids=IDS),code=source_files,fn=execute)
 ledger(m,'A000','Reuse frozen Java and completed 35-comparison baseline; prepare exact missing inputs and rectangular adapter')
 print(json.dumps(m['summary'],indent=2),flush=True)

def run(case_filter=None):
 prep=TUNE/PREP/'out';cases=read(prep/'cases.csv');tuner=Tuner(TUNE)
 for c in sorted(cases,key=lambda r:IDS.index(r['case_id'])):
  if case_filter and c['case_id']!=case_filter:continue
  for group in GROUPS:
   number=group[0][:2];short=c['case_id'].replace('incucyte_vid74_','inc_').replace('microglia_1432_brightfield','brightfield')
   attempt='r26_e'+number+'_'+short
   params=dict(case_id=c['case_id'],input_sha256=c['sha256'],methods=group,config='all_defaults',preprocessing='native',classes_sha256=sha(TUNE/PREP/'run.json'),max_heap_gb=4,native_threads=1,truth='unscored placeholder')
   def execute(_,params,dirs):
    assert sha(c['input_path'])==c['sha256']
    command=['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/run_external_parameter_sweep.ps1'),'-ProjectRoot',str(ROOT),'-Dataset','publication_final_translation','-Config','all_defaults','-PreprocessingArm','native','-RootOverride',str(prep/'inputs'),'-RunRootOverride',str(dirs.out),'-OnlySeries',c['case_id'],'-OnlyMethod',','.join(group),'-ClassesOverride',str(prep/'classes'),'-MaxHeapGb','4']
    (dirs.qc/'invocation.json').write_text(json.dumps(command,indent=2))
    print('Starting',c['case_id'],number,flush=True)
    env=os.environ.copy()
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=1 -Djava.util.concurrent.ForkJoinPool.common.parallelism=1'
    env['OMP_NUM_THREADS']='1';env['OPENBLAS_NUM_THREADS']='1'
    with (dirs.qc/'driver.log').open('w') as log:subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    csvs=list(dirs.out.rglob('*.csv'));assert len(csvs)==1
    rows=read(csvs[0]);assert {r['method_id'] for r in rows}==set(group)
    for r in rows:
     assert r['series_id']==c['case_id']
     if r['status']=='ok':
      for key in ['transform_x_px','transform_y_px','transform_theta_rad']:
       values=np.array([float(x) for x in r[key].split(';')]);assert len(values)==int(c['frames']) and np.isfinite(values).all()
    return dict(outputs={'external_results':csvs[0]},summary={'case_id':c['case_id'],'methods':len(rows),'successful':sum(r['status']=='ok' for r in rows),'failures':[dict(method=r['method_id'],status=r['status']) for r in rows if r['status']!='ok']})
   m=tuner.stage_run('s2_estimate_motion',attempt,params=params,code=[Path(__file__),ROOT/'scripts/run_external_parameter_sweep.ps1',ROOT/'library/benchmark/run_external_full_no_dialog.groovy'],upstream=PREP,fn=execute)
   ledger(m,attempt,'Execute '+','.join(group)+' on '+c['case_id'])
   print('Completed',c['case_id'],number,json.dumps(m['summary']),flush=True)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('stage',choices=['prepare','run']);p.add_argument('--case');a=p.parse_args()
 if a.stage=='prepare':prepare()
 else:run(a.case)
