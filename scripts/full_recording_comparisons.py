"""Frozen full-recording extension; reuse settled Java engines and review producer."""
from __future__ import annotations
import argparse,ctypes,json,os,shutil,subprocess,sys,tempfile,traceback
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
import tifffile
sys.dont_write_bytecode=True
from complete_external_review_set import ROOT,TUNE,JAVA,IJ,METHODS,GROUPS,Tuner,read,write,sha,resolve

ROUND='R27_full_recording_comparisons'
PREP='s1_prepare_review_inputs/r27_a001_full_inputs'
RENDER='s4_render_review/r27_r001_full_recordings'
JAVA_PREFIX='r27_j002_'
EXTERNAL_PREFIX='r27_e'
EXTRA_CODE=[]
BASELINE_LABEL='38 previous comparisons unchanged'
BUNDLE=TUNE/RENDER/'out/bundle'
DELIVERY=ROOT/'RIPR_Java_external_comparisons/Full_recordings'
OLD=ROOT/'RIPR_Java_external_comparisons/Video_audit'
EXTERNAL_CLASSES=TUNE/'s1_prepare_review_inputs/r26_a001_complete_external_inputs/out/classes'
BASE_INVOCATION=TUNE/'s2_estimate_motion/r23_f003_synrcamp_a1_full_v4413/out/invocation.json'
CASES=[dict(case_id='synrcamp_a1_whole',recording='A1',channel_one_based=1,channel='SynRCaMP',image_type='DENSE_FLUORESCENCE',motion='INTERMITTENT_JUMPS',old_case='synrcamp_a1',old_input='per2_a1',window_start=193,role='control'),
       dict(case_id='transmitted_a3_whole',recording='A3',channel_one_based=2,channel='Transmitted light',image_type='PHASE_CONTRAST',motion='STEADY_DIRECTIONAL_DRIFT',old_case='transmitted_a3',old_input='synrcamp_a3',window_start=1,role='failure')]

def stamp():return datetime.now(timezone.utc).isoformat()
def record(m,attempt,description):
    tuner=Tuner(TUNE)
    if any(r['attempt_id']==attempt for r in tuner.attempts(ROUND)):return
    tuner.append_attempt(ROUND,dict(attempt_id=attempt,date=stamp(),approaches=description,stage_runs=m['dir'],outputs_sha256=m['outputs_sha256'],
        primary='234 native source frames and fourteen fixed panels per recording',controls=BASELINE_LABEL,
        regressions='same frozen numerical recipes and display producer',guardrails='one Java numerical process; recorded failures retained',
        elapsed_s=m['elapsed_seconds'],review_status='pending final registered delivery',decision='full-recording extension; no recipe promotion',notes='No ground-truth or controlled timing claim'))

def prepare():
    round_dir=TUNE/'rounds'/ROUND;round_dir.mkdir(exist_ok=True)
    if not (round_dir/'round.md').exists():
        (round_dir/'round.md').write_text("""# Full-recording comparison extension

## Jamie's words
2026-09-09: "produce some on the full recordings"

## Question
Following the request above, compare complete A1 SynRCaMP fluorescence and A3 transmitted-light recordings, 234 original images each, using the same 14-panel format and both movie versions. The earlier tissue comparisons covered only 40-image windows. These two recordings represent sudden movement and gradual drift in two imaging channels.

## Diagnosis
The source TIFF headers establish 234 times, four channels and 512 by 512 native pixels. Each selected channel was identified by exact equality of all 40 images in its previous review window: A1 channel 1, source images 193-232; A3 channel 2, source images 1-40. No existing 234-frame motion run was found in the tuning stage. The existing 38 reviews are the reused A000 baseline, not complete-recording evidence for these two new inputs.

## Approaches and gates
The request authorises this fixed-method extension. WORKFLOW_CONTRACT.md supplies s1_prepare_review_inputs, s2_estimate_motion, s3_score_alignment and s4_render_review. Use the settled Java numerical recipe and the exact frozen sixteen-worker build already used for the tissue reviews, plus all twelve frozen external routes with installed defaults and native intensities. This is not a new tuning lattice or accepted-default promotion.
First verify channel extraction against all original pixels and the prior windows. Reuse the previously passed square/rectangle external-adapter gates. Process two complete 234-frame inputs: one Java numerical process at a time, native internal threads one; each method and recording has a separate immutable checkpoint. Retain genuine method failures in their panel positions. Do not replace a full input with a crop or early-stop a slow valid method. Record the actual executed Java route, selection source, transforms, native output hashes and runtime. Full-recording contextual reference selection can differ from the earlier window and is not constrained to reproduce cropped-window movements.
Render through the established reusable producer with identical font, layout, guide/time formatting and FAILED warnings. Missing ground truth remains unavailable. Every TIFF must contain all 234 frames; verify native Java replay and reopen all output pixels, decode every MP4 and verify frame order. Full movies use 10 frames per second; slow movement movies use 2 frames per second and 12 consecutive source frames plus endpoint holds. Preserve all 38 earlier TIFFs and 76 MP4s byte-for-byte. Register and inspect all four new figure masters, verify stage outputs, then deliver under Full_recordings. No scientific accuracy ranking or speed ranking is claimed.

## Verdict
In progress. No accepted numerical recipe or production default is replaced.
""",encoding='utf-8')
    raw_root=TUNE.parent/'Per2_SynRCamp_SynGABASnFr_MF'
    originals=[raw_root/(c['recording']+'.tif') for c in CASES]
    params=dict(originals={str(p):sha(p) for p in originals},cases=CASES,methods=METHODS,variant=4413,frames=234,
        external_adapter_manifest_sha256=sha(EXTERNAL_CLASSES.parent.parent/'run.json'),java_invocation_sha256=sha(BASE_INVOCATION))
    def execute(_,params,dirs):
        rows=[]
        for c,source in zip(CASES,originals):
            native=tifffile.memmap(source);assert native.shape==(234,4,512,512) and native.dtype.kind=='u' and native.dtype.itemsize==2
            frames=native[:,c['channel_one_based']-1]
            old=tifffile.imread(TUNE/'inputs'/(c['old_input']+'.tif'));start=c['window_start']-1
            assert np.array_equal(frames[start:start+len(old)],old)
            image_class='DENSE_FLUOR' if c['image_type']=='DENSE_FLUORESCENCE' else 'PHASE_CONTRAST'
            dest=dirs.out/'inputs'/image_class/c['case_id']/c['motion']/'NATIVE_ONLY/00_input_uncorrected.tif'
            dest.parent.mkdir(parents=True,exist_ok=True)
            tifffile.imwrite(dest,frames,imagej=True,metadata={'axes':'TYX'},photometric='minisblack',compression='deflate',compressionargs={'level':1})
            histogram=np.zeros(65536,dtype=np.int64)
            with tifffile.TiffFile(dest) as saved:
                assert len(saved.pages)==234
                for i,p in enumerate(saved.pages):
                    assert np.array_equal(p.asarray(),frames[i]);histogram+=np.bincount(frames[i].ravel(),minlength=65536)
            cumulative=np.cumsum(histogram);total=int(cumulative[-1])
            low=int(np.searchsorted(cumulative,int(.005*(total-1))+1));high=int(np.searchsorted(cumulative,int(.998*(total-1))+1))
            assert high>low
            layout=dirs.out/(c['case_id']+'_display.csv');write(layout,[dict(contrast_low=low,contrast_high=high,frames=234,contrast_source='full-recording pixel histogram, quantiles 0.005 and 0.998')])
            write(dest.parent/'truth.csv',[dict(frame=i,dx=0,dy=0,theta_radians=0) for i in range(234)])
            rows.append(dict(c,source=str(dest),input_path=str(dest),sha256=sha(dest),original=str(source),original_sha256=params['originals'][str(source)],frames=234,width=512,height=512,layout=str(layout),original_pixels_exact=True,previous_window_exact=True))
            del frames,native
        write(dirs.out/'cases.csv',rows);write(round_dir/'cases.csv',rows)
        baseline=[]
        previous=ROOT/'RIPR_Java_external_comparisons'
        for path in sorted(list(previous.glob('*.tif'))+list(previous.glob('*.mp4'))):baseline.append(dict(path=str(path),sha256=sha(path),bytes=path.stat().st_size))
        assert len(baseline)==114;write(dirs.out/'previous_media_hashes.csv',baseline)
        invocation=json.loads(BASE_INVOCATION.read_text());args=invocation['arguments'];cp=[resolve(p) for p in args[args.index('-cp')+1].split(';')]
        dependencies=[]
        for path in cp+[EXTERNAL_CLASSES,JAVA]:
            files=sorted(path.rglob('*')) if path.is_dir() else [path]
            for p in files:
                if p.is_file():dependencies.append(dict(path=str(p),sha256=sha(p),bytes=p.stat().st_size))
        write(dirs.out/'runtime_hashes.csv',dependencies)
        (dirs.out/'java_runtime.json').write_text(json.dumps(dict(java=str(JAVA),classpath=[str(p) for p in cp],variant=4413,baseline_invocation=str(BASE_INVOCATION)),indent=2))
        classes=dirs.out/'export_classes';classes.mkdir()
        command=[str(JAVA.parent/'javac.exe'),'--release','11','-cp',os.pathsep.join(map(str,cp)),'-d',str(classes),str(ROOT/'scripts/FullRecordingNativeExport.java')]
        with (dirs.qc/'compile.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
        return dict(outputs={'cases':dirs.out/'cases.csv','runtime':dirs.out/'java_runtime.json','runtime_hashes':dirs.out/'runtime_hashes.csv','baseline_media':dirs.out/'previous_media_hashes.csv'},summary={'recordings':2,'frames_each':234,'native_channel_pixels_exact':True,'previous_windows_exact':True,'previous_media_files':114})
    m=Tuner(TUNE).stage_run('s1_prepare_review_inputs',PREP.split('/')[1],params=params,code=[Path(__file__),ROOT/'scripts/FullRecordingNativeExport.java'],fn=execute)
    record(m,'A000','Freeze two complete recordings and reuse the 38-comparison baseline')
    print(json.dumps(m['summary']),flush=True)

def command_run(command,dirs,name,external=False):
    (dirs.qc/(name+'_invocation.json')).write_text(json.dumps(command,indent=2))
    env=os.environ.copy();env['OMP_NUM_THREADS']='1';env['OPENBLAS_NUM_THREADS']='1'
    if external:env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=1 -Djava.util.concurrent.ForkJoinPool.common.parallelism=1'
    with (dirs.qc/(name+'.log')).open('w') as log:subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)

def java_case(c):
    prep=TUNE/PREP/'out';runtime=json.loads((prep/'java_runtime.json').read_text());cp=runtime['classpath']
    for r in read(prep/'runtime_hashes.csv'):assert sha(r['path'])==r['sha256']
    attempt=JAVA_PREFIX+c['case_id']
    def execute(_,params,dirs):
        assert sha(c['source'])==c['sha256']
        probe=dirs.out/'probe'
        native_cache=Path(tempfile.mkdtemp(prefix='r27-native-'))
        (dirs.qc/'native_cache_location.json').write_text(json.dumps({'path':str(native_cache),'purpose':'short Windows DLL extraction path; numerical runtime unchanged'}))
        command=[str(JAVA),'-Djava.awt.headless=true','-Xmx4g','-Dorg.bytedeco.javacpp.cachedir='+str(native_cache),'-cp',os.pathsep.join(cp),'R23RepeatBenchmark',c['source'],c['image_type'],c['motion'],str(probe),'4413','1','512','512','234']
        print('Starting full Java recipe:',c['case_id'],flush=True);command_run(command,dirs,'java')
        native=dirs.out/'registered_native.tif';p=probe/'repeat_0'
        command=[str(JAVA),'-Djava.awt.headless=true','-Xmx2g','-cp',str(prep/'export_classes')+os.pathsep+os.pathsep.join(cp),'ripr.core.FullRecordingNativeExport',c['source'],str(p/'transforms.csv'),str(p/'pixel_hashes.csv'),str(native)]
        command_run(command,dirs,'native_export')
        summary=json.loads((p/'summary.json').read_text());assert summary['frames']==234 and summary['route']
        assert len(read(p/'transforms.csv'))==234 and len(read(p/'pixel_hashes.csv'))==468
        return dict(outputs={'summary':p/'summary.json','transforms':p/'transforms.csv','pixel_hashes':p/'pixel_hashes.csv','native_tiff':native,'raw':p/'raw.csv','preliminary':p/'preliminary.csv'},summary={'case_id':c['case_id'],'frames':234,'route':summary['route'],'estimation_seconds':summary['estimation_seconds'],'native_replay_and_imagej_reopen_verified':True})
    m=Tuner(TUNE).stage_run('s2_estimate_motion',attempt,params=dict(case_id=c['case_id'],input_sha256=c['sha256'],image_type=c['image_type'],motion=c['motion'],variant=4413,repeats=1,frames=234),upstream=PREP,code=[Path(__file__),ROOT/'scripts/FullRecordingNativeExport.java']+EXTRA_CODE,fn=execute)
    record(m,attempt,'Execute the existing declared-image Java recipe on the complete recording');print('Java complete:',json.dumps(m['summary']),flush=True)

def external_case(c):
    for group in GROUPS:
        attempt=EXTERNAL_PREFIX+group[0][:2]+'_'+c['case_id']
        def execute(_,params,dirs):
            assert sha(c['input_path'])==c['sha256']
            cmd=['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/run_external_parameter_sweep.ps1'),'-ProjectRoot',str(ROOT),'-Dataset','publication_final_translation','-Config','all_defaults','-PreprocessingArm','native','-RootOverride',str(TUNE/PREP/'out/inputs'),'-RunRootOverride',str(dirs.out),'-OnlySeries',c['case_id'],'-OnlyMethod',','.join(group),'-ClassesOverride',str(EXTERNAL_CLASSES),'-MaxHeapGb','4']
            print('Starting external:',c['case_id'],group[0][:2],flush=True);command_run(cmd,dirs,'external',True)
            csvs=list(dirs.out.rglob('*.csv'));assert len(csvs)==1
            rows=read(csvs[0]);assert {r['method_id'] for r in rows}==set(group)
            for r in rows:
                assert r['series_id']==c['case_id']
                if r['status']=='ok':
                    for key in ['transform_x_px','transform_y_px','transform_theta_rad']:
                        values=np.array([float(v) for v in r[key].split(';')]);assert len(values)==234 and np.isfinite(values).all()
            return dict(outputs={'external_results':csvs[0]},summary={'case_id':c['case_id'],'methods':len(rows),'successful':sum(r['status']=='ok' for r in rows),'failures':[dict(method=r['method_id'],status=r['status']) for r in rows if r['status']!='ok']})
        m=Tuner(TUNE).stage_run('s2_estimate_motion',attempt,params=dict(case_id=c['case_id'],input_sha256=c['sha256'],methods=group,config='all_defaults',preprocessing='native',max_heap_gb=4,native_threads=1,truth='unscored placeholder'),upstream=PREP,code=[Path(__file__),ROOT/'scripts/run_external_parameter_sweep.ps1',ROOT/'library/benchmark/run_external_full_no_dialog.groovy']+EXTRA_CODE,fn=execute)
        record(m,attempt,'Execute complete-input external comparison');print('External complete:',json.dumps(m['summary']),flush=True)

def start_bundle(cases):
    if (BUNDLE/'sources.csv').exists():return
    jobs=[];sources=[ROOT/'scripts/reproduce_registration_montage.py',OLD/'plot.py',ROOT/'src/ripr/core.py',ROOT/'src/ripr/types.py',TUNE/'s4_render_review/r25_g002_selected_reviews/out/bundle/figure_data_synrcamp_a1.csv',TUNE/PREP/'run.json',TUNE/PREP/'out/cases.csv',TUNE/PREP/'out/runtime_hashes.csv',BASE_INVOCATION,EXTERNAL_CLASSES.parent.parent/'run.json']
    for c in cases:
        run=TUNE/'s2_estimate_motion'/(JAVA_PREFIX+c['case_id']);p=run/'out/probe/repeat_0';summary=json.loads((p/'summary.json').read_text())
        route=summary['route']
        if route.startswith('bright_dim_references'):label='RIPR | Bright/dim'
        elif route.startswith('edge_dark_landmarks'):label='RIPR | Landmarks'
        else:raise AssertionError('Unresolved executed recipe: '+route)
        job=dict(case_id=c['case_id'],frames=234,source=c['source'],source_sha256=c['sha256'],title=c['recording']+' | '+c['channel']+' | complete recording',recipe_id=route+'__'+c['image_type'].lower(),recipe_label=label,recipe_source=str(p/'summary.json'),layout=c['layout'],registered=str(run/'out/registered_native.tif'),transforms=str(p/'transforms.csv'),transform_kind='longitudinal',pixel_hashes=str(p/'pixel_hashes.csv'),selection_source='manual_declared_category_executed_on_complete_recording')
        jobs.append(job);sources.extend([run/'run.json',p/'summary.json',p/'transforms.csv',p/'pixel_hashes.csv',Path(c['layout'])])
    config=dict(jobs=jobs,delivery=str(DELIVERY),tuning_root=str(TUNE),external_run_glob=EXTERNAL_PREFIX+'*/run.json',full_fps=10,movement_fps=2,clip_frames=12)
    stage=TUNE/RENDER;stage.mkdir(exist_ok=True);qc=stage/'qc';qc.mkdir(exist_ok=True)
    configfile=qc/'full_recording_complete_external_inputs.json';configfile.write_text(json.dumps(config,indent=2));sources.insert(0,configfile)
    figures=[]
    for job in jobs:
        for kind in ['full','movement']:
            figures.append(dict(figure=job['case_id']+'_'+kind+('.tif' if kind=='full' else '.reprofig'),claim='Compare the original, the executed Relative-Intensity Pattern Registration Java recipe and the same twelve external routes on '+job['title']+(' across all 234 original frames.' if kind=='full' else ' immediately before and after the largest recorded movement at two frames per second.'),grammar='review-montage',statistics_status='not_applicable',producer='plot.py'))
    manifest=qc/'figures.csv';write(manifest,figures)
    cmd=[sys.executable,str(Path.home()/'.claude/skills/plot-that/scripts/register.py'),'start',str(BUNDLE),'--figures',str(manifest)]
    for p in sources:cmd.extend(['--source',str(p)])
    subprocess.run(cmd,check=True)
    shutil.copy2(ROOT/'scripts/render_completed_external_reviews.py',BUNDLE/'plot.py')

def compute():
    cases=read(TUNE/PREP/'out/cases.csv')
    for c in cases:java_case(c)
    start_bundle(cases)
    from render_completed_external_reviews import Producer
    producer=Producer(BUNDLE)
    for c in cases:
        external_case(c)
        producer.produce(next(j for j in producer.config['jobs'] if j['case_id']==c['case_id']))
    print('Both full recordings and all four videos are complete and decoded.',flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','compute']);a=p.parse_args()
    if a.action=='prepare':prepare();return
    status=ROOT/'tmp/full_recordings_status.json'
    def state(value,**extra):status.write_text(json.dumps(dict(state=value,pid=os.getpid(),time_utc=stamp(),**extra),indent=2))
    ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
    state('running')
    try:compute();state('complete')
    except BaseException as exc:state('failed',error=str(exc));print('COMPUTE FAILED: '+str(exc),flush=True);traceback.print_exc();raise
    finally:ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)

if __name__=='__main__':main()
